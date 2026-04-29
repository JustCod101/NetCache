package com.netcache.server.netty;

import com.netcache.protocol.Frame;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.server.handler.CommandHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 把协议帧分发给具体命令处理器的路由中枢，像快递分拣台一样决定每个请求该送到哪个 handler。
 *
 * <p>这个类解决的是“解码后的请求如何落到具体业务实现”这一问题：统一完成 payload 解码、OpCode 查表、
 * handler 执行以及 Response 回写。如果没有它，每个连接处理器都要重复解析和路由逻辑，代码会高度分散。
 *
 * <p>上游是 {@link ProtocolDecoder} 产出的 {@link Frame}；下游依赖一组按 OpCode 建好的
 * {@link CommandHandler} 映射。执行结束后结果会重新编码为 {@link Frame} 返回给 Netty 出站链。
 *
 * <p>线程安全说明：该类被标记为 {@link ChannelHandler.Sharable}，内部只持有不可变 handler 映射，
 * 因而可被多个 channel 安全共享；具体并发安全由各 handler 和其底层依赖负责。
 *
 * <p>典型用例：
 * <pre>{@code
 * Map<OpCode, CommandHandler> handlers = HandlerRegistry.singleNode(storageEngine);
 * CommandDispatcher dispatcher = new CommandDispatcher(handlers);
 * pipeline.addLast(dispatcher);
 * }</pre>
 *
 * <p>Netty Inbound 处理链：
 * <pre>
 * Socket.read() → LengthFieldBasedFrameDecoder → MagicValidator
 *               → ProtocolDecoder → CommandDispatcher → Handler
 *               → ProtocolEncoder → Socket.write()
 * </pre>
 */
@ChannelHandler.Sharable
public final class CommandDispatcher extends SimpleChannelInboundHandler<Frame> {
    /** 记录分发失败与异常回写的日志器。 */
    private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

    /** 按操作码索引的命令处理器表。 */
    private final Map<com.netcache.protocol.OpCode, CommandHandler> handlers;

    /**
     * 创建可共享的命令分发器。
     *
     * @param handlers 按操作码组织的 handler 映射
     * @throws NullPointerException 当映射本身为 {@code null} 时抛出
     * @implNote 构造器通过 {@link Map#copyOf(Map)} 做不可变快照，防止外部在运行期篡改路由表。
     */
    public CommandDispatcher(Map<com.netcache.protocol.OpCode, CommandHandler> handlers) {
        this.handlers = Map.copyOf(Objects.requireNonNull(handlers, "handlers"));
    }

    /**
     * 读取单个协议帧、执行对应命令并把结果写回当前连接。
     *
     * @param ctx 当前 channel 的上下文，用于分配缓冲区与回写响应
     * @param frame 已经通过协议层校验与解码的请求帧
     * @implNote 处理流程固定为“帧解码 → 查找 handler → 执行命令 → 编码响应”；若任一步抛出运行时异常，
     * 会统一转换为错误响应并带上原请求 ID，最后始终关闭 frame 持有的资源。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        try {
            Request request = Request.decodePayload(frame.requestId(), frame.payload().slice());
            CommandHandler handler = handlers.get(request.opCode());
            Response response = handler == null
                    ? error(request.requestId(), "unsupported command: " + request.opCode())
                    : handler.handle(request);
            ByteBuf payload = response.encodePayload(ctx.alloc());
            ctx.writeAndFlush(Frame.response(response.requestId(), payload));
        } catch (RuntimeException ex) {
            LOG.error("command dispatch failed reqId={}", frame.requestId(), ex);
            Response response = error(frame.requestId(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            ByteBuf payload = response.encodePayload(ctx.alloc());
            ctx.writeAndFlush(Frame.response(frame.requestId(), payload));
        } finally {
            frame.close();
        }
    }

    /**
     * 构造协议层统一格式的错误响应。
     *
     * @param requestId 出错请求对应的请求 ID
     * @param message 需要回传给客户端的错误消息
     * @return 状态为 {@link Status#ERROR} 的响应对象
     * @implNote 错误消息始终按 UTF-8 编码为字节数组，以保持客户端与服务端的错误表达一致。
     */
    private static Response error(long requestId, String message) {
        return new Response(Status.ERROR, ResultType.ERROR_MSG, message.getBytes(StandardCharsets.UTF_8), requestId);
    }
}
