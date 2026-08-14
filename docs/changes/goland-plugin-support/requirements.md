---
title: GoLand 插件支持需求说明
type: requirements
status: draft
updated: 2026-08-14
---

# GoLand 插件支持需求说明

本文定义 ReqWS 本次 GoLand 支持的完整交付范围：跨 IDE manifest 契约、ReqWS Desktop 的 GoLand 启动入口，以及以 manifest 为目标状态的 GoLand 插件 v0.1。

## 1. 背景与问题

ReqWS 按需求创建物理隔离的多仓库工作区：每个仓库都是独立 clone，使用同一 feature branch，并由 ReqWS 生成 `.code-workspace` 供 VS Code 和 Cursor 使用。每个工作区同时包含：

```text
<workspace-root>/.reqws/workspace.json
```

GoLand 可以打开工作区根目录，但仅打开父目录不等同于 ReqWS workspace：

- GoLand 不解析 `.code-workspace`；
- 一个工作区包含多个独立 Git repository；
- ReqWS 的“移除仓库”是逻辑移除，只更新 manifest 和 `.code-workspace`，不会删除磁盘目录；
- 因此工作区根目录下可能同时存在活动仓库、已移除但保留的仓库和普通目录；
- 如果 GoLand 把整个父目录无差别纳入项目，已移除仓库仍会参与索引、搜索、代码分析和 Git 操作，违背 ReqWS 的活动仓库语义。

本需求通过 Desktop 与专用 GoLand 插件协作，让 GoLand 的项目内容和 Git Root 始终向 `.reqws/workspace.json` 收敛。

## 2. 本次目标

本次必须同时交付三个组成部分：

1. **跨 IDE manifest 契约**：明确 Desktop 与 IDE 适配器的读写边界、活动仓库语义、兼容规则和安全校验。
2. **ReqWS Desktop GoLand 入口**：探测本机 GoLand，并用受控进程参数打开 workspace root。
3. **GoLand 插件 v0.1**：识别 ReqWS workspace，同步活动仓库的项目内容和 Git Root，监听 manifest 变化，并提供只读状态和诊断。

成功标准不是让 GoLand 支持 VS Code 的 workspace 文件，而是：

> 同一个 ReqWS workspace 在 GoLand 中表现为一个受管多仓库项目，活动仓库集合与 Desktop 写入的 manifest 一致。

## 3. 完成定义

只有同时满足以下条件，本次能力才算完成：

- Desktop 能正确展示 GoLand 可用性并打开目标 workspace root；
- 插件 ZIP 可由仓库源码构建、测试并通过 GoLand 的磁盘安装入口安装；
- manifest v1 由 TypeScript 和 Kotlin 契约测试共同覆盖；
- 首次打开、自动同步、手动同步和重启恢复均可用；
- 活动仓库对应的项目内容和 Git Root 与 manifest 一致；
- 已逻辑移除但仍在磁盘上的仓库不参与默认索引、搜索、代码分析和 Git 操作；
- 插件不执行 Git 生命周期操作，不删除目录，不绕过 Trusted Project / Safe Mode；
- 用户已有、且不属于 ReqWS 的 module、Content Root 和 VCS mapping 不被删除；
- Desktop、插件、Plugin Verifier、真实 macOS GoLand GUI 和文档检查均有 exact-head 证据；
- 用户指南和开发指南在实现稳定后更新，需求包状态按证据从 `draft` 调整。

## 4. 本次范围

### 4.1 Manifest 契约

必须实现：

- 继续使用 `<workspace-root>/.reqws/workspace.json`；
- Desktop 是唯一 writer，GoLand 插件只读；
- `schemaVersion: 1` 继续作为本次支持版本，不为 GoLand 新建第二份重复 manifest；
- `repositories` 数组是活动仓库的唯一权威集合；
- 不在数组中的目录不属于当前 IDE workspace，即使目录仍存在于磁盘；
- 插件使用完整文件内容摘要识别目标状态，不依赖文件事件顺序或时间戳单调；
- Desktop 与插件使用同一组 golden fixture 验证兼容行为；
- 未知附加字段可以忽略，不支持的 major version 必须拒绝；
- manifest 写入仍由 Desktop 使用原子替换完成。

### 4.2 ReqWS Desktop

必须实现：

