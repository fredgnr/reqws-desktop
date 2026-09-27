---
title: Compose S2 界面与组件验证记录（2026-09-27）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S2 界面与组件验证

本记录固定 S2 同一生产屏幕的功能、布局、语义和测试隔离证据；独立审查与最终同候选检查完成，G2 pass、S2 completed。

## 1. 候选和范围

G1 交接为 `166f564`，S2 初始实现与测试为 `89796e7`；`b0e5eb6` 统一源文件空白，`34f1392` 修复真实宿主暴露的合并语义标签，并补充直接摘要状态测试。Factory 继续挂载 [ReqwsScreen](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/compose/ReqwsScreen.kt)，摘要、仓库列表、诊断和三个操作共用一个内容根；组件仅接受不可变数据和事件，不持有 Project/service/VFS，不执行领域写入。旧 Swing 源码与测试仍保留，S4 再删除，不存在运行时 UI 回退开关。

资源 catalog 未改变，全部 UI 文案仍来自 `ReqwsBundle`，没有翻译 delta，也没有重新确认翻译基线。最低版本不变：整个 262 系列，无任何兼容上限。工具链/代表宿主与 [S1 记录](s1-verification-2026-09-27.md)一致。

## 2. 生产行为

- 摘要显示工作区、分支、生命周期、已确认加载数量和诊断；用户字符串仅由普通 Text 渲染，保留完整语义。有限行数的省略配合限宽、限高、可滚动的全文 tooltip；空 workspace/branch 不显示冗余 tooltip。
- 仓库 key 为 `catalogRepositoryId`，本地单选随 ID 重排保留，删除后清除；该选择不向 Desktop 写加载配置。列表按内容增长并在 320dp 上限内滚动，空集合有现有文案，仓库总数和已加载数分别展示。
- 底部固定纵向操作区使用 Jewel 主/次按钮；真实组件事件产生 typed action，禁用状态阻止指针激活，键盘 Tab/Space/Enter 可操作。复制反馈独立显示，错误诊断继续保留。
- 生产颜色与字体来自 IDE 桥接的 Jewel tokens；不再包装第二层主题。Tooltip 需要局部 `androidx.compose.foundation.ExperimentalFoundationApi` opt-in，没有引用 JetBrains `@ApiStatus.Internal` / `@ApiStatus.Experimental`，既有禁用 API 与 Verifier 检查未放宽。

## 3. 已执行检查

[ReqwsScreenTest](../../../integrations/goland/src/composeUiTest/kotlin/com/reqws/goland/ui/ReqwsScreenTest.kt)直接调用生产屏幕和生产全文组件。[测试主题](../../../integrations/goland/src/composeUiTest/kotlin/com/reqws/goland/ui/ReqwsTestTheme.kt)只提供公开 Jewel 测试 tokens，不复制屏幕，不启动完整 IDE，也不引入 standalone Jewel。

| 检查 | 真实结果 |
|---|---|
| `composeUiTest` | 最终 18 用例，0 failure/error/skipped；JBR/Skiko 实际渲染。 |
| `test --tests 'com.reqws.goland.ui.*' --tests 'com.reqws.goland.project.TerminalStatePublisherTest'` | 95 用例通过，0 skipped，S1 状态与生命周期回归保持。 |
| `python3 -m unittest discover -s tests/workflows -p 'test_compose_*.py' -v` | 11 用例通过，0 skipped；已扩充本轮组件和宿主代理类的产物拒绝反例。 |
| 受影响配置/隔离入口回归 | `test_plugin_configuration.py` 8、`test_ide_compatibility.py` 16、`test_ci_cache_config.py` 27、`test_local_ide_launcher.py` 8，共 59 用例通过。 |
| `check_compose_artifact.py --archive <明确 ZIP> --version 0.1.7` | pass；正式 JAR 未嵌入宿主库、测试 natives 或已知控制类。 |
| `npm run check:goland` | 最终 `34f1392` 候选 pass；41 类、403 用例，0 failure/error/skipped；编译、禁用符号、结构/descriptor 和新冻结的 7/7 API 目标通过。 |

