package com.netcache.server.netty;

import com.netcache.protocol.codec.ProtocolDecoder;
import com.netcache.protocol.codec.ProtocolEncoder;
import com.netcache.server.ServerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Objects;

public final class ServerBootstrapBuilder {
    private final ServerConfig config;
    private final CommandDispatcher dispatcher;

    public ServerBootstrapBuilder(ServerConfig config, CommandDispatcher dispatcher) {
        this.config = Objects.requireNonNull(config, "config");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public BuiltServerBootstrap build() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(config.bossThreads(), new DefaultThreadFactory("nc-server-boss"));
        EventLoopGroup workerGroup = new NioEventLoopGroup(config.effectiveWorkerThreads(), new DefaultThreadFactory("nc-server-worker"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new ProtocolDecoder());
                        pipeline.addLast(new ProtocolEncoder());
                        pipeline.addLast(dispatcher);
                    }
                });
        return new BuiltServerBootstrap(bootstrap, bossGroup, workerGroup);
    }

    public record BuiltServerBootstrap(ServerBootstrap bootstrap, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        public void shutdownGracefully() {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
