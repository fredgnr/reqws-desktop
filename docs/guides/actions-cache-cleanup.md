---
title: GitHub Actions PR 缓存清理
type: guide
status: active
updated: 2026-09-14
---

# GitHub Actions PR 缓存清理

本指南说明 PR 合入后自动清理临时缓存的范围、安全边界、启用方式和排障方法；实现以[清理工作流](../../.github/workflows/cleanup-pr-cache.yml)和[回归测试](../../tests/workflows/test_cleanup_pr_cache.py)为准。

## 自动清理范围

工作流 `Cleanup merged PR caches` 监听 `pull_request_target: closed`，仅处理 `merged == true` 的本仓库内部 PR。只关闭但未合入、来自 fork、源分支为默认分支或 PR 目标分支的事件都会跳过。

以本仓库 `feature/example` 合入 `main` 的 PR #42 为例，使用固定 SHA 的 `toshimaru/delete-action-cache` 清理：

- `refs/pull/42/merge`：PR 工作流创建的缓存。
- `refs/heads/feature/example`：分支 push 工作流创建的缓存。

按精确 ref 清理其下所有缓存 key，每个 ref 每次最多 1,000 条，不删除 `main` 或其他 ref 下的缓存。PR 读取过但归属 `main` 的缓存也不会被删除。构建 artifacts、Release 附件、Git 分支和本地文件均不属于清理对象。现有 CI、发布工作流及其检查内容不变。

此策略适用于每个 PR 使用独立临时源分支的流程。不要复用同一源分支服务多个未合入 PR；工作流不检测其他 PR 是否仍引用该分支，也不自动识别非默认的长期分支。采用长期或共享分支前，应先收紧工作流条件，避免提前删除仍需复用的分支缓存。

## 权限与安全边界

工作流顶层为 `permissions: {}`，只有清理 job 获得 `actions: write`；使用 GitHub 自动提供的 `GITHUB_TOKEN`，无需 PAT 或新建 secret。第三方 Action 固定到 `a12c3ca71338e3312989302c48edeb5ce66293b7`，升级前需重新审查其源码和输入处理。

工作流不 checkout、不安装依赖、不执行 PR 中的脚本。源分支名先通过环境变量传入 Bash，限制为 ASCII 字母、数字、点、下划线、斜杠和连字符，并校验 Git ref 格式；不符合条件时在调用 Action 前失败，缓存保持不动。这项限制用于防护该版本 Action 直接把输入插入 Shell 的实现。fork PR 整体跳过，以免外部同名分支误删本仓库缓存。

不能设置 Action 的 `branch` 输入：该输入会令 Action 提前退出，跳过 PR merge-ref 缓存清理。每个 PR 的清理采用独立 concurrency group，重复运行不会主动取消正在执行的同 PR 清理。

## 启用与验收

先将包含该配置的 PR 合入默认分支 `main`；仅存在于功能分支时不视为已启用。之后正常合入本仓库内部的临时分支 PR，在 Actions 中查看 `Cleanup merged PR caches` 的运行记录。此工作流不追溯清理已经合入的历史 PR，也不提供任意 ref 的手动删除入口。

在仓库的 **Actions → Caches** 中分别检查 `refs/pull/<PR 编号>/merge` 和 `refs/heads/<源分支>`，并确认默认分支缓存仍存在。没有匹配缓存时无需删除。Actions 缓存本身会被其他工作流和平台淘汰策略更新，不能仅凭缓存总数变化判定清理范围。

安全边界的本地回归命令为：

```bash
python3 -m unittest discover -s tests/workflows -p 'test_cleanup_pr_cache.py' -v
npm run docs:check
```

现有 CI 的 `tests/workflows/test_*.py` discovery 会自动执行新增测试；真实 token 权限与远端删除效果需要在合入后通过实际事件验收，静态检查和临时 fixture 测试不代替这一验收。

## 排障与限制

- job 被跳过：检查是否真正合入、是否来自 fork，以及源分支是否命中默认分支或目标分支保护。
- 分支名校验失败：没有调用删除 Action；按安全命名规则创建后续临时分支，不要移除校验或直接执行该不受信任的分支名。
- 权限或 Action 被策略拒绝：检查仓库/组织是否允许该第三方 Action，以及清理 job 的 `actions: write` 是否被更高层策略限制；不要为此扩大其他工作流权限。
- 仍有缓存：检查 Action 日志和对应 ref；每个 ref 每次有 1,000 条上限，且合入时仍在运行的 CI 可能在清理后重新写入缓存。相关 CI 完成后可重跑该次清理。该 Action 内部的列表查询失败也可能未令整个步骤报错，因此绿色状态不能代替 ref 检查。

回滚时禁用或移除清理工作流即可；已删除缓存不可恢复，后续 CI 会按原有缓存策略重建，不影响源码和 Release 工件。
