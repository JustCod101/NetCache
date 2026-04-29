package com.netcache.common.exception;

/**
 * 协议异常 —— 编解码链路里的「语法警报器」。
 * <p>
 * 当请求报文格式不对、字段不完整或协议状态机走偏时，使用这个异常统一上抛。
 * 没有它的话，调用方只能依赖通用非法参数异常，难以区分是业务错还是报文错。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由协议解码器、命令解析器和帧校验逻辑抛出</li>
 *   <li>下游由连接层把它映射成协议错误响应或断链策略</li>
 * </ul>
 * 线程安全：异常实例创建后不可变，可安全跨线程传播。
 * <p>
 * 典型用例：
 * <pre>
 * if (argc < 2) {
 *     throw new ProtocolException("SET 命令缺少 value 参数");
 * }
 * </pre>
 */
public final class ProtocolException extends NetCacheException {
    /**
     * 创建一个协议错误。
     *
     * @param message 协议层的人类可读错误描述
     * @implNote 错误码固定为 {@code PROTOCOL_ERROR}，便于上层统一拦截。
     */
    public ProtocolException(String message) {
        super("PROTOCOL_ERROR", message);
    }

    /**
     * 创建一个带根因的协议错误。
     *
     * @param message 协议层的人类可读错误描述
     * @param cause 底层解析失败根因，允许为 {@code null}
     * @implNote 当协议解析依赖下层 I/O 或数值转换时，保留根因更利于排障。
     */
    public ProtocolException(String message, Throwable cause) {
        super("PROTOCOL_ERROR", message, cause);
    }
}
