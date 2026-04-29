# NetCache —— 分布式 KV 缓存引擎 架构设计与编码方案

> 本文档为 opencode agent 实施 NetCache 项目的完整工程蓝图。包含模块切分、接口契约、数据结构、协议格式、关键算法、目录骨架、阶段路线图与验收标准。请严格按照"模块依赖顺序 + 阶段路线图"推进，每完成一个阶段即跑通对应测试再进入下一阶段。

---

## 0. 项目目标与边界

**目标**：基于 Java 17 + Netty 4 实现一个轻量级分布式 KV 缓存系统，覆盖 Redis 核心子集（GET/SET/DEL/EXPIRE/INCR 等），支持一致性哈希分片、主从异步复制、Sentinel 哨兵故障转移，提供 Java 客户端 SDK。

**性能目标**：
- 单节点 ≥ 50,000 QPS（GET/SET，1KB value，单连接 pipeline 关闭场景）
- p99 延迟 ≤ 5ms（局域网）
- 故障转移恢复时间 < 3s
- 内存利用率稳定在 85% 以上

**显式不做**：
- 持久化（RDB/AOF）—— 仅做内存存储
- 集群间事务、Lua 脚本、发布订阅
- TLS、ACL（预留接口但不实现）

---

## 1. 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Java 17（var、record、switch pattern matching） |
| 网络 | Netty 4.1.x |
| 构建 | Maven 多模块 |
| 日志 | SLF4J 2.x + Logback 1.4.x |
| 序列化 | 自定义二进制协议（无第三方依赖） |
| 测试 | JUnit 5、Mockito、AssertJ |
| 基准 | JMH 1.37 |
| 工具库 | Lombok（可选）、Caffeine（仅指标缓存） |
| 容器 | Docker + docker-compose（用于多节点演练） |

---

## 2. 模块划分（Maven 多模块）

依赖方向：上层依赖下层，下层不反向依赖。

```
netcache-parent (pom)
├── netcache-common         核心常量、异常、工具类、ByteBuf 帮手
├── netcache-protocol       二进制协议定义、编解码器、命令枚举
├── netcache-storage        内存存储引擎、LRU、TTL、淘汰策略
├── netcache-cluster        一致性哈希、分片路由、复制、哨兵
├── netcache-server         Netty 服务端、命令分派、节点生命周期
├── netcache-client         Java SDK：连接池、路由缓存、重试
├── netcache-sentinel       Sentinel 进程入口（复用 cluster 的哨兵逻辑）
└── netcache-benchmark      JMH 基准、压测工具、集成测试场景
```

依赖图：

```
common ← protocol ← storage ← cluster ← server
                                ↑
                              client
                                ↑
                            sentinel
                                ↑
                          benchmark (依赖所有)
```

---

## 3. 二进制协议设计

### 3.1 帧格式（固定头 22 字节 + 变长 payload）

```
 0        4         5      6           14        18           N
 +--------+---------+------+-----------+--------+--------------+
 | Magic  | Version | Type | RequestId | Length |   Payload    |
 | 4B     | 1B      | 1B   | 8B        | 4B     |   N B        |
 +--------+---------+------+-----------+--------+--------------+
```

| 字段 | 长度 | 说明 |
|------|------|------|
| Magic | 4B | 固定 `0xC0DECAFE`，用于快速校验帧合法性 |
| Version | 1B | 协议版本，初版为 `0x01` |
| Type | 1B | 0x01=请求 0x02=响应 0x03=复制流 0x04=哨兵心跳 |
| RequestId | 8B | 客户端单调递增，用于响应匹配 |
| Length | 4B | Payload 长度（不含头），上限 16MB |
| Payload | NB | 命令体（请求）或结果体（响应） |

### 3.2 请求 Payload 编码

```
+--------+--------------+----------+----------+----+----------+
| OpCode | ArgCount(2B) | Arg1Len  | Arg1Body | ...| ArgNBody |
| 1B     | 2B           | 4B       | M B      |    |          |
+--------+--------------+----------+----------+----+----------+
```

OpCode 枚举（部分，完整见 `OpCode.java`）：

