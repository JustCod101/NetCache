package com.netcache.server.handler;

import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerRegistryTest {
    @Test
    void handlesAllSingleNodeCommands() throws InterruptedException {
        try (StorageEngine storage = new StorageEngine()) {
            Map<OpCode, CommandHandler> handlers = HandlerRegistry.singleNode(storage);

            assertThat(handlers).containsKeys(
                    OpCode.GET, OpCode.SET, OpCode.DEL, OpCode.EXPIRE, OpCode.TTL,
                    OpCode.EXISTS, OpCode.INCR, OpCode.DECR, OpCode.PING, OpCode.INFO
            );

            assertThat(handle(handlers, OpCode.SET, 1, "alpha", "1").status()).isEqualTo(Status.OK);
            assertThat(handle(handlers, OpCode.GET, 2, "alpha").body()).containsExactly(bytes("1"));
            assertThat(intBody(handle(handlers, OpCode.EXISTS, 3, "alpha"))).isEqualTo(1L);
            assertThat(intBody(handle(handlers, OpCode.INCR, 4, "alpha"))).isEqualTo(2L);
            assertThat(intBody(handle(handlers, OpCode.DECR, 5, "alpha"))).isEqualTo(1L);
            assertThat(intBody(handle(handlers, OpCode.TTL, 6, "alpha"))).isEqualTo(-1L);
            assertThat(intBody(handle(handlers, OpCode.EXPIRE, 7, "alpha", "20"))).isEqualTo(1L);
            Thread.sleep(60);
            assertThat(handle(handlers, OpCode.GET, 8, "alpha").status()).isEqualTo(Status.NIL);
            handle(handlers, OpCode.SET, 9, "alpha", "1");
            assertThat(intBody(handle(handlers, OpCode.DEL, 10, "alpha"))).isEqualTo(1L);
            assertThat(new String(handle(handlers, OpCode.PING, 11).body(), StandardCharsets.UTF_8)).isEqualTo("PONG");
            assertThat(handle(handlers, OpCode.INFO, 12, "default").type()).isEqualTo(ResultType.STRING);
        }
    }

    private static Response handle(Map<OpCode, CommandHandler> handlers, OpCode opCode, long requestId, String... args) {
        return handlers.get(opCode).handle(new Request(opCode, java.util.Arrays.stream(args).map(HandlerRegistryTest::bytes).toList(), requestId));
    }

    private static long intBody(Response response) {
        return ByteBuffer.wrap(response.body()).getLong();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
