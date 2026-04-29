package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 协议解码器，像拆箱流水线，把连续到达的 TCP 字节流重新切分成一个个完整的 {@link Frame}。
 * <p>
 * 它解决的是“半包/粘包条件下如何恢复协议边界”的问题；如果没有它，上层业务处理器将直接面对不完整或拼接在一起的字节流，
 * 无法安全读取请求头和 payload。
 * <p>
 * 上游依赖 Netty 从 socket 累积输入字节，下游把解码得到的 {@link Frame} 交给命令解码器、复制处理器或心跳处理器。
 * <p>
 * 线程安全性：该解码器本身无共享可变字段，但它依赖 Netty 对单个 channel 的串行事件循环模型；不要让多个线程并发驱动同一实例处理同一连接数据。
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
 * pipeline.addLast(new ProtocolDecoder());
 * // 下游 handler 将收到完整 Frame，而不是零碎字节块
 * }</pre>
 */
public final class ProtocolDecoder extends ByteToMessageDecoder {
    @Override
    /**
     * 从输入缓冲区中尽可能解出一个完整帧。
     *
     * @param ctx 当前连接的处理上下文；当检测到非法 magic 或非法长度时会用它关闭连接
     * @param in Netty 累积的入站字节流，可能只包含半个头、一个完整帧，或多个粘连帧
     * @param out 解码结果输出列表；仅在成功拿到完整帧时追加一个 {@link Frame}
     * @implNote 当字节数不足 18B 头时直接返回；读取头前先 {@code markReaderIndex()}，若 payload 未到齐则用
     *           {@code resetReaderIndex()} 回滚，等待更多字节到达；payload 使用 {@code readRetainedSlice} 截取，
     *           将引用计数所有权转交给下游处理器。单次成功解码复杂度为 O(payloadLength)。
     */
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < Frame.HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();
        int magic = in.readInt();
        if (magic != Frame.MAGIC) {
            ctx.close();
            return;
        }

        byte version = in.readByte();
        byte type = in.readByte();
        long requestId = in.readLong();
        int payloadLength = in.readInt();
        if (payloadLength < 0 || payloadLength > Frame.MAX_PAYLOAD_LENGTH) {
            ctx.close();
            return;
        }

        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLength);
        out.add(new Frame(magic, version, type, requestId, payload));
    }
}