- `SystemAvailability`、shared types、IPC、preload 和 renderer 增加 GoLand；
- 探测标准 `/Applications`、用户 `~/Applications` 和经过验证的 JetBrains Toolbox 安装结果；
- 打开 manifest 绑定的 workspace root，而不是 `.code-workspace`；
- 所有启动使用固定可执行文件和参数数组，`shell: false`；
- 工作区非 `ready`、路径缺失或 GoLand 不可用时返回稳定错误；
- 工作区列表和详情增加 GoLand 操作入口、禁用态和本地化错误反馈；
- 不自动安装插件，不修改 GoLand 全局设置，不伪造“插件已安装”状态；
- VS Code、Cursor 和 Finder 现有行为保持不变。

### 4.3 GoLand 插件 v0.1

必须实现：

- 插件源码、Gradle wrapper 和测试位于 `integrations/goland/`；
- 可构建本地 ZIP，并通过 “Install Plugin from Disk” 安装；
- 只在项目根存在有效 ReqWS manifest 时启用项目级能力；
- 解析和校验 manifest，但不根据 URL、branch 或其他字段执行网络或命令；
- 把活动仓库转换为受管 Content Root、Workspace Model 实体或经过 spike 验证的等价项目模型；
- 为每个活动仓库建立 Git VCS Directory Mapping；
- 逻辑移除仓库后，从插件受管项目内容和 Git Root 中移除，但不删除目录；
- 重新添加同一仓库时恢复映射，且不制造重复 root、module 或 mapping；
- 监听 manifest 的 create、move、replace 和 content change，并对原子写入事件做防抖；
- 同步串行、幂等、latest-wins，重复同一内容不重复刷新项目模型；
- 项目重启后从 manifest 冷恢复，不依赖 Desktop 正在运行；
- 提供 ReqWS Tool Window，至少展示 workspace、feature branch、活动仓库、同步状态、最近错误和“立即同步”；
- 只删除插件明确拥有且当前不再需要的项目模型和 VCS 条目；
- 普通非 ReqWS 项目不受到行为或 UI 干扰；
- Safe Mode 下只允许读取和诊断，不修改项目模型或启动外部进程。

### 4.4 构建、测试和文档

必须实现：

- 使用 IntelliJ Platform Gradle Plugin 2.x；
- 在构建 spike 中锁定 GoLand target、JDK、Gradle、Kotlin、JVM target 和 `since-build`；
- 插件单元测试、平台模型测试、`verifyPlugin`、Plugin Verifier 和 `buildPlugin` 通过；
- 在 Apple Silicon macOS 的真实 GoLand 上完成 GUI smoke；
- 验证报告记录 exact commit、GoLand version/build、JBR/JDK、macOS、插件 ZIP SHA-256、fixture 和结果；
- `npm run check`、macOS Desktop package smoke 和现有编辑器回归通过；
- 实现稳定后补充 `integrations/goland/README.md`、用户指南、开发指南和按次验证报告。

## 5. 用户场景

### 5.1 从 ReqWS 打开 GoLand

用户在工作区列表或详情选择“GoLand”：

1. Desktop 确认 workspace 为 `ready`；
2. Desktop 检查 root 和 manifest 仍存在；
3. Desktop 解析受信任的 GoLand 安装候选；
4. Desktop 用参数数组打开 workspace root；
5. 插件检测 manifest，并在项目已受信时同步活动仓库；
6. Project 和 Git 工具窗口展示与 manifest 一致的活动仓库。

GoLand 未安装时，按钮禁用或操作返回稳定的 `EDITOR_NOT_FOUND` 类错误，其他编辑器入口不受影响。

### 5.2 直接从 GoLand 打开已有 workspace

用户也可以直接打开 workspace root：

- 插件检测固定 manifest 路径；
- manifest 有效且项目 trusted 时执行首次同步；
- Safe Mode 时只显示 workspace 信息和信任提示；
- manifest 无效时不修改已有项目模型，并在 Tool Window 给出错误码和恢复动作。

### 5.3 Desktop 新增仓库

用户在 Desktop 向已打开 workspace 添加仓库：

- Desktop 继续负责 clone、分支切换、路径校验、manifest 和 `.code-workspace` 写入；
- 插件观察到最新有效 manifest；
- 新仓库进入项目内容和 Git Root；
- 不要求关闭或重启 GoLand。

