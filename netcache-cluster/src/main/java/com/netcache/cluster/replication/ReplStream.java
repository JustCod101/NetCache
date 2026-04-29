package com.netcache.cluster.replication;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;

/**
 * 复制流帧 —— 主从之间传递命令的「封装信封」。
 * <p>
 * 它把 offset、命令类型、key 和 value 打包成一个可编码/解码的最小复制单元，供 backlog、主复制器
 * 和从复制器共享协议格式。没有它，复制链路只能依赖松散的字节拼接，协议边界很容易出错。
 * <p>
 * 协作关系：由 {@link MasterReplicator} 创建并编码，由 {@link SlaveReplicator} 解码并执行。
 * <p>
 * 线程安全：线程安全。该类型是不可变 {@code record}，并且对数组字段做了 defensive copy。
 * <p>
 * 典型用例：
 * <pre>{@code
 * ReplStream frame = new ReplStream(offset, OpCode.SET, key, value);
 * ByteBuf wire = frame.encode(ByteBufAllocator.DEFAULT);
 * ReplStream decoded = ReplStream.decode(wire);
 * }</pre>
 *
 * @param offset 该命令在复制字节流中的起始 offset
 * @param opCode 命令类型
 * @param key 命令 key
 * @param value 命令 value
 */
public record ReplStream(long offset, OpCode opCode, byte[] key, byte[] value) {
    /**
     * 复制流帧的规范化构造。
     *
     * @throws NullPointerException 当 {@code opCode}、{@code key} 或 {@code value} 为 {@code null} 时抛出
     * @implNote 对数组参数立刻 clone，避免调用方后续复用数组导致帧内容被篡改。
     */
    public ReplStream {
        Objects.requireNonNull(opCode, "opCode");
        key = Objects.requireNonNull(key, "key").clone();
        value = Objects.requireNonNull(value, "value").clone();
    }

    /**
     * 返回 key 的防御性拷贝。
     *
     * @return key 字节数组副本
     * @implNote record 默认会直接暴露数组引用，这里覆盖 accessor 是为了维持不可变语义。
     */
    @Override
    public byte[] key() {
        return key.clone();
    }

    /**
     * 返回 value 的防御性拷贝。
     *
     * @return value 字节数组副本
     * @implNote 与 {@link #key()} 相同，避免外部代码拿到内部数组后原地修改。
     */
    @Override
    public byte[] value() {
        return value.clone();
    }

    /**
     * 把复制流帧编码成线上的字节表示。
     *
     * @param allocator 用于申请输出缓冲区的分配器
     * @return 编码后的 {@link ByteBuf}
     * @implNote 采用定长头 + 变长 payload：offset、opcode、keyLength、key、valueLength、value。
     */
    public ByteBuf encode(ByteBufAllocator allocator) {
        // 帧布局：
        // | offset(8) | opcode(1) | keyLen(4) | key(n) | valueLen(4) | value(m) |
        // 显式带长度字段，才能让从节点在连续字节流里准确切分变长命令。
        ByteBuf out = allocator.buffer(Long.BYTES + 1 + Integer.BYTES + key.length + Integer.BYTES + value.length);
        out.writeLong(offset);
        out.writeByte(opCode.code());
        out.writeInt(key.length);
        out.writeBytes(key);
        out.writeInt(value.length);
        out.writeBytes(value);
        return out;
    }

    /**
     * 从字节流中解码出一条复制帧。
     *
     * @param in 输入字节流
     * @return 解码后的复制帧
     * @throws RuntimeException 当输入数据不完整或 opcode 非法时可能抛出异常
     * @implNote 解码过程必须严格遵守编码布局顺序，否则 offset 与 payload 边界都会错位。
     */
    public static ReplStream decode(ByteBuf in) {
        long offset = in.readLong();
        OpCode opCode = OpCode.fromCode(in.readByte());
        int keyLength = in.readInt();
        byte[] key = new byte[keyLength];
        in.readBytes(key);
        int valueLength = in.readInt();
        byte[] value = new byte[valueLength];
        in.readBytes(value);
        return new ReplStream(offset, opCode, key, value);
    }
}
