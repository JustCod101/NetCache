package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.util.Objects;

/**
 * 处理 INFO 命令的节点信息处理器，像状态看板一样输出节点角色与当前键数量。
 *
 * <p>它解决客户端观测服务端基础状态的问题；如果没有它，调试者只能借助日志或外部探针查看节点概要信息。
 *
 * <p>上游是 {@code CommandDispatcher}；下游依赖 {@link StorageEngine#size()} 获取当前键数。
 *
 * <p>线程安全说明：处理器仅保存共享存储引擎引用，不保存请求级状态，可并发复用。
 *
 * <p>典型用例：
 * <pre>{@code
 * InfoHandler handler = new InfoHandler(storageEngine);
 * Response response = handler.handle(request);
 * }</pre>
 */
public final class InfoHandler implements CommandHandler {
    /** 用于生成 INFO 文本内容的存储引擎引用。 */
    private final StorageEngine storageEngine;

    /**
     * 创建 INFO 命令处理器。
     *
     * @param storageEngine 底层存储引擎
     * @throws NullPointerException 当 {@code storageEngine} 为 {@code null} 时抛出
     * @implNote INFO 当前只使用键数量，但保留整个存储引擎引用，便于后续扩展更多节点统计项。
     */
    public InfoHandler(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    /**
     * 生成节点概要信息文本。
     *
     * @param request 当前请求，仅使用请求 ID 构造响应
     * @return 包含节点角色与键数量的字符串响应
     * @implNote 输出格式目前采用简单换行文本，保持实现轻量，便于客户端直接打印或进一步解析。
     */
    @Override
    public Response handle(Request request) {
        return Responses.string(request.requestId(), "role:master\nkeys:" + storageEngine.size());
    }
}
