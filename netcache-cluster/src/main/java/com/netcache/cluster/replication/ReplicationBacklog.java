package com.netcache.cluster.replication;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class ReplicationBacklog {
    private final int capacityBytes;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextOffset;
    private long firstOffset;
    private int usedBytes;

    public ReplicationBacklog(int capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive");
        }
        this.capacityBytes = capacityBytes;
    }

    public synchronized long write(byte[] command) {
        Objects.requireNonNull(command, "command");
        if (command.length > capacityBytes) {
            throw new IllegalArgumentException("command exceeds backlog capacity");
        }
        long offset = nextOffset;
        byte[] copy = command.clone();
        entries.addLast(new Entry(offset, copy));
        nextOffset += copy.length;
        usedBytes += copy.length;
        trim();
        return offset;
    }

    public synchronized ByteBuf readFrom(long offset) {
        if (offset < firstOffset || offset > nextOffset) {
            throw new IllegalArgumentException("offset outside backlog range: " + offset);
        }
        ByteBuf out = ByteBufAllocator.DEFAULT.buffer((int) (nextOffset - offset));
        for (Entry entry : entries) {
            long entryEnd = entry.offset + entry.bytes.length;
            if (entryEnd <= offset) {
                continue;
            }
            int start = (int) Math.max(0, offset - entry.offset);
            out.writeBytes(entry.bytes, start, entry.bytes.length - start);
        }
        return out;
    }

    public synchronized long nextOffset() {
        return nextOffset;
    }

    public synchronized long firstOffset() {
        return firstOffset;
    }

    private void trim() {
        while (usedBytes > capacityBytes && !entries.isEmpty()) {
            Entry removed = entries.removeFirst();
            usedBytes -= removed.bytes.length;
            firstOffset = removed.offset + removed.bytes.length;
        }
    }

    private record Entry(long offset, byte[] bytes) {
    }
}
