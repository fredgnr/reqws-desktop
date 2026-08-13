# ReqWS MVP 验证记录

验证日期：2026-08-13  
目标机：macOS 26.2（arm64），Git 2.55.0

## 验证结论

目标 Mac 的自动检查、开发启动、打包和 GUI smoke 已完成。14 组 smoke 中，产品可在本机完成的项目均通过；唯一未形成完整证据的是“私有 HTTPS remote 经 credential helper 完成认证”，原因是目标机没有一个已在 Keychain 中保存凭据且不会被 Git URL rewrite 改写为 SSH 的测试 remote。SSH Agent 认证和无凭据 HTTPS transport 均已实测通过。

本次修复了三个会阻塞目标机验收的问题：

- Forge 的 main/preload 入口原先都以 `index` 命名，产物与 `package.json`、BrowserWindow 期待的 `main.js`/`preload.js` 不一致；现已使用显式入口名，并增加回归测试。
- renderer 的 `outDir` 原先相对 `src/renderer` 解析，产物不在 Forge 打包路径；现固定输出到项目级 `.vite/renderer/main_window`。
- macOS 将 `/var` 解析到 `/private/var`，导致合法的临时目录被误判为路径不一致；现只兼容操作系统管理的顶层别名，仍拒绝中间层 symlink 和路径逃逸。

依赖审计中的旧 `extract-zip` 无官方 patched version。项目已将该传递依赖定向替换为 `@electron-internal/extract-zip@1.0.5`；clean install、完整审计、测试和真实 Forge package 路径均通过。

## 自动验证结果

| 检查 | 结果 |
|---|---|
| Node 基线 | 系统 Node v24.10.0/npm 11.6.0；`nvm use` 为 Node v24.19.0/npm 11.17.0 |
| 冷 `npm ci` | 通过；本次脚手架验证安装 693 个包、审计 694 个包 |
| TypeScript strict typecheck | 通过 |
| ESLint | 通过 |
| Vitest | 20 个文件、172 项测试通过 |
| Unit / integration / renderer | 全部通过 |
| 完整 `npm audit` | 0 vulnerabilities |
| `npm audit --omit=dev` | 0 vulnerabilities |
| Forge development build | renderer dev server、main bundle、preload bundle 均成功，ReqWS 窗口正常启动 |
| `electron-forge package` | 通过；生成 arm64 `ReqWS.app`，asar 内含 main、preload 和 renderer 三类产物 |
| 打包应用启动 | 通过；sandbox renderer 从 `Resources/app.asar` 加载 |
| 本机安装脚手架 | 通过；固定 `com.reqws.desktop`，arm64 本机 ad-hoc 签名通过严格校验，并在真实 `/Applications` 启动、存活和完成 renderer 窗口渲染 |

测试不会访问真实远端仓库：Git 集成测试在临时目录创建本地 bare remotes，覆盖 feature 分支已存在/不存在、默认分支缺失、rollback、复用与冲突路径。只有下述手工 smoke 使用了真实的只读 remote；远端 refs 前后快照一致。

## 关键安全回归

- 生产 URL 仅允许无凭据 HTTPS/SSH；HTTP/Git/file/local path、userinfo、敏感 query、控制字符、option-like 和 `ext::` remote-helper URL 被拒绝。
- Git 子进程清除继承的 `GIT_*` 路由环境变量；测试专用本地 clone 使用显式隔离开关和 `--no-hardlinks`。
- Git stderr/stdout 中 URL userinfo、Authorization Bearer/Basic 和常见 token 字段被清理。
- `spawn` 固定 `shell: false`，Git 用户参数使用格式校验和/或 `--`。
- Renderer 不能导航或打开 popup；Node integration 关闭。
- macOS 顶层系统路径别名可被规范化；workspace 中间层 symlink 或指向外部的 symlink 仍被拒绝。
- manifest 的 ID、root、workspace 文件、名称、分支和创建时间与索引不匹配时被拒绝。
- 并发 workspace mutation 由共享 FIFO 协调器串行化。
- renderer 在发送 progress 时消失不会中断核心操作或跳过 rollback。
- 创建目标在检查后竞态出现时不会覆盖非空目录；尚未发布的随机 staging 可清理，公开工件永不通过路径自动删除。
- 公开后的 root/`.code-workspace` 永不做破坏性路径回滚；state 失败时保留完整工件并返回恢复路径。
- 复用 repo 必须具有 workspace 内真实 `.git/objects`；拒绝 symlink、gitfile、commondir 与 alternates。

## macOS 目标机 smoke 结果

