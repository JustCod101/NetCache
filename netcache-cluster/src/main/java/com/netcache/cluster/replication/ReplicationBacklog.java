package com.netcache.cluster.replication;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 复制积压缓冲区 —— 主从复制链路的「录音带」。
 * <p>
 * 主节点每写入一条变更命令，都会先把序列化后的字节流追加到这里；落后的从节点随后可以按 offset
 * 从积压区补读数据，完成增量同步。没有它，主节点只能每次都做全量重放，复制延迟和恢复成本都会飙升。
 * <p>
 * 协作关系：上游由 {@link MasterReplicator} 负责写入和回放；下游读者通常是
 * {@link SlaveReplicator}，数据载体则是 {@link ByteBuf}。
 * <p>
 * 线程安全：线程安全。全部可变状态通过实例级 {@code synchronized} 保护，采用单对象串行读写模型。
 * <p>
 * 典型用例：
 * <pre>{@code
 * ReplicationBacklog backlog = new ReplicationBacklog(16 * 1024 * 1024);
 * long offset = backlog.write(encodedCommand);
 * ByteBuf delta = backlog.readFrom(offset);
 * }</pre>
 * <p>
 * 偏移推进示意：
 * <pre>
 * firstOffset                         nextOffset
 *     ↓                                   ↓
 * [ entry-1 ][ entry-2 ][ entry-3 ][ free... ]
 *            ↑
 *         slave offset
 * </pre>
 */
public final class ReplicationBacklog {
    /** 积压区允许保留的最大字节数。 */
    private final int capacityBytes;
    /** 按写入顺序保存的复制条目队列，队首是最旧数据。 */
    private final Deque<Entry> entries = new ArrayDeque<>();
    /** 下一次写入的全局逻辑偏移。 */
    private long nextOffset;
    /** 当前积压区最早仍可读取的偏移。 */
    private long firstOffset;
    /** 当前缓冲区已占用的总字节数。 */
    private int usedBytes;

    /**
     * 创建复制积压缓冲区。
     *
     * @param capacityBytes 最大保留字节数，必须大于 0
     * @throws IllegalArgumentException 当 {@code capacityBytes <= 0} 时抛出
     * @implNote 容量既决定可承载的增量窗口，也决定从节点允许落后的最大距离。
     */
    public ReplicationBacklog(int capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive");
        }
        this.capacityBytes = capacityBytes;
    }

    /**
     * 追加一条已经编码完成的复制命令。
     *
     * @param command 命令字节流
     * @return 该命令写入前的起始 offset
     * @throws NullPointerException 当 {@code command} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当单条命令长度超过积压区容量时抛出
     * @implNote 数据会先 clone 一份，避免调用方后续修改原数组导致积压区内容被污染。
     */
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

    /**
     * 从指定 offset 开始读取增量复制流。
     *
     * @param offset 从节点已确认的下一个 offset
     * @return 包含从该 offset 到当前尾部全部数据的新 {@link ByteBuf}
     * @throws IllegalArgumentException 当 offset 不在当前积压窗口内时抛出
     * @implNote 读取时会跳过已经完全早于目标 offset 的条目；若命中条目中间位置，只复制其后半段。
     */
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
            // 允许从条目中间开始复制，这样从节点即使停在某条命令末尾也能无缝续传。
            int start = (int) Math.max(0, offset - entry.offset);
            out.writeBytes(entry.bytes, start, entry.bytes.length - start);
        }
        return out;
    }

    /**
     * 返回下一次写入将使用的 offset。
     *
     * @return 当前积压区尾偏移
     * @implNote 可把它理解成“主节点已经复制到哪里”的上界游标。
     */
    public synchronized long nextOffset() {
        return nextOffset;
    }

    /**
     * 返回当前积压区仍然保留的最小 offset。
     *
     * @return 当前最早可读 offset
     * @implNote 当从节点 offset 早于该值时，说明增量窗口已经丢失，需要退化到全量同步。
     */
    public synchronized long firstOffset() {
        return firstOffset;
    }

    /**
     * 裁剪超出容量的旧条目。
     *
     * @implNote 使用“从队首不断淘汰”的策略，是因为复制积压区只关心最新窗口；旧数据一旦超容就不再有增量价值。
     */
    private void trim() {
        while (usedBytes > capacityBytes && !entries.isEmpty()) {
            Entry removed = entries.removeFirst();
            usedBytes -= removed.bytes.length;
            firstOffset = removed.offset + removed.bytes.length;
        }
    }

    /**
     * 积压区中的单条复制记录。
     *
     * @param offset 该记录起始 offset
     * @param bytes 记录内容
     */
    private record Entry(long offset, byte[] bytes) {
    }
}
