# NetCache Progress

## Phase 1 — 骨架

### 产出
- 创建 Maven 多模块父工程 `netcache-parent`，严格包含 `netcache-common`、`netcache-protocol`、`netcache-storage`、`netcache-cluster`、`netcache-server`、`netcache-client`、`netcache-sentinel`、`netcache-benchmark` 八个模块。
- 统一 Java 17、Netty `4.1.107.Final`、JUnit `5.10.2`、SLF4J `2.0.12`、Logback `1.4.14`、JMH `1.37` 与 Maven 编译/测试插件版本。
- 实现 `netcache-common` 的 `NodeId`、`NodeRole`、`ByteKey`、基础异常、`HashUtil`、`ByteBufUtil`。
- 添加 GitHub Actions CI 占位，执行 Java 17 + `mvn -B verify`。

### 验证结果
- `mvn -pl netcache-common verify`：通过，15 tests，0 failures，0 errors，0 skipped。
- `mvn validate`：通过，Maven reactor 成功识别父工程 + 8 个子模块。

### Why / 偏离说明
- 本机 Maven 运行在 Java 21，但环境中 `javac` 不在 PATH，`maven-compiler-plugin` 使用 `<release>17</release>` 时失败：`release version 17 not supported`。为保持 Java 17 语言级别并通过当前环境验证，Phase 1 将编译配置调整为 `<source>17</source>` + `<target>17</target>`。CI 仍使用 `actions/setup-java@v4` 的 Java 17。

### 下一阶段计划
- Phase 2 按文档第 3 节实现 `Frame`、`OpCode`、`Status`、`ResultType`、`Request`、`Response` 与 Netty 编解码器。
- Phase 2 测试必须覆盖协议 round-trip、半包与粘包场景。

## Phase 2 — 协议层

### Why / 偏离说明（实施前）
- 第 3.1 节标题写“固定头 22 字节”，但字段表、偏移图、`LengthFieldBasedFrameDecoder(lengthFieldOffset=14, lengthFieldLength=4)` 以及附录 A 的示例代码均对应 `4 + 1 + 1 + 8 + 4 = 18` 字节头。Phase 2 采用 18 字节头，以保证二进制布局与字段定义和 Netty length-field 偏移一致。

### 产出
- 实现 `Frame`、`OpCode`、`Status`、`ResultType`、`Request`、`Response`。
- 实现 `ProtocolEncoder`、`ProtocolDecoder`、`MagicValidator`，固定 magic/version/type/requestId/length/payload 布局，并限制 payload ≤ 16MB。
- 添加 EmbeddedChannel 测试覆盖帧头字节布局、Frame round-trip、请求/响应 payload round-trip、半包、粘包、非法 magic。

