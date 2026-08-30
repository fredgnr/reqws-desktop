---
title: GoLand 插件支持需求说明
type: requirements
status: active
updated: 2026-08-30
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

本需求通过 Desktop 与专用 GoLand 插件协作，让 GoLand 的项目内容自动向 `.reqws/workspace.json` 收敛；Git Root 由插件只读比对并提示用户在 GoLand Directory Mappings 中手动维护。

## 2. 本次目标

本次必须同时交付三个组成部分：

1. **跨 IDE manifest 契约**：明确 Desktop 与 IDE 适配器的读写边界、活动仓库语义、兼容规则和安全校验。
2. **ReqWS Desktop GoLand 入口**：探测本机 GoLand，并用受控进程参数打开 workspace root。
3. **GoLand 插件 v0.1**：识别 ReqWS workspace，自动同步活动仓库的项目内容，只读检查 Git Root，监听 manifest 与 VCS 配置变化，并提供状态和手动配置诊断。

成功标准不是让 GoLand 支持 VS Code 的 workspace 文件，而是：

> 同一个 ReqWS workspace 在 GoLand 中表现为一个受管多仓库项目；活动项目内容与 manifest 自动一致，Git Root 差异可见并由用户在 GoLand 中明确配置。

## 3. 完成定义

只有同时满足以下条件，本次能力才算完成：

- Desktop 能正确展示 GoLand 可用性并打开目标 workspace root；
- 插件 ZIP 可由仓库源码构建、测试并通过 GoLand 的磁盘安装入口安装；
- manifest v1 由 TypeScript 和 Kotlin 契约测试共同覆盖；
- 首次打开、自动同步、手动复核和重启恢复均可用；
- 活动仓库对应的项目内容与 manifest 一致，Git Root 缺失或冲突有明确的手动配置步骤；
- 已逻辑移除但仍在磁盘上的仓库自动退出默认索引、搜索和代码分析；若仍是 Git Root，插件提示用户在 Directory Mappings 中手动移除；
- 插件不执行 Git 生命周期操作，不删除目录，不绕过 Trusted Project / Safe Mode；
- 用户已有、且不属于 ReqWS 的 module、Content Root 不被删除；所有 VCS mapping、顺序和 `rootSettings` 均不被插件修改；
- Desktop、插件、Plugin Verifier、真实 macOS GoLand GUI 和文档检查均有 exact-head 证据；
- 用户指南和开发指南反映实际本地流程，需求包完成状态只按 exact-head 证据调整。

## 4. 本次范围

### 4.1 Manifest 契约

必须实现：