| 值 | 命令 | 参数 | 说明 |
|----|------|------|------|
| 0x10 | GET | key | 读取 |
| 0x11 | SET | key, value, [ttlMs] | 写入 |
| 0x12 | DEL | key | 删除 |
| 0x13 | EXPIRE | key, ttlMs | 设置过期 |
| 0x14 | TTL | key | 查询剩余 TTL |
| 0x15 | EXISTS | key | 是否存在 |
| 0x16 | INCR | key | 原子自增 |
| 0x17 | DECR | key | 原子自减 |
| 0x20 | PING | - | 心跳 |
| 0x21 | INFO | section | 节点信息 |
| 0x30 | CLUSTER_NODES | - | 拓扑查询（客户端用） |
| 0x40 | SLAVEOF | host, port | 设为某节点从 |
| 0x41 | PSYNC | replId, offset | 复制握手 |
| 0x50 | SENTINEL_HELLO | - | 哨兵互发现 |
| 0x51 | SENTINEL_FAILOVER | masterName | 触发选主 |

### 3.3 响应 Payload 编码

```
+--------+-------------+----------+
| Status | ResultType  |  Body    |
| 1B     | 1B          |  varies  |
+--------+-------------+----------+
```

- Status：0x00=OK 0x01=ERROR 0x02=MOVED（带目标节点） 0x03=ASK 0x04=NIL
- ResultType：0x00=NULL 0x01=STRING 0x02=INT64 0x03=BYTES 0x04=NODE_LIST 0x05=ERROR_MSG
- 当 Status=MOVED，Body 为 `nodeId(16B) + host(varStr) + port(2B)`，客户端据此更新路由

### 3.4 Pipeline 组装（Netty）

```
Inbound:
  LengthFieldBasedFrameDecoder(maxFrame=16MB, lengthFieldOffset=14, lengthFieldLength=4)
    → MagicValidator
    → ProtocolDecoder (输出 Request/Response 对象)
    → CommandDispatcher (服务端) / ResponseRouter (客户端)

Outbound:
  ProtocolEncoder
    → LengthFieldPrepender 隐含在 Encoder 内
```

---

## 4. 存储引擎（netcache-storage）

### 4.1 核心数据结构

```java
public sealed interface StoredValue permits StringValue, CounterValue {
    long expireAtMs();      // 0 表示无过期
    long lastAccessMs();    // LRU 用
    int sizeBytes();
}

public final class StorageEngine {
    private final ConcurrentHashMap<ByteKey, StoredValue> map;
    private final LruIndex lruIndex;            // 见 4.2
    private final ExpirationQueue expirationQueue; // 见 4.3
    private final MemoryWatermark watermark;    // 见 4.4
}
```

`ByteKey` 包装 `byte[]`，重写 hashCode/equals（基于 Arrays.hashCode + memcmp）。

### 4.2 LRU 实现

- 数据结构：分段（segment）双向链表 + 主存 ConcurrentHashMap
- 16 个 segment 降低锁竞争，每段独立链表与 ReentrantLock
- 访问时把节点移到段头；淘汰时从段尾驱逐
- 不使用 LinkedHashMap（不支持并发安全 + 命中率较低）

```java
class LruIndex {
    private static final int SEGMENTS = 16;
    private final LruSegment[] segments;
    void touch(ByteKey k);
    ByteKey evictOne();   // 各段轮询，选最旧
}
```

### 4.3 TTL 过期策略

**懒过期**：GET 命中时校验 expireAt，已过期则删除并返回 NIL。

**主动过期**（后台线程）：
- 使用基于时间轮 `HashedWheelTimer`（Netty 自带）
- 每 100ms 扫描一个槽位，槽位上挂着即将到期的 key 列表
- 每次最多处理 200 个 key，避免 STW
- 过期项进入"过期事件队列"广播给从节点

### 4.4 内存水位与淘汰

- `MemoryWatermark` 通过 `ManagementFactory.getMemoryMXBean()` 采样堆使用率
- 高水位 85%：触发同步淘汰（每写入 1 次淘汰 1 个）
- 危险水位 92%：拒写（返回 OOM_GUARD 错误），仅允许读和 DEL
- 配置可选淘汰算法：`lru`（默认）、`ttl-priority`（先淘汰最近过期）

### 4.5 并发模型

- 同 key 写：`map.compute(k, (k, v) -> ...)` 保证原子
- INCR/DECR：基于 compute + 类型校验 + AtomicLong wrapper
- 读 LRU touch：异步投递给 LRU 段，避免读路径加锁阻塞

---

## 5. 集群与分片（netcache-cluster）

### 5.1 一致性哈希环

