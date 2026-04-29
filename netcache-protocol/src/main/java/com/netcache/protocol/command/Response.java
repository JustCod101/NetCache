package com.netcache.protocol.command;

import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;

/**
 * 命令响应模型，像“回执单”，把处理状态、结果类型、响应体和请求编号集中描述给调用方。
 * <p>
 * 它解决的是“响应字节如何在业务层被清晰表达”的问题；如果没有该对象，客户端和服务端都需要直接拼装状态码、类型码和 body，
 * 协议语义会散落在各处。
 * <p>
 * 上游由命令执行器或响应解码器创建，下游由客户端结果解释层消费，或由 {@link #encodePayload(ByteBufAllocator)} 序列化回线协议。
 * <p>
 * 线程安全性：record 字段不可重新赋值，构造器对 {@code body} 做了 clone，访问器也返回副本，因此可视为线程安全的值对象。
 * <p>
 * 典型用例：
 * <pre>{@code
 * Response response = new Response(Status.OK, ResultType.BYTES, valueBytes, 42L);
 * ByteBuf payload = response.encodePayload(allocator);
 * Response decoded = Response.decodePayload(42L, payload);
 * }</pre>
 */
public record Response(Status status, ResultType type, byte[] body, long requestId) {
    /**
     * 创建响应对象并保护响应体不被外部修改。
     *
     * @param status 本次请求的处理状态，例如成功、失败或重定向
     * @param type 响应体解释方式，例如字符串、整数或错误消息
     * @param body 实际响应数据；可为空字节数组，但不能为 {@code null}
     * @param requestId 被响应请求的编号，客户端依赖它匹配未完成请求
     * @throws NullPointerException 当 {@code status}、{@code type} 或 {@code body} 为 {@code null} 时抛出
     * @implNote 构造器会 clone {@code body}，避免调用方在构造后继续修改原数组造成响应对象状态漂移。
     */
    public Response {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(type, "type");
        body = Objects.requireNonNull(body, "body").clone();
    }

    /**
     * 将响应对象编码为协议 payload。
     *
     * @param allocator Netty 缓冲区分配器，用于申请容纳状态码、类型码和 body 的输出缓冲区
     * @return 编码后的响应 payload；格式为 1B status + 1B type + body，绝不返回 {@code null}
     * @throws NullPointerException 当 {@code allocator} 为 {@code null} 时抛出
     * @implNote 该方法时间复杂度为 O(body.length)，输出缓冲区容量精确等于 2 + body.length。
     */
    public ByteBuf encodePayload(ByteBufAllocator allocator) {
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf out = allocator.buffer(2 + body.length, 2 + body.length);
        out.writeByte(status.code());
        out.writeByte(type.code());
        out.writeBytes(body);
        return out;
    }

    /**
     * 从协议 payload 解码响应对象。
     *
     * @param requestId 已从帧头读取的请求编号，用于让调用方把响应归还给正确的请求上下文
     * @param payload 仅包含响应负载的缓冲区，读取位置应指向状态码起始处
     * @return 解码后的响应对象；body 可能为空数组，但返回对象本身绝不为 {@code null}
     * @throws NullPointerException 当 {@code payload} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当状态码或结果类型码无法识别时抛出
     * @implNote 当前实现会一次性读取剩余全部字节作为 body，时间复杂度为 O(body.length)。
     */
    public static Response decodePayload(long requestId, ByteBuf payload) {
        Objects.requireNonNull(payload, "payload");
        Status status = Status.fromCode(payload.readByte());
        ResultType type = ResultType.fromCode(payload.readByte());
        byte[] body = new byte[payload.readableBytes()];
        payload.readBytes(body);
        return new Response(status, type, body, requestId);
    }

    @Override
    /**
     * 返回响应体的防御性副本。
     *
     * @return body 的 clone 副本；即使响应体为空也返回长度为 0 的数组而非 {@code null}
     * @implNote 该方法时间复杂度为 O(body.length)，用于确保调用方无法通过访问器修改内部状态。
     */
    public byte[] body() {
        return body.clone();
    }
}
