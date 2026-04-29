package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

/**
 * 处理 GET 命令的读取处理器，像档案室取件员一样按键把值取出来。
 *
 * <p>它解决客户端按键读取值的问题；如果没有它，分发器拿到 GET 后将无法把请求落到存储读取语义上。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#get(com.netcache.common.ByteKey)}。
 *
 * <p>线程安全说明：处理器自身无可变状态，只复用共享存储引擎，因此可被并发请求安全调用。
 *
 * <p>典型用例：
 * <pre>{@code
 * GetHandler handler = new GetHandler(storageEngine);
 * Response response = handler.handle(request);
 * }</pre>
 */
public final class GetHandler extends AbstractStorageHandler {
    /**
     * 创建 GET 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 构造逻辑全部复用父类，保持存储类命令处理器注入方式一致。
     */
    public GetHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 按键查询缓存值。
     *
     * @param request 包含单个 key 参数的请求
     * @return 命中时返回 BYTES 响应，未命中时返回 NIL 响应
     * @throws IllegalArgumentException 当参数个数不是 1 时抛出
     * @implNote 先校验参数，再把第 0 个参数转为 {@code ByteKey} 查询；空结果与空字节值通过不同状态区分。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return storageEngine.get(key(request.args(), 0))
                .map(value -> Responses.bytes(request.requestId(), value))
                .orElseGet(() -> Responses.nil(request.requestId()));
    }
}
