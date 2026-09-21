---
title: GoLand 独立入口与工作加载集合技术方案
type: technical-design
status: active
updated: 2026-09-21
---

# GoLand 独立入口与工作加载集合技术方案

本方案将 Desktop 的工作加载意图投影为受保护的 IDE 内容根，并分别处理入口文件范围和普通 Project 树呈现。

本文的精确 IDE build 约束已由[兼容与自动化方案](../ide-plugin-compatibility-automation/README.md)替代：最低系列 262、无预设上限、固定代表环境和自动 API 集合。以下独立入口/所有权契约继续有效；历史 build 与验收结果仍只解释当时的候选。

## 1. 基线、结论与适用范围

设计源码基线为 `cb25cbba2b58fc767d48cbbc001004082e7de390`，已包含 macOS 自更新变更；不得从较旧的 `701b8bab` 全量覆盖服务或活动门禁。基线实现为 workspace-root + owned excludes；此处定义唯一独立入口契约，实际实施及按次验收见[实施记录](implementation-2026-09-19.md)。

用户报告在 `GO-262.9437.286` 上验证了多 roots、精确 shell 排除、树过滤、空集合重开和具体用户 root 保护。其结论只支持进入实现。报告来源、源码路径及未验证范围统一见[验证依据](verification-basis.md)，不在本方案复制工件摘要。

选定结构：保留原生 shell module/root 和 ProjectRootEntity；另建一个 ReqWS 承载 module；按 root 条目维护加载集合；精确排除 shell 的文件范围，并过滤 shell 树节点。不清理原生 root，不用 module unload，不修改 VCS mappings。

### 1.1 无兼容负担的实施决策

用户已明确：不考虑任何兼容性，历史代码和历史逻辑可以直接清除。本需求采用破坏式替换，不维护新旧双路径、功能开关、旧 schema/状态读取器、迁移器、降级或回退入口；不要求旧 Desktop、旧插件和新版本混用。

本轮唯一 GoLand 目标为验证报告中的 `2026.2.1.1 / GO-262.9437.286`。S0 将编译 SDK、descriptor 和验证目标统一到该版本；不保留 261 或其他历史版本矩阵，不按 IDE 版本分支。不测试未来版本，不以某个跨度的版本号代替实际验证。

可以调整当前数据契约并删除历史容错/别名逻辑；TS/Kotlin/IPC 和调用点必须一起更新，但无需继续接受旧格式。复用通用校验/存储原语是减少重复实现，不是保留历史运行路径。实际代码和构建清理按 S0–S4 执行，当前进度见[实施记录](implementation-2026-09-19.md)。

删除范围是受本需求替代的仓库源码、旧协议实现、专用测试和过时文档，不是用户磁盘上的仓库、`.idea` 或业务文件。新模式的 ownership、取消恢复、原子写入与 trust 检查是当前正确性要求，继续保留。

## 2. 三层模型与职责

```text
Desktop（成员/选择唯一 writer）
  workspace.json：需求成员 M
  reqws-project.json：绑定 + 选择意图
                 │ 只读、固定文件契约
                 ▼
Plugin：验证绑定和 trust → 一份有效快照 → single-flight reconcile
  ├─ ManagedRootsAdapter：只改 ReqWS-owned Content Roots
  ├─ ShellExclusionAdapter：精确入口文件范围排除
  ├─ ProjectViewProvider：精确入口节点过滤
  └─ Public PFI verifier + 只读 VCS diagnostics → 状态发布

GoLand Project
  ├─ 平台原生 module/root：保留，shell 受精确排除
  └─ ReqWS module：受管仓库 roots + 可能存在的用户额外 roots
```

