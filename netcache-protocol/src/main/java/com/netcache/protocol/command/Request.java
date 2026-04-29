package com.netcache.protocol.command;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.List;
import java.util.Objects;

/**
 * 命令请求模型，像一张“待执行工单”，把操作码、参数列表和请求编号打包为上层业务可读对象。
 * <p>
 * 它解决的是“原始请求 payload 如何在进入业务层前被规范化表示”的问题；如果没有该对象，调用方只能直接操作字节数组，
 * 可读性、可测试性和防御性复制都会明显变差。
 * <p>
 * 上游由客户端 API 或服务器解码链路创建，下游通常由请求处理器读取其操作码与参数，并可通过 {@link #encodePayload(ByteBufAllocator)}
 * 再次序列化回协议负载。
 * <p>
 * 线程安全性：record 字段不可变，内部参数列表也被复制为不可变集合；但每个 {@code byte[]} 仍是数据副本，读取安全，
 * 对外访问时会再次 clone，因此可以视为线程安全的值对象。
 * <p>
 * 典型用例：
 * <pre>{@code
 * Request request = new Request(OpCode.GET, List.of("user:1".getBytes()), 42L);
 * ByteBuf payload = request.encodePayload(allocator);
 * Request decoded = Request.decodePayload(42L, payload);
 * }</pre>
 */
public record Request(OpCode opCode, List<byte[]> args, long requestId) {
    /**
     * 创建请求对象并对参数做防御性复制。
     *
     * @param opCode 本次请求要执行的命令类型
     * @param args 命令参数列表，顺序与业务语义一一对应，例如 key、value、ttl
     * @param requestId 请求唯一标识，后续会被响应原样带回
     * @throws NullPointerException 当 {@code opCode}、{@code args} 或任一参数元素为 {@code null} 时抛出
     * @throws IllegalArgumentException 当参数个数超过 unsigned short 可表达范围时抛出
     * @implNote 当前实现会复制参数列表并 clone 每个参数字节数组，避免调用方后续修改影响已创建请求实例。
     */
    public Request {
        Objects.requireNonNull(opCode, "opCode");
        Objects.requireNonNull(args, "args");
        if (args.size() > 0xffff) {
            throw new IllegalArgumentException("argument count exceeds unsigned short: " + args.size());
        }
        args = List.copyOf(args.stream()
                .map(arg -> Objects.requireNonNull(arg, "arg").clone())
                .toList());
    }

    /**
     * 将请求对象编码为协议 payload。
     *
     * @param allocator Netty 缓冲区分配器，用于申请恰好容纳请求数据的 {@link ByteBuf}
     * @return 编码后的 payload；返回缓冲区 readerIndex 位于起始处，writerIndex 位于末尾，绝不返回 {@code null}
     * @throws NullPointerException 当 {@code allocator} 为 {@code null} 时抛出
     * @implNote 编码格式为 1B opCode + 2B 参数个数 + N 组(4B 参数长度 + 参数内容)；时间复杂度为 O(m + bytes)，其中 m 为参数个数。
     */
    public ByteBuf encodePayload(ByteBufAllocator allocator) {
        Objects.requireNonNull(allocator, "allocator");
        int length = 1 + Short.BYTES + args.stream().mapToInt(arg -> Integer.BYTES + arg.length).sum();
        ByteBuf out = allocator.buffer(length, length);
        out.writeByte(opCode.code());
        out.writeShort(args.size());
        for (byte[] arg : args) {
            out.writeInt(arg.length);
            out.writeBytes(arg);
        }
        return out;
    }

    /**
     * 从协议 payload 解码出请求对象。
     *
     * @param requestId 已从帧头解析出的请求编号，用于与原始调用关联
     * @param payload 仅包含请求负载部分的字节缓冲区，读取位置应指向 opCode 起始字节
     * @return 解码后的请求对象；成功时绝不返回 {@code null}
     * @throws NullPointerException 当 {@code payload} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当参数长度为负数或剩余字节不足以读取完整参数时抛出
     * @implNote 当前实现按顺序消费缓冲区，时间复杂度为 O(m + bytes)；读取完成后 payload 的 readerIndex 会前移到末尾。
     */
    public static Request decodePayload(long requestId, ByteBuf payload) {
        Objects.requireNonNull(payload, "payload");
        OpCode opCode = OpCode.fromCode(payload.readByte());
        int argCount = payload.readUnsignedShort();
        List<byte[]> args = new java.util.ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            int length = payload.readInt();
            if (length < 0 || payload.readableBytes() < length) {
                throw new IllegalArgumentException("invalid argument length: " + length);
            }
            byte[] arg = new byte[length];
            payload.readBytes(arg);
            args.add(arg);
        }
        return new Request(opCode, args, requestId);
    }

    @Override
    /**
     * 返回参数列表的防御性副本视图。
     *
     * @return 新构造的参数列表，其中每个 {@code byte[]} 都是 clone 副本；即使原请求参数为空也返回空列表而非 {@code null}
     * @implNote 该方法会遍历全部参数并逐个 clone，时间复杂度为 O(m + bytes)，用于保证 record 对外暴露时仍保持不可变语义。
     */
    public List<byte[]> args() {
        return args.stream().map(byte[]::clone).toList();
    }
}
