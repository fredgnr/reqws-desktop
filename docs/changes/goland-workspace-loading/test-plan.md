---
title: GoLand 工作加载集合测试与本地验收
type: test-plan
status: active
updated: 2026-09-19
---

# GoLand 工作加载集合测试与本地验收

本计划验证 ReqWS 控制链、项目模型、持久化和用户配置保护；GUI 直接通过 GoLand 普通 Project 面板观察目录与文件。

## 1. 固定测试原则

**不使用 Find in Files、Find in Path、Search Everywhere、Project Files 搜索 scope 或搜索命中数量确认文件存在/可见性。** 不重跑历史的两范围搜索或四格搜索截图。历史报告原结论保留，但不是新用例的操作步骤。

三个判断分开：

| 要回答的问题 | 正确证据 |
|---|---|
| 当前 Project 是否展示这个文件 | 真实普通 Project 面板展开对应仓库/文件夹，看到完整文件名和父层级；反向观察完整相关根列表。 |
| 文件是否仍在磁盘 | 限定临时夹具的 stat/read 或内容比较；不要把“树里没有”写成“磁盘不存在”。 |
| 平台内容/排除边界是否生效 | unit/platform 测试中的实时模型和公开 ProjectFileIndex；不启动搜索 UI。 |

已关闭的父目录、折叠的 module、未滚动到的节点、输入过滤或只有状态提示，不能证明文件不可见。观测前确认普通 Project 视图、清空 speed-search/过滤、展开所有相关层级并滚动完整的小夹具；允许 module 有/无包装层。取证包含父目录和具体文件名，不能只截空白区域。

## 2. 按层组织，不重复原生能力

| 层 | 必须覆盖的 ReqWS 契约 | 不做 |
|---|---|---|
| TS/Kotlin 共用 fixture | all/selected/empty、ID 求交、协议/非法数据、root/binding identity | 多语言构建文件识别。 |
| Desktop unit/integration | 原子选择写入、冲突、路径/入口、活动门禁、IPC/UI、本版成员/其他编辑器职责正确 | 重复原生 GoLand 或 Git 全套行为。 |
| Kotlin unit/platform | root ownership、用户子配置、恢复与并发、PFI、shell 精确排除、tree provider | 用 Internal 诊断替代公开成功门禁。 |
| 必要 GUI | Desktop 自动同步、真实 Project 节点、冷启动、用户额外 root/普通项目 | 搜索、语言补全/引用、Go test/run/debug、性能长测。 |

S0–S4 当步执行各自直接回归；最终候选运行 `npm run check`、按本需求已调整为唯一 GO-262.9437.286 目标的 `npm run check:goland` 和必要打包。删除 261/历史版本矩阵，不跑旧格式迁移、双模式、旧插件混用或降级/回退测试。当前目标 SDK 环境缺失则记录 BLOCKED，不通过旧版本成功替代。

自动化应覆盖具体故障，不建立无收益的巨大组合矩阵：选择损坏不变空、bad binding 无写、borrowed/marker/用户子项保护、prepared intent 恢复、跨 JVM 互斥、generation/cancel/dispose、安全路径与无模型差异的排除重读。已存在的有效安全回归保留；零执行/全跳过不是通过。

## 3. 本地环境与夹具

验证产品候选，不安装旧 spike 当作正式实现。Desktop 使用明确的临时 userData；GoLand config/system/plugins/log 全部在唯一 TEST_RUN 下。安装测试 ZIP、启动和完整重启测试 IDE 需明确授权；不关闭用户日常 IDE。按 manual-only 安装规范记录 exact 工件确认，摘要只留本地确认/测试输出，不写文档台账。

夹具使用三个本地独立 `.git` 仓库和普通文本，不需要 go.mod/SDK/账号。通过 Desktop 正常流程建立 ready workspace，必要时使用测试专用本地 Git 输入；不得向生产 URL 规则加入本地路径后门。无法用生产规则构造离线夹具时，可用已有测试 harness 构造合法完整 manifest/本地目录，但选择保存与打开必须走真实 Desktop 路径，并注明成员创建未在 GUI 重测。