```java
public class HashRing {
    private final NavigableMap<Long, VirtualNode> ring; // TreeMap.ceiling 用
    private final Map<NodeId, List<VirtualNode>> nodeToVnodes;
    private final HashFunction hash;  // MurmurHash3_x64_128 取低 64 位
    private final int virtualPerNode = 160;

    public NodeId routeOf(byte[] key);
    public void addNode(NodeId node);
    public List<KeyMigration> removeNode(NodeId node);
}
```

- 虚拟节点数 160（可配置），保证负载均衡
- `addNode` 返回需要从哪些节点迁移哪段 hash 区间到新节点
- 哈希值用 `key bytes` 直接计算，不做字符串编码

### 5.2 集群拓扑管理

`ClusterTopology`：
- 持有当前所有节点（id、host、port、role: MASTER/SLAVE、masterId）
- 通过 Sentinel 推送或客户端拉取更新
- 版本号 `epoch` 单调递增，旧消息直接丢弃

### 5.3 数据迁移

- 触发：节点加入 / 节点下线（被哨兵确认）
- 流程：源节点扫描自身负责区间，把不再属于自己的 key 通过 `MIGRATE_KEY` 命令推到目标节点
- 期间客户端访问旧节点拿到 `MOVED` 响应自动重路由
- 限速：每秒迁移 ≤ 5000 key，避免影响业务流量

---

## 6. 主从复制

### 6.1 复制模型

- 异步复制，最终一致
- 每个 master 维护 `ReplicationBacklog`（环形缓冲区，默认 16MB）
- slave 通过 `PSYNC <replId> <offset>` 请求增量
- offset 不在 backlog 范围 → 全量同步：master 触发"内存快照"（dump 当前 map） + 增量

### 6.2 复制流编码

复制流是单向 stream（Type=0x03 帧），payload：

```
+--------+----------+----------+
| OpCode | KeyLen+K | ValLen+V |  (写命令的简化版，不含 RequestId)
+--------+----------+----------+
```

每条命令有 8 字节 offset 前缀，slave 持久化最新 offset 用于断点续传。

### 6.3 关键类

```java
class ReplicationBacklog { write(byte[] cmd); ByteBuf readFrom(long offset); }
class MasterReplicator { onWriteCommand(Command c); registerSlave(Channel ch); }
class SlaveReplicator { connect(host, port); applyStream(); reportOffset(); }
```

---

## 7. Sentinel 哨兵

### 7.1 部署形态

- 独立进程（`netcache-sentinel`），建议 3/5 个奇数节点
- 监控所有 master，互相通过 SENTINEL_HELLO 发现彼此
- 共识算法：Raft 简化版（仅 leader 选举，不做日志复制）

### 7.2 故障检测

- 每 1s 向所监控的 master/slave 发 PING
- 连续 5s 无响应 → SDOWN（主观下线）
- 收到 ≥ quorum 个哨兵的 SDOWN 投票 → ODOWN（客观下线）

### 7.3 Failover 流程

1. 选出 sentinel leader（Raft 风格，term + voteFor）
2. leader 在该 master 的 slave 中挑选最优（offset 最大、优先级最高）
3. 给该 slave 发 `SLAVEOF NO ONE` 提升为 master
4. 给其他 slave 发 `SLAVEOF <newMaster>`
5. 广播新拓扑给所有 sentinel 与已知客户端
6. epoch += 1

目标：步骤 1-5 总耗时 < 3s。

### 7.4 客户端感知

- 客户端连接任一节点，定期拉取 `CLUSTER_NODES`
- 收到 MOVED 响应即触发拓扑刷新
- 哨兵也可主动推送（客户端订阅 `SENTINEL_HELLO`）

---

## 8. 客户端 SDK（netcache-client）

### 8.1 公开 API

```java
public interface NetCacheClient extends AutoCloseable {
    byte[] get(byte[] key);
    void set(byte[] key, byte[] value);
    void set(byte[] key, byte[] value, Duration ttl);
    long incr(byte[] key);
    boolean del(byte[] key);
    boolean expire(byte[] key, Duration ttl);
    // ... 异步版本：CompletableFuture<...>
}

NetCacheClient client = NetCacheClient.builder()
    .seeds("10.0.0.1:7001", "10.0.0.2:7001")
    .poolSizePerNode(8)
    .connectTimeout(Duration.ofMillis(500))
    .readTimeout(Duration.ofSeconds(2))
    .maxRetries(3)
    .build();
```

### 8.2 内部组件