- 继续使用 `<workspace-root>/.reqws/workspace.json`；
- Desktop 是唯一 writer，GoLand 插件只读；
- `schemaVersion: 1` 继续作为本次支持版本，不为 GoLand 新建第二份重复 manifest；
- `repositories` 数组是活动仓库的唯一权威集合；
- 不在数组中的目录不属于当前 IDE workspace，即使目录仍存在于磁盘；
- 插件使用完整文件内容摘要识别目标状态，不依赖文件事件顺序或时间戳单调；
- Desktop 与插件使用同一组 manifest golden fixture 和版本化 repository URL safety corpus 验证兼容行为；
- 未知附加字段可以忽略，不支持的 major version 必须拒绝；
- repository URL 必须通过双方共同消费的 versioned corpus 所定义、与 Desktop `isSafeRepositoryUrl` 一致的无凭据 HTTPS/SSH 或 SCP-like SSH 校验；规则必须保留安全 IDN 并对畸形 URI/credential 输入作相同判定，插件不得访问或记录 URL；
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
- 插件 ID 为 `com.reqws.workspace`，Kotlin package 为 `com.reqws.goland`；
- 可构建本地 ZIP，并通过 “Install Plugin from Disk” 安装；
- 只在项目根存在有效 ReqWS manifest 时启用项目级能力；
- 解析和校验 manifest，但不根据 URL、branch 或其他字段执行网络或命令；
- 把活动仓库转换为受管 Content Root、Workspace Model 实体或经过 spike 验证的等价项目模型；
- 只读比对每个活动仓库与现有 Git VCS Directory Mapping，为缺失、VCS 类型冲突或保留仓库 mapping 提供手动配置诊断；
- 逻辑移除仓库后，从插件受管项目内容中移除但不删除目录；Git Root 只提示用户手动调整；
- 重新添加同一仓库时恢复受管项目内容并重新检查 mapping；插件不创建重复 root/module，也不创建或修改任何 mapping；
- 监听 manifest 的 create、move、replace 和 content change，并对原子写入事件做防抖；
- 同步串行、幂等、latest-wins，重复同一内容不重复刷新项目模型；
- 项目重启后从 manifest 冷恢复，不依赖 Desktop 正在运行；
- 首次启动的有效 manifest read 或首次 Project Model apply 收到一次瞬时平台/coroutine cancellation 时，由仍存活的 project service 自动安排一次有界重试；恢复不依赖 manifest 再次变化、VFS 事件、Tool Window 可见性或用户直接调用 service API；
- 提供 ReqWS Tool Window，至少展示 workspace、feature branch、活动仓库、项目模型同步状态、Git Root 配置诊断、最近错误和“立即同步”；
- Tool Window 必须与 GoLand 原生主题和控件密度一致，清晰区分状态、工作区摘要、仓库列表、诊断摘要和操作区；状态不能只靠颜色表达，常用窄宽度下不得出现仓库行异常拉伸、控件裁切或不可达操作；
- 只删除插件明确拥有且当前不再需要的项目模型条目；VCS Directory Mappings 完全由用户与 GoLand 所有；
- 生产代码不得调用 VCS mapping mutation API，不得直接写 `.idea/vcs.xml` 或 VCS ownership state；GoLand 原生 auto-detection 仍由 IDE/用户设置控制，插件既不调用也不禁用；旧 `.idea/reqws-vcs-ownership.json` 及其 lock 为 inert 文件，不读取、不迁移、不自动清理；
- 普通非 ReqWS 项目不受到行为或 UI 干扰；
- VCS configuration listener 只在首个有效 manifest candidate 后注册；注册后立即复检同一 snapshot，普通非 ReqWS 项目的 VCS 事件不得触发 ReqWS 读取、状态抖动或额外 IO；
- Safe Mode 下只允许读取和诊断，不修改项目模型或启动外部进程；VCS 在 trusted 与 Safe Mode 下都保持只读。
- Safe Mode 使用稳定的 `TrustedProjects.isProjectTrusted` 查询；仅在有效 ReqWS project 被阻塞期间低频检查信任状态，转为 trusted 后只提交一次强制 Project Model reconcile，不得因 manifest digest 与 blocked 前相同而 no-op，也不依赖 261 中的 experimental trust listener。

### 4.4 构建、测试和文档

必须实现：

- 使用 IntelliJ Platform Gradle Plugin 2.x；
- 固定 IntelliJ Platform Gradle Plugin 2.18.1、Gradle 9.3.0、Kotlin 2.3.20、GoLand 2026.1.3 target、Java/JVM 21 与 `since-build: 261`，不设置 `until-build`；
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
5. 插件检测 manifest，并在项目已受信时同步活动项目内容、只读检查 Git Roots；
6. 缺失或保留的 Git Root 在 ReqWS Tool Window 中显示手动配置提示；用户在 Directory Mappings 应用变更后，插件自动复核状态。

GoLand 未安装时，按钮禁用或操作返回稳定的 `EDITOR_NOT_FOUND` 类错误，其他编辑器入口不受影响。

### 5.2 直接从 GoLand 打开已有 workspace

用户也可以直接打开 workspace root：

- 插件检测固定 manifest 路径；
- manifest 有效且项目 trusted 时执行首次项目模型同步和 VCS 只读检查；
- 若首次 read/apply 被一次瞬时取消，启动流程自身在 owner/service 仍存活且该取消仍为 latest 时自动重试并最终收敛，不要求用户修改 manifest 或重新打开项目；
- Safe Mode 时只读显示 workspace、当前 VCS 配置诊断和信任提示，不修改 Project Model 或 VCS；
- manifest 无效时不修改已有项目模型，并在 Tool Window 给出错误码和恢复动作。

### 5.3 Desktop 新增仓库

用户在 Desktop 向已打开 workspace 添加仓库：

- Desktop 继续负责 clone、分支切换、路径校验、manifest 和 `.code-workspace` 写入；
- 插件观察到最新有效 manifest；
- 新仓库进入项目内容；若缺少精确 Git mapping，Tool Window 提示用户在 Settings → Version Control → Directory Mappings 中添加为 `Git`；
- 用户应用配置后，VCS 配置事件触发自动复核，不要求关闭或重启 GoLand。

