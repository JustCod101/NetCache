package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

/**
 * 处理 PING 命令的健康检查处理器，像前台应答器一样快速回复“我还活着”。
 *
 * <p>它解决最基础的连通性探测问题；如果没有它，客户端难以在不触发业务读写的情况下判断节点是否可用。
 *
 * <p>上游是 {@code CommandDispatcher}；下游只依赖 {@link Responses} 生成固定响应，因此不需要存储引擎参与。
 *
 * <p>线程安全说明：该处理器完全无状态，可安全共享给所有连接。
 *
 * <p>典型用例：
 * <pre>{@code
 * PingHandler handler = new PingHandler();
 * Response response = handler.handle(request);
 * }</pre>
 */
public final class PingHandler implements CommandHandler {
    /**
     * 响应客户端的 PING 请求。
     *
     * @param request 当前请求，仅使用其中的请求 ID
     * @return 始终为字符串 {@code PONG} 的成功响应
     * @implNote 方法忽略参数列表，只保留 requestId 用于与客户端请求对应。
     */
    @Override
    public Response handle(Request request) {
        return Responses.string(request.requestId(), "PONG");
    }
}