- `TopologyCache`：本地缓存的哈希环 + 节点表，通过 seeds 启动时拉取
- `ConnectionPool`：每节点 N 条长连接，FixedChannelPool（Netty）
- `RequestRouter`：key → node → channel
- `ResponseRouter`：基于 RequestId 的 `ConcurrentHashMap<Long, CompletableFuture>`
- `RetryPolicy`：指数退避（base=50ms，factor=2，cap=2s，jitter ±20%）
- `MovedHandler`：收到 MOVED 后重路由 + 异步刷新拓扑

### 8.3 线程模型

- 用户调用走 BIO API，但底层 Netty 是 NIO；用户线程 await 在 CompletableFuture 上
- 提供 `getAsync` 等方法返回 CompletableFuture，避免阻塞
- Netty EventLoop 数 = `cores * 2`（可配）

---

## 9. 目录骨架（必须严格遵守）

```
netcache/
├── pom.xml                                  父 POM，统一依赖版本
├── docker-compose.yml                       3 master + 3 slave + 3 sentinel 演练
├── README.md
├── netcache-common/
│   └── src/main/java/com/netcache/common/
│       ├── NodeId.java                      record(UUID id)
│       ├── NodeRole.java                    enum MASTER, SLAVE
│       ├── ByteKey.java
│       ├── exception/                       NetCacheException 等
│       └── util/                            HashUtil, ByteBufUtil
├── netcache-protocol/
│   └── src/main/java/com/netcache/protocol/
│       ├── Frame.java                       record(magic, version, type, reqId, payload)
│       ├── OpCode.java
│       ├── Status.java
│       ├── ResultType.java
│       ├── codec/
│       │   ├── ProtocolDecoder.java         extends ByteToMessageDecoder
│       │   ├── ProtocolEncoder.java         extends MessageToByteEncoder<Frame>
│       │   └── MagicValidator.java
│       └── command/
│           ├── Request.java                 record(opCode, args, reqId)
│           └── Response.java                record(status, type, body, reqId)
├── netcache-storage/
│   └── src/main/java/com/netcache/storage/
│       ├── StorageEngine.java
│       ├── StoredValue.java                 sealed interface
│       ├── StringValue.java
│       ├── CounterValue.java
│       ├── lru/LruIndex.java
│       ├── lru/LruSegment.java
│       ├── ttl/ExpirationQueue.java         基于 HashedWheelTimer
│       ├── eviction/EvictionPolicy.java
│       ├── eviction/LruEviction.java
│       ├── eviction/TtlPriorityEviction.java
│       └── memory/MemoryWatermark.java
├── netcache-cluster/
│   └── src/main/java/com/netcache/cluster/
│       ├── HashRing.java
│       ├── VirtualNode.java
│       ├── ClusterTopology.java
│       ├── migration/MigrationPlanner.java
│       ├── migration/MigrationExecutor.java
│       ├── replication/ReplicationBacklog.java
│       ├── replication/MasterReplicator.java
│       ├── replication/SlaveReplicator.java
│       ├── replication/ReplStream.java
│       └── sentinel/
│           ├── SentinelNode.java
│           ├── HealthChecker.java
│           ├── FailoverCoordinator.java
│           ├── RaftLite.java                简化 leader 选举
│           └── QuorumDecision.java
├── netcache-server/
│   └── src/main/java/com/netcache/server/
│       ├── NetCacheServer.java              main 入口
│       ├── ServerConfig.java
│       ├── netty/ServerBootstrapBuilder.java
│       ├── netty/CommandDispatcher.java     channelRead0
│       ├── handler/                         一个 OpCode 对应一个 handler
│       │   ├── GetHandler.java
│       │   ├── SetHandler.java
│       │   ├── DelHandler.java
│       │   └── ...
│       ├── lifecycle/NodeLifecycle.java
│       └── metrics/MetricsCollector.java    QPS / p99 / mem
├── netcache-client/
│   └── src/main/java/com/netcache/client/
│       ├── NetCacheClient.java              公开接口
│       ├── DefaultNetCacheClient.java
│       ├── ClientBuilder.java
│       ├── pool/ConnectionPool.java
│       ├── pool/NodeChannel.java
│       ├── routing/TopologyCache.java
│       ├── routing/RequestRouter.java
│       ├── routing/ResponseRouter.java
│       └── retry/RetryPolicy.java
├── netcache-sentinel/
│   └── src/main/java/com/netcache/sentinel/
│       └── SentinelMain.java
├── netcache-benchmark/
│   └── src/main/java/com/netcache/benchmark/
│       ├── ThroughputBenchmark.java         JMH
│       ├── LatencyBenchmark.java            JMH
│       └── FailoverScenario.java            集成场景
└── docs/
    ├── protocol.md                          协议详解
    ├── deploy.md
    └── failover.md
```

