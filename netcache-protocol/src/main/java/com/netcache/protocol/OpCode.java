package com.netcache.protocol;

import java.util.Arrays;

/**
 * 协议操作码枚举，像一张“命令字典”，把网络上传输的单字节指令翻译成系统可理解的业务动作。
 * <p>
 * 它解决的是“字节值与语义命令如何稳定映射”的问题；如果没有它，编码端和解码端只能依赖零散常量，
 * 很容易在扩展命令或跨模块协作时出现错配。
 * <p>
 * 上游通常由客户端命令构造器、请求编码器和服务端命令分发器使用；下游依赖 {@link #code()} 提供线协议值，
 * 依赖 {@link #fromCode(byte)} 在解码阶段完成反向解析。
 * <p>
 * 线程安全性：枚举实例在 JVM 中天然单例且不可变，本类型线程安全，可在多线程 Netty pipeline 中共享。
 * <p>
 * 典型用例：
 * <pre>{@code
 * OpCode opCode = OpCode.SET;
 * byte wireCode = opCode.code();
 * OpCode decoded = OpCode.fromCode(wireCode);
 * }</pre>
 */
public enum OpCode {
    /** 存储类命令：按 key 读取值。 */
    GET(0x10),
    /** 存储类命令：写入或覆盖 key 对应的值。 */
    SET(0x11),
    /** 存储类命令：删除指定 key。 */
    DEL(0x12),
    /** 存储类命令：更新 key 的过期时间。 */
    EXPIRE(0x13),
    /** 存储类命令：查询 key 剩余存活时间。 */
    TTL(0x14),
    /** 存储类命令：检查 key 是否存在。 */
    EXISTS(0x15),
    /** 存储类命令：将整数值加一。 */
    INCR(0x16),
    /** 存储类命令：将整数值减一。 */
    DECR(0x17),
    /** 系统类命令：用于连通性和存活探测。 */
    PING(0x20),
    /** 系统类命令：查询节点运行信息。 */
    INFO(0x21),
    /** 集群类命令：拉取集群节点视图。 */
    CLUSTER_NODES(0x30),
    /** 集群类命令：建立主从复制关系。 */
    SLAVEOF(0x40),
    /** 集群类命令：发起部分同步复制。 */
    PSYNC(0x41),
    /** Sentinel 命令：交换哨兵 hello 元数据。 */
    SENTINEL_HELLO(0x50),
    /** Sentinel 命令：触发或广播故障转移动作。 */
    SENTINEL_FAILOVER(0x51);

    /** 线协议中的单字节操作码。 */
    private final byte code;

    OpCode(int code) {
        this.code = (byte) code;
    }

    /**
     * 返回当前命令对应的线协议字节值。
     *
     * @return 用于写入请求 payload 的单字节操作码
     * @implNote 该方法为 O(1)，直接返回构造时固化的不可变字段。
     */
    public byte code() {
        return code;
    }

    /**
     * 根据线协议字节值解析出对应的命令枚举。
     *
     * @param code 来自网络 payload 的原始操作码字节，业务上表示客户端希望执行的命令
     * @return 匹配到的 {@link OpCode}；当字节值合法时绝不返回 {@code null}
     * @throws IllegalArgumentException 当 {@code code} 不在当前协议支持范围内时抛出
     * @implNote 当前实现通过遍历 {@link #values()} 查找，时间复杂度为 O(n)；命令集合很小，因此保持实现直观性。
     */
    public static OpCode fromCode(byte code) {
        return Arrays.stream(values())
                .filter(opCode -> opCode.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown op code: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
