---
title: 文档调整逐文件处置清单
type: technical-design
status: draft
updated: 2026-09-27
---

# 逐文件处置清单

本清单覆盖审计基线的 107 份 Markdown 和 21 项资源。它是一次性迁移审计附件，不是新增的全库常驻索引；后续新增文件不回填本快照。普通任务仍从领域入口按需阅读。

基线：`99d9c1bb174c1c9c8e8fc95026d02805fb87f8d9`。表中目标是拟议归属，不表示页面已经存在；实际迁移前仍需核对当前源码、测试和入链。P1–P4 均为待实施阶段，见[实施计划](implementation-plan.md)。所有 Markdown 已全文阅读，资源仅清点归属，不作视觉或可执行性验收。

## 处置含义

“保留证据”保留实际结果、首次失败、候选、环境和授权范围；不把 active 报告升级为当前实现。“迁 owner”先写完整新事实归属并修复引用，最后才收缩原页。冻结 reference 不新增 metadata、不移动或改写。

## Markdown：107 份

### docs/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../../README.md) | 总路由 | 保留四类导航并补当前架构入口；按任务说明下一站，不列全库文件。 | `本文件`；P1 |

### docs/changes/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../README.md) | 需求主题索引 | 保留 13 个既有主题；移除父级阶段计数，只指向局部入口。 | `本文件`；P1/P4 |

### docs/guides/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../../guides/README.md) | 分类索引 | 保留用户指南入口，补 testing/release 的直属路由。 | `本文件`；P1/P4 |
| [installation.md](../../guides/installation.md) | 安装教程 | 保留用户安装、信任及自更新限制；发布侧实现只链接。 | `本文件 + release 入口`；P4 |
| [user-guide.md](../../guides/user-guide.md) | 用户任务教程 | 保留图文、成功条件与恢复说明；长文补可直接跳转的任务目录，不重写为架构手册。 | `本文件`；P4 |
| [goland-plugin-guide.md](../../guides/goland-plugin-guide.md) | 插件用户教程 | 保留独立入口和加载选择；链接当前兼容 owner，旧截图按其真实来源核查后处理。 | `本文件 + IDE 契约`；P2/P4 |
| [development-guide.md](../../guides/development-guide.md) | 混合开发指南 | 保留起步与常用命令；迁出契约、专项操作和按次验收。 | `architecture/*、guides/testing/*、guides/release/*`；P2/P4 |
| [agent-workflow.md](../../guides/agent-workflow.md) | 协作入口及通用政策 | 保留任务流程；通用模型/effort、所有权政策原义迁单一 owner，并修全部入链。 | `standards/agent-collaboration.md`；P1 |
| [actions-cache-policy.md](../../guides/actions-cache-policy.md) | 缓存政策/配置解释 | 保留当前政策；区分配置契约与历史容量/耗时，不借缓存优化改变验收。 | `本文件`；P4 |
| [actions-cache-cleanup.md](../../guides/actions-cache-cleanup.md) | 操作教程 | 保留有授权前提的清理和失败处理；不从旧例子推导可删除当前缓存。 | `本文件`；P4 |
| [images/README.md](../../guides/images/README.md) | 指南资源归属 | 保留图文映射与 fixture/示意来源；迁移只修真实入链，截图真实性另验。 | `本文件`；P4 |

