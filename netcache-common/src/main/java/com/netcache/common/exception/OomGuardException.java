package com.netcache.common.exception;

/**
 * OOM 保护异常 —— 内存闸机前的「红灯」。
 * <p>
 * 当系统检测到继续分配可能把进程推向内存耗尽（Out Of Memory）时，使用这个异常
 * 提前拒绝写入或扩容。没有它的话，请求会一路冲到 JVM 真正 OOM，代价往往是整进程
 * 抖动甚至直接崩溃。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由内存配额检查、容量保护和淘汰前置判断逻辑抛出</li>
 *   <li>下游由写路径、告警系统和运维日志识别并做降载处理</li>
 * </ul>
 * 线程安全：异常对象创建后不可变，可安全跨线程传播。
 * <p>
 * 典型用例：
 * <pre>
 * if (usedBytes > softLimitBytes) {
 *     throw new OomGuardException("达到软上限，拒绝继续写入");
 * }
 * </pre>
 */
public final class OomGuardException extends StorageException {
    /**
     * 创建一个 OOM 保护异常。
     *
     * @param message 面向调用方的保护原因说明
     * @implNote 错误码固定为 {@code OOM_GUARD}，方便监控把它和普通存储失败区分开。
     */
    public OomGuardException(String message) {
        super("OOM_GUARD", message);
    }
}
