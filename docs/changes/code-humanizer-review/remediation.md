---
title: Code Humanizer 审查整改契约与验证
type: design
status: active
updated: 2026-09-26
---

# Code Humanizer 审查整改

本次在 `review/code-humanizer-2026-09-21` 上处理[固定基线报告](review-2026-09-21.md)的 R01–R06、C01/C02；U01–U03 保留为独立待验证方向。原报告中的复现和历史结论不改写成新候选的验证结果。

## 行为与技术契约

- **R01：** 新增、更新仓库及 Desktop 工作区写入拒绝 NFC、trim、大小写折叠后的 `.reqws` 和 `.git`。拒绝必须早于 clone 和 manifest 写入。旧 state 与 schema v1 manifest 的读取格式保持不变，旧条目可查看、改名或移除；Desktop 禁止以保留路径继续构建或变更工作区，不自动迁移、删除用户目录。TypeScript/Kotlin manifest 读取接受集合不变。
- **R02：** Git 通用返回结果、进度和错误仍脱敏；origin 查询仅在内部读取原始 stdout，验证为支持的无凭据 URL 后返回。比较仍是精确相等，不重写 URL。非法或带凭据 origin 只能得到不匹配结果。
- **R03：** 从远端默认分支创建 feature 时显式 `--no-track`；远端 feature 已存在时保持跟踪；已有本地分支配置保持不变。首次发布由用户显式建立 feature upstream。
- **R04：** clone 使用 `--progress`，连续 15 分钟无 stdout/stderr 后终止；持续传输会重置期限，不设总传输时长上限。先 SIGTERM，2 秒后仍未关闭则 SIGKILL，macOS 对独立进程组发送信号以覆盖 Git 辅助进程。只有 close 后 Promise 才完成、门禁才释放，staging 才可清理；超时使用 `GIT_PROCESS_TIMEOUT` 和 `cloning` 阶段。此策略不新增 UI 取消入口。
- **R05：** access/stat/lstat 仅把 ENOENT 视为缺失，EACCES/EPERM/EIO 保留 cause 和系统错误码，使用稳定 `WORKSPACE_PATH_UNAVAILABLE` 诊断。单个工作区检查失败显示 error，不使其他工作区从列表消失。缺失与 dangling symlink 的既有语义保留。
- **R06：** 全部 App 列表重载共用递增请求代次，只有最新有效请求可写入列表、可用性、错误及 loading/refreshing；卸载使在途请求失效。
- **C01：** 复用 manifest 路径构造与绝对路径基础断言，保留输入规范化、错误码、stage 和各边界的 containment/canonical 重验。
- **C02：** toast 共用入队和 3200ms 过期调度，卸载清理定时器；key/values 保留渲染时翻译，原始文本保持原样。

## 验证计划

按条目补受影响的 unit、真实临时 Git integration 或延迟 Promise renderer 回归。重点检查保留名称拒绝前无写入、SSH URI remove→add、feature upstream、clone 超时升级及 close 后清理、文件系统错误矩阵、过期刷新与 toast 翻译。中间运行受影响测试，最终运行 `npm run check` 并检查完整 diff。无 Kotlin 或 manifest 读取契约变更，不以本次 Desktop 测试宣称 GoLand GUI 验收。

## 执行记录

实现和验证进行中，完成后填写真实命令、结果与剩余限制。

返回[审查与整改索引](README.md)。