### docs/standards/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../../standards/README.md) | 规则索引 | 补协作/回归直属入口；不复制全部规则。 | `本文件`；P1 |
| [documentation-standard.md](../../standards/documentation-standard.md) | 治理 owner | 保留按需产出；修权威语义、元数据/锚点/读取/冻结规则并与 checker 一起验证。 | `本文件`；P1/P3 |
| [ide-plugin-development-testing.md](../../standards/ide-plugin-development-testing.md) | IDE 当前规则 | 保留 floor262/无上限/JPS 精确例外等；共用证据规则和具体命令分离。 | `本文件 + regression-testing + local-ide`；P2/P4 |
| [templates/README.md](../../standards/templates/README.md) | 模板路由 | 保留按需模板选择，说明新字段及短文豁免，不要求每次全套。 | `本文件`；P1/P4 |
| [templates/requirement.md](../../standards/templates/requirement.md) | 需求模板 | 补用途/边界/authority 示例；不添加空进度表或每次必填全套清单。 | `本文件`；P1/P4 |
| [templates/technical-design.md](../../standards/templates/technical-design.md) | 设计模板 | 区分 proposal/current，增加 owner/必要依赖/源码入口，避免完整 API 复写。 | `本文件`；P1/P4 |
| [templates/test-plan.md](../../standards/templates/test-plan.md) | 计划模板 | 区分计划与实际执行，保留环境/候选/负例和未覆盖条件。 | `本文件`；P1/P4 |
| [templates/delivery.md](../../standards/templates/delivery.md) | 交付模板 | 明确具体交付范围及候选；不把 active 当已发布，不要求纯文档补交付报告。 | `本文件`；P1/P4 |

### docs/reference/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../../reference/README.md) | 冻结输入 | 原文原位保留，不补 metadata、不按当前实现改写；当前入口明确标作历史。 | `原位置（禁止写）`；全部阶段 |
| [reqws-desktop-mvp-technical-design.md](../../reference/reqws-desktop-mvp-technical-design.md) | 冻结输入 | 原文原位保留，不补 metadata、不按当前实现改写；当前入口明确标作历史。 | `原位置（禁止写）`；全部阶段 |

### docs/changes/code-humanizer-review/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../code-humanizer-review/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P4 |
| [remediation.md](../code-humanizer-review/remediation.md) | 方案/当前契约混合 | 保留 R01–R06/C01–C02 修复关系和候选证据；当前稳定行为迁 owner，不重开已修复历史问题。 | `architecture/workspace.md 与相应当前源码/测试`；P4 |
| [review-2026-09-21.md](../code-humanizer-review/review-2026-09-21.md) | 候选/历史证据 | 保留 30ea9432 固定基线、六缺陷/两债务和探针局限；不得由报告 active 推断当前仍有这些缺陷。 | `原位置（evidence/historical）`；P4 |

