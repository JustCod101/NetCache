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

@ChannelHandler.Sharable
public final class CommandDispatcher extends SimpleChannelInboundHandler<Frame> {
    private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

    private final Map<com.netcache.protocol.OpCode, CommandHandler> handlers;

    public CommandDispatcher(Map<com.netcache.protocol.OpCode, CommandHandler> handlers) {
        this.handlers = Map.copyOf(Objects.requireNonNull(handlers, "handlers"));
    }

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

    private static Response error(long requestId, String message) {
        return new Response(Status.ERROR, ResultType.ERROR_MSG, message.getBytes(StandardCharsets.UTF_8), requestId);
    }
}
