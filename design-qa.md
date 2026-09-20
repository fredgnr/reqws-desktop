---
title: 工作区详情方案一设计与交互验证
type: test-report
status: active
updated: 2026-09-20
---

# 工作区详情方案一设计与交互验证

本记录验证用户选定的第一张 Product Design 设计稿在现有 Desktop 组件中的实现，不替代此前插件模型与隔离 GoLand 验收。

## 对照来源与范围

- Source visual truth：`/Users/fred/.codex/generated_images/01a0b932-b565-70d0-b4ec-e6bab107f131/exec-a4ad27a9-3dba-403e-b2b5-73a24d5a2a39.png`；原图 1192 × 1319。
- 实现：现有 `WorkspaceDetailDrawer`、`GoLandLoadingSection`、`Dialog` 和 renderer CSS；未重建应用、修改 IPC/持久化契约或插件源码。
- 预览：`http://127.0.0.1:4175/`，直接导入上述真实组件，Main bridge 为临时内存夹具；保存/打开只记录模拟动作，不改变真实工作区或启动编辑器。
- 证据目录：`/private/tmp/reqws-focused-loading-preview/evidence/`。
- Implementation screenshot：`implementation-polished.png`，由内置浏览器捕获的 `full-polished.png` 裁出真实 drawer，650 × 720。
- Viewport：最终 1280 × 720 CSS px，浏览器 DPR 2；该浏览器返回的完整截图为 1280 × 720 像素，因此未再次按 DPR 放大。drawer 为 650 × 720 CSS px。
- Density normalization：设计图等比缩至 650 × 719，底部补 1px 白色至 650 × 720；实现裁剪未缩放。比较同一浅色、三成员、repo1/repo2 勾选、repo3 不勾选、未保存、折叠规则/路径状态；滚动位置为顶部。
- Full-view comparison：`comparison-polished.png`，左设计、右实现。
- Focused comparisons：`comparison-polished-controls.png` 和 `comparison-polished-footer.png`，分别检查模式/仓库行和常驻操作区。

## Findings 与迭代

1. 初次截图由自动化点击引起了 42px 的滚动偏移，标题位于可视区上方；该状态不与设计稿比较。重新定位到顶部后才做布局判断。
2. [P2，已关闭] 路径摘要仍显示长绝对路径，弱化了加载任务。改为末两级路径摘要，完整路径保留在 tooltip 与展开内容中。
3. [P2，已关闭] 纵向节奏偏松，工作区文件说明与底部操作区交叠。收紧标题、模式、仓库行和说明间距，底部 footer 独立于滚动内容。最终 DOM 量测文件区底边 627.39、footer 顶边 631.5，内容未被覆盖。
4. 复查 `comparison-polished.png` 与两份局部并排图，无未关闭 P0/P1/P2。修改后的短窗口中文状态仍通过可见性和无横向溢出检查。

## 必查视觉面

| 面向 | 结论 |
|---|---|
| 字体与层级 | 使用项目系统字体，标题/说明/路径分级清晰，文字不裁切；原生字体渲染与生成稿的细微字重差异为 P3。 |
| 间距与布局 | 勾选框、文件夹图标与名称同组；上下文内容滚动，保存操作保持可见。最终主体及 footer 与设计结构一致。 |
| 颜色 | 沿用 ReqWS accent、muted、line 和状态色；选中行浅色背景与未选行区分，焦点轮廓保留。 |
| 资产 | 设计仅含标准 UI 图标，无独立照片/插画。文件夹、折叠箭头、关闭及状态采用项目已有 Lucide 库，未绘制仿造图标。 |
| 文案 | 15 个新增/变更 key 经同模型继承、显式 high reasoning 的只读翻译代理审校，再校验 key 顺序、中文原文与占位符后写回。数量包含已选/总数，保存成功与 IDE 同步分别说明。 |

## 交互验证

内置浏览器实际验证：单项勾选、保存选择、显式空集合、保存后请求打开、默认全部、取消草稿、重载已保存配置、并发冲突保留草稿并阻止打开、恢复配置、Cursor 工作区/目录两条入口、完整路径展开、成员管理展开、空工作区和不可用工作区。关闭按钮反向 Tab 回到最后可见操作，再正向 Tab 回到关闭按钮，折叠内容未进入焦点循环。

中文及长工作区/仓库名称分别在 1040 × 600 视口下验证，footer 底边为 600，主按钮可见，drawer 无横向溢出。证据为 `chinese-short.png`、`long-names.png`。冲突状态另见 `conflict.png`。浏览器 console error/warn 查询为空。预览没有验证原生 Electron 编辑器启动；既有 launcher/IPC 测试承接该边界。

自动化收尾：`npm run check` 的 TypeScript、ESLint、i18n（337 keys）和 docs 检查通过，42 个测试文件中 443 项通过；2 项既有临时钥匙串测试受沙箱权限限制失败。仅将 `tests/integration/macos-signing-import.test.ts` 在所需权限下补跑，2 项均通过并恢复原钥匙串搜索列表，因此全部 445 项实际验证通过，零跳过。直接 renderer 回归为 3 文件 / 11 项，包含新增 Cursor 菜单和折叠详情行为；新增行为测试未替代原有回归。

## Implementation Checklist

- [x] 实现用户选定的第一张设计稿，保留原有加载和成员保护语义。
- [x] Cursor 下拉中保留两种打开方式；成员管理收进单独次级折叠区。
- [x] 修正折叠内容的焦点循环，保留键盘操作及 pending/error 禁用。
- [x] 对照真实截图，关闭 P2，验证中英文和短窗口。
- [x] 独立翻译审校与 i18n apply/check。
- [x] 直接 renderer 回归、完整 Desktop 检查及两项受限系统测试的定向补跑通过。

## Follow-up Polish 与证据边界

保留真实相对路径而非设计稿的示例路径；数量采用审校后的单复数表达；额外保留 Cancel 和 Manage workspace，以免丢失已有能力。后者位于次级滚动区域，不挤占加载主流程。原生 checkbox、字体及品牌按钮的细微渲染差异为 P3，不影响层级或使用。

此前 443 项 Desktop / 356 项插件及 G1–G7 结论属于界面改版前的验收快照；此次未修改插件，也未安装或重启测试 IDE，不把旧 GUI 结果写成改版后的原生验收。

final result: passed