本次使用的隔离目录为 `/private/tmp/reqws-smoke-20260813-112005`。SSH fixture 使用已有 feature 分支 `feat/26.4`，HTTPS fixture 不包含该分支。

| # | 项目 | 结果与证据 |
|---|---|---|
| 1 | `nvm use && npm ci && npm run check && npm start` | 通过；GUI smoke 当时的 149 项测试通过并启动应用；新增构建、安装、签名和安全启动环境回归后，当前独立 Vitest 基线为 172 项。 |
| 2 | 单实例 | 通过；第二次直接启动 Electron 立即以 0 退出，现有窗口保持唯一并被聚焦。 |
| 3 | macOS 窗口与 Renderer 隔离 | 通过；hidden-inset traffic lights 目视正常；`require`、`process`、`Buffer` 均为 `undefined`，`window.reqws` 为 `object`。 |
| 4 | Git 认证与 transport | 部分通过；SSH Agent remote 和公开 HTTPS remote 均可在应用内测试并识别默认分支。私有 HTTPS credential-helper 认证因缺少合适的本机 fixture 未验证，详见下文。 |
| 5 | Catalog CRUD 与失败保存 | 通过；新增 SSH/HTTPS，失败 remote 返回 `REPOSITORY_UNREACHABLE` 后仍可保存，编辑为有效 remote，删除临时目录项时确认文案和保留本地 clone 语义正确。 |
| 6 | 双仓库 workspace 创建 | 通过；代码父目录和 workspace 文件目录分别选择，最终状态 Ready。 |
| 7 | 独立 clone、分支与 no-push | 通过；两仓库均有独立 `.git`。SSH repo tracking `origin/feat/26.4`；HTTPS repo 从 `origin/main` 创建同名本地 feature。远端 refs 前后 diff 为空。 |
| 8 | no-overwrite | 通过；预置 root 得到 `WORKSPACE_ROOT_EXISTS`，预置 `.code-workspace` 得到 `WORKSPACE_FILE_EXISTS`；两个 sentinel hash 均未变化。 |
| 9 | 缺失默认分支与 rollback | 通过；返回 `DEFAULT_BRANCH_NOT_FOUND`，未登记半成品，最终 root/workspace 文件和 sibling staging 均不存在。 |
| 10 | Ready workspace 增加/逻辑移除 repo | 通过；增加后 manifest/workspace 为 3 个 repo，确认移除后恢复为 2 个，第三份本地 `.git` 保留。 |
| 11 | Missing/Error/恢复/遗忘 | 通过；分别可逆移走 workspace 文件、manifest 和 root，观察到 Missing 与对应错误，恢复后 Sync 回到 Ready；最终“遗忘 Workspace”只移除全局索引，磁盘工件全部保留。 |
| 12 | VS Code、Cursor、Finder | 通过；VS Code/Cursor 都打开生成的 `.code-workspace`，Cursor 根目录入口打开正确 folder，Finder 选中正确 root。两款应用均已安装，因此“未安装时禁用”属于条件分支，本机未强行破坏安装来复现。 |
| 13 | 重启持久化 | 通过；Cmd+Q 后重新 `npm start`，catalog/workspace 恢复；创建对话框恢复 workspace 文件目录，输入名称后代码 root 自动使用上次父目录。 |
| 14 | state 权限与凭据检查 | 通过；目录为 `0700`、state 为 `0600`，JSON schemaVersion 为 1；无敏感命名字段、无 credentialed HTTPS URL，未发现密码/token。 |

额外验证：连接失败项可以保存；bad-default 目录项可以确认删除；VS Code/Cursor 的 workspace storage 均记录了本次 `.code-workspace`；Finder 确认选中了实际代码 root；逻辑移除和遗忘操作均保留磁盘内容。

## 尚缺的外部认证证据

目标机配置了 `credential.helper=osxkeychain`，但现有公司 GitLab HTTPS URL 会被全局 Git 配置改写为 SSH；绕过 rewrite 的 URL 又没有可供无交互使用的 Keychain 凭据。公开 HTTPS remote 只能证明 HTTPS transport，不能证明 helper 完成私有认证。

这不是应用代码修复可以安全消除的阻塞：验收过程不应读取、写入或伪造用户 token。若要补齐该证据，需要提供一个无 userinfo/token、已由本机 credential helper 可无交互访问的私有 HTTPS remote，再在应用内执行“测试”即可。应用对 SSH Agent 与 HTTPS transport 的路径已通过。

## 验收后状态与工件

