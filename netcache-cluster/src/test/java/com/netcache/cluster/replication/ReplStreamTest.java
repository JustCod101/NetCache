package com.netcache.cluster.replication;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplStreamTest {
    @Test
    void replicationCommandRoundTrips() {
        ReplStream command = new ReplStream(7L, OpCode.SET, new byte[]{1}, new byte[]{2, 3});
        ByteBuf encoded = command.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            ReplStream decoded = ReplStream.decode(encoded);

            assertThat(decoded.offset()).isEqualTo(7L);
            assertThat(decoded.opCode()).isEqualTo(OpCode.SET);
            assertThat(decoded.key()).containsExactly(1);
            assertThat(decoded.value()).containsExactly(2, 3);
        } finally {
            encoded.release();
        }
    }
}
