# 12 - 调试与工具

## TL;DR

排查 NetCache 问题有三板斧：**日志**（grep+级别过滤）、**线程栈**（jstack 看死锁和阻塞）、**协议抓包**（Wireshark 看字节流）。配合 `mvn verify` 和单元测试，基本能定位 90% 的问题。

---

## 它解决什么问题

代码写完跑不通、跑通了但结果不对、跑通了但忽然变慢了——这些都需要调试手段。NetCache 的问题是分布式问题，比单机调试更难，需要特殊的工具和方法。

---

## 日志系统

### 日志框架

NetCache 使用 **SLF4J 2.x + Logback 1.4.x**。

**日志级别**（从轻到重）：
- `TRACE`：最详细，debug 配置开启才会输出
- `DEBUG`：调试信息
- `INFO`：正常运行信息
- `WARN`：警告（可恢复的错误）
- `ERROR`：错误（需要关注）

**配置示例**（`src/main/resources/logback.xml`）：
```xml
<logger name="com.netcache" level="DEBUG"/>
<appender name="CONSOLE" target="SYSTEM_OUT">
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

### 关键日志位置

| 场景 | 看哪个类的日志 |
|---|---|
| 请求路由 | `RequestRouter`、`TopologyCache` |
| 命令处理 | `CommandDispatcher`、`SetHandler`、`GetHandler` |
| 复制 | `MasterReplicator`、`SlaveReplicator`、`ReplicationBacklog` |
| 故障转移 | `SentinelNode`、`FailoverCoordinator`、`HealthChecker` |
| 连接池 | `ConnectionPool`、`TcpNodeChannel` |

### 日志 grep 技巧

```bash
# 看 ERROR 日志
grep -r "ERROR" .

# 看某个请求的日志（用 requestId）
grep -r "reqId=1001" .

# 看某个 key 的日志
grep -r "user:123" .

# 看 failover 相关日志
grep -r "failover\|SDOWN\|ODOWN\|leader" .

# 看复制延迟
grep -r "replication\|backlog\|offset" .
```

### 日志规范（代码中）

按照架构文档要求：
- `error` 必须带上下文（nodeId、reqId、key 摘要前 16B）
- `trace` 仅在 debug 配置开启
- 禁止 `println`（用 logger 替代）

---

## jstack 线程分析

### 什么情况下用 jstack

- 应用响应慢/无响应
- 怀疑死锁
- 某个操作卡住不动
- CPU 100%

### 基本用法

```bash
# 找到 Java 进程 PID
jps -l | grep NetCache

# 抓线程栈
jstack <PID> > threaddump.txt

# 抓并带锁信息（更详细）
jstack -l <PID> > threaddump.txt
```

### 关键线程名

| 线程名 | 作用 |
|---|---|
| `nc-server-NettyServerBoss-N` | Netty boss 线程，监听连接 |
| `nc-server-NettyServerWorker-N` | Netty worker 线程，处理 I/O |
| `nc-storage-ExpirationTimer-N` | 时间轮 TTL 扫描线程 |
| `nc-replication-MasterReplicator-N` | 复制推流线程 |
| `nc-sentinel-HealthChecker-N` | 哨兵健康检查线程 |

### 常见问题诊断

**1. 请求卡在 EventLoop**

特征：`CommandDispatcher` 的线程堆栈显示长时间在某个 handler 中。

原因：handler 做了阻塞操作（同步 I/O、计算密集型任务）。

```text
"nc-server-NettyServerWorker-1" #50
   at com.netcache.server.handler.SetHandler.handle(SetHandler.java:45)
   at com.netcache.server.netty.CommandDispatcher.channelRead0()
   // 卡在 StorageEngine.set() —— 但 set() 应该很快
```

**2. 死锁**

特征：多个线程互相等待对方持有的锁。

```text
Found one Java-level deadlock:
"pool-1-thread-3" waiting for ownable synchronizer
"pool-1-thread-4" waiting for ownable synchronizer
// 互相等待
```

**3. 高并发下锁竞争**

特征：大量线程在 `LruSegment.lock` 上 blocked。

```text
"pool-1-thread-10" waiting for lock on LruSegment
"pool-1-thread-11" waiting for lock on LruSegment
```

---

## Wireshark 协议分析

### 什么情况下用

- 怀疑协议编解码有 bug
- 客户端/服务端交互异常
- 想看实际在网络上传的字节

### 过滤 NetCache 协议

Wireshark 支持自定义协议解析器，但也可以用过滤器手动筛选：

```
tcp.port == 7001
```

### 帧头解析

```
  0        4         5      6           14        18           N
  +--------+---------+------+-----------+--------+--------------+
  | Magic  | Version | Type | RequestId | Length |   Payload    |
  | 4B     | 1B      | 1B   | 8B        | 4B     |   N B        |
  +--------+---------+------+-----------+--------+--------------+
```

- Magic = `0xC0DECAFE`（little endian 是 `FE CADE C0`，big endian 是 `C0 DECAFE`）
- Version = `0x01`
- Type = `0x01`（请求）/ `0x02`（响应）/ `0x03`（复制流）

### 常见协议问题

**1. 粘包**：两个帧粘在一起

```
[Frame1: 18+N1 bytes][Frame2: 18+N2 bytes]
                 ↑ 应该分开但没分开
```

**2. 半包**：帧不完整

```
[Frame1: 只有 18 字节 header，没有 payload]
```

**3. Magic 校验失败**：连接被关

如果 Wireshark 显示 `TCP segment of a reassembled PDU`，说明 Magic 校验失败，服务端关了连接。

---

## 测试工具

### 单元测试

```bash
# 运行单个模块的测试
mvn -pl netcache-storage test

# 运行单个测试类
mvn -pl netcache-storage test -Dtest=StorageEngineTest

# 带覆盖率
mvn -pl netcache-storage test jacoco:report
```

### 集成测试

```bash
# 跑完整的 verify（编译 + 测试）
mvn verify

# 用 docker-compose 跑集成测试
docker compose up --build
./scripts/integration-test.sh
```

### SampleClient 手动测试

```bash
# SET
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dexec.args="set user:123 hello"

# GET
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dexec.args="get user:123"

# INCR
mvn -q -pl netcache-client -am exec:java \
  -Dexec.mainClass=com.netcache.client.SampleClient \
  -Dexec.args="incr counter"
```

---

## 常见问题快速排查

### 应用启动失败

```
1. 检查端口占用：lsof -i :7001
2. 检查配置文件：application.yml 是否存在
3. 检查内存：JVM heap 是否够
```

### 客户端连不上

```
1. 服务端是否启动：jps | grep NetCache
2. 防火墙：telnet <host> 7001
3. 网络：ping <host>
```

### 请求超时

```
1. 看服务端日志是否有 ERROR
2. jstack 看是否卡在某个操作
3. 检查 StorageEngine 是否在高水位淘汰
```

### 数据不一致

```
1. 检查主从复制是否正常：slave 是否跟上 master
2. 检查 replication backlog 是否有溢出
3. 检查 failover 后是否有双主
```

---

## 下一步

- 学会了调试方法，下一步看 [13-faq.md](./13-faq.md)，了解常见问题。