- smoke 根目录、manifest、managed `.code-workspace`、两份正式 clone 和逻辑移除后保留的额外 clone 仍在 `/private/tmp/reqws-smoke-20260813-112005`。
- no-overwrite sentinel、失败回滚证据、远端 refs 前后快照和 workspace 文件恢复时留下的 `.moved` 副本一并保留。
- ReqWS state 当前保留 3 个 catalog 项、0 个 workspace；最后一步已验证遗忘只影响索引。
- 未执行递归删除或远端 push。清理这些本地 smoke 工件需另行明确授权。

## 本机安装脚手架验证

新增 `npm run install:macos`，默认执行 clean dependency install、全量检查、Forge package、bundle 校验、`/Applications` 同卷 staging、整体替换、安装后复验和启动。已验证：

- package 产物 bundle ID 固定为 `com.reqws.desktop`，可执行文件为 arm64，版本与 `package.json` 一致；
- Forge 对 app 与所有嵌套 framework/helper 生成通过 `codesign --verify --deep --strict` 校验的本机 ad-hoc 签名；该签名不提供发布者身份或系统信任链；
- 端到端命令多次成功安装/更新到隔离目录 `/private/tmp/reqws-install-smoke.jAlrXH/ReqWS.app`，每次均没有 staging/backup 残留；验证后已清理该临时安装，仓库 `out/` 中的 package 产物保留；
- 自动测试覆盖首次安装、带空格目录、整体替换时清除旧文件、可捕获校验失败恢复旧版、发布前身份复验、拒绝 symlink/重叠目标、并发安装锁、遗留事务工件 fail-closed 和 userData sentinel 不变；
- 脚本不强制退出正在运行的 ReqWS；检测到已安装应用或当前 checkout 的开发实例时会停止并要求用户先正常退出；
- `/Applications` 无写权限时只提升最终的 `ditto`/`mv`/清理操作，不允许以 root 运行整个 npm 流程；自定义安装目录永不提权。

目标机首次真实安装曾出现 macOS 通用“因为出现问题而无法打开”弹窗。两份系统崩溃报告确认 DYLD 拒绝加载 `Electron Framework`，原因为主程序和 framework 都是分别生成的 ad-hoc 签名，却被 `@electron/osx-sign` 默认加上 Hardened Runtime，macOS 26 的 library validation 因无共同 Team ID 而终止进程。现已对本机 ad-hoc profile 显式设置 `hardenedRuntime: false`，并在 package/安装校验中拒绝任何 `adhoc,runtime` 的主程序、framework 或 helper 签名组合。

另已处理 Electron 工具环境可能继承的 `ELECTRON_RUN_AS_NODE=1`：安装器在调用 LaunchServices 前仅清除此变量，同时保留 `HOME`、`SSH_AUTH_SOCK` 等正常环境。启动后等待并检查 `ReqWS` 主进程；最终 `make install` 在 `/Applications/ReqWS.app` 通过存活探针，Computer Use 目视确认 Workspaces 主界面完整渲染，且未产生新的 ReqWS 崩溃报告。

这是一条从可信源码进行本机安装/更新的路径，不等同于公开分发。当前签名为 ad-hoc，且只在该本机 profile 中关闭 Hardened Runtime；DMG、Apple Developer ID、公证、发布服务器和在线自动更新仍不在本次脚手架范围内。正式发布必须采用统一 Developer ID 身份、Hardened Runtime 和公证，而不是复用本机 profile。

安装回滚不是面向 `SIGKILL`、系统崩溃或断电的 crash-consistent transaction。异常终止可能留下 `.reqws-{install,backup,failed}-*.app` 和 `/tmp/reqws-install-*.lock`；在旧版移到 backup、新版发布到正式路径的两次 rename 之间，目标 app 也可能暂时缺失。下次运行会停止并输出需检查的路径，不会自动删除或猜测恢复。须确认没有安装进程并核对 bundle 身份、backup 与 lock PID 后，再人工恢复或清理精确工件。

## 验收解释

源码检查、macOS 开发启动、arm64 打包和目标机 GUI 主流程均已通过。Forge 启动仍会输出上游 `inlineDynamicImports` deprecation warning，但不影响构建、启动或打包。

残余平台边界：Node.js 未暴露 macOS 的 `RENAME_NOREPLACE` 和 fd-relative open/rename API。workspace 创建通过重复 canonical 校验、同父 staging、单次 rename、no-overwrite 文件发布和“公开后不自动删除”收窄风险；安装脚手架则在发布前重复校验目标身份并串行化同一仓库/目标目录。一个主动的同用户本地进程若精准在检查与系统调用之间交换路径，仍存在理论 TOCTOU 窗口。该边界不影响正常应用并发，若未来需要对抗本机恶意进程，应引入经过审计的原生 helper。