配置回归首轮因本工作树未安装 `js-yaml`，3 个 suite 报环境错误；通过 `NODE_PATH` 只读复用既有 340d 依赖缓存后重跑通过，没有安装系统工具或修改其他工作树。组件测试首轮补齐 Tooltip 的局部 Compose Foundation opt-in；后续新增空值 hover 测试时修正重复 mouse-enter 注入为 move，最终 18 用例全部执行，不删除失败用例。

独立测试 runtime 使用 S0 验证的 Compose test 1.11.0、coroutines-test 1.10.2、Skiko macOS arm64 0.144.5；仅追加到 `composeUiTest` source set，生产 runtime 不增加这些依赖。S3 仍负责 CI 图形环境、发现/失败传播和报告接线；本轮不修改 CI。

### 实际组件 selectors

下列均为 `com.reqws.goland.ui.ReqwsScreenTest.<method>`，每项 1 个 JUnit 用例，全部通过：

| V-ID | method | 实际断言 |
|---|---|---|
| V08/V09 | `rendersAllActionsWithButtonRoleAndDispatchesTypedEvents` | 三按钮角色/enabled、指针事件和对应 typed action；边界不溢出。 |
| V08/V09 | `disabledActionsRejectPointerEventsAndExposeDisabledSemantics` | 三按钮 disabled 且指针事件不产生动作。 |
| V09 | `keyboardTabSpaceAndEnterActivateTheFocusedProductionButtons` | RequestFocus 后真实组件键盘事件、Tab 顺序与动作。 |
| V07 | `sameNameRowsKeepSelectionByIdThroughReorderAndDeletion` | 同名不同 ID、重排、删行和重新加入；不产生领域 action。 |
| V07 | `emptyListAndConfirmedLoadedCountAreIndependent` | 空集合提示、总数和加载数。 |
| V07 | `largeListScrollsToStableIdsWithoutInventingLoadedCount` | 200 行、滚动到 key、选择身份及固定加载计数。 |
| V08/V09 | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` | 240dp、字面 HTML、emoji/组合字符和超长值；TextLayout/完整语义/控件边界。 |
| V08/V09 | `fullTextTooltipContentWrapsWithoutDroppingSpacesMarkupOrGraphemes` | 生产全文组件的完整值、多行与320×240dp边界。 |
| V08 | `hoverExposesTheActualProductionTooltip` | 实际 hover 弹出生产 tooltip、完整文本。 |
| V08/V09 | `errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback` | 错误与一次反馈共存，重复状态无重复行，新状态清除反馈。 |
| V08/V09 | `longestStatusesAndUserRootExplanationRemainAvailableInNarrowRows` | 用户 root 说明与 Git 状态的完整值/状态语义。 |
| V09 | `summaryAndRepositoryCardsUseThemeBackgroundAndThemeChangesPreserveSelection` | Screen 根背景明暗像素实际改变，选择身份保留并导出截图；卡片样式另以代码和截图核对。 |
| V09 | `fontAndDensityChangesKeepButtonsWithinTheScreen` | 1.25 density、1.5 fontScale 后按钮可见、尺寸有效且不越界。 |
| V07/V08 | `repositoryHeadingAndCountHaveSeparateNonOverlappingBounds` | 标题与总数分离、计数右侧、无重叠。 |
| V08 | `emptyManifestValuesDoNotShowRedundantTooltips` | 空工作区/分支 hover 后无 tooltip。 |
| V07/V08 | `repositoryViewportGrowsWithShortContentAndCapsLongLists` | 1→2行内容增长、长列表上限、操作区保持可见。 |
| V08 | `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth` | 9 种资源状态逐项确认 240dp 下完整文字无视觉溢出。 |
| V08/V09 | `summaryStatusIsAddressableInMergedSemanticsForEveryLifecycle` | 8 种摘要状态的合并节点 tag、完整文本/StateDescription 与240dp无视觉溢出。 |

Main 实际查看 `build/reports/compose-s2/screenshots/` 的 light/dark/narrow-unicode/scaled 四张图，确认摘要和列表同一 card 布局、长值省略、错误与操作区边界。测试 tokens 的明暗切换不是真实 IDE LaF/系统缩放或 VoiceOver 证据；这些仍属于 S3。

## 4. 旧 Panel 意图接续

以下旧 `ReqwsToolWindowPanelTest` 用例全部保留；右列为已运行的新组件方法（省略共同 `ReqwsScreenTest.` 前缀）。Swing Border/类层级和固定六行实现细节改为同一 card、内容自适应与有上限滚动的行为断言；不是在本阶段删除原断言。

| 旧方法 | 新方法 |
|---|---|
| `long unbroken tooltips wrap within a bounded real Swing layout without losing text` | `fullTextTooltipContentWrapsWithoutDroppingSpacesMarkupOrGraphemes` |
| `wrapped tooltips preserve literal markup user spaces and whole Unicode graphemes` | `fullTextTooltipContentWrapsWithoutDroppingSpacesMarkupOrGraphemes` |
| `summary and repository groups use the shared card treatment` | `summaryAndRepositoryCardsUseThemeBackgroundAndThemeChangesPreserveSelection` |
| `repository header keeps title and count separate with the count right aligned` | `repositoryHeadingAndCountHaveSeparateNonOverlappingBounds` |
| `manifest text labels disable Swing automatic HTML rendering` | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `empty manifest labels omit redundant tooltips` | `emptyManifestValuesDoNotShowRedundantTooltips` |
| `long manifest labels remain horizontally compressible with complete accessible text` | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `status and detail labels remain horizontally compressible` | `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth` |
| `copy feedback preserves failure details and their accessible text` | `errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback` |
| `repeated copy feedback remains a single separate line` | `errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback` |
| `new state details clear previous copy feedback and accessible hints` | `errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback` |
| `diagnostics and copy feedback fit a narrow summary without losing complete text` | `errorAndRepeatedCopyFeedbackRemainSeparateAndUpdatesClearTheFeedback` |
| `repository lists remain horizontally compressible for long names` | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `repository viewport constrains long rows to the visible width` | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `repository viewport follows content height and caps itself at six rows` | `repositoryViewportGrowsWithShortContentAndCapsLongLists` |
| `repository rows stay compact separated and preserve text status` | `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth` |
| `status pill keeps semantic text and its intrinsic compact width` | `summaryStatusIsAddressableInMergedSemanticsForEveryLifecycle` |
| `longest production status pill remains fully visible inside the padded narrow body` | `summaryStatusIsAddressableInMergedSemanticsForEveryLifecycle` / `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth` |
| `long untrusted repository names fit a narrow row without losing safe accessible text` | `longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `manual Git Root status remains visible in a narrow repository row` | `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth` |
| `user root coverage preserves repo3 name and complete explanation in narrow rows` | `longestStatusesAndUserRootExplanationRemainAvailableInNarrowRows` |
| `scrolling viewport bounds long status and untrusted name without losing accessible text` | `largeListScrollsToStableIdsWithoutInventingLoadedCount / longLiteralUnicodeValuesRetainCompleteTextAndBoundedLayout` |
| `primary and secondary actions preserve hierarchy in a narrow vertical layout` | `rendersAllActionsWithButtonRoleAndDispatchesTypedEvents` |

