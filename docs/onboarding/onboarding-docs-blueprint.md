任务目标：把已完成的 NetCache 项目改造成「新手实习生 3 天能上手」的状态。
不修改任何业务逻辑、不调整接口签名、不改动协议字节布局，只做两件事：
(A) 给所有源码补充中文注释  (B) 在 docs/ 下产出通俗易懂的模块解析文档。

阅读 NetCache-Architecture.md 作为背景知识，然后按以下规则执行。

═══════════════════════════════════════════════════════════
A. 中文注释规范（对所有 .java 文件生效）
═══════════════════════════════════════════════════════════

1. 类/接口/枚举/record 的 Javadoc（必须）
   - 一句话定位：这个类在系统里扮演什么角色（用比喻最好）
   - 解决什么问题：为什么需要它，没有它会怎样
   - 协作对象：上游谁调它、它依赖谁
   - 线程安全说明：是否线程安全、并发模型
   - 典型用例：3-5 行示例代码
   示例风格：
   /**
    * 一致性哈希环 —— NetCache 的「分拣中心」。
    * <p>
    * 想象快递分拣：每个 key 算一个哈希值落到 0~2^64 的圆环上，顺时针找到
    * 第一个虚拟节点就是它的归宿。这样加减节点时只影响相邻区段，不会全网洗牌。
    * <p>
    * 协作关系：
    *   - 客户端 RequestRouter 用它决定请求发给哪个节点
    *   - ClusterTopology 变化时调用 addNode/removeNode 重建环
    * <p>
    * 线程安全：读多写少，内部 TreeMap 用 ReadWriteLock 保护。
    * <p>
    * 用例：
    * <pre>
    *   HashRing ring = new HashRing(160);
    *   ring.addNode(nodeA);
    *   NodeId target = ring.routeOf("user:123".getBytes());
    * </pre>
    */

2. 方法 Javadoc（公有方法必须，私有方法按需）
   - 做什么（动词开头）
   - 参数语义（不只是类型，是业务含义）
   - 返回值含义（特别是 null/空集合/-1 等特殊值）
   - 抛出哪些异常、什么场景
   - 复杂度（如果不是 O(1)）
   - @implNote 写实现要点（新手看实现前先看这个）

3. 字段注释（行内 // 即可）
   - 解释字段为什么是这个类型、这个初值
   - 单位（毫秒？字节？）
   - 取值范围
   例：private final int virtualPerNode = 160; // 经验值，太小负载不均，太大占内存

4. 关键代码块的「为什么」注释（重点！）
   不要写「这行做什么」（代码本身就能看懂），要写「为什么这么做」。
   特别针对：
   - 位运算、魔数、看似奇怪的写法
   - 性能优化点（为什么不用更直观的写法）
   - 并发处理（为什么这里要 volatile / 为什么不能用 synchronized）
   - 边界处理（为什么判断 == null、为什么 +1）
   - Netty ByteBuf 的 retain/release 决策
   反面例子：// i 自增 1     ← 禁止
   正面例子：// 跳过帧头 4 字节 magic，因为 LengthFieldBasedFrameDecoder 已校验过
   正面例子：// 这里必须用 readRetainedSlice 而不是 readSlice，因为下游 handler
   //       会跨 EventLoop 异步处理，原始 ByteBuf 此时可能已被释放

5. 协议、算法相关代码必须画 ASCII 图
   编解码器、哈希环、复制流、Raft 选举状态机等位置，
   用 ASCII 注释画出数据布局或状态转换。
   例：
   //   Frame 布局（共 18B 头 + N B payload）
   //   +-------+-----+----+-----------+--------+----------+
   //   | Magic | Ver | T  | RequestId | Length | Payload  |
   //   |  4B   | 1B  | 1B |    8B     |   4B   |   N B    |
   //   +-------+-----+----+-----------+--------+----------+

6. 中文风格要求
   - 用「人话」，避免翻译腔（不要写「这个方法」「该对象」，写「它」「这里」）
   - 善用比喻：哈希环=分拣中心、backlog=广播台录像带、sentinel=值班医生
   - 术语首次出现给出英文 + 一句解释，后续直接用中文/英文
   - 禁止口水话和废话注释（"// 设置值" "// 返回结果"）
   - 一行注释 ≤ 60 个汉字，超长换行

═══════════════════════════════════════════════════════════
B. 模块解析文档（在 docs/onboarding/ 下产出）
═══════════════════════════════════════════════════════════