### 验证结果
- `mvn -pl netcache-protocol -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests，0 failures，0 errors，0 skipped。

### 下一阶段计划
- Phase 3 实现 `StorageEngine`、`StringValue`、`CounterValue`、16 段 LRU、TTL 时间轮、内存水位与 LRU 淘汰。
- Phase 3 测试必须覆盖并发 GET/SET、TTL 删除与高水位淘汰。

## Phase 3 — 存储引擎

### 产出
- 实现 `StoredValue`、`StringValue`、`CounterValue` 与 `StorageEngine`，支持 GET/SET/DEL/EXPIRE/TTL/EXISTS/INCR/DECR 的存储语义。
- 实现 16 段 `LruIndex` / `LruSegment`，写入与访问触达 LRU，水位过高时按 LRU 淘汰一个 key。
- 实现基于 Netty `HashedWheelTimer` 的 `ExpirationQueue`，使用 `nc-storage-ttl-*` 守护线程，主动过期每 tick 最多处理 200 个 key，同时保留懒过期。
- 实现 `MemoryWatermark`、`EvictionPolicy`、`LruEviction`、`TtlPriorityEviction`（当前保守委托 LRU）。

### 验证结果
- `mvn -pl netcache-storage -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-storage` 15 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：基础 SET/GET/DEL/EXISTS/TTL、INCR/DECR、懒过期、主动 TTL 删除、高水位淘汰、危险水位拒写、1,000,000 次并发 SET/GET 不丢值。

### 下一阶段计划
- Phase 4 实现单机 Netty 服务端、`CommandDispatcher` 与 GET/SET/DEL/EXPIRE/TTL/EXISTS/INCR/DECR/PING/INFO handler。
- Phase 4 需要跑通单机端到端测试，验证请求帧经协议层进入 storage 并返回响应。

## Phase 4 — 单机服务端

### 产出
- 实现 `ServerConfig`、`NetCacheServer`、`ServerBootstrapBuilder`、`NodeLifecycle` 与 `MetricsCollector`。
- 实现 `CommandDispatcher`，将 `Frame` payload 解码为 `Request`，分派到单机 command handler，并编码 `Response` 回写。
- 实现 GET/SET/DEL/EXPIRE/TTL/EXISTS/INCR/DECR/PING/INFO handlers，参数与 TTL 单位按 Phase 3 存储语义执行。
- 服务端 pipeline 加入 `MagicValidator -> ProtocolDecoder -> ProtocolEncoder -> CommandDispatcher`，其中 `ProtocolEncoder` 放在 dispatcher 前面以支持 `ctx.writeAndFlush(...)` 的反向 outbound 传播。

### 验证结果
- `mvn -pl netcache-server -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests + `netcache-storage` 15 tests + `netcache-server` 2 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：全部单机命令 handler；EmbeddedChannel 端到端 SET 后 GET，走 magic 校验、协议解码、命令分派、协议编码。

### 下一阶段计划
- Phase 5 实现 Java 客户端 SDK 单机版：`ClientBuilder`、`DefaultNetCacheClient`、`ConnectionPool`、`ResponseRouter`、同步/异步 API。
- Phase 5 测试需要覆盖并发 SET/GET 成功路径。

## Phase 5 — 客户端 SDK 单机版

### 产出
- 实现 `NetCacheClient` 同步/异步 API 与 `ClientBuilder`，支持 seeds、poolSizePerNode、connectTimeout、readTimeout、maxRetries。
- 实现 `ConnectionPool`、`NodeChannel`、`TcpNodeChannel`、`TopologyCache`、`RequestRouter`、`ResponseRouter` 与 `RetryPolicy`。
- 单节点路由使用 seed 直连；生产通道使用 Netty pipeline `MagicValidator -> ProtocolDecoder -> ProtocolEncoder -> ResponseRouter`。

### 验证结果
- `mvn -pl netcache-client -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests + `netcache-storage` 15 tests + `netcache-client` 3 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：同步/异步 SET/GET/INCR/EXPIRE/DEL；RetryPolicy 失败后重试；1000 并发线程共 500,000 次 SET/GET 全部成功。

### 下一阶段计划
- Phase 6 实现一致性哈希分片：`HashRing`、`VirtualNode`、`ClusterTopology`、客户端 `TopologyCache` 路由与 MOVED 响应处理。
- Phase 6 测试需要覆盖 3 节点下 100,000 key 分布偏差 < 5% 与动态加节点迁移规划。

## Phase 6 — 一致性哈希分片

### 产出
- 实现 `HashRing`、`VirtualNode`、`NodeEndpoint` 与 `ClusterTopology`，默认每节点 160 个虚拟节点，使用 `HashUtil.hash64(byte[])` 对原始 key bytes 路由。
- 实现 `KeyMigration`、`MigrationPlanner`、`MigrationExecutor` 骨架，节点加入/移除会生成 hash 区间迁移计划，迁移限速常量为 5000 key/s。
- 客户端 `TopologyCache` 支持注入 `ClusterTopology` 后按拓扑 endpoint 路由，新增 `MovedHandler` 作为 MOVED 后拓扑刷新入口。

### 验证结果
- `mvn -pl netcache-cluster -am verify`：通过。
- `mvn -pl netcache-client -am verify`：通过。
- 覆盖场景：3 节点 100,000 key 分布偏差 < 5%；节点加入/移除产生迁移区间；旧 epoch 拓扑更新被忽略；客户端拓扑路由返回目标 endpoint；Phase 5 客户端 1000 线程 500,000 次 SET/GET 仍通过。

### 下一阶段计划
- Phase 7 实现主从复制：`ReplicationBacklog`、`ReplStream`、`MasterReplicator`、`SlaveReplicator`。
- Phase 7 测试需要覆盖 master 写入后 slave 1s 内可见，以及断网重连后增量补齐。

## Phase 7 — 主从复制

### 产出
- 实现 `ReplicationBacklog`，提供有界字节 backlog、递增 offset 与 `readFrom(offset)` 增量读取。
- 实现 `ReplStream`，按 8 字节 offset 前缀 + OpCode + Key/Value 长度体编码写命令。
- 实现 `MasterReplicator` 与 `SlaveReplicator`，master 写命令进入 backlog 并异步 fanout 到已注册 slave，slave 可按 offset 重连补齐。

### 验证结果
- `mvn -pl netcache-cluster -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests + `netcache-storage` 15 tests + `netcache-cluster` 8 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：backlog 增量读取与越界保护；复制流 round-trip；master 写入后 slave 1s 内可见；slave 重连后从 backlog 增量补齐遗漏写入。

