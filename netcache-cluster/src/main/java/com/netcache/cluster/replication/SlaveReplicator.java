package com.netcache.cluster.replication;

import com.netcache.common.ByteKey;
import com.netcache.protocol.OpCode;
import com.netcache.storage.StorageEngine;
import io.netty.buffer.ByteBuf;

import java.util.Objects;

public final class SlaveReplicator {
    private final StorageEngine storageEngine;
    private long offset;

    public SlaveReplicator(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    public void connect(MasterReplicator master) {
        master.registerSlave(this);
    }

    public void applyStream(ByteBuf stream) {
        try {
            while (stream.isReadable()) {
                int startReadable = stream.readableBytes();
                ReplStream command = ReplStream.decode(stream);
                apply(command);
                offset = command.offset() + (startReadable - stream.readableBytes());
            }
        } finally {
            stream.release();
        }
    }

    public long offset() {
        return offset;
    }

    private void apply(ReplStream command) {
        ByteKey key = ByteKey.copyOf(command.key());
        if (command.opCode() == OpCode.SET) {
            storageEngine.set(key, command.value());
        } else if (command.opCode() == OpCode.DEL) {
            storageEngine.del(key);
        }
    }
}
