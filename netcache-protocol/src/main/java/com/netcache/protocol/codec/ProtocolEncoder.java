package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class ProtocolEncoder extends MessageToByteEncoder<Frame> {
    @Override
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
