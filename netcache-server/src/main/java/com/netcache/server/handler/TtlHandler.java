package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 TTL 命令的剩余寿命查询处理器，像保质期查询机一样告诉客户端键还能活多久。
 *
 * <p>它解决的是读取剩余过期时间的问题；没有它，客户端无法在不读取值本体的情况下观察 TTL。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#ttl(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：该处理器无状态，可并发复用。
 */
public final class TtlHandler extends AbstractStorageHandler {
    /**
     * 创建 TTL 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 通过父类完成公共依赖保存。
     */
    public TtlHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 查询指定键的剩余 TTL。
     *
     * @param request 包含单个 key 参数的请求
     * @return 由存储层定义语义的剩余毫秒数响应
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @implNote 该处理器不解释特殊返回值，直接透传存储层约定，避免协议层和存储层出现双重语义定义。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.ttl(key(request.args(), 0)));
    }
}
