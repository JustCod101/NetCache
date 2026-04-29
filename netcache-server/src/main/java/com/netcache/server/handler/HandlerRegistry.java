package com.netcache.server.handler;

import com.netcache.protocol.OpCode;
import com.netcache.storage.StorageEngine;

import java.util.EnumMap;
import java.util.Map;

/**
 * 负责装配命令处理器映射的注册表工厂，像字典编目器一样把每个 OpCode 对应到唯一执行者。
 *
 * <p>这个类解决的是 handler 创建和集中注册问题：把散落的命令实现组织成统一路由表，供
 * {@code CommandDispatcher} 常量时间查找。如果没有它，调用方需要手动维护映射关系，既重复又容易漏项。
 *
 * <p>上游通常是 {@code NodeLifecycle}；下游是各个无状态的 {@link CommandHandler} 实现，其中多数依赖
 * {@link StorageEngine} 完成实际数据操作。
 *
 * <p>线程安全说明：类本身无状态，工厂方法每次返回新的映射对象；单个 handler 默认无状态或仅持有线程安全依赖，
 * 因此可安全被多个连接共享。
 *
 * <p>典型用例：
 * <pre>{@code
 * StorageEngine storageEngine = new StorageEngine();
 * Map<OpCode, CommandHandler> handlers = HandlerRegistry.singleNode(storageEngine);
 * CommandDispatcher dispatcher = new CommandDispatcher(handlers);
 * }</pre>
 */
public final class HandlerRegistry {
    /**
     * 禁止实例化纯工厂类。
     *
     * @implNote 注册表只提供静态装配入口，不保存任何运行期状态。
     */
    private HandlerRegistry() {
    }

    /**
     * 构建单节点模式下的命令处理器映射。
     *
     * @param storageEngine 为存储类命令提供底层 KV 能力的存储引擎
     * @return 覆盖当前服务端支持命令的 OpCode → handler 映射
     * @implNote 使用 {@link EnumMap} 可减少枚举键查找成本；每个 handler 在注册时完成依赖注入，运行期不再变更。
     */
    public static Map<OpCode, CommandHandler> singleNode(StorageEngine storageEngine) {
        Map<OpCode, CommandHandler> handlers = new EnumMap<>(OpCode.class);
        handlers.put(OpCode.GET, new GetHandler(storageEngine));
        handlers.put(OpCode.SET, new SetHandler(storageEngine));
        handlers.put(OpCode.DEL, new DelHandler(storageEngine));
        handlers.put(OpCode.EXPIRE, new ExpireHandler(storageEngine));
        handlers.put(OpCode.TTL, new TtlHandler(storageEngine));
        handlers.put(OpCode.EXISTS, new ExistsHandler(storageEngine));
        handlers.put(OpCode.INCR, new IncrHandler(storageEngine));
        handlers.put(OpCode.DECR, new DecrHandler(storageEngine));
        handlers.put(OpCode.PING, new PingHandler());
        handlers.put(OpCode.INFO, new InfoHandler(storageEngine));
        return handlers;
    }
}