### 5.4 Desktop 逻辑移除仓库

用户在 Desktop 移除仓库：

- Desktop 只从 manifest 和 `.code-workspace` 移除记录；
- 磁盘仓库目录继续保留；
- 插件仅移除自己管理的项目内容，不删除或改写 Git mapping；
- 该目录不再参与默认 Project View、搜索范围和代码分析；若它仍是 Git Root，Tool Window 提示用户按自身意图手动移除；
- 已打开文件可以由 GoLand 按平台行为保留为外部文件，但不得因此重新成为受管 root。

### 5.5 重新添加与恢复

- 重新添加保留仓库时，插件恢复同一路径的受管项目模型条目并重新检查 Git mapping；
- 文件监听丢失、Mac 休眠恢复或同步失败后，用户可执行“立即同步”；该动作只重放项目模型同步并重新读取 VCS，不修改 Directory Mappings；
- GoLand 重启后重新读取 manifest；
- 首次 read/apply 的一次瞬时取消由 service-scope 有界自动重试恢复；重复取消不会形成无限重试或持续 CPU；
- 重复同步不产生重复条目；
- manifest 临时缺失或损坏时保留上次有效模型，不立即清空全部仓库。

## 6. 所有权与业务规则

### 6.1 单一事实来源

- manifest 是 IDE 活动仓库集合的唯一事实来源；
- Desktop 是 manifest、Git clone 和 branch 生命周期的唯一 owner；
- 插件只负责把 manifest 投影到 GoLand 项目模型，并只读比较期望 Git Root 与 GoLand 当前配置；
- 插件状态不得成为第二份业务事实来源；其持久化数据只记录项目模型受管条目所有权和最近应用摘要，不记录 VCS 删除权。

### 6.2 项目模型保护

- 插件必须对每个受管 Content Root、exclude 或 module 保留可验证的所有权；
- apply 前计算 `add / keep / remove-owned`，不能用“把当前全部 roots 替换成 manifest”实现同步；
- 不属于插件的 module、SDK、library、source root 和 exclude 必须保留；所有 VCS mapping 无条件保留；
- 所有权不确定时停止破坏性移除并显示诊断，不猜测用户意图；
- 未选项目模型 spike 的实验代码必须删除，生产实现只保留经过验证的策略和确有证据的 fallback。

### 6.3 Git 边界

插件不得：

- clone、fetch、checkout、pull、merge、rebase、push；
- 创建、删除或重命名 branch；
- 修改 remote；
- 删除 repository 或 workspace 目录；
- 自动执行仓库内脚本、Gradle task、Go command 或任意 manifest 字段。

GoLand 用户主动使用 IDE 自带 Git 功能不属于插件自动行为。插件只负责检测当前 Directory Mappings 是否包含活动仓库的精确 Git Root，并显示如何手动修正，不负责登记或移除。

### 6.4 VCS Directory Mappings

