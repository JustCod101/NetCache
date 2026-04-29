package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class ProtocolDecoder extends ByteToMessageDecoder {
    @Override
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
