package com.netcache.cluster.replication;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MasterReplicator {
    private final ReplicationBacklog backlog;
    private final List<SlaveReplicator> slaves = new CopyOnWriteArrayList<>();

    public MasterReplicator(ReplicationBacklog backlog) {
        this.backlog = Objects.requireNonNull(backlog, "backlog");
    }

    public long onWriteCommand(OpCode opCode, byte[] key, byte[] value) {
        ByteBuf encoded = new ReplStream(backlog.nextOffset(), opCode, key, value).encode(ByteBufAllocator.DEFAULT);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.readBytes(bytes);
            long offset = backlog.write(bytes);
            for (SlaveReplicator slave : slaves) {
                slave.applyStream(backlog.readFrom(slave.offset()));
            }
            return offset;
        } finally {
            encoded.release();
        }
    }

    public void registerSlave(SlaveReplicator slave) {
        slaves.add(Objects.requireNonNull(slave, "slave"));
        slave.applyStream(backlog.readFrom(slave.offset()));
    }

    public ReplicationBacklog backlog() {
        return backlog;
    }
}
