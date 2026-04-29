package com.netcache.server.handler;

import com.netcache.common.ByteKey;
import com.netcache.storage.StorageEngine;

import java.util.List;
import java.util.Objects;

/**
 * 为依赖存储引擎的命令处理器提供公共参数校验与取参能力的抽象基类，像工具箱一样收纳重复小动作。
 *
 * <p>它解决的是存储类命令实现中重复样板代码过多的问题：键提取、参数下标校验、参数个数校验都集中在这里。
 * 如果没有它，GET/SET/DEL 等处理器会反复编写类似的防御性逻辑，维护成本高且风格不统一。
 *
 * <p>上游是具体命令处理器子类；下游依赖 {@link StorageEngine} 和 {@link ByteKey} 参与键转换与数据访问。
 *
 * <p>线程安全说明：实例只持有一个 {@link StorageEngine} 引用，不维护命令级临时状态；线程安全主要取决于存储引擎，
 * 因此在当前模型下可被多个请求并发复用。
 */
abstract class AbstractStorageHandler implements CommandHandler {
    /** 所有存储类命令共享的底层 KV 引擎。 */
    protected final StorageEngine storageEngine;

    /**
     * 创建存储类命令处理器基类。
     *
     * @param storageEngine 负责实际读写的存储引擎
     * @throws NullPointerException 当 {@code storageEngine} 为 {@code null} 时抛出
     * @implNote 构造时立即做空值保护，避免子类在执行路径上重复校验同一个依赖。
     */
    AbstractStorageHandler(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    /**
     * 读取指定下标参数并转换成不可变键对象。
     *
     * @param args 请求参数列表
     * @param index 目标键所在的参数下标
     * @return 复制得到的 {@link ByteKey}
     * @throws IllegalArgumentException 当参数不存在时抛出
     * @implNote 通过复制参数内容构造键，避免后续对原始字节数组的外部修改污染存储索引。
     */
    protected static ByteKey key(List<byte[]> args, int index) {
        return ByteKey.copyOf(arg(args, index));
    }

    /**
     * 读取指定下标的原始参数。
     *
     * @param args 请求参数列表
     * @param index 目标参数下标
     * @return 指定位置的参数字节数组
     * @throws IllegalArgumentException 当参数数量不足时抛出
     * @implNote 该方法只做边界校验，不复制字节数组，以便由调用方决定是否需要额外拷贝。
     */
    protected static byte[] arg(List<byte[]> args, int index) {
        if (args.size() <= index) {
            throw new IllegalArgumentException("missing argument at index " + index);
        }
        return args.get(index);
    }

    /**
     * 校验请求参数数量是否落在允许范围内。
     *
     * @param args 请求参数列表
     * @param min 允许的最少参数个数
     * @param max 允许的最多参数个数
     * @throws IllegalArgumentException 当参数数量超出区间时抛出
     * @implNote 采用闭区间校验以同时覆盖固定参数个数和可选参数两种命令形态。
     */
    protected static void requireArgCount(List<byte[]> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException("expected " + min + ".." + max + " arguments but got " + args.size());
        }
    }
}
