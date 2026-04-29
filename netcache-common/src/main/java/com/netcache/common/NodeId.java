package com.netcache.common;

import java.util.Objects;
import java.util.UUID;

/**
 * 节点标识符 —— 集群里每台机器的「身份证号」。
 * <p>
 * 它把底层 {@link UUID} 收敛成统一领域类型，避免业务代码到处直接传裸字符串或
 * UUID。没有它的话，协议字段、日志字段和拓扑结构很容易混用不同表示，导致序列化
 * 和比较逻辑分散在各处。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由集群发现、拓扑同步和持久化反序列化代码创建</li>
 *   <li>下游由路由、复制和监控日志使用它标识唯一节点</li>
 * </ul>
 * 线程安全：{@code record} 自带不可变语义，内部只持有 {@link UUID}，因此线程安全。
 * <p>
 * 典型用例：
 * <pre>
 * NodeId nodeId = NodeId.random();
 * String text = nodeId.toString();
 * NodeId restored = NodeId.fromString(text);
 * </pre>
 */
public record NodeId(UUID id) {
    /**
     * 校验并保存节点 UUID。
     *
     * @param id 节点全局唯一标识，不能为空
     * @throws NullPointerException 当 {@code id} 为 {@code null} 时抛出
     * @implNote 构造约束集中在 compact constructor，所有工厂方法都会自动复用。
     */
    public NodeId {
        Objects.requireNonNull(id, "id");
    }

    /**
     * 生成一个新的随机节点标识。
     *
     * @return 新建的 {@code NodeId}，理论上全局唯一，不返回 {@code null}
     * @implNote 底层使用随机 UUID，适合节点首次启动或测试场景快速分配身份。
     */
    public static NodeId random() {
        return new NodeId(UUID.randomUUID());
    }

    /**
     * 从字符串反序列化节点标识。
     *
     * @param value UUID 的文本表示，通常来自配置、协议报文或持久化快照
     * @return 解析后的节点标识对象，不返回 {@code null}
     * @throws NullPointerException 当 {@code value} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当字符串不是合法 UUID 文本时抛出
     * @implNote 这里显式保留 {@link UUID#fromString(String)} 的格式校验能力。
     */
    public static NodeId fromString(String value) {
        return new NodeId(UUID.fromString(Objects.requireNonNull(value, "value")));
    }

    /**
     * 输出适合跨进程序列化的标准字符串。
     *
     * @return 标准 UUID 文本，不返回 {@code null}
     * @implNote 统一委托底层 UUID，保证日志、JSON 和协议文本格式保持一致。
     */
    @Override
    public String toString() {
        return id.toString();
    }
}
