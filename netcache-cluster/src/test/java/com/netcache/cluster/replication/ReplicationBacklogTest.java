package com.netcache.cluster.replication;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicationBacklogTest {
    @Test
    void readsIncrementalBytesFromOffset() {
        ReplicationBacklog backlog = new ReplicationBacklog(32);
        long first = backlog.write(new byte[]{1, 2});
        long second = backlog.write(new byte[]{3, 4});

        ByteBuf all = backlog.readFrom(first);
        ByteBuf incremental = backlog.readFrom(second);
        try {
            assertThat(read(all)).containsExactly(1, 2, 3, 4);
            assertThat(read(incremental)).containsExactly(3, 4);
        } finally {
            all.release();
            incremental.release();
        }
    }

    @Test
    void rejectsOffsetsTrimmedOutOfRange() {
        ReplicationBacklog backlog = new ReplicationBacklog(4);
        backlog.write(new byte[]{1, 2, 3});
        backlog.write(new byte[]{4, 5, 6});

        assertThat(backlog.firstOffset()).isGreaterThan(0L);
        assertThatThrownBy(() -> backlog.readFrom(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] read(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }
}
