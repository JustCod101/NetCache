# 13 - 新人常见疑问 20 问

## 灵魂三问

### Q1: NetCache 是什么？能用来做什么？

NetCache 是一个**轻量级分布式 KV 缓存引擎**，用 Java 17 + Netty 4 实现。它学习 Redis 的核心功能（GET/SET/DEL/EXPIRE/INCR），并增加了分布式特性（一致性哈希、主从复制、故障转移）。

**适用场景**：
- 热点数据缓存（减少数据库压力）
- 分布式锁
- 限流计数器
- 学习分布式系统原理

**不适用场景**：
- 需要持久化（AOF/RDB）—— NetCache 不做
- 需要复杂数据类型（List、Set、ZSet）—— 只支持 string 和 counter
- 需要 Lua 脚本—— 不支持
- 生产级高可用—— 这是学习项目，生产环境用 Redis Cluster 或 etcd

---

### Q2: 为什么叫「NetCache」？

Net + Cache = 网络缓存。顾名思义，这是一个通过网络访问的缓存系统。

---

### Q3: 我该怎么开始读代码？

1. 先读 [00-README.md](./00-README.md) 的「阅读顺序」
2. 按顺序读 01~09，理解每个模块的设计
3. 跑通 quickstart：`mvn clean verify && docker compose up --build`
4. 用 [12-debug-and-tools.md](./12-debug-and-tools.md) 的方法调试

---

## 架构问题

### Q4: 一致性哈希为什么不直接用 hash(key) % nodeCount？

因为 nodeCount 变化时，所有数据都要重新映射（缓存雪崩）。一致性哈希让加减节点只影响相邻区段。

**示例**：
```
假设有 3 个节点：hash(key) % 3
加一个新节点变成 4 个：hash(key) % 4
旧节点上 2/3 的数据需要迁移！
```

一致性哈希用环和虚拟节点解决这个问题。

---

### Q5: 虚拟节点为什么要 160 个？

经验值。太少负载不均，太多占内存。

数学上：160 个虚拟节点可以让负载标准差降到可接受范围。

---

### Q6: 主从复制是同步还是异步？

**异步**。master 写完就返回，不等 slave 确认。

优点：延迟低（P99 ≤ 5ms）
缺点：master 挂了可能丢少量数据

如果需要同步复制，需要改 `MasterReplicator.onWriteCommand()` 的实现。

---

### Q7: 复制 back

（问题被截断，猜测是问 ReplicationBacklog）

### Q7: ReplicationBacklog 是什么？太小会怎样？

master 端维护的固定大小环形缓冲区（默认 16MB），存储最近的写命令。

**太小会怎样？**
如果 slave 断开太久，写入命令覆盖了 backlog 中 slave 需要的 offset，slave 必须全量同步（很慢且占资源）。

**调优**：
```yaml
netcache.replication.backlogMb: 32  # 增大到 32MB
```

---

### Q8: Sentinel 和 Raft 有什么区别？

Sentinel 是**简化版协调方案**，Raft 是**完整共识算法**。

| 方面 | Sentinel | Raft |
|---|---|---|
| 选举 | 简化 term + voteFor | 完整 leader 选举 |
| 日志复制 | 无 | 有 |
| 用途 | 缓存故障转移 | 分布式日志/状态机 |

NetCache 用 Sentinel 是因为缓存场景不需要日志复制，只需要选出一个「协调者」。

---

### Q9: 为什么需要 Quorum？

防止误判。如果只有 1 个哨兵，它自己误判（网络闪断）就会触发不必要的 failover。

Quorum = 2 意味着「至少 2 个哨兵都认为挂了才算真挂了」。

---

### Q10: Failover 需要多长时间？

目标 < 3s。实际分布：
- SDOWN 检测：5s（连续 5 次 PING 无响应）
- ODOWN + 选举：~3s
- 提升 + 广播：~2s

可以通过调参优化，但太大反而影响故障检测灵敏度。

---

## 代码问题

### Q11: ByteKey 为什么要 clone？

因为 `byte[]` 在 Java 中是可变对象。外部修改了数组会影响 `ByteKey` 的 hashCode 和内容。

```java
byte[] bytes = "hello".getBytes();
ByteKey key = new ByteKey(bytes);
bytes[0] = 'X';  // 如果不 clone，key 的内容就被改了
```

---

### Q12: LRU 为什么要分段？

降低锁竞争。如果用单个全局 LRU，所有读写都要竞争一把锁，并发下性能很差。

分段后每段独立加锁，读写只在对应段竞争。

---

### Q13: TTL 是精确的吗？

**不是**。时间轮每 100ms tick 一次，所以 TTL 有 ±100ms 的误差。

这是可接受的。缓存本来就不是精确到毫秒的，过期 key 早几百毫秒晚几百毫秒清理没区别。

---

### Q14: 为什么要用 readRetainedSlice 而不是 readSlice？

因为下游 handler 可能**跨 EventLoop 异步处理**。如果用 `readSlice`，原始 ByteBuf 释放后，slice 也跟着失效了。

`readRetainedSlice` 保留引用，计数 +1，slice 和原始 ByteBuf 独立管理生命周期。

---

### Q15: 为什么 HandlerRegistry 用静态方法创建？

因为每个 OpCode 只对应**一个 handler 实例**（`@Sharable`），不需要每次请求都 new 一个。

```java
// 错误：每次请求都 new 一个
CommandHandler handler = new GetHandler(storage);

// 正确：复用单例
CommandHandler handler = HandlerRegistry.get(GET);  // 返回同一个实例
```

---

## 操作问题

### Q16: 怎么启动集群？

```bash
docker compose up --build
```

这会启动 3 master + 3 slave + 3 sentinel。

---

### Q17: 怎么测试 SET/GET？

```bash
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dexec.args="set hello world"

mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dexec.args="get hello"
```

---

### Q18: 怎么触发一次 failover？

```bash
./scripts/kill-master.sh master-1
```

观察 sentinel 日志，看 failover 是否在 3s 内完成。

---

### Q19: 怎么跑单元测试？

```bash
mvn verify
```

或单独跑某个模块：
```bash
mvn -pl netcache-storage test
```

---

### Q20: 怎么提交 PR？

1. Fork 仓库
2. 创建分支：`git checkout -b docs/your-feature`
3. 改代码，加测试
4. 提交：`git commit -m "docs(module): add chinese comments"`
5. Push：`git push origin docs/your-feature`
6. 在 GitHub 创建 PR

详细规则见 [14-contribute.md](./14-contribute.md)。

---

## 还有问题？

- 看 [12-debug-and-tools.md](./12-debug-and-tools.md) 学习调试方法
- 看源码的 Javadoc 注释
- 在 GitHub 提 Issue

---

## 下一步

- 没问题了？去看 [14-contribute.md](./14-contribute.md)，学习如何贡献代码。
