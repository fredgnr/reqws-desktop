---
title: ReqWS GoLand 插件使用指南
type: guide
status: active
updated: 2026-09-22
---

# ReqWS GoLand 插件使用指南

本指南说明当前源码中的独立 GoLand 入口、仓库加载选择、用户配置保护和排障方式。

实际检查及其候选范围见[实施记录](../changes/goland-workspace-loading/implementation-2026-09-19.md)。旧版本截图和 GO 不能证明另一候选已安装或已通过验收。

## 1. 安装条件与构建

当前开发中的兼容声明从 GoLand **262 系列**开始，不设置上限；编译基线为 **2026.2**，固定自动集成环境为 **2026.2.1.1**。开发后的[验证记录](../changes/ide-plugin-compatibility-automation/verification-2026-09-21.md)区分已执行检查与本机 UI 授权阻塞，新范围尚未形成完整验收结论；使用已发布版本时以其真实描述文件和发布说明为准。见[兼容开发记录](../changes/ide-plugin-compatibility-automation/implementation-2026-09-21.md)。源码构建使用 Node.js 24、JDK 25 和仓库锁定的 Gradle/Kotlin 工具链。

```bash
npm run check:goland
npm run package:goland
```

本地测试 ZIP 位于 `integrations/goland/build/distributions/`，普通构建未签名。正式 Release 的 `ReqWS-X.Y.Z-goland-plugin.zip` 要求作者签名；校验和见同一 Release 的 SHA256SUMS。Marketplace 是否可安装及可更新以实际审核结果为准，当前不得从源码的发布能力推断已上架。首次上架与模式切换见[发布操作](../changes/goland-plugin-marketplace/bootstrap-and-operations.md)。只有可信且能对应当前源码的工件才应安装。

手动安装：GoLand **Settings → Plugins → 齿轮菜单 → Install Plugin from Disk**，选择 ZIP，确认 ReqWS，再按 IDE 提示保存工作并重启。更新也使用相同步骤。Agent 安装必须由用户显式调用 manual-only 的 `$reqws-goland-plugin-install` 并确认 exact 工件与目标；普通开发或构建请求不授权安装或重启日常 IDE。

## 2. 在 Desktop 选择加载集合

工作区需要处于“就绪”状态。在详情的 **GoLand 工作加载** 中选择：

- **默认全部**：加载全部需求成员，未来新增成员也会加载。
- **指定仓库**：仅加载勾选且仍属于需求的仓库，未来新增成员不会自动选中。
- 指定模式下全部取消勾选：保存明确的空集合，不会恢复为全部。

**保存选择**只写加载配置；**保存并打开 GoLand**先保存再打开。保存成功不代表 IDE 或插件已经同步，请在 GoLand 的 ReqWS 面板中确认。没有保存的编辑会显示提示，可取消；并发冲突会保留当前选择并要求重新加载已保存配置。

保存操作固定在详情底部；**加载规则**展开说明新增成员如何处理，**工作区文件**展开完整路径与文件维护说明。成员增删放在独立的**管理工作区**中。顶部 Cursor 菜单分别提供工作区文件与代码目录入口。

未加载不会删除仓库、切换分支、改变需求成员或重写 VS Code/Cursor 的 `.code-workspace`。已移出需求的旧选中 ID 暂时不生效；同 ID 再加入时恢复意图。本次保存新选择时只提交当前成员并清理失效 ID。

## 3. 唯一入口

Desktop 所有 GoLand 操作均打开：

```text
<workspace>/.reqws/ide/goland/
  reqws-project.json
  .idea/
```

原 workspace root 及普通无绑定目录不会触发 ReqWS 项目模型适配。不要手工预建 shell 或复制旧 `.idea`；首次创建由 Desktop 完成。未知、异工作区或中断后没有完整绑定的入口会报冲突并保留原内容，需要人工核对。ReqWS 不迁移或删除原 workspace 的 `.idea`。

Desktop 是 `.reqws/workspace.json` 成员清单和 `reqws-project.json` 绑定/选择的唯一 writer；插件只读业务配置。

## 4. Project 面板与状态

信任项目后，插件把选择投影为一个 ReqWS module 下的多个 Content Roots。原生 shell module/root 保留；正常同步完成后，普通 Project 面板不显示 shell 或内部文件，Excluded Files 两态均应成立。初始读取和 Safe Mode 允许暂态入口展示。

用户另外添加的 roots 始终保留，即使位于 ReqWS module 内。因此空加载集合不保证整个 Project 空白；Libraries、Scratches 和用户额外内容可以继续显示。

| 仓库状态 | 含义 |
|---|---|
| 已加载 | 当前选择中存在的仓库已进入验证后的项目内容范围；不代表语言服务就绪。 |
| 未加载 | 仍是需求成员，但未请求加载，属于正常状态。 |
| 目录缺失 | 请求加载的目录不存在；选择仍保留。 |
| 仍被其他项目根包含 | 已退出 ReqWS 加载集合，但用户或其他 root 仍覆盖它；插件不会删除该用户条目。 |
| 项目内容未生效 / 错误 | 当前投影未通过模型/PFI 或所有权检查。 |

**已同步**只证明当前配置的 ReqWS 契约收敛。插件不检查语言工具链、Go Modules、补全或运行配置。**立即同步**走同一条校验链并强制重核；**打开清单文件**查看成员清单；**复制诊断信息**提供脱敏版本/状态/错误信息。

## 5. Git 配置与保护

插件只读检查已加载仓库的 Git Directory Mappings。需要时由用户在 **Settings → Version Control → Directory Mappings** 配置，随后等待更新或使用立即同步。未加载成员与额外 mappings 都是用户配置；插件不会要求删除它们，也不承诺 Git Log/Commit 自动缩小。

ReqWS 不执行 clone/fetch/checkout 等 Git 生命周期、不访问仓库 URL、不删除仓库、不写 VCS mappings 或语言/构建配置。IDE 自身对受信任项目的语言分析和原生行为由 IDE 与用户环境控制。

## 6. 故障处理

- **绑定错误**：保留最后有效模型并冻结受管增删，撤销 shell 适配。检查当前入口与 Desktop 的工作区对应关系；恢复完整配置后自动重核。
- **所有权冲突**：marker 丢失、root 替换或用户在受管 root 中新增子配置时，插件拒绝破坏性删除。先核对用户配置，不删除 ledger/module 来强行重试。
- **读取或保存失败**：保存端保留原完整文件；已有绑定发生临时写入失败时，检查目录权限与空间后可直接重试当前草稿，无需先重载。绑定或版本冲突仍需重载。初次中断留下的 shell 不会被自动认领或清空，需保留现场并人工处理。
- **Safe Mode**：首次未信任时仅只读展示；信任后才应用受管模型。

不通过清空 `.idea`、删除仓库、重建绑定或强制重装来掩盖错误。旧 ownership 文件是惰性历史文件，不参与新删除权，也不会自动清理。

完整技术边界见[技术方案](../changes/goland-workspace-loading/technical-design.md)，验收使用普通 Project 面板展开具体目录，见[测试计划](../changes/goland-workspace-loading/test-plan.md)。
