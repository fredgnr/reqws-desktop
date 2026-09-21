---
title: GitHub Actions 缓存分层与复用
type: guide
status: active
updated: 2026-09-21
---

# GitHub Actions 缓存分层与复用

ReqWS 的 CI 缓存优先保存下载成本高、与源码提交无关的依赖。默认分支负责写入共享缓存；PR、功能分支的手动运行和 Release 只读取。缓存未命中时照常安装依赖、构建和测试，不把命中缓存当作通过检查的条件。

配置入口为 [CI](../../.github/workflows/ci.yml)、[Release](../../.github/workflows/release.yml)、[Desktop 环境](../../.github/actions/setup-desktop/action.yml)、[GoLand 环境](../../.github/actions/setup-goland/action.yml)和[共享缓存动作](../../.github/actions/cache-dependency/action.yml)。这组配置合入默认分支并完成一次成功运行后，其他分支才能使用新生成的共享缓存；仅在 PR 中跑绿不代表共享缓存已经预热。

## 为什么不只是缩短 key

GitHub 的缓存访问范围包含当前分支、默认分支，以及 PR 可见的目标分支，但不允许任意兄弟分支互相读取。PR 运行写入的缓存归属 `refs/pull/<编号>/merge`。因此，把所有分支的 key 改成相同字符串，仍不能建立全仓库共享池。

共享缓存动作将恢复和保存权限分开：普通消费者只调用 `actions/cache/restore`，即使缺少缓存也不会在结束时保存一份副本。只有显式请求写入、事件为 `push` 或 `workflow_dispatch`，并且完整 ref 指向默认分支时，才注册保存步骤。标签即使也叫 `main`，仍然不能写入。

这个策略解决正常工作流的缓存占用问题，不宣称能约束被人主动修改过的 PR 工作流。缓存不能包含私钥、凭据或发布签名状态；Release 的环境保护和源码校验仍然独立保留。

## 哪些内容保留缓存

| 内容 | key 的主要边界 | 唯一写入职责 | 取舍 |
|---|---|---|---|
| npm tarball，`~/.npm/_cacache` | OS、架构、Node 主版本、lockfile 中已解析的依赖集合 | 默认分支 `project-checks` | 可以回退到同工具链的旧下载缓存；每次仍执行 `npm ci`，不缓存 `node_modules`。 |
| Electron 下载 | OS、架构、lockfile 中的 Electron 精确版本 | 默认分支 `macos-package` | 不再因其他 npm 依赖或应用版本变化而复制 Electron；不做跨版本回退。 |
| GoLand 安装包下载 | OS、架构、构建与 Verifier 共用的 GoLand 精确版本 | 默认分支 `goland-plugin` | 与普通 Gradle 依赖拆开，修改任务或测试配置不再复制整套 IDE；不做跨版本回退。 |
| Gradle Wrapper、普通依赖、DSL、转换及小型任务缓存 | 保留固定版本 `setup-gradle` 的分层去重策略 | 默认分支 `goland-plugin` | 只排除单独缓存的 IDE 安装包，保留便宜的增量构建状态。 |

npm key 不包含应用自身的名称和版本，但包含已解析依赖的版本、下载地址、integrity 等 lockfile 数据。单纯发布版本号递增不会生成一份相同的 npm 下载快照。完整 lockfile 的一致性仍由 `npm ci` 校验，而不是由缓存 key 替代。

[缓存配置读取脚本](../../scripts/ci-cache-config.py)从现有 Gradle 文件读取两处 GoLand 字面量声明，不另建一份版本清单，也不提前启动 Gradle。两处版本不一致、缺少声明、出现多个目标或改用动态 DSL 时会明确失败；调整 IDE 支持范围时，应同时调整这个读取器及测试，而不是放宽到一个与目标版本无关的 key。

### 不再跨运行保存的内容

不单独保存 `.intellijPlatform/ides` 中解压后的 IDE、插件 sandbox、项目 `.gradle`、configuration cache，或 Desktop 的 `.vite` / `out` / `dist`。不把整个 build 目录作为跨分支共享产物；Gradle 自带、按任务输入寻址的小型 build cache 继续保留。迁移前抽查的 Gradle home 主条目约 5 MB，没有证据表明它是容量瓶颈，因此不一刀切禁用编译与测试任务缓存。报告测试结果时仍应区分实际执行与 `FROM-CACHE`，不能把缓存回放写成重新运行的测试。

解压后的 IDE 和安装包长期保存两份，空间收益很差。现在恢复安装包后重新解压；这会增加解压工作，是否缩短总耗时要看真实 runner 的网络与磁盘表现，不能仅凭减少缓存体积下结论。普通 Gradle DSL、Wrapper 与转换缓存仍保留，不是一刀切关闭所有 Gradle 缓存。

GoLand 安装包动作必须排在 `setup-gradle` 后面。Actions 的 post 阶段反向执行：先保存独立安装包，再由 `setup-gradle` 删除排除目录、保存较小的 Gradle 状态。调换顺序可能在独立缓存保存前删掉安装包。CI 的下载路径检查会确认对应版本的安装包确实落在缓存目录；目录规则失效时直接失败，不把空缓存当作优化成功。

## CI 如何拆分

