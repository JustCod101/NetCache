package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Objects;

/**
 * 节点端点 —— 集群里每个节点的「名片」。
 * <p>
 * 它把节点身份、网络地址、角色以及主从关系收拢到一个不可变对象里，供拓扑同步、路由选择和
 * 故障切换共同使用。没有它，各个模块就得分别维护 host、port、role 等零散字段，极易产生不一致。
 * <p>
 * 协作关系：由 {@link ClusterTopology}、Sentinel 相关类和客户端拓扑缓存共同消费；依赖
 * {@link NodeId} 和 {@link NodeRole} 描述节点身份与角色。
 * <p>
 * 线程安全：线程安全。该类型是不可变 {@code record}，适合作为跨线程传播的拓扑快照元素。
 * <p>
 * 典型用例：
 * <pre>{@code
 * NodeEndpoint master = new NodeEndpoint(nodeId, "127.0.0.1", 7001, NodeRole.MASTER, null);
 * String address = master.address();
 * boolean isMaster = master.role() == NodeRole.MASTER;
 * }</pre>
 *
 * @param nodeId 节点唯一标识
 * @param host 节点主机名或 IP
 * @param port 节点监听端口
 * @param role 节点角色，例如 master / slave
 * @param masterId 当节点是 slave 时，它当前跟随的 master ID；master 节点通常为 {@code null}
 */
public record NodeEndpoint(NodeId nodeId, String host, int port, NodeRole role, NodeId masterId) {
    /**
     * 校验节点端点的基本合法性。
     *
     * @throws NullPointerException 当必填字段为 {@code null} 时抛出
     * @throws IllegalArgumentException 当端口不在 1~65535 范围内时抛出
     * @implNote 这里故意不强制校验 slave 必须携带 {@code masterId}，因为某些拓扑重写阶段会暂存中间状态。
     */
    public NodeEndpoint {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(role, "role");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
    }

    /**
     * 生成 {@code host:port} 形式的可读地址。
     *
     * @return 拼接后的网络地址
     * @implNote 统一地址格式能让排序、日志输出和比较逻辑更稳定。
     */
    public String address() {
        return host + ':' + port;
    }
}
