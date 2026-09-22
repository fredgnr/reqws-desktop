---
title: Marketplace 跳过上传后的重试修复
type: test-report
status: active
updated: 2026-09-22
---

# Marketplace 跳过上传后的重试修复

本次修复区分“任务明确没有执行上传”和“可能上传过但回执丢失”，避免 bootstrap 或 paused 阻塞后续自动提交。

## 问题与修正规则

v0.1.6 的 Release 运行 `35730015801` 使用 bootstrap 模式，Marketplace 的 submit 任务被跳过，因而没有生成提交回执。旧脚本却要求每次历史运行都必须有回执，后续切换 automatic 时会将这类正常跳过误判为不确定上传。

[发布脚本](../../../scripts/publish_jetbrains_marketplace.py)现在允许另一种明确未 POST 的证据：GitHub 对该次 attempt 返回的唯一目标 submit 任务，状态必须同时为 `completed`、`skipped`，并且 `steps` 明确为空数组。任务的 run ID、attempt、commit，以及运行的仓库、来源仓库、tag、事件和工作流必须匹配。

这落实了[技术方案中的未 POST 重试规则](technical-design.md#72-不把公开查询当作全部状态)，没有取消上传结果不明时的保护：

- 逐一审计历史 attempt，并读取全部 jobs 分页。最后一次跳过不能覆盖此前可能发生的 POST。
- 主 Release 的 `Submit signed plugin to Marketplace / submit` 和独立工作流的 `submit` 分别精确匹配；任务重命名时必须同步脚本和测试。
- 任务缺失、重名、查询失败、身份不符、未结束或存在执行步骤，都不能作为未上传证明。
- 已存在的过期、冲突或未决回执仍然阻断重试；匹配的成功回执仍返回 `already-submitted`，不重复 POST。当前仓库变量和市场公开版本列表不作为历史未上传证据。

任务查询使用 GitHub 的 [List jobs for a workflow run attempt](https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt) 接口，沿用现有 `actions: read` 权限和有界分页，不增加生产权限或外部依赖。

## 验证范围

新增[历史重试回归](../../../tests/workflows/test_marketplace_skipped_history.py)，覆盖两个入口、首次运行、同 run 重跑、逐 attempt 审计、跨页任务、身份检查、查询失败、已提交去重、过期及未决回执保护。

离线环境已执行 19 个测试，全部通过；历史检查运行真实发布脚本，未涉及的 ZIP 校验和签名导入使用隔离替身，不代表真实签名或上传验收。旧脚本在 bootstrap 正向案例中复现 `Prior run has no retained submission evidence`。Python 语法检查通过。

仓库完整环境中的回归命令为：

```bash
python3 -m unittest discover -s tests/workflows -p 'test_marketplace*.py' -v
npm run docs:check
```

当前容器不能直接联网拉取仓库及完整依赖，未在本机执行完整 workflow 套件或 docs:check；完整检查结果以本次修复 PR 的最终提交 CI 为准。没有启动 IDE、使用生产 Token、生成签名或执行 Marketplace POST。

## 交付边界

最低 IDE 版本无影响：保持 `since-build="262"`，不增加兼容上限，不改插件代码、描述文件、版本号或产物字节。

修复合入 main 不会修改已有 v0.1.6 tag。该 tag 的重跑或 `workflow_dispatch --ref v0.1.6` 仍读取旧脚本，不能将 main 合并成功当作旧版本补发成功。需要使用包含修复的新版本发布流程；旧版本补交需另行确认市场后台状态并获得发布授权，不移动旧 tag、不删除历史记录、不关闭防重复上传检查。

仓库模式切换、Token/Environment 配置和真实上架不属于本次代码修复。bootstrap/paused 继续禁止自动上传，只有 automatic 才进入提交任务。
