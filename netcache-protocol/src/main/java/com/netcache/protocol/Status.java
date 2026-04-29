package com.netcache.protocol;

import java.util.Arrays;

/**
 * 响应状态枚举，像协议里的“交通信号灯”，告诉调用方这次请求是成功、失败还是需要改道重试。
 * <p>
 * 它解决的是“响应结果如何用稳定、紧凑且可扩展的字节语义表达”的问题；如果没有统一状态码，
 * 客户端只能依赖字符串猜测结果，分支处理会变得脆弱。
 * <p>
 * 上游由服务端响应组装逻辑写入状态，下游由客户端响应解码器和路由器读取状态后决定下一步动作。
 * <p>
 * 线程安全性：枚举不可变且实例全局唯一，线程安全，可安全跨线程共享。
 * <p>
 * 典型用例：
 * <pre>{@code
 * Status status = Status.OK;
 * byte wireCode = status.code();
 * Status decoded = Status.fromCode(wireCode);
 * }</pre>
 */
public enum Status {
    /** 请求已成功处理。 */
    OK(0x00),
    /** 请求处理失败，通常需结合响应体查看错误详情。 */
    ERROR(0x01),
    /** 发生槽位重定向，客户端应转向目标节点重试。 */
    MOVED(0x02),
    /** 发生临时 ASK 重定向，客户端需按 ASK 语义重试。 */
    ASK(0x03),
    /** 业务上不存在结果，例如 key 缺失。 */
    NIL(0x04);

    /** 写入响应 payload 的单字节状态码。 */
    private final byte code;

    Status(int code) {
        this.code = (byte) code;
    }

    /**
     * 返回当前状态对应的线协议字节值。
     *
     * @return 可直接写入响应 payload 的状态码
     * @implNote 该方法为 O(1)，仅返回枚举实例中的常量字段。
     */
    public byte code() {
        return code;
    }

    /**
     * 根据线协议状态码解析为响应状态枚举。
     *
     * @param code 从网络响应中读取的单字节状态码，业务上表示本次请求的处理结果
     * @return 对应的 {@link Status}；合法输入下绝不返回 {@code null}
     * @throws IllegalArgumentException 当收到未知状态码时抛出，表示对端与当前协议实现不兼容或数据损坏
     * @implNote 当前实现顺序遍历全部枚举值，时间复杂度为 O(n)；由于状态集合固定且很小，无需额外索引结构。
     */
    public static Status fromCode(byte code) {
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown status: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
