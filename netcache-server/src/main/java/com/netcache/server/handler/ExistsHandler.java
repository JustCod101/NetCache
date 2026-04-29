package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 EXISTS 命令的存在性检查处理器，像门卫一样回答“这个键在不在”。
 *
 * <p>它解决的是轻量存在性判断需求；如果没有它，客户端只能通过 GET 曲线判断，既浪费带宽也模糊语义。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#exists(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：该处理器不保存可变状态，可并发复用。
 */
public final class ExistsHandler extends AbstractStorageHandler {
    /**
     * 创建 EXISTS 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 统一沿用存储类处理器的构造签名，便于注册表批量装配。
     */
    public ExistsHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 判断指定键是否存在。
     *
     * @param request 包含单个 key 参数的请求
     * @return 1 表示存在，0 表示不存在
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @implNote 返回值使用整型而不是布尔型响应，以便和其他计数型命令使用统一协议表示。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.exists(key(request.args(), 0)) ? 1L : 0L);
    }
}