### docs/changes/cursor-ide-launch/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../cursor-ide-launch/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P4 |
| [technical-design.md](../cursor-ide-launch/technical-design.md) | 方案/当前契约混合 | 保持紧凑设计；提取重复命令到现有指南（需要时），不为了统一格式再拆多个空页面。 | `现有用户指南与启动实现（保持小文档）`；P4 |
| [verification-2026-08-14.md](../cursor-ide-launch/verification-2026-08-14.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |

### docs/changes/goland-plugin-support/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../goland-plugin-support/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [requirements.md](../goland-plugin-support/requirements.md) | 方案/当前契约混合 | 保留用户承诺与非目标；标清哪些范围后续替代，当前跨层行为只在 owner 维护。 | `architecture/ide-integration.md + compose-ui.md`；P2 |
| [technical-design.md](../goland-plugin-support/technical-design.md) | 方案/当前契约混合 | 先迁仍有效的语言无关/所有权规则；修复 superseded header 与正文 active 矛盾；旧方案不得默认压过后续契约。 | `architecture/ide-integration.md + compose-ui.md`；P2 |
| [testing/README.md](../goland-plugin-support/testing/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P2 |
| [testing/test-plan.md](../goland-plugin-support/testing/test-plan.md) | 验收要求 | 保留现有重定向及旧锚点价值；只复核是否指向最终 current owner，不恢复旧完整 Go/Git 验收。 | `原位置 + 测试标准/操作指南`；P2 |
| [testing/verification-2026-09-13.md](../goland-plugin-support/testing/verification-2026-09-13.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [ui/README.md](../goland-plugin-support/ui/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P2 |
| [ui/tool-window-visual-design.md](../goland-plugin-support/ui/tool-window-visual-design.md) | 方案/当前契约混合 | 核对旧 Swing 视觉规范在 Compose 后的剩余效力；先保留可复用产品要求，再明确历史样式/原型身份。 | `architecture/ide-integration.md + compose-ui.md`；P2 |

### docs/changes/github-actions-ci-release/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../github-actions-ci-release/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [delivery.md](../github-actions-ci-release/delivery.md) | 候选/历史证据 | 区分常青发布说明与 v0.1.1 旧范围；当前操作迁 release，原版本交付留 evidence。 | `guides/release/* + 原记录`；P2 |
| [requirements.md](../github-actions-ci-release/requirements.md) | 方案/当前契约混合 | 保留用户承诺与非目标；标清哪些范围后续替代，当前跨层行为只在 owner 维护。 | `guides/release/*`；P2 |
| [technical-design.md](../github-actions-ci-release/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `guides/release/*`；P2 |
| [testing/README.md](../github-actions-ci-release/testing/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P2 |
| [testing/test-plan.md](../github-actions-ci-release/testing/test-plan.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P2 |
| [testing/verification-2026-09-13.md](../github-actions-ci-release/testing/verification-2026-09-13.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |

### docs/changes/global-settings/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../global-settings/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P4 |
| [technical-design.md](../global-settings/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `architecture/settings-and-i18n.md`；P4 |
| [testing/README.md](../global-settings/testing/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P4 |
| [testing/verification-2026-08-13.md](../global-settings/testing/verification-2026-08-13.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |

### docs/changes/goland-compose-rebuild/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../goland-compose-rebuild/README.md) | 局部索引 | 保留 draft 与实施/验收状态分开的事实；缩短 S0–S4 计数，指向当前 UI owner 和精确候选报告。 | `本文件`；P2 |
| [product-design.md](../goland-compose-rebuild/product-design.md) | 方案/当前契约混合 | 当前状态、intent 与宿主边界迁稳定 UI 契约；后续方案选择、G0–G4 和候选相关证据保持范围。 | `architecture/compose-ui.md`；P2 |
| [research.md](../goland-compose-rebuild/research.md) | 调研与来源 | 保留当时 SDK/源码调研和替代方案；不将固定基线调查称为上游当前事实。 | `architecture/compose-ui.md`；P2 |
| [s0-verification.md](../goland-compose-rebuild/s0-verification.md) | 候选/历史证据 | 保留 PoC/探针条件与性能观察，不升格为当前产品结果，也不恢复已撤销的性能门槛。 | `原位置（evidence/historical）`；P2 |
| [s1-verification.md](../goland-compose-rebuild/s1-verification.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [s2-verification.md](../goland-compose-rebuild/s2-verification.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [s3-verification.md](../goland-compose-rebuild/s3-verification.md) | 候选/历史证据 | 保留全部验证事实；修复指向 S2 的失效章节锚点，并补 fragment 负例。 | `原位置（evidence/historical）`；P2 |
| [s4-verification.md](../goland-compose-rebuild/s4-verification.md) | 候选/历史证据 | 保留同一候选、三套原生验收、明确 VoiceOver 豁免与性能非门禁范围；不传播为任意新 ZIP 通过。 | `原位置（evidence/historical）`；P2 |
| [subagent-plan.md](../goland-compose-rebuild/subagent-plan.md) | 阶段计划 | 保留当次分域/文件/资源分工；通用模型政策只引用唯一 owner，不复制另一份白名单。 | `原位置 + 通用规范链接`；P2 |
| [tasks/README.md](../goland-compose-rebuild/tasks/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P2 |
| [tasks/s0-feasibility.md](../goland-compose-rebuild/tasks/s0-feasibility.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [tasks/s1-state-and-host.md](../goland-compose-rebuild/tasks/s1-state-and-host.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [tasks/s2-compose-ui.md](../goland-compose-rebuild/tasks/s2-compose-ui.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [tasks/s3-test-automation.md](../goland-compose-rebuild/tasks/s3-test-automation.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [tasks/s4-cleanup-and-acceptance.md](../goland-compose-rebuild/tasks/s4-cleanup-and-acceptance.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [technical-design.md](../goland-compose-rebuild/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `architecture/compose-ui.md`；P2 |
| [test-plan.md](../goland-compose-rebuild/test-plan.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P2 |

### docs/changes/ide-plugin-compatibility-automation/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../ide-plugin-compatibility-automation/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [implementation-2026-09-21.md](../ide-plugin-compatibility-automation/implementation-2026-09-21.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [implementation-plan.md](../ide-plugin-compatibility-automation/implementation-plan.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [local-integration.md](../ide-plugin-compatibility-automation/local-integration.md) | 当前操作/候选示例混合 | 迁出当前命令、profile/ZIP/报告流程；旧候选与验收协议版本保留原适用范围。 | `IDE 标准 + guides/testing/local-ide.md`；P2 |
| [local-verification-2026-09-22.md](../ide-plugin-compatibility-automation/local-verification-2026-09-22.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [technical-design.md](../ide-plugin-compatibility-automation/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `IDE 标准 + guides/testing/local-ide.md`；P2 |
| [verification-2026-09-21.md](../ide-plugin-compatibility-automation/verification-2026-09-21.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |

### docs/changes/goland-workspace-loading/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../goland-workspace-loading/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [implementation-2026-09-19.md](../goland-workspace-loading/implementation-2026-09-19.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [implementation-plan.md](../goland-workspace-loading/implementation-plan.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P2 |
| [requirements.md](../goland-workspace-loading/requirements.md) | 方案/当前契约混合 | 保留用户承诺与非目标；标清哪些范围后续替代，当前跨层行为只在 owner 维护。 | `architecture/ide-integration.md`；P2 |
| [technical-design.md](../goland-workspace-loading/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `architecture/ide-integration.md`；P2 |
| [test-plan.md](../goland-workspace-loading/test-plan.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P2 |
| [verification-basis.md](../goland-workspace-loading/verification-basis.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |

### docs/changes/ide-plugin-language-decoupling/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../ide-plugin-language-decoupling/README.md) | 局部索引 | 保留已经明确的设计/任务/验收职责；只补当前 owner 和证据范围，不破坏有用的局部组织。 | `本文件`；P4 |
| [tasks/README.md](../ide-plugin-language-decoupling/tasks/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P4 |
| [tasks/s1-core-sync-decoupling.md](../ide-plugin-language-decoupling/tasks/s1-core-sync-decoupling.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P4 |
| [tasks/s2-scheduling-dependency-cleanup.md](../ide-plugin-language-decoupling/tasks/s2-scheduling-dependency-cleanup.md) | 阶段计划 | 保留该阶段范围/依赖/验收；通用政策只链接；计划完成不使其内容变成常青运行说明。 | `原位置 + 通用规范链接`；P4 |
| [technical-design.md](../ide-plugin-language-decoupling/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `architecture/ide-integration.md`；P4 |
| [testing/README.md](../ide-plugin-language-decoupling/testing/README.md) | 局部索引 | 只解释本目录职责、直接子项与当前/历史阅读方向；不复述完整子文档。 | `本文件`；P4 |
| [testing/acceptance-2026-09-19.md](../ide-plugin-language-decoupling/testing/acceptance-2026-09-19.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |
| [testing/final-acceptance.md](../ide-plugin-language-decoupling/testing/final-acceptance.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P4 |
| [testing/test-plan.md](../ide-plugin-language-decoupling/testing/test-plan.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P4 |

### docs/changes/playwright-regression-automation/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../playwright-regression-automation/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P4 |
| [implementation-2026-09-26.md](../playwright-regression-automation/implementation-2026-09-26.md) | 候选/历史证据 | 保留各阶段首次失败、JPS 例外来源、精确 ZIP 与宿主身份；不得把局部/旧阶段成功汇总成新候选通过。 | `原位置（evidence/historical）`；P4 |
| [manual-inventory.md](../playwright-regression-automation/manual-inventory.md) | 当前逐项替代裁决 | 保留唯一裁决 owner 和原 ID；数字/运行细节链接证据，禁止父级重写另一份 49 项裁决。 | `原位置（current 裁决）`；P4 |
| [subagent-plan.md](../playwright-regression-automation/subagent-plan.md) | 阶段计划 | 保留当次分域/文件/资源分工；通用模型政策只引用唯一 owner，不复制另一份白名单。 | `原位置 + 通用规范链接`；P4 |
| [technical-design.md](../playwright-regression-automation/technical-design.md) | 方案/当前契约混合 | 初始缺口/S0–V 计划保留时间范围；当前真实链路与隔离规则迁标准/操作指南；80% 仍非已证实整体收益。 | `回归标准 + guides/testing/electron.md`；P4 |
| [verification-v-2026-09-26.md](../playwright-regression-automation/verification-v-2026-09-26.md) | 候选/历史证据 | 保留 v7 两轮、负向、有限成本样本及原生缺口；当前裁决仍由 manual-inventory 独占。 | `原位置（evidence/historical）`；P4 |

### docs/changes/macos-self-update/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../macos-self-update/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [implementation-2026-09-19.md](../macos-self-update/implementation-2026-09-19.md) | 候选/历史证据 | 保留多次失败、例外 tag 授权、身份配置与 U6/U7/U9 缺口的当时状态；绝不回填为当前发布结果。 | `原位置（evidence/historical）`；P2 |
| [technical-design.md](../macos-self-update/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `architecture/self-update.md + guides/release/macos-signing.md`；P2 |

### docs/changes/goland-plugin-marketplace/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../goland-plugin-marketplace/README.md) | 局部索引 | 保留主题入口；链接当前契约 owner 与原始证据，不再汇总全部阶段计数。 | `本文件`；P2 |
| [bootstrap-and-operations.md](../goland-plugin-marketplace/bootstrap-and-operations.md) | 当前操作/事件授权混合 | 日常配置与重试迁指南；§6 事故、Token 恢复和一次性 tag 授权留本包按次记录。 | `guides/release/marketplace.md`；P2 |
| [history-retry-fix-2026-09-22.md](../goland-plugin-marketplace/history-retry-fix-2026-09-22.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [implementation-2026-09-20.md](../goland-plugin-marketplace/implementation-2026-09-20.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P2 |
| [requirements.md](../goland-plugin-marketplace/requirements.md) | 方案/当前契约混合 | 保留用户承诺与非目标；标清哪些范围后续替代，当前跨层行为只在 owner 维护。 | `guides/release/marketplace.md`；P2 |
| [technical-design.md](../goland-plugin-marketplace/technical-design.md) | 方案/当前契约混合 | 逐章节核对当前事实和当时计划；当前事实先迁 owner，原取舍及初始范围保留并标明适用性。 | `guides/release/marketplace.md`；P2 |
| [test-plan.md](../goland-plugin-marketplace/test-plan.md) | 验收要求 | 保留本需求独有断言与历史范围；当前通用测试规则/命令只链接；按裁决处理已等价替代项。 | `原位置 + 测试标准/操作指南`；P2 |

### docs/changes/mvp/

| 文件 | 当前职责/问题 | 拟议处理 | 归属/阶段 |
|---|---|---|---|
| [README.md](../mvp/README.md) | 局部索引 | 保留 archived 历史索引；不得恢复为当前实现入口。 | `本文件`；P4 |
| [delivery.md](../mvp/delivery.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |
| [testing/README.md](../mvp/testing/README.md) | 局部索引 | 保留 archived 历史索引；不得恢复为当前实现入口。 | `本文件`；P4 |
| [testing/verification-2026-08-13.md](../mvp/testing/verification-2026-08-13.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |
| [traceability.md](../mvp/traceability.md) | 候选/历史证据 | 保留环境、候选、失败与结果；只补适用范围及必要导航，不回填当前通过或复制历史授权。 | `原位置（evidence/historical）`；P4 |

## 资源：21 项

以下仅记录资源用途与迁移边界，未重新执行脚本、渲染 HTML、逐图检查或解压 ZIP。代码探针属于证据辅助资源，不算 Markdown 全文审计的运行结果。

| 文件 | 归属与处理 |
|---|---|
| [docs/guides/images/add-repository.png](../../guides/images/add-repository.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/create-workspace-fields.png](../../guides/images/create-workspace-fields.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/create-workspace-repositories.png](../../guides/images/create-workspace-repositories.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/goland-loading-selection.png](../../guides/images/goland-loading-selection.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/manage-workspace.png](../../guides/images/manage-workspace.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/settings-directories.png](../../guides/images/settings-directories.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/workspace-model.svg](../../guides/images/workspace-model.svg) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/guides/images/workspaces-overview.png](../../guides/images/workspaces-overview.png) | 指南插图/示意图；保留 images/README 归属和具体操作页入链。是否更新图片另按当前 UI 实测，不由此次清点判断。 |
| [docs/reference/original-reqws-desktop-mvp-handoff.zip](../../reference/original-reqws-desktop-mvp-handoff.zip) | 冻结原始交接/原型资源，原位保留，不重命名、不覆盖；只作历史输入。 |
| [docs/reference/reqws-desktop-mvp-ui-preview.png](../../reference/reqws-desktop-mvp-ui-preview.png) | 冻结原始交接/原型资源，原位保留，不重命名、不覆盖；只作历史输入。 |
| [docs/reference/reqws-desktop-mvp-ui-prototype.html](../../reference/reqws-desktop-mvp-ui-prototype.html) | 冻结原始交接/原型资源，原位保留，不重命名、不覆盖；只作历史输入。 |
| [docs/changes/code-humanizer-review/reproduce-probes.mjs](../code-humanizer-review/reproduce-probes.mjs) | 固定历史基线的复现探针；保留与审计报告/整改的关系，不在文档整理中执行或改为当前行为断言。 |
| [docs/changes/goland-plugin-support/ui/desktop-repository-readded.jpg](../goland-plugin-support/ui/desktop-repository-readded.jpg) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/repository-tooltip-dark.png](../goland-plugin-support/ui/repository-tooltip-dark.png) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/repository-tooltip-light.png](../goland-plugin-support/ui/repository-tooltip-light.png) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/tool-window-prototype.png](../goland-plugin-support/ui/tool-window-prototype.png) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/tool-window-registry-failure.jpg](../goland-plugin-support/ui/tool-window-registry-failure.jpg) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/tool-window-synchronized-dark.jpg](../goland-plugin-support/ui/tool-window-synchronized-dark.jpg) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/goland-plugin-support/ui/tool-window-synchronized-light.jpg](../goland-plugin-support/ui/tool-window-synchronized-light.jpg) | 原插件视觉/原型证据；标明旧实现与采集上下文。Compose 当前说明不得自动把它当现行截图，迁移时不删除证据。 |
| [docs/changes/global-settings/testing/settings-en-us.png](../global-settings/testing/settings-en-us.png) | 2026-08-13 设置验收配图，跟随原报告保持历史身份；不作为新候选或当前全部语言页面的通过证据。 |
| [docs/changes/global-settings/testing/settings-zh-cn.png](../global-settings/testing/settings-zh-cn.png) | 2026-08-13 设置验收配图，跟随原报告保持历史身份；不作为新候选或当前全部语言页面的通过证据。 |

## 迁移时的复核

作者在每个实际迁移提交中说明已处理文件、来源章节与目标章节、入链变化和未完成项。不要在此追加第二份任务进度或源文件 checksum 清单；本清单保留审计时的建议，执行状态由实施 PR/阶段记录负责。
