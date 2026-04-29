package com.netcache.storage.lru;

import com.netcache.common.ByteKey;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个 LRU 分段，相当于一条带锁的候诊队列。
 * 每段只管理自己那部分 key，用双向链表维护冷热顺序。
 * 没有分段，所有 key 都挤在一个链表里，锁竞争会很明显。
 *
 * <p>上游由 {@link LruIndex} 调度；内部依赖 HashMap + 双向链表，
 * 同时保证 O(1) 定位和 O(1) 调整顺序。</p>
 *
 * <p>线程安全说明：线程安全，但粒度仅限单段。
 * 并发模型是“一段一把 {@link ReentrantLock}”。</p>
 *
 * <pre>{@code
 * LruSegment segment = new LruSegment();
 * segment.touch(key);
 * ByteKey victim = segment.evictOne();
 * segment.remove(key);
 * }</pre>
 */
final class LruSegment {
    /** 保护链表和索引表的一致性。 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 负责 O(1) 找到节点本体。 */
    private final Map<ByteKey, Node> nodes = new HashMap<>();
    /** 链表头代表最近访问。 */
    private Node head;
    /** 链表尾代表最久未访问。 */
    private Node tail;

    /**
     * 标记某个 key 被访问过，并把它提到队头。
     *
     * @param key 刚被命中的缓存键
     * @implNote 平均复杂度 O(1)。新 key 直接插头部，旧 key 则搬到头部。
     */
    void touch(ByteKey key) {
        lock.lock();
        try {
            Node node = nodes.get(key);
            if (node == null) {
                node = new Node(key);
                nodes.put(key, node);
                // 新节点直接放到头部，天然就是“刚被访问过”。
                addFirst(node);
                return;
            }
            moveToHead(node);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从当前分段移除一个 key。
     *
     * @param key 要从 LRU 结构里摘掉的缓存键
     * @implNote 平均复杂度 O(1)。如果 key 不存在，会静默忽略。
     */
    void remove(ByteKey key) {
        lock.lock();
        try {
            Node node = nodes.remove(key);
            if (node != null) {
                unlink(node);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 淘汰当前分段里最冷的 key。
     *
     * @return 链表尾部的 key；若分段为空则返回 {@code null}
     * @implNote 平均复杂度 O(1)。链表尾部就是最久没碰过的节点。
     */
    ByteKey evictOne() {
        lock.lock();
        try {
            if (tail == null) {
                return null;
            }
            Node evicted = tail;
            nodes.remove(evicted.key);
            unlink(evicted);
            return evicted.key;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前分段登记的 key 数量。
     *
     * @return 本段的节点数
     */
    int size() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把已有节点移动到链表头。
     *
     * @param node 已存在的节点
     * @implNote 先摘链再插头，逻辑更统一，也少分支。
     */
    private void moveToHead(Node node) {
        if (node == head) {
            return;
        }
        unlink(node);
        addFirst(node);
    }

    /**
     * 把节点插到链表头部。
     *
     * @param node 待插入节点
     */
    private void addFirst(Node node) {
        node.previous = null;
        node.next = head;
        if (head != null) {
            head.previous = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    /**
     * 把节点从链表中摘下来。
     *
     * @param node 待摘除节点
     * @implNote 这里统一处理头尾指针，确保删除头节点、尾节点和中间
     *     节点都走同一条路径，不容易漏边界。
     */
    private void unlink(Node node) {
        if (node.previous != null) {
            node.previous.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.previous = node.previous;
        } else {
            tail = node.previous;
        }
        node.previous = null;
        node.next = null;
    }

    /**
     * LRU 双向链表节点。
     * 只保存 key，不保存值，避免索引层和数据层互相绑死。
     */
    private static final class Node {
        /** 当前节点代表的缓存键。 */
        private final ByteKey key;
        /** 前驱节点，离头更近。 */
        private Node previous;
        /** 后继节点，离尾更近。 */
        private Node next;

        /**
         * 创建一个新的链表节点。
         *
         * @param key 当前节点绑定的缓存键
         */
        private Node(ByteKey key) {
            this.key = key;
        }
    }
}
