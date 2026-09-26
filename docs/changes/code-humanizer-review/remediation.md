---
title: Code Humanizer 审查整改契约与验证
type: technical-design
status: active
updated: 2026-09-26
---

# Code Humanizer 审查整改

本次在 `review/code-humanizer-2026-09-21` 上处理[固定基线报告](review-2026-09-21.md)的 R01–R06、C01/C02；U01–U03 保留为独立待验证方向。原报告中的复现和历史结论不改写成新候选的验证结果。

## 行为与技术契约

- **R01：** 新增、更新仓库及 Desktop 工作区写入拒绝 NFC、trim、大小写折叠后的 `.reqws` 和 `.git`。拒绝必须早于 clone 和 manifest 写入。旧 state 与 schema v1 manifest 的读取格式保持不变，旧条目可查看、改名或移除；Desktop 禁止以保留路径继续构建或变更工作区，不自动迁移、删除用户目录。TypeScript/Kotlin manifest 读取接受集合不变。
- **R02：** Git 通用返回结果、进度和错误仍脱敏；origin 查询仅在内部读取原始 stdout，验证为支持的无凭据 URL 后仅向调用方返回比较结论。比较仍是精确相等，不重写 URL。非法或带凭据 origin 只能得到不匹配结果。
- **R03：** 从远端默认分支创建 feature 时显式 `--no-track`；远端 feature 已存在时保持跟踪；已有本地分支配置保持不变。首次发布由用户显式建立 feature upstream。
- **R04：** clone 使用 `--progress`，连续 15 分钟无 stdout/stderr 后终止；持续传输会重置期限，不设总传输时长上限。先 SIGTERM，2 秒后仍未关闭则 SIGKILL，macOS 对独立进程组发送信号以覆盖 Git 辅助进程。只有 close 后 Promise 才完成、门禁才释放，staging 才可清理；超时使用 `GIT_PROCESS_TIMEOUT` 和 `cloning` 阶段。此策略不新增 UI 取消入口。
- **R05：** access/stat/lstat 仅把 ENOENT 视为缺失，EACCES/EPERM/EIO 保留 cause 和系统错误码，使用稳定 `WORKSPACE_PATH_UNAVAILABLE` 诊断。单个工作区检查失败显示 error，不使其他工作区从列表消失。缺失与 dangling symlink 的既有语义保留。
- **R06：** 全部 App 列表重载共用递增请求代次，只有最新有效请求可写入列表、可用性、错误及 loading/refreshing；卸载使在途请求失效。
- **C01：** 复用 manifest 路径构造与绝对路径基础断言，保留输入规范化、错误码、stage 和各边界的 containment/canonical 重验。
- **C02：** toast 共用入队和 3200ms 过期调度，卸载清理定时器；key/values 保留渲染时翻译，原始文本保持原样。

## 验证计划

按条目补受影响的 unit、真实临时 Git integration 或延迟 Promise renderer 回归。重点检查保留名称拒绝前无写入、SSH URI remove→add、feature upstream、clone 超时升级及 close 后清理、文件系统错误矩阵、过期刷新与 toast 翻译。中间运行受影响测试，最终运行 `npm run check` 并检查完整 diff。无 Kotlin 或 manifest 读取契约变更，不以本次 Desktop 测试宣称 GoLand GUI 验收。

## 执行记录

R01–R06、C01/C02 已在整改分支实现，尚未合并或发布。实现分别使用独立提交，源代码身份通过 Git 历史追溯。

