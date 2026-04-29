package com.netcache.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

/**
 * ByteBuf 工具类 —— Netty 缓冲区的「安全操作手册」。
 * <p>
 * 它把项目里常见的切片、可读性校验、内容复制和引用释放动作收拢到一起，避免每个
 * 调用点都手写一遍边界检查。没有它的话，读指针误移动、引用计数泄漏和测试比较噪音
 * 会在网络热路径里反复出现。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由协议编解码、复制流和测试代码传入 {@link ByteBuf}</li>
 *   <li>下游依赖 Netty 的引用计数模型与切片 API</li>
 * </ul>
 * 线程安全：类本身无状态，但 {@link ByteBuf} 通常不是线程安全对象。
 * 并发时应遵循调用方自己的线程归属模型。
 * <p>
 * 典型用例：
 * <pre>
 * ByteBuf header = ByteBufUtil.readRetainedSlice(frame, 8);
 * byte[] body = ByteBufUtil.copyReadableBytes(frame);
 * ByteBufUtil.assertEqual(expected, actual);
 * ByteBufUtil.release(header);
 * </pre>
 */
public final class ByteBufUtil {
    private ByteBufUtil() {
    }

    /**
     * 读取并保留一段切片，同时推进源缓冲区读指针。
     *
     * @param source 源缓冲区，当前读指针之后至少要有 {@code length} 个可读字节
     * @param length 需要切出的长度，单位为字节，必须大于等于 0
     * @return 引用计数加一后的切片；返回值与源缓冲区共享底层内存
     * @throws NullPointerException 当 {@code source} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code length} 小于 0 时抛出
     * @throws IndexOutOfBoundsException 当可读字节不足时抛出
     * @implNote 这里选用 {@code readRetainedSlice}，因为协议解析通常要“读走”头部。
     */
    public static ByteBuf readRetainedSlice(ByteBuf source, int length) {
        requireReadable(source, length);
        return source.readRetainedSlice(length);
    }

    /**
     * 从指定下标创建保留切片，但不改变读指针。
     *
     * @param source 源缓冲区，不能为空
     * @param index 切片起始下标，基于整个容量空间，单位为字节
     * @param length 切片长度，单位为字节，必须大于等于 0
     * @return 引用计数加一后的切片；与源缓冲区共享底层内存
     * @throws NullPointerException 当 {@code source} 为 {@code null} 时抛出
     * @throws IndexOutOfBoundsException 当 {@code index/length} 超出容量边界时抛出
     * @implNote 与 {@code readRetainedSlice} 不同，这个方法更像“拍一张局部快照”，
     *           适合随机访问而不是顺序消费。
     */
    public static ByteBuf retainedSlice(ByteBuf source, int index, int length) {
        Objects.requireNonNull(source, "source");
        if (index < 0 || length < 0 || index > source.capacity() - length) {
            throw new IndexOutOfBoundsException("index/length outside ByteBuf capacity");
        }
        return source.retainedSlice(index, length);
    }

    /**
     * 校验源缓冲区还有足够的可读字节。
     *
     * @param source 待校验的源缓冲区，不能为空
     * @param length 期望读取的字节数，单位为字节，必须大于等于 0
     * @throws NullPointerException 当 {@code source} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code length} 小于 0 时抛出
     * @throws IndexOutOfBoundsException 当可读字节不足时抛出
     * @implNote 把前置校验抽出来，目的是让各个解析点的报错文案保持一致。
     */
    public static void requireReadable(ByteBuf source, int length) {
        Objects.requireNonNull(source, "source");
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        if (source.readableBytes() < length) {
            throw new IndexOutOfBoundsException("required " + length + " readable bytes but found " + source.readableBytes());
        }
    }

    /**
     * 复制当前可读区域的全部字节。
     *
     * @param source 源缓冲区，不能为空
     * @return 新分配的字节数组；长度等于当前 {@code readableBytes()}，可能为空数组
     * @throws NullPointerException 当 {@code source} 为 {@code null} 时抛出
     * @implNote 用 {@code getBytes} 而不是 {@code readBytes}，避免偷偷推进读指针。
     */
    public static byte[] copyReadableBytes(ByteBuf source) {
        Objects.requireNonNull(source, "source");
        byte[] bytes = new byte[source.readableBytes()];
        source.getBytes(source.readerIndex(), bytes);
        return bytes;
    }

    /**
     * 断言两个缓冲区的可读内容完全一致。
     *
     * @param expected 期望值缓冲区，不能为空
     * @param actual 实际值缓冲区，不能为空
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     * @throws AssertionError 当可读长度或任意字节不同步时抛出
     * @implNote 这个方法面向测试代码设计，按 readable 区间比较，避免受写索引干扰。
     */
    public static void assertEqual(ByteBuf expected, ByteBuf actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        int expectedReadable = expected.readableBytes();
        int actualReadable = actual.readableBytes();
        if (expectedReadable != actualReadable) {
            throw new AssertionError("ByteBuf readable bytes differ: expected " + expectedReadable + " but was " + actualReadable);
        }

        int expectedIndex = expected.readerIndex();
        int actualIndex = actual.readerIndex();
        for (int i = 0; i < expectedReadable; i++) {
            byte expectedByte = expected.getByte(expectedIndex + i);
            byte actualByte = actual.getByte(actualIndex + i);
            // 逐字节比对虽然是 O(n)，但失败时能给出最精确的偏移，
            // 对排查协议编码问题比“整体不相等”更有价值。
            if (expectedByte != actualByte) {
                throw new AssertionError("ByteBuf differs at readable offset " + i + ": expected " + expectedByte + " but was " + actualByte);
            }
        }
    }

    /**
     * 释放一个可能带引用计数的对象。
     *
     * @param reference 待释放对象，通常是 {@link ByteBuf} 或其他 Netty 引用计数对象
     * @return {@code true} 表示本次调用让引用计数归零并真正释放；
     *         {@code false} 表示对象不是引用计数类型或仍有剩余引用
     * @implNote 统一走 {@link ReferenceCountUtil}，让调用方不必自己做类型分支。
     */
    public static boolean release(Object reference) {
        return ReferenceCountUtil.release(reference);
    }
}
