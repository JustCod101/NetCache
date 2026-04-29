package com.netcache.client.pool;

import java.time.Duration;

@FunctionalInterface
public interface NodeChannelFactory {
    NodeChannel connect(String seed, int poolIndex);

    static NodeChannelFactory tcp(Duration connectTimeout, Duration readTimeout) {
        return (seed, poolIndex) -> TcpNodeChannel.connect(seed, connectTimeout, readTimeout, poolIndex);
    }
}
