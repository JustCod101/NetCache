package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 协议编码器，像装箱流水线，把内存中的 {@link Frame} 对象按线协议顺序压平成可发送的字节流。
 * <p>
 * 它解决的是“业务对象如何稳定落到网络字节布局上”的问题；如果没有它，调用方需要手工拼接 magic、版本、类型和长度字段，
 * 容易在字段顺序或长度计算上出错。
 * <p>
 * 上游通常由业务 handler 写出 {@link Frame}，下游直接依赖 Netty 的 {@link ByteBuf} 输出能力把字节发到 socket。
 * <p>
 * 线程安全性：该编码器本身无可变字段，作为 Netty handler 可安全复用；但单次 {@code encode} 调用依赖事件循环串行执行，
 * 不应在多个线程并发操作同一个 {@link Frame}。
 * <p>
 * Frame 布局（共 18B 头 + N B payload）
 * <pre>{@code
 *   +-------+-----+----+-----------+--------+----------+
 *   | Magic | Ver | T  | RequestId | Length | Payload  |
 *   |  4B   | 1B  | 1B |    8B     |   4B   |   N B    |
 *   +-------+-----+----+-----------+--------+----------+
 * }</pre>
 * <p>
 * 典型用例：
 * <pre>{@code
 * pipeline.addLast(new ProtocolEncoder());
 * channel.writeAndFlush(Frame.request(42L, payload));
 * }</pre>
 */
public final class ProtocolEncoder extends MessageToByteEncoder<Frame> {
    @Override
    /**
     * 将单个 {@link Frame} 序列化为协议字节流。
     *
     * @param ctx 当前 channel 的处理上下文，主要用于符合 Netty 编码器签名约定
     * @param frame 待发送的协议帧，包含已校验的头字段和 payload
     * @param out 输出缓冲区，编码结果会按协议顺序依次写入其中
     * @throws IllegalArgumentException 当 payload 长度超过协议允许的 16MB 上限时抛出
     * @implNote 编码顺序严格为 magic、version、type、requestId、payloadLength、payload；时间复杂度为 O(payloadLength)。
     *           方法末尾会调用 {@link Frame#close()} 释放 payload 引用计数，表示编码器接管并完成了该帧的发送前生命周期。
     */
    protected void encode(ChannelHandlerContext ctx, Frame frame, ByteBuf out) {
        int payloadLength = frame.payload().readableBytes();
        if (payloadLength > Frame.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload exceeds 16MB limit: " + payloadLength);
        }

        out.writeInt(frame.magic());
        out.writeByte(frame.version());
        out.writeByte(frame.type());
        out.writeLong(frame.requestId());
        out.writeInt(payloadLength);
        out.writeBytes(frame.payload(), frame.payload().readerIndex(), payloadLength);
        frame.close();
    }
}