为新手实习生写的，目标是「读完这套文档 + 跑通 quickstart，能改一个简单 bug」。
统一风格：先讲「这是什么」，再讲「为什么需要」，再讲「怎么实现」，最后「怎么调试」。
全部用中文，避免学术腔，多用类比、流程图、时序图。

产出以下文档（缺一不可）：

docs/onboarding/
├── 00-README.md                  导读：阅读顺序 + 知识图谱
├── 01-architecture-overview.md   从全局视角讲系统：一张架构图 + 一次完整请求的旅程
├── 02-module-common.md           common 模块导览
├── 03-module-protocol.md         协议层：手把手解释 18 字节帧头每一位
├── 04-module-storage.md          存储引擎：LRU 分段、时间轮 TTL、内存水位
├── 05-module-cluster.md          集群：一致性哈希为什么需要虚拟节点（带图）
├── 06-module-replication.md      主从复制：backlog 与 PSYNC 的来龙去脉
├── 07-module-sentinel.md         哨兵：SDOWN/ODOWN/Raft 选举三幕剧
├── 08-module-server.md           Netty 服务端：Pipeline 装配与命令分派
├── 09-module-client.md           客户端 SDK：连接池、路由、重试
├── 10-end-to-end-trace.md        一次 SET 请求的全链路追踪（从 client 到 storage 到 replication）
├── 11-failover-walkthrough.md    一次故障转移的全过程（带时间线）
├── 12-debug-and-tools.md         如何看日志、如何 jstack、如何用 wireshark 看协议
├── 13-faq.md                     新人常见疑问 20 问
└── 14-contribute.md              如何提 PR、命名约定、提交规范

每篇文档遵循以下结构：
1. **TL;DR**（3-5 句话讲完核心）
2. **它解决什么问题**（场景化，不要学术化）
3. **核心概念**（3-7 个名词，每个一段话 + 类比）
4. **关键流程**（带图：架构图用 mermaid，状态机用 mermaid stateDiagram，时序用 mermaid sequenceDiagram）
5. **代码导读**（标出 3-5 个最值得读的类/方法，给出文件路径 + 行号区间，附一句话解释）
6. **常见坑**（3-5 个新手容易踩的雷）
7. **动手练习**（2-3 个由浅入深的小任务，例如「把 LRU 段数从 16 改成 32 看 QPS 变化」）

写作要求：
- 每篇文档至少 1 张 mermaid 图
- 关键算法（一致性哈希、LRU、Raft 选举）必须有图示
- 出现专业术语时插入「💡 类比：xxx」小框
- 所有代码片段标注来源文件路径
- 文档之间用相对链接互相跳转

═══════════════════════════════════════════════════════════
C. 执行规则
═══════════════════════════════════════════════════════════

1. 不动业务逻辑：禁止修改方法签名、调整字段类型、变更协议字节序。
   如果发现真正的 bug，单独记录到 docs/onboarding/FOUND_ISSUES.md，不要顺手改。

2. 注释先行，文档后行：
   Step 1 - 先给所有源码补完整注释（按模块依赖顺序：common → protocol → storage → cluster → server → client → sentinel）
   Step 2 - 每完成一个模块的注释，立即写对应的 docs/onboarding/0X-module-*.md
   Step 3 - 全部模块完成后，再写跨模块的 01/10/11/12/13/14 文档

3. 质量自检（每个模块完成后必做）：
   - 公有 API Javadoc 覆盖率 100%
   - 跑 `mvn javadoc:javadoc` 无 warning
   - 让自己充当新手：合上代码，只看文档能不能讲出该模块的工作流程？讲不出就回去补
   - 跑一遍 `mvn verify`，确保只加注释没破坏任何测试

4. 提交节奏：
   每完成一个模块的注释 + 文档为一次 commit，message 格式：
   `docs(<module>): add chinese comments and onboarding guide`

5. 最终验收：
   - 所有 .java 文件均有 Javadoc，无空文档块
   - docs/onboarding/ 下 15 篇文档齐全
   - 在仓库根新增 ONBOARDING.md，指向 docs/onboarding/00-README.md，
     并在主 README 顶部加一行「👶 新人入职请先读 ONBOARDING.md」
   - 用一句话回答新人灵魂三问，写在 docs/onboarding/00-README.md 开头：
     ① NetCache 是什么？  ② 我从哪开始读？  ③ 第一周该做什么？

6. 中途不必向我确认，按 ultrawork 模式自驱直至 Done。

开始执行。
