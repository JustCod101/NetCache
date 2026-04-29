package com.netcache.common;

/**
 * 节点角色枚举 —— 集群拓扑里的「岗位牌」。
 * <p>
 * 它用最小集合描述节点当前承担的是主库还是从库职责，避免系统各处散落字符串常量。
 * 没有它的话，角色判断容易出现拼写错误，也不利于 switch 分支做编译期校验。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由拓扑发现、故障转移和配置解析模块决定角色值</li>
 *   <li>下游由路由、复制、监控展示等模块读取并作分支处理</li>
 * </ul>
 * 线程安全：枚举实例在 JVM 中天然单例，可被所有线程安全共享。
 * <p>
 * 典型用例：
 * <pre>
 * if (node.role() == NodeRole.MASTER) {
 *     router.routeWrite(node);
 * }
 * </pre>
 */
public enum NodeRole {
    /**
     * 主节点（master）：负责处理写流量，通常也是复制源头。
     */
    MASTER,

    /**
     * 从节点（slave）：跟随主节点复制，常用于读扩展或故障接管。
     */
    SLAVE
}