此表是功能意图接续，不是逐项复制 Swing 断言：卡片自动像素断言取的是 Screen 根背景，卡片边框/样式另外依据同一生产 `ReqwsCard` 和截图。初次 Reviewer 指出摘要状态只有通用组件间接覆盖；随后真实宿主暴露标签合并问题，`34f1392` 已增加直接摘要测试并修复 tag。旧测试删除仍由 S4 复核。

S0 中的 20 个旧 ViewModel 意图已在 S1 新 Mapper 同名测试接续，另增 ID/生命周期/加载组合；4 个旧 UI dispatcher 意图由同步 Mapper、Presenter 的注册/终态/关闭/快速通知测试接续；原 publisher、availability、Factory 用例保留。完整删除判定仍属 S4。

## 5. 最终产物和宿主复核

`b0e5eb6` 重编译后 ZIP 字节与初始矩阵候选不同，已拒绝复用旧结果；比较只保存在本地 `format-candidate-equality.json`。最终代码固定后已重新运行完整检查，未携带旧候选的矩阵结果。

`h7t1_yq6` 对完整 UI 的最小宿主检查执行 1 用例但失败：Driver 在合并后的语义树无法定位叶子上的 `reqws.status`，虽然屏幕、摘要和操作均实际存在。`34f1392` 将稳定 tag 放在合并节点，保留完整状态文本；新18用例全部通过。最终运行 `5wrhuc0z` 使用 `34f1392` 的完整 UI ZIP 通过：1 用例、0 failure/error/skipped，GO 2026.2.1.1 / 262.9437.286，进程 `started → passed → exited`，无强制终止。覆盖同一生产屏幕的未打开面板自动同步、3轮隐藏/重建/owner销毁和内容删除后项目继续同步。未发送键鼠输入；不借旧 S0/S1 报告替代本轮结果。