- VCS 配置完全由用户和 GoLand 所有；ReqWS 在 trusted、Safe Mode、自动同步、手动同步和重启恢复中都不得调用 mapping mutation API。
- 对每个存在且为普通 Git repository 的活动路径，精确的 `Git` mapping 视为已配置；同路径非 Git mapping 视为冲突，缺失 mapping 视为待配置。
- 活动路径的 lexical 与 live canonical filesystem identity 必须在单次 inspection 中捕获并复用；manifest 时点的旧 canonical target 不得与目录替换或 symlink retarget 后的 live identity 合并。
- 已从 manifest 移除但仍存在的 repository mapping 只显示为待用户复核；插件不得根据历史状态删除它。其他无关 mapping 不影响 ReqWS 项目模型同步，也不得被重排或覆盖。
- 只有已经确认有效 manifest 的项目才订阅 VCS 配置事件；事件触发后台刷新且 VCS 阶段始终只读。若同一 service 的 Project Model baseline 尚未建立或因前次失败保持 dirty，该刷新可与本来就需要的 ReqWS-owned Project Model reconcile 合并，但不得写 manifest、VCS mapping 或其他用户项目配置。
- `Sync Now` 强制重新读取当前 mapping，但不自动 add/remove，不修改 `rootSettings` 或 `.idea/vcs.xml`。
- 旧开发候选留下的 `.idea/reqws-vcs-ownership.json` 及其 lock 不再是契约输入；插件不读取、不迁移、不压缩，也不自动删除。

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
- 同一 live project service/coordinator 生命周期内，只有上一次项目模型 apply 和 VCS 只读快照均完成后，相同 manifest digest 才可跳过自动项目模型重放；VCS 配置事件仍必须触发一次只读复核；
- `SAFE_MODE_BLOCKED → trusted` 是 same-digest no-op 的明确例外：必须保留一个强制 reconcile intent，直到最新有效 candidate 真正开始 Project Model apply；
- 原子 rename 产生的多个 VFS 事件合并为一次稳定读取；
- manifest 临时不存在时有限重试，不因单个 delete 事件立即清空 roots；
- invalid manifest 保留上次有效状态，并在文件恢复后自动重试；
- 项目重启不依赖内存状态即可恢复；持久化最近摘要只用于 UI/诊断，新 service 必须重新读取并收敛，不能据此跳过 apply；
- 单个活动目录缺失时不得创建目录，可同步其他有效活动仓库并把整体状态标记为 degraded。
- Project Model authoritative state 的跨 JVM writer 必须互斥在已经验证并打开的稳定 `.idea` directory inode 上；替换、删除或重建任何 lock 子文件都不得产生第二把独立锁，也不得绕过 generation fence。
- latest manifest/VCS read 收到 `ProcessCanceledException` 或 coroutine cancellation 时，仍必须原样结束该 read；若 request 仍为 latest 且 project/service 存活，UI 必须恢复进入 `READING` 前最近的稳定状态且不写业务 `lastError`。已有可见稳定状态时保留用户可达的 `Sync Now`；首次启动回滚到无 snapshot 的 `INACTIVE` 时，由 service scope 延迟提交一次有界 automatic successor，并以 predecessor generation 与 exact state version 防止旧重试覆盖更新 read/apply/dispose。
- 单次 Project Model applier 收到平台或 coroutine cancellation 时不得发布普通 `Failed`、不得仅因该 submission 把 coordinator 永久关闭，也不得保留 `SYNCHRONIZING`；该 submission 保持 dirty 并以同一终止信号结束，owner scope 仍 active 时后续 refresh 必须能在同一 service/coordinator 生命周期重新 apply。首次 apply 回滚到无 snapshot 的 `INACTIVE` 时复用同一项一次性自动恢复；retry 自身再次取消不得续订，避免 cancellation storm。owner scope 取消或 service dispose 仍永久停止 coordinator；observer 自身抛出的终止信号继续按既有 raw-propagation 边界结束 worker。

### 7.3 性能与体验

- 文件读取、JSON、digest 和路径扫描不在 EDT 执行；
- 项目模型更新在 JetBrains 要求的写事务中完成；
- Tool Window 更新在 EDT 完成；
- Tool Window 在浅色与深色主题使用平台颜色，长 manifest 文本安全截断并可查看完整值，按钮保留原生键盘焦点和可访问名称；
- listener 回调不做阻塞 IO；
- 无目标变化时不触发重复索引；
- 以 50 个活动仓库和 20 个保留仓库作为规模回归；
- 不允许持续 CPU、无限 indexing、明显 UI freeze 或与事件数量等量的重复 apply；
- 初始 cancellation recovery 必须有固定次数上限和延迟，新的 startup/VFS/VCS/manual generation、owner scope cancellation 或 dispose 必须使旧 timer 失效，不得形成热循环；
- 插件错误不能阻止普通 GoLand 项目打开或使用不相关功能。
- IntelliJ `ProcessCanceledException` 与 coroutine cancellation 必须以原实例传播到当前 read/apply 边界，不得被包装成普通 VCS degraded 或 Project Model failure；瞬时 submission 取消完成状态回滚后不得使仍存活的 service 失去后续同步能力。

### 7.4 兼容性