```text
TEST_RUN/
  workspace/
    .reqws/workspace.json
    .reqws/ide/goland/reqws-project.json
    .reqws/ide/goland/shell-probe.txt
    repo1/probe-folder/repo1-probe.txt
    repo2/probe-folder/repo2-probe.txt
    repo3/probe-folder/repo3-probe.txt
    notes/notes-probe.txt
  user-extra/extra-folder/user-extra-probe.txt
  unbound-project/.reqws/ide/goland/shell-probe.txt
  evidence/
```

上图是最终夹具布局，不是要求预先创建 shell。G1 首次准备前只准备成员/仓库和 notes；由 Desktop 创建合法入口与绑定后，再写 shell-probe.txt。不得提前创建无绑定 shell，或手写 binding 来绕过首次创建流程。

shell/late-shell 文件是专门用于防止“空目录假通过”的夹具。必须保留真实文件位置，不移入 `.idea`、不改名、不删除、不添加忽略规则。测试进程只访问这些夹具。用文本比较确认既有 probe 未修改，结果留在测试输出，不生成提交到仓库的摘要清单。

## 4. 必要 GUI 用例

### G1 唯一入口与两仓库

在 Desktop 详情选择 repo1、repo2 并保存，准备唯一的 GoLand 入口；在其创建的合法 shell 中放入 shell-probe.txt，再从 Desktop 打开。等待插件本轮收敛，在普通 Project 面板展开每个仓库的 probe-folder，确认 repo1-probe.txt、repo2-probe.txt 可见；完整查看根列表，repo3/notes/shell 不作为项目内容节点出现。取一份清楚包含父层级的截图。

只记录 Desktop “已保存”和插件“已同步”的区别；不能用手工 Add Content Root 替代本用例的自动投影。缺失插件/非法本版输入主要由自动化检查覆盖，不安装历史版本或测试混用。

### G2 暂不加载、空集合和恢复

仅在 Desktop 修改集合，依次 `2 → 1 → 0 → 2`。每步等自动同步，不先点 Sync Now 掩盖 watcher 失效。Project 面板显示对应仓库，并展开剩余/恢复仓库的 probe-folder 查看文件；零集合时没有受管仓库/shell 节点，允许 Libraries/Scratches 和其他非仓库平台节点。

每一步用限定磁盘断言确认被移出仓库仍存在；需求 M、分支、VS Code/Cursor 工作区未因选择变化被重写。受管集合数量由模型断言辅助，不能数全部 Content Roots 或仅看 UI summary。

### G3 显示开关与运行中新文件

记录 Project → Options → Appearance → Excluded Files 原值，真实切换开/关并恢复。两态都展开 repo1/probe-folder 查看 repo1-probe.txt，shell 节点不能重新出现。

运行中在 repo1/probe-folder 新建 late-repo.txt，并在 shell 新建 late-shell.txt。等待普通 VFS/插件更新后，在 Project 面板展开 repo1/probe-folder 确认 late-repo.txt 出现；shell 仍没有可展开入口。文件系统断言证明 late-shell.txt 真实存在，平台 PFI 测试证明其 excluded 边界。不得用搜索代替任一 GUI 观察。

### G4 用户额外 root、显式空集合和冷启动

在测试 IDE 的 Project Structure 中将 TEST_RUN/user-extra 添加到 ReqWS 承载 module（此处手动添加仅构造用户配置，不替代 Desktop 加载操作）。从 Desktop 清空受管选择，Project 面板展开 user-extra/extra-folder，确认 user-extra-probe.txt 仍可见；受管仓库退出。

正常保存/关闭测试项目再打开，随后完整退出**测试 IDE 进程**并重新启动同一隔离环境和入口。不要退出日常 IDE。冷启动先只读观察自动恢复结果，不手工 reapply；再次在 Project 面板核对 user-extra 文件保留、受管集合仍为空、shell 不可见。再从 Desktop 恢复两仓库，展开三个目录查看各自文件。

项目关闭重开和进程重启分别记录，不能以窗口消失代替进程退出。检查既有 `.idea` 和用户额外 content 条目没有被覆盖；无需测试所有 IDE 自定义配置类型。

