package com.netcache.client.pool;

import com.netcache.client.routing.ResponseRouter;
import com.netcache.protocol.Frame;
import com.netcache.protocol.codec.ProtocolDecoder;
import com.netcache.protocol.codec.ProtocolEncoder;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class TcpNodeChannel implements NodeChannel {
    private final Channel channel;
    private final EventLoopGroup group;
    private final ResponseRouter responseRouter;

    private TcpNodeChannel(Channel channel, EventLoopGroup group, ResponseRouter responseRouter) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.group = Objects.requireNonNull(group, "group");
        this.responseRouter = Objects.requireNonNull(responseRouter, "responseRouter");
    }

    static TcpNodeChannel connect(String seed, Duration connectTimeout, Duration readTimeout, int poolIndex) {
        InetSocketAddress address = parseSeed(seed);
        EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("nc-client-io-" + poolIndex));
        ResponseRouter responseRouter = new ResponseRouter(readTimeout);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new ProtocolDecoder());
                            pipeline.addLast(new ProtocolEncoder());
                            pipeline.addLast(responseRouter);
                        }
                    });
            ChannelFuture connectFuture = bootstrap.connect(address);
            boolean connected = connectFuture.await(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            Channel channel = connectFuture.channel();
            if (!connected || !channel.isActive()) {
                throw new IllegalStateException("failed to connect to " + seed);
            }
            return new TcpNodeChannel(channel, group, responseRouter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            group.shutdownGracefully();
            throw new IllegalStateException("interrupted connecting to " + seed, ex);
        } catch (RuntimeException ex) {
            group.shutdownGracefully();
            throw ex;
        }
    }

    @Override
    public CompletableFuture<Response> send(Request request) {
        ByteBuf payload = request.encodePayload(channel.alloc());
        CompletableFuture<Response> future = responseRouter.register(request.requestId());
        channel.writeAndFlush(Frame.request(request.requestId(), payload)).addListener(write -> {
            if (!write.isSuccess()) {
                responseRouter.fail(request.requestId(), write.cause());
            }
        });
        return future;
    }

    @Override
    public void close() {
        channel.close();
        group.shutdownGracefully();
    }

    private static InetSocketAddress parseSeed(String seed) {
        String[] parts = seed.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("seed must be host:port");
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