- 产品范围是本地 macOS GoLand；
- 编译基线固定为 GoLand 2026.1.3 / Java 21 / build 261，真实 GUI 必须记录本机实际安装的 exact build；
- Plugin Verifier 必须覆盖 GoLand 2026.1.3 与 2026.2，262 兼容性不能只由 261 GUI 结果推断；
- `since-build` 为 261 且不设置 `until-build`；若 262 Verifier 失败，必须修复或明确收窄范围，不能以 `continue-on-error` 掩盖；
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
| GL-04 | 首次打开 | 插件识别 manifest 并自动同步活动项目内容；首次 read/apply 的一次 PCE 或 coroutine cancellation 由单次 startup trigger 自动重试并最终收敛，不依赖 direct service API、manifest 改写或新 VFS event；缺失 Git Root 有明确 Directory Mappings 手动步骤，用户配置后事件自动复核为已配置。 |
| GL-05 | 新增仓库 | Desktop 操作完成后 GoLand 无需重启即可加入项目内容；缺失 Git Root 显示待用户配置且插件零 VCS 写入。 |
| GL-06 | 逻辑移除 | 磁盘目录保留并退出受管项目内容与默认搜索；原 Git mapping 原样保留，直到用户按提示在 Directory Mappings 中手动决定是否移除。 |
| GL-07 | 重新添加 | 同一目录的项目内容恢复；插件重新检查现有 mapping，不创建重复 root/module 或任何 mapping。 |
| GL-08 | 原子替换和快速连续变更 | 最终项目模型与最新 manifest 一致，VCS 诊断来自最新可读配置，无并发异常、VCS 写入或持续索引循环；已有稳定状态时瞬时 read/apply 取消不留下 `READING`/`SYNCHRONIZING` 且 `Sync Now` 可恢复，首次启动的一次取消由有界 automatic retry 自动收敛。 |
| GL-09 | manifest 损坏或临时缺失 | 保留上次有效模型，不应用部分数据，并提供稳定诊断和恢复入口。 |
| GL-10 | 路径攻击 | root mismatch、绝对 relativePath、`..` 或 symlink escape 被拒绝。 |
| GL-11 | Safe Mode | 可读诊断与 VCS 只读检查保留，受管模型更新和外部进程动作禁用；恢复 trusted 后即使 digest 未变也强制重放一次 Project Model，修复 blocked 期间的 live drift。 |
| GL-12 | 重启恢复 | GoLand 重启后从 manifest 幂等重建项目模型并重新读取 VCS 配置，不依赖 Desktop 正在运行。 |
| GL-13 | 用户自定义配置 | 插件同步不删除非 ReqWS-owned module/Content Root，并且不调用 API 新增、删除、替换、重排 VCS mapping 或修改 `rootSettings`/`.idea/vcs.xml`；GoLand 原生 auto-detection 的独立行为必须单独记录。 |
| GL-14 | 50+20 仓库样例 | 同步完成，无重复事件风暴、持续 CPU 或无限 indexing。 |
| GL-15 | 全面回归 | VS Code、Cursor、Finder、workspace 增删、i18n、Desktop package 和现有测试通过。 |
| GL-16 | Tool Window 视觉与可用性 | 实现不只包含与原型相同的元素，还应尽可能复现其空间结构与主次层级：紧凑状态徽标、带边界的工作区摘要、右侧独立计数的仓库卡片、固定高度且有行分隔的仓库列表，以及全宽主同步按钮和居中的次级动作；因原生 Tool Window chrome 无法复现的差异必须明确记录。同步、VCS 待手动配置、降级、错误和 Safe Mode 均有文字语义；`Sync Now` 明示为重新检查而非自动修改 VCS；窄宽度与浅/深主题无异常拉伸、裁切或操作不可达，并保存真实 GoLand 候选截图供后续验收。 |

## 10. 术语

- **活动仓库**：当前存在于 manifest `repositories` 数组中的仓库。
- **保留仓库候选**：当前不在 manifest、但位于 workspace root 直系目录且仍包含普通 `.git` 的仓库目录；只读 VCS 诊断不宣称或证明它曾属于 manifest，用户必须自行确认 mapping 是否应保留。
- **受管条目**：由 ReqWS GoLand 插件创建或修改、且插件能证明所有权的项目模型条目；VCS mapping 不属于受管条目。
- **目标状态**：对当前 manifest 完整校验后得到的活动仓库路径集合、项目模型投影及期望 Git Root 检查集合。
- **同步**：把插件拥有的 GoLand 项目模型收敛到目标状态，并只读刷新 Git Root 配置诊断。
- **Desktop**：ReqWS Electron 主程序，是 workspace、Git 生命周期和 manifest 的唯一写入方。
