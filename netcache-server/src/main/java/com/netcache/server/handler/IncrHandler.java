package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 INCR 命令的自增处理器，像计数器拨盘一样把整数值向上拨一格。
 *
 * <p>它解决的是原子递增需求；如果没有它，客户端只能先读再写，既不高效也更容易在并发下出错。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#incr(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：处理器本身无状态，真正的并发正确性由存储引擎保证。
 */
public final class IncrHandler extends AbstractStorageHandler {
    /**
     * 创建 INCR 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 统一采用构造注入，便于在测试中替换底层存储实现。
     */
    public IncrHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 将指定键对应的整数值加一。
     *
     * @param request 包含单个 key 参数的请求
     * @return 自增后的整数结果
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @throws RuntimeException 当值不是可递增的整数表示时由存储层抛出
     * @implNote 该处理器不自行解析数字，而是把原子更新责任交给存储层，以避免协议层引入额外竞态窗口。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.incr(key(request.args(), 0)));
    }
}
