package com.netcache.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

/**
 * 协议帧模型，像网络世界里的“标准快递箱”，把所有请求、响应和复制消息都装进统一的 18 字节头 + payload 结构中。
 * <p>
 * 它解决的是“不同业务消息如何共用同一条 TCP 字节流通道并被可靠拆包”的问题；如果没有统一帧，编码器、解码器、
 * 校验器和上层命令对象都必须各自维护边界信息，协议会迅速失控。
 * <p>
 * 上游通常由 {@code Request}/{@code Response} 在完成 payload 编码后组装为 {@code Frame}，
 * 下游由 {@code ProtocolEncoder} 写出、由 {@code ProtocolDecoder} 读入，并交给后续业务处理器消费。
 * <p>
 * 线程安全性：record 自身字段引用不可重新赋值，但其中的 {@link ByteBuf} 是引用计数对象且通常不具备线程安全保证；
 * 因此本类型适合在线程内或明确所有权转移的 pipeline 阶段使用，不应在多个线程间并发读写同一 payload。
 * <p>
 * Frame 布局（共 18B 头 + N B payload）
 * <pre>{@code
 *   0        4         5      6           14        18           N
 *   +--------+---------+------+-----------+--------+--------------+
 *   | Magic  | Version | Type | RequestId | Length |   Payload    |
 *   | 4B     | 1B      | 1B   | 8B        | 4B     |   N B        |
 *   +--------+---------+------+-----------+--------+--------------+
 * }</pre>
 * <p>
 * 典型用例：
 * <pre>{@code
 * ByteBuf payload = request.encodePayload(allocator);
 * try (Frame frame = Frame.request(request.requestId(), payload)) {
 *     channel.writeAndFlush(frame);
 * }
 * }</pre>
 */
public record Frame(int magic, byte version, byte type, long requestId, ByteBuf payload) implements AutoCloseable {
    /** 帧魔数：0xC0DECAFE，用于快速识别合法协议流。 */
    public static final int MAGIC = 0xC0DECAFE;
    /** 当前支持的协议版本。 */
    public static final byte VERSION = 0x01;
    /** 固定头长度：4B magic + 1B version + 1B type + 8B requestId + 4B payload length。 */
    public static final int HEADER_LENGTH = 18;
    /** 单帧 payload 上限，避免异常流量或错误数据导致内存失控。 */
    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    /** 帧类型：业务请求。 */
    public static final byte TYPE_REQUEST = 0x01;
    /** 帧类型：业务响应。 */
    public static final byte TYPE_RESPONSE = 0x02;
    /** 帧类型：主从复制流量。 */
    public static final byte TYPE_REPLICATION = 0x03;
    /** 帧类型：Sentinel 心跳。 */
    public static final byte TYPE_SENTINEL_HEARTBEAT = 0x04;

    /**
     * 创建并校验一个协议帧。
     *
     * @param magic 帧魔数，用于快速检测是否为 NetCache 协议数据
     * @param version 协议版本号，用于未来升级时做兼容控制
     * @param type 帧类型，决定 payload 由哪条业务链路解释
     * @param requestId 请求标识，客户端通常单调递增，响应会原样带回以便匹配
     * @param payload 实际业务负载，长度由其 {@code readableBytes()} 决定
     * @throws NullPointerException 当 {@code payload} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当魔数、版本或 payload 长度不合法时抛出
     * @implNote record 紧凑构造器仅负责防御性校验，不复制 {@link ByteBuf} 内容，因此调用方仍需管理引用计数与生命周期。
     */
    public Frame {
        Objects.requireNonNull(payload, "payload");
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid magic: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported version: " + version);
        }
        int readableBytes = payload.readableBytes();
        if (readableBytes > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload exceeds 16MB limit: " + readableBytes);
        }
    }

    /**
     * 构造一个请求帧。
     *
     * @param requestId 请求唯一标识，用于后续与响应配对
     * @param payload 已按请求协议编码完成的负载内容
     * @return 类型为 {@link #TYPE_REQUEST} 的帧对象
     * @throws NullPointerException 当 {@code payload} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 payload 超过上限时抛出
     * @implNote 该方法为 O(1)，仅补齐固定头字段并委托给紧凑构造器校验。
     */
    public static Frame request(long requestId, ByteBuf payload) {
        return new Frame(MAGIC, VERSION, TYPE_REQUEST, requestId, payload);
    }

    /**
     * 构造一个响应帧。
     *
     * @param requestId 被响应请求的唯一标识，客户端依赖它完成请求-响应关联
     * @param payload 已按响应协议编码完成的负载内容
     * @return 类型为 {@link #TYPE_RESPONSE} 的帧对象
     * @throws NullPointerException 当 {@code payload} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 payload 超过上限时抛出
     * @implNote 该方法为 O(1)，除类型位外其余头字段均使用协议固定值。
     */
    public static Frame response(long requestId, ByteBuf payload) {
        return new Frame(MAGIC, VERSION, TYPE_RESPONSE, requestId, payload);
    }

    @Override
    /**
     * 释放 payload 的引用计数。
     *
     * @implNote 该方法委托 {@link ReferenceCountUtil#release(Object)}，允许调用方以 try-with-resources 风格管理帧生命周期。
     */
    public void close() {
        ReferenceCountUtil.release(payload);
    }
}
