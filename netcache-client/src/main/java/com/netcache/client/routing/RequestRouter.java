package com.netcache.client.routing;

import com.netcache.client.pool.ConnectionPool;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 请求路由器，像快递分拣中心一样根据 key 把请求投递到正确节点，必要时对无 key 请求做种子节点轮询。
 * <p>
 * 之所以需要它，是为了把“请求该发往哪里”的决策从客户端业务 API 中剥离；如果没有这层，调用方或客户端实现就得重复关心哈希路由和节点选择。
 * <p>
 * 上游由 {@link com.netcache.client.DefaultNetCacheClient} 调用；下游依赖 {@link TopologyCache} 计算目标节点，依赖 {@link ConnectionPool}
 * 获取实际发送通道。
 * <p>
 * 线程安全说明：该类支持并发使用。它只持有只读依赖，路由计算依赖的拓扑缓存和连接池本身也采用并发安全结构。
 * <p>
 * 典型用例：
 * <pre>{@code
 * RequestRouter router = new RequestRouter(topologyCache, connectionPool);
 * CompletableFuture<Response> future = router.route(request);
 * Response response = future.join();
 * }</pre>
 */
public final class RequestRouter implements AutoCloseable {
    private final TopologyCache topologyCache;
    private final ConnectionPool connectionPool;

    /**
     * 创建请求路由器。
     *
     * @param topologyCache 表示负责 key→节点映射的拓扑缓存，不能为空
     * @param connectionPool 表示负责按节点返回连接的连接池，不能为空
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     * @implNote 构造器只保存依赖，真正的节点决策发生在每次 {@link #route(Request)} 调用时。
     */
    public RequestRouter(TopologyCache topologyCache, ConnectionPool connectionPool) {
        this.topologyCache = Objects.requireNonNull(topologyCache, "topologyCache");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool");
    }

    /**
     * 根据请求内容选择目标节点并发送请求。
     *
     * @param request 表示待路由的协议请求，不能为空
     * @return 返回 future；完成后得到目标节点返回的响应
     * @throws RuntimeException 当找不到目标节点连接或底层通道发送失败时可能抛出运行时异常
     * @implNote 当前约定使用第一个参数作为路由键；若请求没有参数，则退化为按种子节点轮询，适合无 key 管理命令。
     */
    public CompletableFuture<Response> route(Request request) {
        String seed = request.args().isEmpty() ? topologyCache.nextSeed() : topologyCache.route(request.args().get(0));
        return connectionPool.channel(seed).send(request);
    }

    @Override
    /**
     * 关闭请求路由器及其持有的连接池。
     *
     * @implNote 当前实现把生命周期管理下沉给连接池，调用后路由器不应继续被使用。
     */
    public void close() {
        connectionPool.close();
    }
}