---

## 10. 实施阶段（路线图）

每个阶段必须跑通对应测试再进入下一阶段。

### Phase 1 — 骨架（0.5 天）
- [ ] 父 POM、子模块、统一依赖版本（Netty 4.1.107.Final、JUnit 5.10、SLF4J 2.0.x）
- [ ] CI 占位（GitHub Actions 跑 mvn verify）
- [ ] common 模块：异常、工具类、ByteKey

### Phase 2 — 协议层（1 天）
- [ ] Frame、OpCode、Request、Response
- [ ] ProtocolDecoder/Encoder
- [ ] **测试**：编码 → 解码 round-trip 100% 一致；半包 / 粘包用 EmbeddedChannel 验证

### Phase 3 — 存储引擎（1.5 天）
- [ ] StorageEngine + StringValue / CounterValue
- [ ] LruIndex（16 段）
- [ ] ExpirationQueue（HashedWheelTimer）
- [ ] MemoryWatermark + LruEviction
- [ ] **测试**：并发 GET/SET 1M 操作不丢；TTL 触发删除；高水位触发淘汰

### Phase 4 — 单机服务端（1 天）
- [ ] NetCacheServer 启动 Netty
- [ ] CommandDispatcher 路由到 handler
- [ ] 全部单机命令：GET/SET/DEL/EXPIRE/TTL/EXISTS/INCR/DECR/PING/INFO
- [ ] **测试**：单机端到端，redis-cli-like 工具能跑通

### Phase 5 — 客户端 SDK 单机版（0.5 天）
- [ ] ClientBuilder + DefaultNetCacheClient
- [ ] ConnectionPool + ResponseRouter
- [ ] 同步 + 异步 API
- [ ] **测试**：1000 并发线程 50 万次 SET/GET 全部成功

### Phase 6 — 一致性哈希分片（1 天）
- [ ] HashRing + VirtualNode
- [ ] ClusterTopology + epoch
- [ ] 客户端 TopologyCache，按 key 路由到目标节点
- [ ] MOVED 响应处理
- [ ] **测试**：3 节点拓扑下，10 万 key 分布偏差 < 5%；动态加节点触发迁移

### Phase 7 — 主从复制（1.5 天）
- [ ] ReplicationBacklog
- [ ] MasterReplicator（每条写命令进 backlog + 推流）
- [ ] SlaveReplicator（PSYNC 全量 / 增量）
- [ ] **测试**：master 写入 → slave 1s 内可见；断网重连后增量补齐

### Phase 8 — Sentinel 哨兵（1.5 天）
- [ ] SentinelNode + HealthChecker
- [ ] RaftLite 选举
- [ ] FailoverCoordinator：SDOWN → ODOWN → 选 leader → 提升 slave → 广播
- [ ] **测试**：kill master，3s 内客户端透明切换到新 master 继续写入

### Phase 9 — 基准与压测（0.5 天）
- [ ] JMH ThroughputBenchmark（单节点 GET/SET QPS）
- [ ] LatencyBenchmark（p50/p99/p999）
- [ ] FailoverScenario（端到端故障演练，断言恢复时间）
- [ ] **目标**：单节点 ≥ 50000 QPS，p99 ≤ 5ms，failover < 3s

### Phase 10 — 文档与部署（0.5 天）
- [ ] README（quickstart、架构图、命令参考）
- [ ] docker-compose.yml（3M3S3Sentinel）
- [ ] docs/ 三篇细节文档

---

## 11. 配置项（统一 application.yml + 启动参数覆盖）

```yaml
netcache:
  server:
    host: 0.0.0.0
    port: 7001
    bossThreads: 1
    workerThreads: 0   # 0 = cores * 2
  storage:
    maxMemoryMb: 2048
    evictionPolicy: lru   # lru | ttl-priority
    highWatermark: 0.85
    dangerWatermark: 0.92
  cluster:
    nodeId: auto         # auto = UUID
    role: master         # master | slave
    masterAddr:           # 仅 slave 填
    virtualNodes: 160
  replication:
    backlogMb: 16
    pingIntervalMs: 1000
  sentinel:
    quorum: 2
    sdownAfterMs: 5000
    failoverTimeoutMs: 10000
```

---

## 12. 关键约束与代码规范