Workspace Model 变更必须保留正常平台通知。树过滤不是范围排除；没有 ContentRootEntity 也不代表没有 ProjectRootEntity 贡献。插件成功不能只凭 `.iml`、API 返回或可见节点数量判断。[源码依据](verification-basis.md#2-源码与接口依据)

## 3. 磁盘布局与业务协议

### 3.1 固定布局

```text
<workspace-root>/
  .reqws/
    workspace.json                 # 本版成员契约；Desktop 管理 M
    ide/goland/
      reqws-project.json           # 新协议：Desktop 唯一 writer
      .idea/                       # GoLand 保存配置；Desktop 不写
        reqws-loaded-roots.json    # 插件技术所有权/恢复记录
        reqws/                     # 插件创建的 module 文件位置
  repo1/
  repo2/
  repo3/
```

IDE project basePath 为 `.reqws/ide/goland`，workspaceRoot 从这三个固定祖先目录推导，并与原 manifest 的 canonical root 和 workspace ID 交叉校验。绑定中不接受任意 manifest 路径，不扩展 symlink、gitfile/worktree 或目录搬迁支持。

独立文件不是第二份成员 manifest：不复制仓库 URL、名称、路径或分支，只保存绑定和选中 ID。成员与选择分离是业务职责划分，不是为了兼容旧 schema。可复用现有成员字段作为本版契约；如需改变，一次性更新全部消费者，不增加旧格式分支。VS Code/Cursor 仍使用完整 `M`，这是现有功能边界，不是旧 GoLand 模式兼容。

### 3.2 reqws-project.json（新文件协议 v1）

```json
{
  "schemaVersion": 1,
  "adapterProtocol": 1,
  "workspaceId": "ws_example",
  "bindingId": "95dc7c6a-0eaa-4c96-824a-e117316a1db3",
  "revision": 1,
  "selection": {
    "mode": "selected",
    "repositoryIds": ["repo_1", "repo_2"]
  },
  "updatedAt": "2026-09-19T00:00:00.000Z"
}
```

`all` 形态为 `selection: {"mode":"all"}`，不得附带 IDs。`selected` 必须有数组，允许 `[]`；ID 使用已有 ID 安全约束，拒绝重复。`revision` 为正安全整数，创建为 1，每次有效保存递增；溢出拒绝，不回绕。`updatedAt` 只展示。协议限制为普通 UTF-8 JSON 文件、最大 1 MiB；只接受本版声明的 schema/protocol 和字段形态；不协商版本、不读取旧格式、不实现别名或默认字段迁移。非法或未知输入明确拒绝，不做 fallback。

只有 Desktop 首次创建本版专用入口时可以采用默认 `all`；用户已给出选择时将该选择随绑定一起原子发布，避免先打开全部仓库再改子集。已打开项目缺少绑定时保持不受管或报告绑定错误，不猜测 `all`，也不回到原目录适配。

加载计算：`L = M` 或 `M ∩ repositoryIds`；顺序来自 manifest。不存在于当前 M 的旧选中 ID 只是不生效，不能解析为磁盘路径。目录缺失与选择分开：`L` 保留请求语义，当前可应用路径集合为通过目录/Git/containment 校验的 present 子集；缺失项显示缺失，不自动移除选择。

### 3.3 写入与并发

所有入口准备/保存操作由 Main 重读 workspace ID 对应的 ready manifest，并进入现有共享 WorkspaceMutationCoordinator，复用 ApplicationActivityGate，避免与成员变更和自更新安装互相穿插。不得另建不带活动门禁的 coordinator。IPC 保存请求只传 workspaceId、选择、expectedBindingId 和 expectedRevision，不接受 renderer 指定路径；首次创建入口使用单独输入。

更新前比较请求的 expectedBindingId/expectedRevision 与当前 bindingId/revision，绑定重新创建也不能被相同 revision 冒充；陈旧编辑返回 `GOLAND_SELECTION_CONFLICT`；UI 保留未提交选择并提示重新加载。原子 JSON writer 在同目录写临时文件后发布，失败保留上一完整文件；不先更新 UI 缓存冒充已保存。Desktop 跨实例写入沿用现有单实例保护；实现核对未有该保护时，增加此文件的跨进程互斥，不能把 revision 比较单独当成 CAS。

成员增删不需要与选择文件组成双文件事务，按第 3.2 节求交即可。消费端读绑定 A、manifest、绑定 B，要求 A/B 原始内容和目录身份一致；必要时再复核 manifest，检测中途替换后有界重读。成员与选择不存在全局原子快照承诺，但每个已发布候选必须由完整有效文件构成，并通过最新 generation 与身份复核；不能把读取失败解释成空集合。

## 4. Desktop 唯一入口与 UI

新增服务建议名 `GoLandWorkspaceService`，封装读取选择、准备入口、保存选择和启动；具体接线从当前服务/IPC 结构扩展，不复制 workspace 业务。

首次准备入口：在验证后的 R/.reqws 下创建缺失的固定目录，shell 必须以 exclusive mkdir 创建；创建绑定作为最后发布标记，成功后才可启动。存在有效同 workspace 绑定则复用；存在无绑定/异 ID 的 shell（即使看似空）或未知配置则返回 `GOLAND_ENTRY_CONFLICT`，不自动认领或删除。首次发布中断留下的目录保留并提示人工处理，不自动覆盖 `.idea`。

启动保持 `/usr/bin/open -a <validated-GoLand.app> <validated-shell>`、参数数组、`shell:false` 和现有应用/路径检查；不使用私有打开参数，不强行关闭旧窗口。不设计新旧客户端能力协商；部署前提是本版 Desktop、插件与目标 GoLand 一起使用。没有握手证明时不宣称插件已安装/已同步；插件缺失或配置无效时说明错误，不回到打开整个 workspace。

工作区详情直接提供“默认全部”“指定仓库”、勾选、保存、保存并打开，没有启用新模式开关或旧入口按钮；仅保存选择不重写 manifest 或 `.code-workspace`。在选择尚未保存、请求进行中、冲突或失败时显示明确状态；新区域需键盘可达和中英文文案，按既有 i18n skill 走翻译检查。

详情采用用户选定的方案一布局：勾选框与仓库名称/相对路径同行，规则及完整路径按需展开，保存、保存并打开和重载固定在底部。Cursor 的工作区文件与代码目录入口收进同一菜单，成员管理独立折叠；加载选择不承担成员增删。折叠内容不进入对话框焦点循环，保存状态旁始终保留 IDE 同步确认提示。视觉与交互证据见[设计验证](../../../design-qa.md)。

已有有效绑定的保存遇到 `GOLAND_WRITE_FAILED` 时，保留草稿和原 binding/revision，允许直接重试保存或保存并打开；再次写入仍由 Main 校验当前身份和 revision。初次读取失败、首次 prepare 失败、binding/revision 冲突或其他未知错误继续阻止写入，需显式重载，不能将未验证状态当作可重试的普通保存。

所有 ReqWS GoLand 启动动作都走“校验本版 workspace → 准备/复用合法 shell → 打开 shell”这一条路径。直接删除原目录启动命令分支、旧模式 UI、fallback 和能力协商代码。手工在 GoLand 打开普通目录不触发 ReqWS 的旧适配器。

本版不迁移历史 workspace、原 `<R>/.idea`、旧 ownership ledger 或旧配置字段。不符合当前格式的输入明确报错，由用户另建符合本版格式的配置；不能先破坏旧配置再创建新配置。原目录中的磁盘数据不主动清理，不测试旧窗口/旧插件能否继续工作，也不提供版本降级、旧入口回退或设置合并。

## 5. 插件绑定、快照和生命周期

建议新增 `loading/` 内的绑定 reader、selection resolver、managed roots adapter、shell adapters；删除 legacy adapter 和 workspace-root 检测分支。入口检测只验证精确 shell 绑定，不从任意祖先“寻找可用 workspace”。原目录/普通无绑定项目都不执行 ReqWS 模型更新，不得被同名目录命中。

`VerifiedBinding` 包含 workspaceId、bindingId、canonical workspaceRoot/shell、固定 manifest/选择路径、受验证的目录身份和 protocol。它是受限本地授权上下文，不是签名或防同用户恶意改文件的密码学凭据。

读取在后台使用既有稳定句柄、普通文件/大小/编码、路径 containment 与目录身份检查。Safe Mode 只读取并展示，不创建 module、不发布 ReqWS roots 事件、不激活排除/树过滤。取得信任后才进入应用。`TreeStructureProvider.modify()` 和 exclusion getter 只消费不可变缓存，不做磁盘 IO、不访问 URL、不遍历仓库或等待语言服务。

同时监听原 manifest 与新选择文件的精确路径和直接父目录，复用原子替换感知、定向 VFS refresh、防抖与 single-flight；不递归刷新整个 workspace。选择改变需要即使 manifest digest 不变也触发更新，clean key 至少包含两个文件摘要、binding identity、投影策略版本；摘要仅用于运行时，不进文档台账。manual/trust/model-drift 强制核对仍覆盖相同摘要的 no-op。

一个 coordinator 串行应用最新候选。每次变更前、Workspace Model 提交内、异步 PFI 返回和最终发布前检查 generation、trust、project/service 存活及 binding identity。禁止在持久化/模型操作跨越后，使用旧结果覆盖新配置。

冷启动不得从持久化 Synced/digest 初始化成功证明；重新读取绑定、ledger、实时模型与 PFI。`.idea` 尚未就绪沿用有限 startup readiness 等待，不能无限创建重试作业，也不由 Desktop/插件清空或强制重写 `.idea`。首次未信任/读取阶段允许暂态入口展示，正常同步完成后才满足隐藏契约。

首次 startup 在受信任、project 已 initialized、绑定/最新 generation 仍有效、从未观察到 metadata 且无已有投影记录时，可以通过公开 `Project.scheduleSave()` 请求 IDE 正常保存一次；只在 `.idea` 仍确认为缺失时排队，不手写目录。该请求位于模型事务/目录锁外，仍沿原有界 readiness loop 等待真实落盘；未 initialized 的 tick 不消耗一次性标记。既存或曾被观察后移走的 metadata、所有权冲突、Safe Mode 不触发保存，排队也不是成功证明。

绑定缺失/非法/被替换：冻结受管增删，不将其转为空集合；既有模型保留并报告错误。撤销绑定能力缓存并以 S0 验证的路径使入口排除/树过滤失效；不得把属于新 identity 的目录继续隐藏。只有 selection 内容错误而同一绑定身份仍可信时，可以保留上次有效模型和 shell 适配，但状态必须说明 stale，不报新配置已生效。恢复后自动全链重核，不依赖用户反复 Sync Now。

## 6. Module 与 root 级所有权

### 6.1 唯一受管承载 module

在项目 metadata 就绪后创建一个专用 module，使用唯一稳定 ID 和 shell/.idea/reqws 下的精确 module 文件路径；类型通过公开 `ModuleTypeManager.getInstance().getDefaultModuleType().getId()` 采用目标平台已注册的默认类型，不硬编码 `EMPTY_MODULE`，不引用 Go/Web 实现类、不调用语言 module builder、不按语言构建文件挑选。创建前记录意图，重名或同路径不明条目 fail closed，不仅按 `reqws-*` 名字认领。

原生 module、shell Content Root、ProjectRootEntity 不认领、不删除。承载 module 即使受管集合为空也保留；模块目录/名称不因仓库数量而改变。用户可以在该 module 中添加自己的 root，插件不能清空整个 contentRoots 数组或对整个 module 做 replaceBySource。

### 6.2 单个受管 root 的可重建证据

所有权文件独立使用 `reqws-loaded-roots.json`，不读取旧 `reqws-managed-project-model.json`；其 reader、格式迁移和恢复分支直接删除。记录 binding identity、精确 module 文件位置、相对仓库路径、repository ID、创建 nonce、已管理项与未完成 intent。禁止记录 Git URL/凭据。

正式实现不能只持久化一个路径清单。采用本版“ledger + 模型 companion marker”证据，在每个新建 ContentRoot 下使用标准可持久化的虚拟 exclude URL：`<repo>/.reqws-goland-ownership/<random-nonce>`；目录本身不创建，要求 namespace 真实不存在且非 symlink。marker 使用同 root 的标准 entity source 保存于 `.iml`；不是自定义 EntitySource 在重启后仍存在的假设。S2 必须验证 marker 在目标 SDK 正常保存/冷启动后的实际形态，未通过不得发布删除权。

只有绑定 ledger、唯一 exact root、唯一对应 marker、精确 module 文件及文件系统身份同时匹配时才允许删除。已有同路径用户 root 只借用，不自动补 marker 接管；别名/路径名相似不取得删除权。marker 丢失、重复、namespace 变成真实目录、根被替换或跨集合冲突，均保留内容并报告 OWNERSHIP_CONFLICT。

root 删除会连带其子配置，因此还需检查是否新增了非受管 SourceRoot、Exclude、properties 等子项。发现用户子配置或不支持的实体关系就阻止删除并解释原因，而不是悄悄销毁它们；不使用不透明快照回灌覆盖用户修改。另一个独立用户 root 无论是否位于受管 module 内，都必须保留。

### 6.3 应用事务和崩溃恢复

```text
读有效候选与当前模型
→ 计算 add / keep / remove-owned / borrowed / conflict
→ 获得 shell metadata 身份绑定的互斥
→ 原子持久化 PREPARED intent + 原有 claims
→ 一次 Workspace Model 事务精确增删 root/marker
→ 实时 PFI 验证
→ 记录应用结果并发布当前 generation 成功证明
```

intent 与 `.iml` 保存不是跨文件原子事务。不能因 API 返回就清空恢复记录；新进程从 ledger + 平台重载模型复核完成状态。intent 存在但模型/marker 全不存在，仅在能证明该新增从未持久化且没有用户冲突时重试创建；部分存在或身份不明时冲突，不通过删除或重新认领修复。

同进程的明确未提交失败可恢复：Workspace Model updater 尚未正常返回时，在仍持有 writer lock 的范围内复核 metadata/module 目录身份与完整 PREPARED journal，记录该 intent 对应的原 journal（首次创建时为空）。该证据仅保存在当前 project 内存；新 adapter 只有在当前 journal 完整匹配且绑定/目录重新验证通过时，才能按原 journal 重新规划最新选择，不能据此接管未知 module 或用户 root。updater 正常返回前撤销旧未提交证据；后续失败按可能已提交处理并保留正常恢复记录。模型提交及新 module 创建证据登记置于短小的不可取消区间，区间内仍检查 generation/trust/目录身份，随后立即恢复取消检查。冷启动没有此内存证据，缺失 module/marker 仍 fail closed。

同一个 shell 只允许一个插件 writer。锁绑定真实 `.idea` 目录身份，不依赖可被替换的 lock 文件路径；复用既有 verified storage 原语并保留错误分类。只实现本版 ledger 的恢复，不导入旧项目 ledger。

取消与失败只回滚能确认仍是本事务的精确增量；遇到后续用户/平台修改不得整图回退。无法安全回滚时保留 intent 和 dirty 状态，下一轮重核，不误报 Synced。目录 rename/替换后不得向替换目录继续写入或删除。底层状态格式/API 变动与调用点/测试在同一任务原子完成。

## 7. 入口排除、树过滤与 API 合规门禁

### 7.1 范围层

候选为 `DirectoryIndexExcludePolicy#getExcludeUrlsForProject()`，仅在 trusted、有效绑定的本项目返回 exact shell URL。不得排除整个 R、所有 `.reqws` 同名目录或全局 ignored files。shell 与仓库根互不包含，先做关系校验。

这会覆盖保留的 native shell root 和 ProjectRootEntity 所贡献的入口内容。保持平台 ProjectRootEntity 与非索引机制原样；不把真实 shell 文件移入 `.idea` 来制造通过。[源码说明](verification-basis.md#2-源码与接口依据)

### 7.2 呈现层

`TreeStructureProvider` 只过滤绑定 shell 及其内部文件/目录对应的节点，不按文本名称过滤。Project/Module 容器不是 shell 目录，不能连带删除其仓库或用户 roots；Libraries/Scratches 保留。缓存更新后用受支持的 Project View 刷新方式生效。Excluded Files 两态均不得重新显示 shell。

该扩展不负责搜索范围，不篡改 scope，也不构造一个假的 ReqWS 树来代替普通 Project 验收。

### 7.3 单一目标 SDK 的 API 决策

S0 已在实际 GO-262.9437.286 SDK 验证 `ProjectRootManagerEx.makeRootsChange(Runnable, RootsChangeRescanningInfo)` 包裹真实排除缓存变化能够使 PFI 重读策略，且无需修改 Workspace Model 实体。仅在 capability 发生变化时调用，使用 `TOTAL_RESCAN` 覆盖策略撤销后的内容恢复；不调用 Experimental 的 `AdditionalLibraryRootsListener`。当前公开 API 判定及最小平台回归见[实施记录](implementation-2026-09-19.md)。

用户已撤销本需求的旧平台兼容要求，因此现行规范中的 261 编译基线和旧矩阵不是新实现的约束，S0 应同步相关说明和配置。API 使用政策与跨版本兼容是两个问题：spike 报告中的 `AdditionalLibraryRootsListener` 为 Experimental，`updateProjectModel` 为 Obsolete；现行 Internal/Experimental 禁令没有因此被默认为全部取消。

S0 仅对目标 `GO-262.9437.286` SDK 确定具体签名、声明注解、可持久化实体、锁要求、模型 update 入口与排除缓存失效路径；不为其他版本设计 adapter 或 fallback。优先复用真实模型更新产生的正常事件；必须证明冷启动“无模型差异”、空集合、绑定撤销和 trust 转换也能正确重读策略，不能为了事件伪造 roots 变更或无限广播。

若存在完全合规路径，记录实际 API 并做最小平台回归后关闭门禁。若只能依赖该 Experimental 通知方法，必须在实现前单独提出精确到类/方法/适配文件/目标版本的规范例外，取得明确批准并同步 AGENTS、标准和目标 API 检查；不得由 subagent 自行放行、削弱 verifier 或忽略 API 警告。未获批准则 S3/最终产品 GO 阻塞，S1 和 S2 的独立合规工作可以继续。不得退化为只隐藏树却宣称范围已排除。

编译、Plugin Verifier 与 GUI 只验证这一个目标 SDK；删除旧版本矩阵和为其保留的桥接代码，descriptor 不宣称旧版本可用。本轮已获准取消兼容要求，无需再为删除旧版本支持单独申请。保留目标 SDK 的 API/依赖错误检查，不能用“无需兼容”跳过真实错误。Internal 实体查询只允许留在不打入产品的隔离诊断中。

## 8. 成功、冲突和 VCS 语义

每轮公开 PFI 校验至少覆盖：已加载 present root 归属预期 module/contentRoot 且非 excluded；shell 为 excluded 且不在内容范围；撤下的受管 root 不再受该 managed claim 管理，是否被用户/其他 root 包含另判。root 不是 loaded 时无需强行 `isExcluded=true`，它可以只是项目外目录。

| 状态/诊断 | 含义 |
|---|---|
| LOADED | 当前有效投影中的存在仓库；不代表语言就绪。 |
| NOT_LOADED | 仍属于 M，但未请求加载；正常状态。 |
| MISSING | 已请求加载但目录缺失，不假装已卸载成功。 |
| USER_ROOT_COVERAGE | 未被 ReqWS 选中加载，但仍被用户或其他父 root 包含。 |
| OWNERSHIP_CONFLICT | 删除权或配置保存不确定，不做破坏性修复。 |
| SAFE_MODE / BINDING_ERROR / MODEL_ERROR | 沿现有生命周期映射，不吞成空集合。 |

具体 i18n key 和本版状态枚举由 S0 冻结，删除废弃状态/映射，不设旧枚举兼容层；不另建互相矛盾的全套状态机。Synced 仅表示本 generation 的 ReqWS 契约收敛，无未处理失败；保存选择成功、历史摘要、旧 GUI GO 都不能初始化它。

仓库列表保持紧凑单行，仓库名与状态使用有界列宽；长状态不能挤掉仓库名或越出行边界。USER_ROOT_COVERAGE 使用简短状态，完整说明放在安全转义的悬停提示和可访问性描述中。未曾选中过的仓库同样可能被用户 root 包含，文案不暗示它一定发生过退出加载操作。

VCS observer 的模型期望集改为 L 中存在的仓库；M − L 的 mappings 是用户保留配置，不按缺失映射报失败，也不一概归类为“已从需求删除的 retained repo”。M 之外的已有 mapping 只作为额外用户 VCS 配置报告，不为旧排除策略继续扫描磁盘推导 retained 成员。宽范围/显式映射是否存在只诊断，不写 `.idea/vcs.xml` 或调用 detector/setters。因用户 U 导致范围不同必须如实显示，不承诺 Git Log/Commit 自动缩小。

## 9. 代码改动与执行入口

| 层 | 改动点（新增名称为建议，S0 登记精确文件） |
|---|---|
| shared | 独立 GoLand 绑定/选择 schema、输入输出 DTO、错误码；定义本版成员/加载语义，TS/Kotlin 同步更新，不保留旧 schema reader。 |
| Main | 新 GoLandWorkspaceService、原子写入与路径验证；接入已有 coordinator/activity gate；editor-launcher 仅启动 shell，删除旧模式分支。 |
| IPC/preload/renderer | typed read/prepare/save 调用、工作区详情加载区、错误和冲突反馈、i18n。 |
| Plugin loading/contract | 固定绑定识别、两个文件的快照与集合求交。 |
| Plugin loading/model | module、root claims/markers、journal、差量更新和 PFI verifier。 |
| Plugin loading/shell | 精确 exclusion、不可变缓存、Project View provider、公开失效通知适配。 |
| Plugin project/sync/ui/vcs | 唯一入口、双 watcher、生命周期/状态和 VCS 只读分类接线。 |
| 配置/文档 | 单一目标 SDK 的 descriptor/构建/Verifier、当前指南与规范；只清理相关旧矩阵，不停用检查或改发布/签名业务。 |

任务分工、并发与最小回归以[实施计划](implementation-plan.md)为准；最终 GUI 以[Project 面板验收](test-plan.md)为准。不得把测试插件的菜单动作作为最终控制面；最终必须从 Desktop 修改选择自动生效。

### 9.1 旧代码清理清单与顺序

S0 列出准确路径和引用关系，S4 集成后删除旧路径；不是再追加一个兼容阶段。可直接删除：
- `WorkspaceExcludeModelAdapter`、`ReqwsExcludePlanner` 及仅为“大根 + excludes”服务的 retained 扫描、错误分支和 UI 文案；`ReqwsProjectModelAdapter` 若保留名字则直接替换实现，不保留双策略选择。
- 旧 `ReqwsManagedModelState`/`ReqwsManagedModelFileState` 的专用 ledger reader、旧版本迁移、恢复分支及调用；若其中通用原子存储/目录句柄仍被新实现使用，先提取真实使用的原语再删除专用包装。
- 原目录识别/启动、旧模式入口、feature flag、fallback、旧 IPC/状态别名和未再使用的扩展注册。
- 只验证已移除行为的测试、fixtures、截图步骤、旧矩阵和过时有效文档；当前仍有效的安全/并发回归迁入新实现，不按目录批量丢失。

Main 统一执行依赖顺序删除，Reviewer 只读检查引用、构建注册和死代码；最终不能保留不可达旧实现或恒定为 false 的兼容开关。历史事实留在 Git/明确归档的证据中，不把历史报告改成新实现已经通过。

## 10. 交付边界

本需求先完成 API 门禁，再交付源码和必要测试包；安装使用既有 manual-only 安装授权流程，确认 exact 工件和隔离目标。正式候选记录源码 commit、实际未提交差异、工件入口、IDE build 和检查结果；临时完整性比较留在测试输出，不把逐文件 SHA 列表写入文档。

本次不自动发布 tag/Release，不修改签名凭据。发生加载错误时报告错误并保留最后有效模型，不转入另一套工作区模式。删除/重建绑定或 module 不是常规重试；需要明确用户授权并保留用户配置，不能自动清理来解除 ownership 冲突。