| 条目 | 直接回归命令/选择器 | 结果 |
|---|---|---|
| R01 | `npx vitest run tests/unit/repository-service.test.ts tests/unit/schemas.test.ts tests/unit/workspace-file-writer.test.ts tests/unit/workspace-manifest-contract.test.ts tests/integration/workspace-service.test.ts` | 初始修复候选 5 文件、80 项通过；真实 Git 源文件及目录、索引均保持 |
| R02 | `npx vitest run tests/unit/git-runner.test.ts tests/integration/workspace-service.test.ts` | 当步 54 项通过；SSH URI 身份查询使用真实 Git，remove→add fixture 仅替换后续网络 fetch |
| R03 | `npx vitest run tests/integration/git-branches.test.ts` | 6 项通过；含 `branch.autoSetupMerge=always`、本地 bare origin 的显式 feature 发布及 main 引用保持 |
| R04 | `npx vitest run tests/unit/git-runner.test.ts tests/integration/workspace-service.test.ts` | 当步 56 项通过；`kills a stalled clone process group before cleaning staging and continuing the queue` 使用真实 Node 父/子进程模拟挂起 clone，确认两者退出、staging 清理及下一操作成功；fake timers 另覆盖持续进度和终止升级 |
| R05 | `npx vitest run tests/integration/workspace-service.test.ts -t 'inspection failure\|parent stat failure'` | 8 项选中通过；36 项未选中不计通过。ENOENT、EACCES、EPERM、EIO 覆盖 access 与两处 parent stat；双语诊断纳入最终 renderer 回归 |
| R06、C02 | `npx vitest run tests/renderer/app-refresh-order.test.tsx tests/renderer/app-toasts.test.tsx` | 7 项通过；旧成功/错误、刷新标志、卸载、语言切换、独立 3200ms 过期和 dismiss 清理 |
| C01 | `npx vitest run tests/unit/path-service.test.ts tests/unit/workspace-file-writer.test.ts tests/integration/workspace-service.test.ts` | 61 项通过；空白、相对路径、NFC/raw 路径、错误 stage、symlink/canonical 边界保持 |

Rebase 前的验证环境为 macOS arm64、Node.js 24.20.0、npm 11.19.0。`npm ci` 按 lockfile 安装；未修改依赖或 lockfile。完整 `npm run check` 在允许原生安全服务访问的本机权限下通过：TypeScript、ESLint、338 个 i18n key、文档检查、**47 个测试文件，495 项通过、1 项跳过**。跳过项是仅真实 GitHub-hosted macOS runner 可执行的管理员信任回归，不记为通过。

首次沙箱内完整检查的 4 项原生签名测试失败（证书抽取无输出、临时钥匙串导入与离线信任命令受限）；随后完整本机权限检查通过。签名测试仅使用现有套件的一次性身份和临时材料，没有操作发布凭据、安装应用或更改真实信任。

两个翻译 delta 为 `errors.codes.INVALID_REPOSITORY_NAME` 与 `errors.codes.WORKSPACE_PATH_UNAVAILABLE`。按 reqws-i18n 契约分别经过只读翻译 subagent 审查；运行时继承主 agent 模型，reasoning 明确设为 high，主 agent 校验 source/key/占位符及术语后写回，再执行 `i18n:apply`、`i18n:check`。没有主 agent 翻译回退。

使用 Vite 加虚构内存 API 在本机浏览器检查 renderer：工作区显示异常状态，打开详情显示 `WORKSPACE_PATH_UNAVAILABLE` 中文提示，未见布局遮挡；截图保存在本次任务临时产物中。这是 renderer fixture smoke，不是 Electron 安装验收、真实 macOS TCC 测试或 GoLand GUI GO。原生 IDE、Gradle、发布验证不在本次变更范围；U01–U03 未作新结论。

`git diff --check` 通过；最终差异审阅确认没有修改 Kotlin、manifest 读取接受集合、preload 安全边界、发布工作流或凭据。用户指南同步保留目录、upstream、无输出超时与不可访问诊断；原报告保留历史基线，局部及父级索引指向整改记录。

## Rebase 到 main 后的核验

2026-09-26 按用户要求将整改分支 rebase 到 `main@e90cd31`。唯一内容冲突是 GoLand 使用指南，按正式 v0.1.7 插件统一为 GoLand 262 起的兼容范围；不再混入旧 review 分支的精确 build 限制。最低版本影响：**无**，保持既有 `since-build="262"` 且不新增上限，未修改插件实现、构建或发布工作流。

`git range-diff` 核对整改提交保留；rebase 前后 Desktop 源码及相关测试没有内容差异。插件代码和 CI 直接继承 main；本次仅重组使用文档并更新设置页截图。`npm run check` 在 v0.1.7 整合候选上重新执行通过：47 个测试文件，495 项通过、1 项 CI 专用跳过，338 个 i18n key 一致；文档检查通过 25 个索引、104 个文件。没有以旧结果代替本次运行，也没有重跑 Gradle、安装插件或宣称新的 GoLand GUI 验收。

返回[审查与整改索引](README.md)。
