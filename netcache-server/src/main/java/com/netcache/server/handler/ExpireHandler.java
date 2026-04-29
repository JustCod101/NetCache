package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 处理 EXPIRE 命令的过期时间更新处理器，像保鲜贴标签员一样给现有键重新贴上失效时间。
 *
 * <p>它解决的是写入后独立更新 TTL 的需求；如果没有它，客户端只能通过重写整个值来改变过期策略。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#expire(com.netcache.common.ByteKey, Duration)}。
 *
 * <p>线程安全说明：处理器无可变共享状态，可安全并发调用。
 */
public final class ExpireHandler extends AbstractStorageHandler {
    /**
     * 创建 EXPIRE 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 保持与其他存储命令一致的依赖注入方式。
     */
    public ExpireHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 更新指定键的毫秒级过期时间。
     *
     * @param request 包含 key 与 ttlMs 两个参数的请求
     * @return 1 表示更新成功，0 表示键不存在或未能更新
     * @throws IllegalArgumentException 当参数个数不是 2 时抛出
     * @throws NumberFormatException 当 ttlMs 不能解析为 long 时抛出
     * @implNote 过期时间字符串先按 UTF-8 解码，再转换为 {@link Duration} 交给存储层，保持协议层与存储层职责分离。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 2, 2);
        long ttlMs = Long.parseLong(new String(arg(request.args(), 1), StandardCharsets.UTF_8));
        return Responses.int64(request.requestId(), storageEngine.expire(key(request.args(), 0), Duration.ofMillis(ttlMs)) ? 1L : 0L);
    }
}
