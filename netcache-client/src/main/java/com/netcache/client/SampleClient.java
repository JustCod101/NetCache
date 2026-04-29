package com.netcache.client;

import java.nio.charset.StandardCharsets;

public final class SampleClient {
    private SampleClient() {
    }

    public static void main(String[] args) {
        String seed = args.length > 0 ? args[0] : System.getProperty("netcache.seed", "127.0.0.1:7001");
        try (NetCacheClient client = NetCacheClient.builder().seeds(seed).build()) {
            byte[] key = "demo-key".getBytes(StandardCharsets.UTF_8);
            byte[] value = "demo-value".getBytes(StandardCharsets.UTF_8);
            client.set(key, value);
            byte[] found = client.get(key);
            System.out.println("SET/GET succeeded on " + seed + " -> " + new String(found, StandardCharsets.UTF_8));
        }
    }
}