1. **零拷贝优先**：所有 ByteBuf 处理走 `slice()` / `retainedSlice()`，避免数组拷贝
2. **不在 EventLoop 阻塞**：阻塞操作（如全量同步快照）切到独立 `DefaultEventExecutorGroup`
3. **ByteBuf 释放纪律**：每个 Decoder 出站后必须 `ReferenceCountUtil.release` 输入；用 ByteBufUtil.assertEqual 测试
4. **不可变优先**：所有跨线程消息用 record 或 final class
5. **日志规范**：error 必须带上下文（nodeId、reqId、key 摘要前 16B）；trace 仅在 debug 配置开启
6. **没有 println，没有 ad-hoc 线程**：所有线程命名 `nc-{module}-{n}`，便于排障
7. **测试覆盖率门槛**：核心模块（storage、cluster）≥ 80%，其他 ≥ 60%

---

## 13. 验收清单（DoD）

- [ ] `mvn clean verify` 全绿，覆盖率达标
- [ ] docker-compose up 后，sample-client 能跑通 SET/GET
- [ ] 故障演练脚本（`scripts/kill-master.sh`）能复现 < 3s failover
- [ ] JMH 报告显示单节点 ≥ 50000 QPS
- [ ] README 包含架构图、quickstart、命令参考三节
- [ ] 协议文档可独立指导第三方实现客户端

---

## 14. 风险与降级

| 风险 | 缓解 |
|------|------|
| Netty ByteBuf 泄漏导致 OOM | 启动 `-Dio.netty.leakDetection.level=PARANOID` 跑测试 |
| 全量同步阻塞 master | 快照线程独立 + 发送时分片限速 |
| 哨兵脑裂 | quorum 必须 > 节点数/2，部署奇数个 |
| LRU 段锁竞争 | 段数可配；高负载下切到 ConcurrentLinkedHashMap 风格的 CLHM |
| 时间轮 tick 漂移 | TTL 误差容忍 ±100ms，写在文档 |

---

## 15. 给 opencode agent 的执行守则

1. **严格按 Phase 顺序**，不要跨阶段提前实现，每阶段结尾跑测试
2. **代码必须可编译**：每个 commit 都通过 `mvn -pl <module> compile`
3. **遇到歧义优先选保守实现**：例如配置默认值、错误处理路径
4. **接口先行**：先定义 record / interface，再写实现，再写测试
5. **所有 TODO 要带 issue 编号**，禁止 silent skip
6. **完成每个 Phase 时输出一份简短 PROGRESS.md 追加**：本阶段做了什么、验证结果、下阶段计划
7. **遇到不会的 API 直接查官方文档**，不要瞎猜方法名

---

## 附录 A：示例代码片段（启发用，不是最终实现）

### A.1 ProtocolDecoder 骨架

```java
public class ProtocolDecoder extends ByteToMessageDecoder {
    private static final int HEADER_LEN = 18; // magic+ver+type+reqId+len
    private static final int MAGIC = 0xC0DECAFE;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_LEN) return;
        in.markReaderIndex();
        int magic = in.readInt();
        if (magic != MAGIC) {
            ctx.close();
            return;
        }
        byte version = in.readByte();
        byte type = in.readByte();
        long reqId = in.readLong();
        int len = in.readInt();
        if (in.readableBytes() < len) {
            in.resetReaderIndex();
            return;
        }
        ByteBuf payload = in.readRetainedSlice(len);
        out.add(new Frame(magic, version, type, reqId, payload));
    }
}
```

### A.2 HashRing 路由

```java
public NodeId routeOf(byte[] key) {
    long h = MurmurHash3.hash64(key);
    Map.Entry<Long, VirtualNode> e = ring.ceilingEntry(h);
    if (e == null) e = ring.firstEntry();
    return e.getValue().nodeId();
}
```

### A.3 重试退避

```java
long backoff(int attempt) {
    long base = 50L * (1L << Math.min(attempt, 6));   // 50,100,200,...,3200
    long capped = Math.min(base, 2000L);
    long jitter = ThreadLocalRandom.current().nextLong(-capped/5, capped/5);
    return capped + jitter;
}
```

---

## 附录 B：完成后建议的 README 大纲

1. NetCache 是什么 / 不是什么
2. 架构图（一致性哈希 + 主从 + 哨兵）
3. Quickstart（docker-compose 起 9 节点）
4. 命令参考表
5. 客户端 SDK 用法
6. 性能数据（JMH 报告引用）
7. 路线图

---

**End of Architecture Document. opencode agent 请基于本文档进入 Phase 1。**
