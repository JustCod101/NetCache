package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 DECR 命令的自减处理器，像倒计时按钮一样把整数值向下拨一格。
 *
 * <p>它解决的是原子递减需求；没有它，客户端需要自己维护读改写流程，协议层就无法提供统一语义。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#decr(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：处理器无状态，线程安全依赖存储层的原子更新实现。
 */
public final class DecrHandler extends AbstractStorageHandler {
    /**
     * 创建 DECR 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 构造器仅负责依赖注入，不包含其他初始化逻辑。
     */
    public DecrHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 将指定键对应的整数值减一。
     *
     * @param request 包含单个 key 参数的请求
     * @return 自减后的整数结果
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @throws RuntimeException 当值不是可递减的整数表示时由存储层抛出
     * @implNote 与 INCR 一样，这里只负责路由与响应封装，数值校验和并发更新都下沉到存储层。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.decr(key(request.args(), 0)));
    }
}
