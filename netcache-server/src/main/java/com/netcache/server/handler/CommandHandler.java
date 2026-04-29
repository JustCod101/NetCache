package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

/**
 * 定义单条命令执行契约的最小接口，像插座标准一样约束所有命令处理器的输入输出形状。
 *
 * <p>这个接口解决的是不同命令实现如何被统一调度的问题：无论是 GET、SET 还是 INFO，都以
 * {@link Request} 作为输入、以 {@link Response} 作为输出，从而让分发器无需了解内部细节。
 *
 * <p>上游是 {@code CommandDispatcher}；下游是各个具体实现类，如 {@code GetHandler}、{@code PingHandler}。
 *
 * <p>线程安全说明：接口本身不持有状态；实现类是否线程安全取决于其字段与底层依赖。当前注册表创建的实现大多无状态，
 * 或仅依赖线程安全的存储引擎，因此可被多个 channel 共享。
 *
 * <p>典型用例：
 * <pre>{@code
 * CommandHandler handler = new PingHandler();
 * Response response = handler.handle(request);
 * ctx.writeAndFlush(Frame.response(response.requestId(), response.encodePayload(ctx.alloc())));
 * }</pre>
 */
public interface CommandHandler {
    /**
     * 执行一条已经完成协议解码的命令请求。
     *
     * @param request 命令请求，包含操作码、参数与请求 ID
     * @return 可直接编码回网络层的响应对象
     * @throws RuntimeException 当参数非法、存储操作失败或命令实现显式拒绝请求时抛出
     * @implNote 实现类通常不负责网络细节，只专注业务语义；异常可向上抛给分发器统一转成错误响应。
     */
    Response handle(Request request);
}
