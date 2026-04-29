package com.netcache.cluster.replication;

import com.netcache.common.ByteKey;
import com.netcache.protocol.OpCode;
import com.netcache.storage.StorageEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationFlowTest {
    @Test
    void masterWriteIsVisibleOnSlaveWithinOneSecond() throws InterruptedException {
        ReplicationBacklog backlog = new ReplicationBacklog(1024 * 1024);
        MasterReplicator master = new MasterReplicator(backlog);
        try (StorageEngine slaveStorage = new StorageEngine()) {
            SlaveReplicator slave = new SlaveReplicator(slaveStorage);
            slave.connect(master);

            master.onWriteCommand(OpCode.SET, bytes("k"), bytes("v"));

            awaitValue(slaveStorage, "k", "v", Duration.ofSeconds(1));
        }
    }

    @Test
    void slaveReconnectAppliesIncrementalBacklog() throws InterruptedException {
        ReplicationBacklog backlog = new ReplicationBacklog(1024 * 1024);
        MasterReplicator master = new MasterReplicator(backlog);
        try (StorageEngine slaveStorage = new StorageEngine()) {
            SlaveReplicator slave = new SlaveReplicator(slaveStorage);

            master.onWriteCommand(OpCode.SET, bytes("before"), bytes("1"));
            slave.connect(master);
            master.onWriteCommand(OpCode.SET, bytes("after"), bytes("2"));

            awaitValue(slaveStorage, "before", "1", Duration.ofSeconds(1));
            awaitValue(slaveStorage, "after", "2", Duration.ofSeconds(1));
        }
    }

    private static void awaitValue(StorageEngine storage, String key, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            byte[] value = storage.get(ByteKey.copyOf(bytes(key))).orElse(null);
            if (java.util.Arrays.equals(value, bytes(expected))) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(storage.get(ByteKey.copyOf(bytes(key)))).hasValue(bytes(expected));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
