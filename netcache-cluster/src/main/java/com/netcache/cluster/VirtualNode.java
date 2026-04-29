package com.netcache.cluster;

import com.netcache.common.NodeId;

/**
 * 虚拟节点 —— 一致性哈希环上的「刻度点」。
 * <p>
 * 物理节点数量通常不多，直接把它们放到环上容易出现区间分布不均；虚拟节点把一个物理节点拆成
 * 多个离散刻度，从而让 key 分布更平滑、热点更少。
 * <p>
 * 协作关系：由 {@link HashRing} 创建和管理，上游不会直接操作它的生命周期，但会通过它的元数据
 * 推导 key 的归属和迁移边界。
 * <p>
 * 线程安全：线程安全。该类型是不可变 {@code record}，构造完成后状态不再变化。
 * <p>
 * 典型用例：
 * <pre>{@code
 * VirtualNode vnode = new VirtualNode(nodeId, 7, hash);
 * NodeId physicalNode = vnode.nodeId();
 * long ringPosition = vnode.hash();
 * }</pre>
 *
 * @param nodeId 该虚拟节点所属的物理节点 ID
 * @param index 同一物理节点下的虚拟节点序号
 * @param hash 该虚拟节点在一致性哈希环上的位置
 */
public record VirtualNode(NodeId nodeId, int index, long hash) {
}
