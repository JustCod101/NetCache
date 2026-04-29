package com.netcache.client.pool;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

import java.util.concurrent.CompletableFuture;

/**
 * 面向单个缓存节点的请求通道抽象，像“插座”一样对外提供发送请求和关闭连接两个最小能力。
 * <p>
 * 之所以需要这个接口，是为了把连接实现细节（例如 TCP、Mock 通道或未来其他传输）与上层路由和连接池解耦；如果没有它，
 * 上层只能直接依赖具体 Netty 实现，测试和扩展都会更困难。
 * <p>
 * 上游由 {@link ConnectionPool} 和请求路由器调用；下游通常由 {@link TcpNodeChannel} 之类的实现负责真正的网络通信。
 * <p>
 * 线程安全说明：接口本身不约束并发模型，具体线程安全语义由实现类决定；默认 TCP 实现支持并发发送。
 * <p>
 * 典型用例：
 * <pre>{@code
 * NodeChannel channel = factory.connect("127.0.0.1:7001", 0);
 * CompletableFuture<Response> future = channel.send(request);
 * Response response = future.join();
 * }</pre>
 */
public interface NodeChannel extends AutoCloseable {
    /**
     * 发送一条请求到对应节点。
     *
     * @param request 表示已经编码好命令语义的请求对象，不能为空
     * @return 返回 future；完成后得到节点响应
     * @throws RuntimeException 当实现无法接受该请求或发送前即检测到通道异常时可能抛出运行时异常
     * @implNote 默认 TCP 实现会先为请求注册一个响应 future，再异步写入网络通道。
     */
    CompletableFuture<Response> send(Request request);

    /**
     * 关闭当前节点通道并释放关联资源。
     *
     * @implNote 对于 TCP 实现，这通常意味着关闭底层 Netty Channel 与对应的 EventLoopGroup。
     */
    @Override
    void close();
}
