package com.netcache.server.handler;

import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Response;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 统一创建协议响应对象的工具类，像响应装配台一样把常见结果形态快速封装成标准包裹。
 *
 * <p>它解决的是各个 handler 重复拼装 {@link Response} 的问题：状态码、结果类型和字节编码规则集中定义，
 * 避免不同命令实现各自散写造成协议不一致。
 *
 * <p>上游是所有命令处理器；下游直接依赖协议层的 {@link Response}、{@link Status} 与 {@link ResultType}。
 *
 * <p>线程安全说明：类本身无状态，所有方法只创建新对象，因此线程安全。
 */
final class Responses {
    /**
     * 禁止实例化纯工具类。
     *
     * @implNote 所有响应工厂方法均为静态方法，不需要对象状态。
     */
    private Responses() {
    }

    /**
     * 创建表示成功且无返回体的响应。
     *
     * @param requestId 原请求 ID
     * @return 状态为 OK、结果类型为 NULL 的响应
     * @implNote 用于 SET 一类“执行成功但没有业务返回值”的命令。
     */
    static Response okNull(long requestId) {
        return new Response(Status.OK, ResultType.NULL, new byte[0], requestId);
    }

    /**
     * 创建表示键不存在的空响应。
     *
     * @param requestId 原请求 ID
     * @return 状态为 NIL、结果类型为 NULL 的响应
     * @implNote 这里把“没有值”与“执行成功但无返回体”分成不同状态，便于客户端区分语义。
     */
    static Response nil(long requestId) {
        return new Response(Status.NIL, ResultType.NULL, new byte[0], requestId);
    }

    /**
     * 创建字节数组结果响应。
     *
     * @param requestId 原请求 ID
     * @param body 需要原样返回给客户端的字节内容
     * @return 状态为 OK、结果类型为 BYTES 的响应
     * @implNote 方法不复制 {@code body}，调用方应保证传入内容在响应编码前不会被意外修改。
     */
    static Response bytes(long requestId, byte[] body) {
        return new Response(Status.OK, ResultType.BYTES, body, requestId);
    }

    /**
     * 创建 UTF-8 字符串结果响应。
     *
     * @param requestId 原请求 ID
     * @param value 需要返回的字符串值
     * @return 状态为 OK、结果类型为 STRING 的响应
     * @implNote 统一采用 UTF-8 编码，避免不同 handler 各自选择编码带来的兼容性问题。
     */
    static Response string(long requestId, String value) {
        return new Response(Status.OK, ResultType.STRING, value.getBytes(StandardCharsets.UTF_8), requestId);
    }

    /**
     * 创建 64 位整数结果响应。
     *
     * @param requestId 原请求 ID
     * @param value 需要返回的 long 数值
     * @return 状态为 OK、结果类型为 INT64 的响应
     * @implNote 使用 {@link ByteBuffer} 按协议约定编码 long，保持所有数值型命令的二进制表示一致。
     */
    static Response int64(long requestId, long value) {
        return new Response(Status.OK, ResultType.INT64, ByteBuffer.allocate(Long.BYTES).putLong(value).array(), requestId);
    }
}