### 5.4 Desktop 逻辑移除仓库

用户在 Desktop 移除仓库：

- Desktop 只从 manifest 和 `.code-workspace` 移除记录；
- 磁盘仓库目录继续保留；
- 插件仅移除自己管理的项目内容和 Git mapping；
- 该目录不再参与默认 Project View、搜索范围、代码分析和 Git Root；
- 已打开文件可以由 GoLand 按平台行为保留为外部文件，但不得因此重新成为受管 root。

### 5.5 重新添加与恢复

- 重新添加保留仓库时，插件恢复同一路径的受管条目；
- 文件监听丢失、Mac 休眠恢复或同步失败后，用户可执行“立即同步”；
- GoLand 重启后重新读取 manifest；
- 重复同步不产生重复条目；
- manifest 临时缺失或损坏时保留上次有效模型，不立即清空全部仓库。

## 6. 所有权与业务规则

### 6.1 单一事实来源

- manifest 是 IDE 活动仓库集合的唯一事实来源；
- Desktop 是 manifest、Git clone 和 branch 生命周期的唯一 owner；
- 插件只负责把 manifest 投影到 GoLand 项目模型；
- 插件状态不得成为第二份业务事实来源；其持久化数据只记录受管条目所有权和最近应用摘要。

### 6.2 项目模型保护

- 插件必须对每个受管 Content Root、exclude、module 或 VCS mapping 保留可验证的所有权；
- apply 前计算 `add / keep / remove-owned`，不能用“把当前全部 roots 替换成 manifest”实现同步；
- 不属于插件的 module、SDK、library、source root、exclude 和 VCS mapping 必须保留；
- 所有权不确定时停止破坏性移除并显示诊断，不猜测用户意图；
- 未选项目模型 spike 的实验代码必须删除，生产实现只保留经过验证的策略和确有证据的 fallback。

### 6.3 Git 边界

插件不得：

- clone、fetch、checkout、pull、merge、rebase、push；
- 创建、删除或重命名 branch；
- 修改 remote；
- 删除 repository 或 workspace 目录；
- 自动执行仓库内脚本、Gradle task、Go command 或任意 manifest 字段。

GoLand 用户主动使用 IDE 自带 Git 功能不属于插件自动行为，插件只负责正确登记活动 Git Root。

## 7. 非功能需求

### 7.1 安全

- 所有 manifest 字段都按不可信输入处理；
- manifest 必须是普通 UTF-8 文件，并设置大小上限；
- `rootPath` canonical path 必须等于当前 project root；
- `relativePath` 必须是安全相对路径，解析后位于 root 内；
- 符号链接不得把活动仓库指向 root 外；
- root mismatch、path escape、unsupported schema 或无效 JSON 时，本轮不得修改项目模型；
- 插件不访问 repository URL，不在普通日志输出 URL、token、remote 或完整文件内容；
- Safe Mode 下无项目模型和外部进程副作用；
- Desktop 启动 GoLand 时不经过 shell，不从 manifest 拼接命令文本。

### 7.2 一致性与恢复

- 同一项目同一时刻最多一个 apply；
- apply 期间收到的新内容合并为下一轮最新目标；
- 相同 digest 不重复 apply；
- 原子 rename 产生的多个 VFS 事件合并为一次稳定读取；
- manifest 临时不存在时有限重试，不因单个 delete 事件立即清空 roots；
- invalid manifest 保留上次有效状态，并在文件恢复后自动重试；
- 项目重启不依赖内存状态即可恢复；
- 单个活动目录缺失时不得创建目录，可同步其他有效活动仓库并把整体状态标记为 degraded。

### 7.3 性能与体验

- 文件读取、JSON、digest 和路径扫描不在 EDT 执行；
- 项目模型更新在 JetBrains 要求的写事务中完成；
- Tool Window 更新在 EDT 完成；
- listener 回调不做阻塞 IO；
- 无目标变化时不触发重复索引；
- 以 50 个活动仓库和 20 个保留仓库作为规模回归；
- 不允许持续 CPU、无限 indexing、明显 UI freeze 或与事件数量等量的重复 apply；
- 插件错误不能阻止普通 GoLand 项目打开或使用不相关功能。

### 7.4 兼容性

