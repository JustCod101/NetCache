package com.netcache.storage.lru;

import com.netcache.common.ByteKey;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

final class LruSegment {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ByteKey, Node> nodes = new HashMap<>();
    private Node head;
    private Node tail;

    void touch(ByteKey key) {
        lock.lock();
        try {
            Node node = nodes.get(key);
            if (node == null) {
                node = new Node(key);
                nodes.put(key, node);
                addFirst(node);
                return;
            }
            moveToHead(node);
        } finally {
            lock.unlock();
        }
    }

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

    int size() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    private void moveToHead(Node node) {
        if (node == head) {
            return;
        }
        unlink(node);
        addFirst(node);
    }

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

    private static final class Node {
        private final ByteKey key;
        private Node previous;
        private Node next;

        private Node(ByteKey key) {
            this.key = key;
        }
    }
}
