package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public final class MagicValidator extends MessageToMessageDecoder<ByteBuf> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (msg.readableBytes() < Integer.BYTES || msg.getInt(msg.readerIndex()) != Frame.MAGIC) {
            ctx.close();
            return;
        }
        out.add(msg.retain());
    }
}