## 6. 独立审查与剩余范围

只读 Reviewer `/root/s2_review` 审查固定 `89796e7`；请求 `astra`，继承 Main 实际 `gpt-6-astra` / `xhigh`，Main 通过本会话可信配置核验为 verified。Reviewer 仅读限定源码、报告、组件图，不写文件、不执行测试或启动 IDE。Reviewer 完成 S2.4，未发现需要修复的生产代码问题；初次核对 17 个组件用例、11 个 Python 检查和四张图，确认旧意图可由生产组件行为接续；随后对 `34f1392` 和 `5wrhuc0z` 完成窄复核：18 组件用例和1个宿主用例均无失败/跳过，原语义标签问题已关闭，无新增问题。Main 已汇总最终新候选的完整检查，并核对宿主、7个 API 结果和当前 ZIP 的摘要一致；结合 V07–V09 组件覆盖，判定 **G2 pass、S2 completed**。

本轮没有性能门禁。G2 只涉及 V07–V09 组件覆盖；S3 的 CI 图形、实际宿主键鼠/主题/VoiceOver、动态禁用/启用和完整 Project 树集成仍 planned。S4 的旧代码删除、最终20轮资源验收与 G4 也未完成。当前报告不能称为完整 Compose 重构或发行 GO。


## 7. 最终交接

最终源代码/测试 commit 为 `34f1392`；之后仅更新文档和索引，不改候选 ZIP。本轮冻结矩阵通过目标为 `GO-262.10315.135`、`GO-262.10315.160`、`GO-262.10968.67`、`GO-262.8665.270`、`GO-262.8665.336`、`GO-262.9437.195`、`GO-262.9437.286`；无上限声明不等于未来版本已验证。

`check-goland-final.log`、18用例的 `merged-status.log`、各 Python 日志、截图和 `final-evidence.json` 位于忽略目录 `integrations/goland/build/reports/compose-s2/`。矩阵原始报告位于本机临时目录 `reqws-api-4gkrvioq`；最终宿主为 `5wrhuc0z`。摘要仅保存在这些运行证据中，不写入本文。证据目录失效时应重跑。

`npm run docs:check` 通过：25 个索引、105 个文件；相对交接提交的最终 `git diff --check` 通过。开发指南、插件 README、测试方案及最近索引已同步实际入口；无 UI 文案/翻译基线变化。用户未授权的日常 IDE 安装、发布、合并或 tag/Release 均未执行。G1/G2 已完成，不将本报告扩展为 S3/G3、S4/G4 或完整重构验收。
