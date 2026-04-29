package com.netcache.common.exception;

/**
 * NetCache 运行时异常基类 —— 系统里的「统一告警信封」。
 * <p>
 * 它把错误码和错误消息绑在一起，让上层可以既看人类可读文案，也能按机器可读 code
 * 做分类处理。没有这个基类的话，各模块只能抛零散异常，调用方很难稳定识别错误来源。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由协议、存储、路由等模块在不可恢复错误时抛出</li>
 *   <li>下游由服务端异常映射、日志告警和测试断言读取 {@link #code()}</li>
 * </ul>
 * 线程安全：异常对象创建后字段不再变化，可安全跨线程传播；
 * 但异常栈本身通常只作为失败信号一次性使用。
 * <p>
 * 典型用例：
 * <pre>
 * throw new NetCacheException("CONFIG_ERROR", "seed 节点不能为空");
 * } catch (NetCacheException ex) {
 *     log.warn("{}: {}", ex.code(), ex.getMessage());
 * }
 * </pre>
 */
public class NetCacheException extends RuntimeException {
    // 错误码使用字符串而不是枚举，便于跨模块、跨协议直接传输。
    private final String code;

    /**
     * 创建一个不带根因的 NetCache 异常。
     *
     * @param code 机器可读的错误码，不能为空白字符串
     * @param message 面向日志和调用方的错误说明
     * @throws IllegalArgumentException 当 {@code code} 为 {@code null} 或空白时抛出
     * @implNote 错误码校验集中在 {@code requireCode}，避免子类各自重复判断。
     */
    public NetCacheException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    /**
     * 创建一个带根因的 NetCache 异常。
     *
     * @param code 机器可读的错误码，不能为空白字符串
     * @param message 面向日志和调用方的错误说明
     * @param cause 底层根因，允许为 {@code null}
     * @throws IllegalArgumentException 当 {@code code} 为 {@code null} 或空白时抛出
     * @implNote 保留原始 {@code cause}，方便调用方沿着异常链追到真正故障点。
     */
    public NetCacheException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    /**
     * 返回机器可读的错误码。
     *
     * @return 非空且非空白的错误码字符串
     * @implNote 业务层应优先依赖 code 做分支，而不是解析 message 文本。
     */
    public String code() {
        return code;
    }

    /**
     * 校验错误码是否合法。
     *
     * @param code 待校验错误码
     * @return 原样返回合法错误码
     * @throws IllegalArgumentException 当 {@code code} 为 {@code null} 或空白时抛出
     * @implNote 在基类收口校验，可以保证所有子类都不会带着空 code 流出去。
     */
    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        return code;
    }
}
