# 14 - 如何贡献代码

## TL;DR

NetCache 欢迎贡献！但有规矩：**不开业务逻辑**、**不动接口签名**、**不改协议字节**。优先加注释和文档，改 bug 要单独记录。提交前必须跑 `mvn verify`，commit message 要符合规范。

---

## 它解决什么问题

新人第一天可能想改个 bug、加个功能，但不知道规矩。这篇文档告诉你：能改什么、不能改什么、怎么改、改完怎么提交。

---

## 能改什么

### 1. 注释和文档（最高优先级）

**这是本项目当前的首要任务**：
- 给源码加中文 Javadoc 注释
- 补全 docs/onboarding/ 下缺失的文档
- 改进现有文档的可读性

### 2. Bug 修复

发现了真正的 bug？**先记录到 `docs/onboarding/FOUND_ISSUES.md`**，不要顺手改。

```markdown
## Bug #1：描述
- 文件：xxx.java:行号
- 现象：...
- 复现步骤：...
- 建议修复：...
```

然后再建 PR 修复。

### 3. 测试覆盖率

- 单元测试覆盖不足的地方补测试
- `mvn test` 要全部通过
- 不要删测试来「修复」失败的测试

---

## 不能改什么

### 1. 业务逻辑

**红线**：禁止修改方法签名、字段类型、协议字节序。

如果发现业务逻辑有问题，在 `FOUND_ISSUES.md` 记录，不要改。

### 2. 接口签名

如果改了接口签名，所有依赖方都要改，容易引入 bug。

### 3. 协议字节序

`Frame.java` 的 `HEADER_LENGTH = 18` 是固定的，`OpCode` 的值是固定的。改了他们就不兼容了。

### 4. 测试（除非是修复测试本身的问题）

不要删测试来让构建通过。测试是规格，规格错了才应该改规格。

---

## 代码规范

### Java 编码风格

- 基于现有代码风格（项目没有 `.editorconfig`，靠自觉）
- 变量命名清晰
- public 方法必须有 Javadoc
- 禁止 `as any`、`@ts-ignore`（这是 Java 项目，忽略）

### 方法 Javadoc 模板

```java
/**
 * 方法功能的一句话描述。
 * <p>
 * 详细说明：为什么这么做，而不是那么做。
 *
 * @param paramName 参数语义（不只是类型）
 * @return 返回值语义（特别是 null/空集合/-1 等特殊值）
 * @throws IllegalArgumentException 什么场景抛
 * @implNote 实现要点
 */
```

### 字段注释

```java
private final int virtualPerNode = 160; // 经验值，太小负载不均，太大占内存
```

---

## Git 提交规范

### Commit Message 格式

```
<type>(<module>): <subject>

<body>
```

**Type**：
- `docs`：文档和注释
- `fix`：bug 修复
- `feat`：新功能
- `test`：测试相关
- `refactor`：重构

**Module**：
- `common`
- `protocol`
- `storage`
- `cluster`
- `server`
- `client`
- `sentinel`

**Examples**：
```bash
git commit -m "docs(common): add chinese javadoc for ByteKey and NodeId"
git commit -m "docs(storage): add onboarding guide for LRU module"
git commit -m "fix(cluster): correct HashRing.removeNode return type"
```

### 分支命名

```
docs/xxx          # 文档任务
fix/xxx           # bug 修复
feat/xxx          # 新功能
```

---

## PR 创建流程

### 1. Fork 仓库

在 GitHub 上 Fork `your-username/NetCache`。

### 2. Clone 本地

```bash
git clone https://github.com/your-username/NetCache.git
cd NetCache
```

### 3. 创建分支

```bash
git checkout -b docs/onboarding-guide
```

### 4. 开发

- 改代码
- 加测试
- 跑 `mvn verify` 确保通过

### 5. Commit

```bash
git add .
git commit -m "docs(sentinel): add chinese javadoc for SentinelMain"
```

### 6. Push

```bash
git push origin docs/onboarding-guide
```

### 7. 创建 PR

在 GitHub 上创建 PR，描述改了什么、为什么改、怎么测试的。

---

## PR 审核清单

提交前自查：

- [ ] `mvn verify` 通过了吗？
- [ ] 新增的 public API 有 Javadoc 吗？
- [ ] 改了业务逻辑吗？（不应该改）
- [ ] 改了接口签名吗？（不应该改）
- [ ] commit message 符合规范吗？
- [ ] 写测试了吗？测试通过了吗？

---

## Found Issues 文件模板

如果发现了 bug 但还没修，在 `docs/onboarding/FOUND_ISSUES.md` 记录：

```markdown
# NetCache 发现的问题

## Bug #1：标题

**文件**：`netcache-xxx/src/main/java/com/netcache/xxx/ClassName.java:行号`

**描述**：现象描述

**复现步骤**：
1.
2.
3.

**建议修复**：...

**优先级**：高/中/低

**状态**：open/resolved
```

---

## 下一步

恭喜你学会了贡献流程！开始贡献吧——从 [00-README.md](./00-README.md) 的「动手练习」开始。

或者，如果你只是想学习，可以：
1. 读 [01-architecture-overview.md](./01-architecture-overview.md) 理解架构
2. 用 `docker compose up --build` 跑通 quickstart
3. 用 [12-debug-and-tools.md](./12-debug-and-tools.md) 调试一个简单问题
