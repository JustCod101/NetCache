package com.netcache.common.exception;

/**
 * 存储异常 —— 数据层的「故障转运单」。
 * <p>
 * 它承接内存表、持久化、淘汰和容量保护等场景里的运行时失败，让上层知道问题出在
 * 存储子系统。没有这个中间层的话，所有异常都会挤在基类里，语义粒度太粗。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由存储引擎、淘汰器、持久化适配器等模块抛出</li>
 *   <li>下游由服务端响应映射和监控系统按错误码分类处理</li>
 * </ul>
 * 线程安全：异常对象创建后不再修改，可安全跨线程传播。
 * <p>
 * 典型用例：
 * <pre>
 * throw new StorageException("TTL_INVALID", "ttl 不能小于 0");
 * </pre>
 */
public class StorageException extends NetCacheException {
    /**
     * 创建一个不带根因的存储异常。
     *
     * @param code 存储域错误码，比如容量保护、TTL 非法或索引损坏
     * @param message 面向日志与调用方的错误说明
     * @implNote 具体 code 由子类或调用点决定，这里只负责复用基类约束。
     */
    public StorageException(String code, String message) {
        super(code, message);
    }

    /**
     * 创建一个带根因的存储异常。
     *
     * @param code 存储域错误码
     * @param message 面向日志与调用方的错误说明
     * @param cause 底层根因，允许为 {@code null}
     * @implNote 适合包装磁盘 I/O、序列化或并发访问链路里的底层失败。
     */
    public StorageException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
