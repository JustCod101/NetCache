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
