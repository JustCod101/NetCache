package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 DEL 命令的删除处理器，像回收员一样把指定键从存储里移除。
 *
 * <p>它解决按键删除数据的问题；没有它，客户端即便发送 DEL 也无法触发真实删除逻辑。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#del(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：处理器自身无状态，可被多个请求并发共享。
 */
public final class DelHandler extends AbstractStorageHandler {
    /**
     * 创建 DEL 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 依赖注入逻辑完全复用抽象父类。
     */
    public DelHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 删除指定键并返回删除结果。
     *
     * @param request 包含单个 key 参数的请求
     * @return 1 表示删除成功，0 表示键原本不存在
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @implNote 存储层返回布尔值，这里转换成协议约定的 INT64 结果，方便客户端与 Redis 风格命令保持一致。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.del(key(request.args(), 0)) ? 1L : 0L);
    }
}
