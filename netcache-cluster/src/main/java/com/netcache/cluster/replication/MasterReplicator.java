package com.netcache.cluster.replication;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 主复制器 —— 复制链路的「播音台」。
 * <p>
 * 主节点上的写命令会先在这里被编码、写入 backlog，再同步推送给已注册的从节点。没有它，写请求虽然
 * 能落到主库，但从库无法稳定跟进，也就谈不上主从复制与故障恢复。
 * <p>
 * 协作关系：上游通常是执行写命令的服务层；它依赖 {@link ReplicationBacklog} 保存复制历史，依赖
 * {@link ReplStream} 编码命令，依赖 {@link SlaveReplicator} 承接推流。
 * <p>
 * 线程安全：弱线程安全。backlog 自身可串行保护数据一致性，而从节点列表使用
 * {@link CopyOnWriteArrayList} 适合“注册少、广播多”的场景；但整个复制流程未显式串行化，多线程写入时
 * 仍应由更高层控制命令顺序。
 * <p>
 * 典型用例：
 * <pre>{@code
 * MasterReplicator master = new MasterReplicator(backlog);
 * master.registerSlave(slave);
 * long offset = master.onWriteCommand(OpCode.SET, key, value);
 * }</pre>
 */
public final class MasterReplicator {
    /** 保存最近复制历史，供新从节点补读或落后从节点续传。 */
    private final ReplicationBacklog backlog;
    /** 已连接的从节点列表。 */
    private final List<SlaveReplicator> slaves = new CopyOnWriteArrayList<>();

    /**
     * 创建主复制器。
     *
     * @param backlog 复制积压缓冲区
     * @throws NullPointerException 当 {@code backlog} 为 {@code null} 时抛出
     * @implNote backlog 是复制可靠性的核心依赖，因此构造时强制非空。
     */
    public MasterReplicator(ReplicationBacklog backlog) {
        this.backlog = Objects.requireNonNull(backlog, "backlog");
    }

    /**
     * 处理一条写命令，并把它广播给全部从节点。
     *
     * @param opCode 写命令类型，例如 SET 或 DEL
     * @param key 命令中的 key
     * @param value 命令中的 value；对不使用 value 的命令仍需传入协议约定的字节数组
     * @return 该命令在 backlog 中的起始 offset
     * @throws NullPointerException 当编码链路依赖的参数为 {@code null} 时可能抛出
     * @implNote 先编码再落 backlog，然后按每个 slave 自己的 offset 回放增量流，确保慢从节点也能追赶。
     */
    public long onWriteCommand(OpCode opCode, byte[] key, byte[] value) {
        ByteBuf encoded = new ReplStream(backlog.nextOffset(), opCode, key, value).encode(ByteBufAllocator.DEFAULT);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.readBytes(bytes);
            long offset = backlog.write(bytes);
            for (SlaveReplicator slave : slaves) {
                // 这里不直接把刚写入的一条命令发给 slave，而是从其 offset 回放，避免慢节点漏掉中间数据。
                slave.applyStream(backlog.readFrom(slave.offset()));
            }
            return offset;
        } finally {
            encoded.release();
        }
    }

    /**
     * 注册一个新的从节点，并立刻推送它缺失的积压数据。
     *
     * @param slave 待注册的从复制器
     * @throws NullPointerException 当 {@code slave} 为 {@code null} 时抛出
     * @implNote 新 slave 加入后立即补发 backlog，避免它只能从注册之后的新命令开始复制。
     */
    public void registerSlave(SlaveReplicator slave) {
        slaves.add(Objects.requireNonNull(slave, "slave"));
        slave.applyStream(backlog.readFrom(slave.offset()));
    }

    /**
     * 暴露底层 backlog。
     *
     * @return 当前复制积压缓冲区
     * @implNote 主要用于同步状态探测、PSYNC 协商或测试断言。
     */
    public ReplicationBacklog backlog() {
        return backlog;
    }
}
