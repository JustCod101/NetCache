package com.netcache.client;

import java.nio.charset.StandardCharsets;

/**
 * 最小可运行的客户端示例程序，像一张“使用说明卡”，展示如何构建客户端并完成一次最基础的 SET/GET 往返。
 * <p>
 * 之所以需要它，是因为新接入者往往先想看到能跑通的最短路径；如果没有这个示例，用户就得自己翻接口再拼装启动参数，入门成本更高。
 * <p>
 * 上游通常由命令行或 Maven Exec 插件启动；下游依赖 {@link NetCacheClient} 完成真实缓存访问。
 * <p>
 * 线程安全说明：该类主要用于单线程演示，不承担并发访问职责。
 * <p>
 * 典型用例：
 * <pre>{@code
 * mvn -q -pl netcache-client -am exec:java \
 *   -Dexec.mainClass=com.netcache.client.SampleClient \
 *   -Dnetcache.seed=127.0.0.1:7001
 * }</pre>
 */
public final class SampleClient {
    /**
     * 禁止外部实例化示例工具类。
     *
     * @implNote 该类只提供命令行入口，私有构造器用于表达“纯静态用法”。
     */
    private SampleClient() {
    }

    /**
     * 运行最小客户端示例并输出结果。
     *
     * @param args 表示命令行参数；若提供第一个参数则将其作为种子节点地址
     * @throws RuntimeException 当客户端建连、写入或读取失败时抛出运行时异常
     * @implNote 种子节点解析优先级为：命令行首参 → {@code netcache.seed} 系统属性 → 默认地址 {@code 127.0.0.1:7001}。
     */
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
