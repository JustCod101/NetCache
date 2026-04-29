# NetCache 新人入职指南

> 👶 **新人第一步：先读本文档，再动手。**

## 本文档是什么

本文档是 NetCache 项目的入职指南，帮你从零开始理解系统的设计思想、模块结构、关键流程和调试方法。

## 快速导航

| 如果你想... | 去读 |
|---|---|
| 了解系统全貌和技术选型 | [01-architecture-overview](./docs/onboarding/01-architecture-overview.md) |
| 按模块深入学习 | [02~09 模块文档](./docs/onboarding/) |
| 看一个请求从发起到落地的完整流程 | [10-end-to-end-trace.md](./docs/onboarding/10-end-to-end-trace.md) |
| 理解故障转移（failover）如何发生 | [11-failover-walkthrough.md](./docs/onboarding/11-failover-walkthrough.md) |
| 遇到问题不知道怎么查 | [12-debug-and-tools.md](./docs/onboarding/12-debug-and-tools.md) |
| 常见问题 FAQ | [13-faq.md](./docs/onboarding/13-faq.md) |
| 想贡献代码 | [14-contribute.md](./docs/onboarding/14-contribute.md) |

## 推荐阅读路径

### 第一天：跑起来，理解框架

1. 读 [01-architecture-overview](./docs/onboarding/01-architecture-overview.md)（~15分钟）—— 理解系统是什么、有哪些模块、模块间依赖关系
2. 读 [02-module-common.md](./docs/onboarding/02-module-common.md)（~10分钟）—— 理解公共常量和工具类
3. 按照 README 的 Quickstart 跑通本地环境（~15分钟）

### 第三天：深入模块，理解细节

4. 读 [03-module-protocol.md](./docs/onboarding/03-module-protocol.md) —— 理解协议帧格式
5. 读 [04-module-storage.md](./docs/onboarding/04-module-storage.md) —— 理解存储引擎
6. 读 [05-module-cluster.md](./docs/onboarding/05-module-cluster.md) —— 理解一致性哈希和节点管理
7. 读 [06-module-replication.md](./docs/onboarding/06-module-replication.md) 和 [07-module-sentinel.md](./docs/onboarding/07-module-sentinel.md) —— 理解复制和故障转移

### 第一周：掌握调试和贡献

8. 读 [10-end-to-end-trace.md](./docs/onboarding/10-end-to-end-trace.md) —— 跟一遍完整请求路径
9. 读 [11-failover-wailkthrough.md](./docs/onboarding/11-failover-walkthrough.md) —— 理解 failover 全流程
10. 读 [12-debug-and-tools.md](./docs/onboarding/12-debug-and-tools.md) —— 学会查日志和打断点
11. 读 [14-contribute.md](./docs/onboarding/14-contribute.md) —— 了解代码规范和提 PR 流程

## 关键概念速查

| 概念 | 所在模块 | 文档章节 |
|---|---|---|
| 18字节协议帧头 | protocol | 03-module-protocol.md |
| 160虚拟节点/物理节点 | cluster | 05-module-cluster.md |
| 85%/92% 内存水位线 | storage | 04-module-storage.md |
| SDOWN → ODOWN → RaftLite 选举 | sentinel | 07-module-sentinel.md |
| < 3秒 failover | sentinel | 07-module-sentinel.md |
| TreeMap 一致性哈希 | cluster | 05-module-cluster.md |
| HashedWheelTimer TTL | storage | 04-module-storage.md |

## 遇到问题？

1. 先查 [13-faq.md](./docs/onboarding/13-faq.md)
2. 再查 [12-debug-and-tools.md](./docs/onboarding/12-debug-and-tools.md)
3. 最后在团队群里问，记得附上日志

## 文档约定

- 所有 Java 类都附有中文 Javadoc，IDE 悬停可见
- `docs/onboarding/` 下所有文档使用同一套术语体系
- 架构图使用 textforme 模块绘制，保持文本可追溯