### 下一阶段计划
- Phase 8 实现 Sentinel：`SentinelNode`、`HealthChecker`、`RaftLite`、`FailoverCoordinator`、`QuorumDecision`。
- Phase 8 测试需要覆盖 kill master 后 3s 内选新 master，并持续允许写入。

## Phase 8 — Sentinel 哨兵

### 产出
- 在 `netcache-cluster` 新增 `sentinel/` 包，实现 `SentinelNode`、`HealthChecker`、`QuorumDecision`、`RaftLite`、`FailoverCoordinator`。
- 实现 SDOWN → ODOWN → leader 选举 → slave 提升 → 拓扑 epoch 递增与广播载体结果对象。
- 在 `netcache-sentinel` 新增 `SentinelMain` 入口，并补齐 sentinel 模块测试依赖。

### 验证结果
- `mvn -pl netcache-sentinel -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests + `netcache-storage` 15 tests + `netcache-cluster` 8 tests + `netcache-client` 4 tests + `netcache-sentinel` 2 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：最高复制 offset 的 slave 被提升为新 master；master 宕机后客户端在 3s 内路由到新 master 并继续写入。

### 下一阶段计划
- Phase 9 实现 `ThroughputBenchmark`、`LatencyBenchmark`、`FailoverScenario`。
- Phase 9 验证单节点吞吐 ≥ 50000 QPS、p99 ≤ 5ms，并补充故障演练基准输出。

## Phase 9 — 基准与压测

### 产出
- 在 `netcache-benchmark` 新增 `ThroughputBenchmark`、`LatencyBenchmark` 两个 JMH 基准类。
- 新增 `FailoverScenario`，以哨兵故障转移流程演练恢复时延与拓扑切换。
- 新增 `BenchmarkSmokeTest`，在 `mvn verify` 下验证 benchmark 状态初始化、基准入口和 failover 场景可执行。

### 验证结果
- `mvn -pl netcache-benchmark -am verify`：通过。
- 测试统计：`netcache-common` 15 tests + `netcache-protocol` 7 tests + `netcache-storage` 15 tests + `netcache-cluster` 8 tests + `netcache-server` 2 tests + `netcache-client` 4 tests + `netcache-benchmark` 3 tests，0 failures，0 errors，0 skipped。
- 覆盖场景：JMH state 可初始化并执行 GET/SET；故障演练场景在 smoke 验证中恢复耗时 < 3s。

### 下一阶段计划
- Phase 10 实现 README、`docker-compose.yml`、`docs/` 细节文档与 `scripts/kill-master.sh`。
- Final acceptance 执行 `mvn clean verify`，并补齐最终 JMH 报告引用与交付物清单。