`project-checks` 继续在 macOS 执行完整 `npm run check` 和 Python 工作流回归，保留一次性签名测试所需的 Java/OpenSSL，但通过 `ELECTRON_SKIP_BINARY_DOWNLOAD=1` 跳过不用于此 job 的 Electron runtime。`macos-package` 使用另一份干净 checkout，安装 Electron 并执行原有 arm64 package smoke。两者并行运行，不通过 cache 传递源码产物。

聚合检查保留原名 **Checks and macOS package smoke**，只有这两个 job 都成功才成功；失败、取消或跳过都不能变成绿色门禁。**GoLand plugin checks** 名称不变，测试、结构检查、Verifier 和 ZIP 验证仍放在同一个 job，避免拆分后反复恢复、解压相同的大型 SDK。

自动触发改为默认分支 `main` 的 push 和 PR 事件，避免同一 PR 的分支 push 与 merge-ref 各跑一次。没有 PR 的功能分支可通过 `workflow_dispatch` 手动运行；默认分支改名时也要更新 push 分支过滤器。本轮没有新增按路径跳过测试的规则，文档变更仍经过原有检查范围，不用缓存优化顺带缩减安全回归。

Release 仍保留独立的校验、检查、两类打包、签名、上传和发布门禁。它只消费默认分支缓存，缓存缺失时重新下载；不会把标签专属缓存留在仓库，也不从其他运行恢复可发布的 app 或插件产物。Release artifacts 的保留策略未改，它们与 Actions cache 不是同一个存储用途。

## 如何验收，而不是只看 cache hit

迁移前抽查的是 2026-09-21 的 [CI 运行 35569686566](https://github.com/fredgnr/reqws-desktop/actions/runs/35569686566)，不是完整仓库的缓存账单。GoLand job 恢复的普通 Gradle 依赖包为 1,326,283,027 字节，解压后 IDE 的独立缓存为 1,111,582,142 字节；对应 Gradle 设置约 29.9 秒，IDE 缓存恢复约 25.1 秒。前者还包含普通依赖，不能把它全部算成 IDE，也不能把这些数据当作优化后的实测。

迁移验收分两步：

1. 先检查候选 PR：所有原有门禁通过；插件检查保持原范围，区分实际执行与 `FROM-CACHE`；版本化安装包路径检查通过；PR 的下载缓存步骤显示不可写。新 key 在 PR 首次运行未命中是正常现象。
2. 合入后让默认分支成功运行一次，再检查另一分支或 PR：npm、Electron 与 GoLand 下载命中默认分支缓存，PR/ref/tag 没有新增下载副本，Gradle 依赖缓存不再包含独立的 IDE 安装包。对比至少一次冷启动和一次热启动的总耗时、下载字节及解压耗时，再判断速度收益。

CI 聚合 job 会分页读取缓存清单，在日志和 Job Summary 中输出总条目数、总 GiB，以及体积最大的 20 项的 ref、key 和 MiB。该步骤只有 `actions: read`，不删除缓存；API 查询失败会显示警告，但不影响构建门禁，也不能解读为零占用。它是查询时的快照，可能与正在进行的上传或淘汰存在时间差。

本地回归：

```bash
python3 -m unittest discover -s tests/workflows -p 'test_ci_cache_config.py' -v
npm run docs:check
```

完整回归测试需要先安装 npm 依赖，因为工作流结构测试复用仓库已有的 `js-yaml`。只验证缓存键和写入判定，可增加 `-k CacheConfigTests`，该部分只依赖 Python 标准库。临时 fixture、YAML 结构检查均不等同于 macOS 打包、实际缓存上传或默认分支跨分支恢复验收。

## 旧缓存与容量管理

新配置不会自动删除历史缓存。迁移期间 `setup-gradle` 仍可能恢复旧的较大依赖条目；默认分支产生不含 IDE 安装包的新状态后，消费者才会转向较小条目。旧版本的 `goland-ides-v1-*` 不再被本配置使用，但仍占空间直到手动清理或平台淘汰。切换期间可能短暂同时存在新旧缓存，应查看快照再安排清理，不把更换 key 当作已释放空间。

已有的[PR 合入缓存清理](actions-cache-cleanup.md)保留，主要用于旧工作流留下的 PR 和分支缓存。本轮不扩大删除权限，不改为清空全部缓存，也不删除默认分支的暖缓存。需要立刻回收空间时，在 Actions → Caches 先核对旧 key、所属 ref 和仍在运行的旧工作流，只删除确认不用的条目。

GitHub 当前默认缓存额度为 10 GB，超额可能触发淘汰；未访问缓存也会过期。即使只让默认分支写入，新依赖集合和新 IDE 版本仍会产生新条目，本策略不是永久容量上限。频繁升级时应重点检查旧的 GoLand 版本和 npm 快照，不通过放宽跨版本恢复来制造表面命中率。

实现依据：[GitHub 缓存范围与限制](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)、[固定版本 setup-gradle 输入](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/setup-gradle/action.yml)、[Gradle 排除目录在保存前删除的实现](https://github.com/gradle/actions/blob/0723195856401067f7a2779048b490ace7a47d7c/sources/src/caching/gradle-user-home-cache.ts)、[JetBrains 2.18.1 的 GoLand 下载坐标](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/2.18.1/src/main/kotlin/org/jetbrains/intellij/platform/gradle/IntelliJPlatformType.kt)。