- 产品范围是本地 macOS GoLand；
- 本次真实 GUI 验收至少覆盖实现时当前稳定 GoLand，文档制定时为 2026.2；
- W0 优先评估以 GoLand 2026.1 / Java 21 编译，并使用 Plugin Verifier 验证 2026.1 与 2026.2；
- 如果实际使用的公开 API 必须以 2026.2 为基线，则使用 Java 25 和 262 build range，并记录缩小兼容范围的原因；
- 不使用 `@Internal`、`@Experimental`、反射或未记录私有 API作为生产方案；
- GoLand 用户的“新窗口 / 当前窗口 / 询问”设置继续由 IDE 决定，Desktop 不通过私有参数覆盖。

## 8. 明确范围外

本次不设计、不实现，也不为以下能力预留内部协议：

- 从 GoLand 发起添加/移除仓库、分支或 workspace 变更；
- GoLand 与 Desktop 之间的 URL scheme、Unix socket、daemon、MCP 或其他双向通信；
- 自动生成、修改、接管或删除 `go.work`；
- ReqWS 专用 run/debug configuration 模板；
- 插件自动安装、签名、Marketplace、自定义仓库、自动更新或发布流水线；
- JetBrains Remote Development / Split Mode；
- Windows、Linux、IntelliJ IDEA、Fleet 或其他 IDE；
- 把 `.code-workspace` 作为 GoLand 输入格式；
- 修改各仓库内的 `.idea`、`.vscode` 或其他仓库配置；
- 轮询扫描所有用户目录寻找 ReqWS workspace。

若将来需要上述能力，应以新的需求包重新评估，而不是在本次插件中留下未验证的隐藏入口。

## 9. 验收矩阵

| 编号 | 场景 | 通过条件 |
|---|---|---|
| GL-01 | GoLand 未安装 | Desktop 明确显示不可用，其他编辑器入口正常。 |
| GL-02 | 标准路径安装 | Desktop 可打开正确 workspace root，启动不经过 shell。 |
| GL-03 | Toolbox 安装 | 探测和启动策略经真实安装验证，失败时有明确诊断或稳定 fallback。 |
| GL-04 | 首次打开 | 插件识别 manifest，活动项目根和 Git Root 数量、路径一致。 |
| GL-05 | 新增仓库 | Desktop 操作完成后 GoLand 无需重启即可加入仓库。 |
| GL-06 | 逻辑移除 | 磁盘目录保留，但该仓库退出受管项目内容、默认搜索范围和 Git Root。 |
| GL-07 | 重新添加 | 同一目录恢复，且无重复 root、module 或 mapping。 |
| GL-08 | 原子替换和快速连续变更 | 最终状态与最新 manifest 一致，无并发异常或持续索引循环。 |
| GL-09 | manifest 损坏或临时缺失 | 保留上次有效模型，不应用部分数据，并提供稳定诊断和恢复入口。 |
| GL-10 | 路径攻击 | root mismatch、绝对 relativePath、`..` 或 symlink escape 被拒绝。 |
| GL-11 | Safe Mode | 可读诊断保留，受管模型更新和外部进程动作禁用。 |
| GL-12 | 重启恢复 | GoLand 重启后从 manifest 幂等重建，不依赖 Desktop 正在运行。 |
| GL-13 | 用户自定义配置 | 插件同步不删除非 ReqWS-owned module、Content Root 或 VCS mapping。 |
| GL-14 | 50+20 仓库样例 | 同步完成，无重复事件风暴、持续 CPU 或无限 indexing。 |
| GL-15 | 全面回归 | VS Code、Cursor、Finder、workspace 增删、i18n、Desktop package 和现有测试通过。 |

## 10. 术语

- **活动仓库**：当前存在于 manifest `repositories` 数组中的仓库。
- **保留仓库**：曾属于 workspace、已从 manifest 移除但目录仍留在磁盘的仓库。
- **受管条目**：由 ReqWS GoLand 插件创建、采用或修改，且插件能证明所有权的项目模型或 VCS 条目。
- **目标状态**：对当前 manifest 完整校验后得到的活动仓库路径集合及摘要。
- **同步**：把插件拥有的 GoLand 项目模型和 Git mapping 收敛到目标状态，同时保留用户的无关配置。
- **Desktop**：ReqWS Electron 主程序，是 workspace、Git 生命周期和 manifest 的唯一写入方。
