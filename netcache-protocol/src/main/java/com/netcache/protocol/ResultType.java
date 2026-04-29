package com.netcache.protocol;

import java.util.Arrays;

/**
 * 响应结果类型枚举，像“包裹标签”，说明响应体里的字节应该按哪种业务语义来解释。
 * <p>
 * 它解决的是“同一段 body 字节如何被正确解读”的问题；如果没有结果类型，客户端无法区分字符串、整数、
 * 节点列表或错误消息，解码后的业务对象就会失真。
 * <p>
 * 上游由服务端在构造 {@code Response} 时设置，下游由客户端响应解释层根据类型选择反序列化路径。
 * <p>
 * 线程安全性：枚举不可变且天然线程安全，适合在高并发网络处理链中复用。
 * <p>
 * 典型用例：
 * <pre>{@code
 * ResultType type = ResultType.STRING;
 * byte wireCode = type.code();
 * ResultType decoded = ResultType.fromCode(wireCode);
 * }</pre>
 */
public enum ResultType {
    /** 响应体无有效内容。 */
    NULL(0x00),
    /** 响应体表示 UTF-8 或约定编码的字符串。 */
    STRING(0x01),
    /** 响应体表示 64 位整数。 */
    INT64(0x02),
    /** 响应体表示原始字节数组。 */
    BYTES(0x03),
    /** 响应体表示集群节点列表。 */
    NODE_LIST(0x04),
    /** 响应体表示人类可读的错误消息。 */
    ERROR_MSG(0x05);

    /** 写入响应 payload 的单字节结果类型码。 */
    private final byte code;

    ResultType(int code) {
        this.code = (byte) code;
    }

    /**
     * 返回当前结果类型对应的线协议字节值。
     *
     * @return 写入响应 payload 的单字节类型码
     * @implNote 该方法为 O(1)，直接暴露不可变内部字段。
     */
    public byte code() {
        return code;
    }

    /**
     * 根据线协议类型码解析结果类型。
     *
     * @param code 从响应 payload 中读取的单字节类型码，业务上描述 body 的解释方式
     * @return 对应的 {@link ResultType}；成功解析时绝不返回 {@code null}
     * @throws IllegalArgumentException 当收到未定义的类型码时抛出
     * @implNote 当前实现为顺序查找，时间复杂度 O(n)；鉴于类型数量固定且极小，优先保持代码可读性。
     */
    public static ResultType fromCode(byte code) {
        return Arrays.stream(values())
                .filter(resultType -> resultType.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown result type: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
