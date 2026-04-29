package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * 魔数校验器，像协议入口处的“门卫”，先快速确认入站字节是否看起来像 NetCache 帧，再决定是否继续放行。
 * <p>
 * 它解决的是“尽早拦截明显非法流量”的问题；如果没有这层快速校验，后续解码器会在无意义的数据上浪费解析成本，
 * 甚至可能把随机字节误判为合法长度字段。
 * <p>
 * 上游通常直接接在 socket 入站字节之后，下游是正式的 {@link ProtocolDecoder} 或其他更重的协议处理器。
 * <p>
 * 线程安全性：该 handler 无可变状态，适合在 Netty 中复用；具体调用仍依赖单 channel 事件循环串行模型。
 * <p>
 * 典型用例：
 * <pre>{@code
 * pipeline.addLast(new MagicValidator());
 * pipeline.addLast(new ProtocolDecoder());
 * }</pre>
 */
public final class MagicValidator extends MessageToMessageDecoder<ByteBuf> {
    @Override
    /**
     * 校验当前字节流起始处的 magic 是否匹配 NetCache 协议。
     *
     * @param ctx 当前连接上下文；当字节不足 4B 或 magic 不匹配时会直接关闭连接
     * @param msg 待校验的入站字节缓冲区，readerIndex 应指向帧起始位置
     * @param out 放行列表；仅当 magic 合法时才追加一个 retain 后的原始缓冲区
     * @implNote 当前实现使用 {@code getInt(readerIndex)} 做前瞻读取，不推进 readerIndex；校验通过后调用 {@code retain()}，
     *           将引用计数所有权继续传给下游。校验复杂度为 O(1)。
     */
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (msg.readableBytes() < Integer.BYTES || msg.getInt(msg.readerIndex()) != Frame.MAGIC) {
            ctx.close();
            return;
        }
        out.add(msg.retain());
    }
}