### G5 用户 root 覆盖一个未加载仓库

在隔离项目中由用户/操作者显式加一个非受管 repo3 root（或另一个可明确识别的覆盖 root），确保它未取得 ReqWS claim。Desktop 保持只选 repo1/2。Project 面板能展开 repo3/probe-folder 看到 repo3-probe.txt；插件解释该仓库仍被用户 root 包含，不报完全卸载，不删除用户条目。只做这一最小覆盖例，其他关系由模型测试覆盖。

### G6 绑定错误恢复与无绑定项目

在临时夹具中制造一个绑定/选择读取错误，验证没有把失败当空集合并删除上次有效 roots，插件显示错误。恢复原文件后自动重核；Project 面板重新展开已加载仓库确认其文件。身份错误与单纯 selection 错误的不同缓存撤销行为由平台测试覆盖，不要求大量手工篡改。

打开独立 unbound-project，在普通 Project 面板展开 `.reqws → ide → goland`，确认 shell-probe.txt 正常可见，插件不创建受管 module、不过滤同名目录。不得借助在 ReqWS 项目里切换自定义 scope 代替无绑定项目。

### G7 只读 VCS 观察与收尾

只观察 Directory Mappings 并记录平台当前结果；不修改 mappings，不要求 Git Log/Commit 随加载集合自动缩小，不把 repo3 未被检测当作其 VCS 卸载证据。恢复显示选项原值，确认 fixture 目录与普通文件保留；保留测试现场供检查，不自动删除。

G1–G6 是 GUI 核心场景，G7 是同一轮收尾，不扩展为完整 Git 验收。若需要用户协助，逐项标明，不写成全自动通过。

## 5. 自动化与 GUI 的完成口径

增加一次静态删除审查：核对旧 workspace-root/exclude adapter、原目录 UI/IPC、版本分支、旧 ledger/迁移 reader 与历史矩阵已从产品注册和可执行代码中移除。按引用审查，不要求历史 Git/归档报告中不存在这些词。删除仅测试废弃行为的用例；当前路径的安全、用户 root 保护和崩溃恢复仍需真实测试，不能把“无需兼容”当作跳过理由。

普通原目录无本版 shell 绑定时保持不受管，由既有无绑定测试覆盖；不再创建两窗口/旧配置迁移的专用 GUI 场景。准备入口不删除未知磁盘条目由自动化覆盖，不测试旧配置是否继续可用。


PFI/模型测试需要覆盖 shell 非 content/excluded、loaded root 的真实归属、移除后用户 root 覆盖分类以及实时状态失效。直接检查目录/普通子文件即可，不运行搜索服务或对默认搜索做穷举。已在历史报告验证的搜索机制不改写为“本轮复测通过”。

一次真实 GUI 截图不能证明磁盘不存在或全部索引正确；反之，程序 API 断言也不能证明普通 Project 面板真的显示/隐藏。最终报告把两类结果分别列出，不重复制造同一场景的多套搜索截图。

## 6. 执行记录和停止条件

每个场景记录：候选源码 commit/实际 diff、产品/插件版本与工件入口、IDE build、输入加载集合、Project 面板实际父子节点、自动化辅助结果、截图、操作者及用户协助、通过/失败/未运行/阻塞。不得先预填通过数量或粘贴历史 GO。

GUI 工具不可用、无法确认前台窗口或刷新超时时暂停该操作；同一步最多两次有理由的重试，不改用内部 API 或命令截图冒充 computer use。必要授权缺失时只做安全自动化，GUI 标 BLOCKED。

API 规范未关闭、错误 binding 被接管、用户配置/目录被删除、空集合回填、假 Synced 或无限循环均阻塞产品 GO。语言插件报错仅在证据表明违反 ReqWS 平台契约时计为本需求问题，不追加语言全套验收。

文档阶段只运行 `npm run docs:check` 和 diff/链接检查，不构建或安装软件。实现候选只有在上述核心检查真实完成且独立复核通过后才能记录产品验收；本轮文档推送和既有 GO FOR IMPLEMENTATION 均不能替代它。
