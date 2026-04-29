package com.netcache.protocol.command;

import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestResponseTest {
    @Test
    void requestPayloadRoundTrips() {
        Request request = new Request(
                OpCode.SET,
                List.of(bytes("alpha"), bytes("value"), bytes("1000")),
                42L
        );

        ByteBuf payload = request.encodePayload(UnpooledByteBufAllocator.DEFAULT);
        try {
            Request decoded = Request.decodePayload(42L, payload.copy());

            assertThat(decoded.opCode()).isEqualTo(OpCode.SET);
            assertThat(decoded.requestId()).isEqualTo(42L);
            assertThat(decoded.args()).containsExactly(bytes("alpha"), bytes("value"), bytes("1000"));
        } finally {
            payload.release();
        }
    }

    @Test
    void responsePayloadRoundTrips() {
        Response response = new Response(Status.OK, ResultType.BYTES, bytes("stored"), 99L);

        ByteBuf payload = response.encodePayload(UnpooledByteBufAllocator.DEFAULT);
        try {
            Response decoded = Response.decodePayload(99L, payload.copy());

            assertThat(decoded.status()).isEqualTo(Status.OK);
            assertThat(decoded.type()).isEqualTo(ResultType.BYTES);
            assertThat(decoded.body()).containsExactly(bytes("stored"));
            assertThat(decoded.requestId()).isEqualTo(99L);
        } finally {
            payload.release();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
