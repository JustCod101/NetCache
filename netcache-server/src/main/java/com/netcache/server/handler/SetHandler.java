package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 处理 SET 命令的写入处理器，像仓库入库员一样把键值和可选过期时间登记入库。
 *
 * <p>它解决客户端写入缓存的问题；如果没有它，服务端无法把字节参数转换为实际的存储写入操作。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#set(com.netcache.common.ByteKey, byte[], Duration)}。
 *
 * <p>线程安全说明：处理器不保存请求级状态，所有临时对象都在栈上创建，可被并发请求安全复用。
 *
 * <p>典型用例：
 * <pre>{@code
 * SetHandler handler = new SetHandler(storageEngine);
 * Response response = handler.handle(requestWithKeyValueAndOptionalTtl);
 * }</pre>
 */
public final class SetHandler extends AbstractStorageHandler {
    /**
     * 创建 SET 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @implNote 通过父类统一保存共享依赖，避免子类重复字段定义。
     */
    public SetHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    /**
     * 写入键值对，并在提供第三个参数时设置毫秒级过期时间。
     *
     * @param request 包含 key、value 以及可选 ttlMs 的请求
     * @return 表示写入成功的 OK/NULL 响应
     * @throws IllegalArgumentException 当参数数量非法时抛出
     * @throws NumberFormatException 当 ttl 参数无法解析为毫秒数时抛出
     * @implNote 未提供 TTL 时显式传入 {@link Duration#ZERO}，把“永不过期”的判断交给存储层统一处理。
     */
    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 2, 3);
        Duration ttl = request.args().size() == 3
                ? Duration.ofMillis(Long.parseLong(new String(arg(request.args(), 2), StandardCharsets.UTF_8)))
                : Duration.ZERO;
        storageEngine.set(key(request.args(), 0), arg(request.args(), 1), ttl);
        return Responses.okNull(request.requestId());
    }
}
