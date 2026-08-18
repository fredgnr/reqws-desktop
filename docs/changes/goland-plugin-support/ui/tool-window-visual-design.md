---
title: GoLand Tool Window 视觉设计与实现对照
type: technical-design
status: active
updated: 2026-08-18
---

# GoLand Tool Window 视觉设计与实现对照

本文把 ReqWS Tool Window 的视觉目标固化为可实现、可截图对照的界面契约；原型用于指导实现，真实截图只代表待后续验收的候选界面。

## 1. 设计目标

- 保持 GoLand 2026.1 原生 Swing/JetBrains 主题观感，不引入 Web 风格仪表盘或自绘主题系统。
- 在常用窄 Tool Window 中先回答“当前是否同步成功、哪个工作区、有哪些活动仓库”，再提供诊断和恢复动作。
- 同步、降级、错误和 Safe Mode 同时使用文字与颜色表达；颜色只作辅助，不成为唯一状态信号。
- 仓库行保持紧凑且顶部对齐，避免旧实现把少量仓库拉伸成大块空白。
- 保留 `Sync Now`、`Open Manifest File` 和 `Copy Diagnostics` 的现有入口、资源键和安全边界，不新增按钮；`Sync Now` 只重放 Project Model reconcile 并重新检查 VCS，不自动修改 Directory Mappings。
- Git Root 缺失、同路径 VCS 冲突或 retained mapping 以文字说明用户前往 Settings → Version Control → Directory Mappings 手动处理；插件不以视觉状态暗示自动修复。
- manifest 提供的 workspace、branch 和 repository name 继续禁用 Swing HTML 自动渲染，并在空间不足时保留可访问的完整提示。

## 2. 同步态原型

![ReqWS Tool Window 同步态原型](tool-window-prototype.png)

原型由内置 ImageGen 的 `ui-mockup` 模式生成，并以旧版真实 GoLand 截图作为结构参考。提示词要求只优化右侧 ReqWS Tool Window，使用原生浅色 JetBrains 密度，形成状态标题、工作区摘要、三行仓库列表、摘要和分层操作区；禁止渐变、玻璃拟态、图表、装饰插画和额外操作。生成图中的 GoLand 外壳仅作视觉上下文，文字或项目树细节不作为产品事实。该图只定义布局；它没有展示 2026-08-18 新增的手动 Git Root 配置语义，不能作为该状态的验收证据。

## 3. 实施映射

| 原型区域 | 实现约束 |
|---|---|
| 状态标题 | Tool Window 自带标题保持不变；面板首行以内容宽度的状态徽标靠右展示，不使用满宽状态边框，状态色由当前 JetBrains 主题派生。 |
| 工作区摘要 | 使用带主题边界的独立卡片；workspace 名称作为主信息，branch 和活动仓库数量作为次级信息；长文本截断时 tooltip 保留完整值。 |
| 仓库列表 | 使用独立卡片；header 左侧为标题、右侧为纯数字 count；每行固定紧凑高度并带主题分隔线，包含 repository name、目录状态和 Git Root configured/需手动配置等文字语义及辅助色图标；1–6 行不显示滚动条，7 行起固定显示六行并在卡片内滚动。 |
| 诊断摘要 | 显示 digest、保留上次有效模型或无 manifest 提示；VCS 差异写明 Settings → Version Control → Directory Mappings，错误码继续与状态文字同屏。 |
| 操作区 | `Sync Now` 为全宽主操作，但含义是“重新同步项目模型并检查 VCS”；另两个动作居中显示为次级链接，并保持原有 enable 规则。 |

布局必须随 Tool Window 宽度伸缩，不依赖固定像素宽度；滚动只发生在仓库区域，摘要和操作区始终可达。浅色与深色主题都使用平台颜色，不硬编码仅适配单一主题的背景或前景。

## 4. 状态与无障碍

| 状态 | 视觉语义 | 必须保留的文字语义 |
|---|---|---|
| synchronized | 成功色辅助标记 | `Synced` / `已同步` |
| reading、synchronizing | 信息色或进度语义 | 当前读取或同步文字 |
| degraded、VCS 待手动配置、Safe Mode | 警示色辅助标记 | 部分可用、Directory Mappings 手动步骤或信任提示 |
| error | 错误色辅助标记 | `Error` / `错误` 与稳定错误码 |
| inactive、disposed | 中性色 | 不可用或已关闭文字，且操作禁用 |

按钮保持原生键盘焦点和可访问名称。状态圆点属于装饰，不取代相邻文字；repository missing 仍显示完整的 `Directory Missing` / `目录缺失`。复制诊断后的反馈继续出现在摘要区域，不使用自动消失且不可复核的纯颜色提示。

## 5. 实现候选与后续验收

![ReqWS Tool Window 同步态实现候选（待验收）](tool-window-implementation-2026-08-17.png)

该图来自日常使用的 GoLand 2026.1.3（`GO-261.25134.147`）浅色主题，使用固定的无凭据 GUI fixture；插件冷启动后状态为 `Synced`，同屏显示紧凑状态徽标、带边界的工作区摘要、标题与右侧计数分列的三行仓库卡、digest、全宽主按钮和居中的次级操作。截图为 1359×768 PNG，对应插件 ZIP SHA-256：`8c79cafbbc507366654c679a05f24dd15a9aabb6e64b39c64b5a910447ff59c8`，截图文件 SHA-256：`ac792019be814cc776f07f4de76b70e36170f63582f1bd0c077d740bdcb6c564`。实现已按原型的空间结构和主次层级收口；状态无法嵌入原生 Tool Window 标题行、平台字体密度和主题底色等差异继续服从 GoLand 原生 chrome，不通过私有 API 或自绘整套主题强行复刻。

这张图片是旧“实现候选，待验收”，不表示 `PASS` 或 `GO`。它只证明 2026-08-17 候选曾在真实日常 GoLand 中加载并呈现 synchronized 布局；不证明当前只读 VCS 契约、手动 Directory Mappings 说明、配置事件自动复核或 `Sync Now` 零 VCS mutation，也尚未覆盖深色主题、错误、降级、Safe Mode、最窄宽度、键盘焦点和关闭项目/退出 IDE 生命周期。正式结论仍须由新的 dated verification 绑定 exact candidate 与 ZIP 哈希，并至少记录 VCS 待配置提示态、用户完成 Directory Mappings 后的同步态及设置页操作证据；既有 `2026-08-17` `NO-GO` 报告保持为历史证据，不因本图而改写。
