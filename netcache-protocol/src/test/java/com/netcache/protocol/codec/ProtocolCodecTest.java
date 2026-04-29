package com.netcache.protocol.codec;

import com.netcache.protocol.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolCodecTest {
    @Test
    void encodesHeaderWithDocumentedByteLayout() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolEncoder());

        assertThat(channel.writeOutbound(Frame.request(7L, payload))).isTrue();
        ByteBuf encoded = channel.readOutbound();
        try {
            assertThat(encoded.readInt()).isEqualTo(Frame.MAGIC);
            assertThat(encoded.readByte()).isEqualTo(Frame.VERSION);
            assertThat(encoded.readByte()).isEqualTo(Frame.TYPE_REQUEST);
            assertThat(encoded.readLong()).isEqualTo(7L);
            assertThat(encoded.readInt()).isEqualTo(3);
            assertThat(encoded.readableBytes()).isEqualTo(3);
            assertThat(encoded.readByte()).isEqualTo((byte) 1);
            assertThat(encoded.readByte()).isEqualTo((byte) 2);
            assertThat(encoded.readByte()).isEqualTo((byte) 3);
        } finally {
            encoded.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void frameRoundTripsThroughEncoderAndDecoder() {
        ByteBuf encoded = encode(Frame.response(123L, Unpooled.wrappedBuffer(new byte[]{9, 8, 7})));
        EmbeddedChannel decoder = new EmbeddedChannel(new ProtocolDecoder());

        assertThat(decoder.writeInbound(encoded)).isTrue();
        Frame decoded = decoder.readInbound();
        try {
            assertThat(decoded.magic()).isEqualTo(Frame.MAGIC);
            assertThat(decoded.version()).isEqualTo(Frame.VERSION);
            assertThat(decoded.type()).isEqualTo(Frame.TYPE_RESPONSE);
            assertThat(decoded.requestId()).isEqualTo(123L);
            assertThat(readAll(decoded.payload())).containsExactly(9, 8, 7);
        } finally {
            decoded.close();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void decoderWaitsForHalfPacketBeforeEmittingFrame() {
        ByteBuf encoded = encode(Frame.request(1L, Unpooled.wrappedBuffer(new byte[]{4, 5, 6, 7})));
        ByteBuf firstHalf = encoded.readRetainedSlice(10);
        ByteBuf secondHalf = encoded.readRetainedSlice(encoded.readableBytes());
        encoded.release();
        EmbeddedChannel decoder = new EmbeddedChannel(new ProtocolDecoder());

        assertThat(decoder.writeInbound(firstHalf)).isFalse();
        assertThat((Object) decoder.readInbound()).isNull();
        assertThat(decoder.writeInbound(secondHalf)).isTrue();

        Frame decoded = decoder.readInbound();
        try {
            assertThat(decoded.requestId()).isEqualTo(1L);
            assertThat(readAll(decoded.payload())).containsExactly(4, 5, 6, 7);
        } finally {
            decoded.close();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void decoderEmitsAllFramesFromStickyPacket() {
        ByteBuf first = encode(Frame.request(11L, Unpooled.wrappedBuffer(new byte[]{1})));
        ByteBuf second = encode(Frame.response(12L, Unpooled.wrappedBuffer(new byte[]{2, 3})));
        ByteBuf sticky = Unpooled.wrappedBuffer(first, second);
        EmbeddedChannel decoder = new EmbeddedChannel(new ProtocolDecoder());

        assertThat(decoder.writeInbound(sticky)).isTrue();
        Frame decodedFirst = decoder.readInbound();
        Frame decodedSecond = decoder.readInbound();
        try {
            assertThat(decodedFirst.requestId()).isEqualTo(11L);
            assertThat(readAll(decodedFirst.payload())).containsExactly(1);
            assertThat(decodedSecond.requestId()).isEqualTo(12L);
            assertThat(readAll(decodedSecond.payload())).containsExactly(2, 3);
        } finally {
            decodedFirst.close();
            decodedSecond.close();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void magicValidatorClosesInvalidFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new MagicValidator());
        ByteBuf invalid = Unpooled.buffer(Integer.BYTES).writeInt(0x12345678);

        assertThat(channel.writeInbound(invalid)).isFalse();
        assertThat(channel.isOpen()).isFalse();
        channel.finishAndReleaseAll();
    }

    private static ByteBuf encode(Frame frame) {
        EmbeddedChannel encoder = new EmbeddedChannel(new ProtocolEncoder());
        assertThat(encoder.writeOutbound(frame)).isTrue();
        ByteBuf encoded = encoder.readOutbound();
        encoder.finishAndReleaseAll();
        return encoded;
    }

    private static byte[] readAll(ByteBuf payload) {
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        return bytes;
    }
}
