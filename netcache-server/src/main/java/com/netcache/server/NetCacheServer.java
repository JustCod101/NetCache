package com.netcache.server;

import com.netcache.server.lifecycle.NodeLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NetCache 服务端的主入口，像总电闸一样负责把整台服务器从“静止”切到“通电运行”。
 *
 * <p>这个类解决的是进程启动编排问题：集中完成配置读取、生命周期装配、关闭钩子注册与阻塞等待。
 * 如果没有它，启动逻辑会散落在多个位置，既不利于定位入口，也容易遗漏优雅停机步骤。
 *
 * <p>上游通常是 JVM 进程启动器或打包后的启动脚本；下游协作对象是 {@link ServerConfig} 和
 * {@link NodeLifecycle}，前者提供运行参数，后者负责真正拉起 Netty 与存储引擎。
 *
 * <p>线程安全说明：该类本身无共享可变状态，除日志器外全为局部变量，因此线程安全；并发模型主要由
 * {@link NodeLifecycle} 与 Netty event loop 承担。
 *
 * <p>典型用例：
 * <pre>{@code
 * System.setProperty("netcache.port", "7001");
 * System.setProperty("netcache.host", "127.0.0.1");
 * NetCacheServer.main(new String[0]);
 * }</pre>
 */
public final class NetCacheServer {
    /** 记录启动与运行状态的日志器。 */
    private static final Logger LOG = LoggerFactory.getLogger(NetCacheServer.class);

    /**
     * 禁止外部实例化工具型入口类。
     *
     * @implNote 入口类只暴露 {@link #main(String[])}，私有构造器用于明确语义并避免误用。
     */
    private NetCacheServer() {
    }

    /**
     * 启动 NetCache 服务端进程并阻塞等待关闭事件。
     *
     * @param args 启动参数，当前未解析命令行参数，但保留 Java 标准入口签名以便后续扩展
     * @throws InterruptedException 当绑定端口或等待关闭期间线程被中断时抛出
     * @implNote 先读取系统属性生成配置，再创建 {@link NodeLifecycle}；随后注册 JVM shutdown hook，
     * 最后启动服务并同步等待 Channel 关闭，以保证主线程不会提前退出。
     */
    public static void main(String[] args) throws InterruptedException {
        ServerConfig config = ServerConfig.fromSystemProperties();
        NodeLifecycle lifecycle = new NodeLifecycle(config);
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::stop, "nc-server-shutdown"));
        lifecycle.start();
        LOG.info("NetCache server started on {}:{}", config.host(), config.port());
        lifecycle.closeFuture().sync();
    }
}
