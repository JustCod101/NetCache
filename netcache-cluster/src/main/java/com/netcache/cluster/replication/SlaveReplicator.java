package com.netcache.cluster.replication;

import com.netcache.common.ByteKey;
import com.netcache.protocol.OpCode;
import com.netcache.storage.StorageEngine;
import io.netty.buffer.ByteBuf;

import java.util.Objects;

/**
 * 从复制器 —— 复制链路的「收音机」。
 * <p>
 * 它负责接收主节点推送的复制流，逐条解码并回放到本地 {@link StorageEngine}。没有它，从节点即使在线，
 * 也只是一份不会更新的冷备副本。
 * <p>
 * 协作关系：上游由 {@link MasterReplicator} 推送 {@link ByteBuf} 流；内部依赖 {@link ReplStream}
 * 解码协议，最终依赖 {@link StorageEngine} 把命令落到本地存储。
 * <p>
 * 线程安全：非线程安全。{@code offset} 和存储回放流程都假设单线程顺序执行；若要并发使用，应由外层
 * 串行化复制流投递。
 * <p>
 * 典型用例：
 * <pre>{@code
 * SlaveReplicator slave = new SlaveReplicator(storageEngine);
 * slave.connect(masterReplicator);
 * long replicatedOffset = slave.offset();
 * }</pre>
 */
public final class SlaveReplicator {
    /** 复制命令最终回放到的本地存储引擎。 */
    private final StorageEngine storageEngine;
    /** 当前从节点已经成功应用到的逻辑 offset。 */
    private long offset;

    /**
     * 创建从复制器。
     *
     * @param storageEngine 本地存储引擎
     * @throws NullPointerException 当 {@code storageEngine} 为 {@code null} 时抛出
     * @implNote 复制器不自己持久化状态，而是委托给存储引擎执行命令语义。
     */
    public SlaveReplicator(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    /**
     * 连接到主复制器并开始接收同步数据。
     *
     * @param master 主复制器
     * @throws NullPointerException 若上游注册逻辑收到空 master 会抛出异常
     * @implNote 连接动作很轻量，本质上只是向主节点完成注册并触发一次 backlog 补发。
     */
    public void connect(MasterReplicator master) {
        master.registerSlave(this);
    }

    /**
     * 应用一段复制流。
     *
     * @param stream 待消费的复制字节流，方法结束后会负责释放
     * @throws RuntimeException 当流内容损坏或底层存储执行失败时可能向上抛出
     * @implNote 每解出一条命令就立刻应用并推进 offset，这样即使中途失败，也能明确知道已消费到哪里。
     */
    public void applyStream(ByteBuf stream) {
        try {
            while (stream.isReadable()) {
                int startReadable = stream.readableBytes();
                ReplStream command = ReplStream.decode(stream);
                apply(command);
                // 使用“命令起始 offset + 本次实际消耗字节数”推进游标，而不是简单加 1，
                // 是因为复制协议是变长帧，offset 的语义是字节位置而不是条目编号。
                offset = command.offset() + (startReadable - stream.readableBytes());
            }
        } finally {
            stream.release();
        }
    }

    /**
     * 返回当前已应用到的复制 offset。
     *
     * @return 当前复制位点
     * @implNote 该值通常会在 PSYNC 协商时作为“我已经追到哪里”的依据。
     */
    public long offset() {
        return offset;
    }

    /**
     * 回放一条复制命令到本地存储。
     *
     * @param command 已解码的复制命令
     * @implNote 当前仅处理 SET / DEL，两者已覆盖本模块现阶段复制协议；未识别命令会被静默忽略。
     */
    private void apply(ReplStream command) {
        ByteKey key = ByteKey.copyOf(command.key());
        if (command.opCode() == OpCode.SET) {
            storageEngine.set(key, command.value());
        } else if (command.opCode() == OpCode.DEL) {
            storageEngine.del(key);
        }
    }
}
