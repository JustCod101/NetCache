package com.netcache.server.netty;

import com.netcache.protocol.Frame;
import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.codec.MagicValidator;
import com.netcache.protocol.codec.ProtocolDecoder;
import com.netcache.protocol.codec.ProtocolEncoder;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.server.handler.HandlerRegistry;
import com.netcache.storage.StorageEngine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDispatcherTest {
    @Test
    void dispatchesSetThenGetThroughProtocolPipeline() {
        try (StorageEngine storage = new StorageEngine()) {
            EmbeddedChannel server = new EmbeddedChannel(
                    new MagicValidator(),
                    new ProtocolDecoder(),
                    new ProtocolEncoder(),
                    new CommandDispatcher(HandlerRegistry.singleNode(storage))
            );

            Response set = exchange(server, new Request(OpCode.SET, List.of(bytes("key"), bytes("value")), 1L));
            Response get = exchange(server, new Request(OpCode.GET, List.of(bytes("key")), 2L));

            assertThat(set.status()).isEqualTo(Status.OK);
            assertThat(get.status()).isEqualTo(Status.OK);
            assertThat(get.type()).isEqualTo(ResultType.BYTES);
            assertThat(get.body()).containsExactly(bytes("value"));

            server.finishAndReleaseAll();
        }
    }

    private static Response exchange(EmbeddedChannel server, Request request) {
        ByteBuf encodedRequest = encodeRequest(request);
        assertThat(server.writeInbound(encodedRequest)).isFalse();
        ByteBuf encodedResponse = server.readOutbound();
        try {
            return decodeResponse(encodedResponse);
        } finally {
            encodedResponse.release();
        }
    }

    private static ByteBuf encodeRequest(Request request) {
        ByteBuf payload = request.encodePayload(UnpooledByteBufAllocatorHolder.ALLOCATOR);
        EmbeddedChannel encoder = new EmbeddedChannel(new ProtocolEncoder());
        assertThat(encoder.writeOutbound(Frame.request(request.requestId(), payload))).isTrue();
        ByteBuf encoded = encoder.readOutbound();
        encoder.finishAndReleaseAll();
        return encoded;
    }

    private static Response decodeResponse(ByteBuf encodedResponse) {
        EmbeddedChannel decoder = new EmbeddedChannel(new ProtocolDecoder());
        assertThat(decoder.writeInbound(encodedResponse.retainedDuplicate())).isTrue();
        Frame frame = decoder.readInbound();
        try {
            return Response.decodePayload(frame.requestId(), frame.payload().slice());
        } finally {
            frame.close();
            decoder.finishAndReleaseAll();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class UnpooledByteBufAllocatorHolder {
        private static final io.netty.buffer.ByteBufAllocator ALLOCATOR = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
    }
}
