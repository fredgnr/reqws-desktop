# ReqWS Desktop MVP 交付说明

本目录包含可从零安装的完整源码、测试、锁文件、文档和原始参考资源。交付 ZIP 不包含 `node_modules`、Vite/Forge 构建缓存、覆盖率或预先构建的 `.app`；可在目标 Mac 上用仓库脚手架生成。

## 交付内容

- `src/main`：Electron 生命周期、安全窗口、IPC、Git/branch/workspace/storage/editor 服务。
- `src/preload`：窄类型化 `contextBridge` API。
- `src/renderer`：React UI、对话框、详情、进度、错误和样式。
- `src/shared`：类型、Zod schemas、错误码、IPC channels 和纯函数。
- `tests`：unit、真实本地 Git integration、renderer/jsdom 测试。
- `scripts/run-install-macos.mjs`：从旧 shell Node 自动定位并切换到项目要求的 Node 24。
- `scripts/install-macos.mts`：macOS clean build、package、安全替换安装、可捕获错误回滚与启动脚手架。
- `docs/reference`：原始技术方案、HTML 原型、UI 预览图和原 handoff ZIP。
- `package-lock.json`：精确依赖图；请使用 `npm ci`，不要删除锁文件。

## 已验证基线

- 目标机 macOS 26.2 arm64；本次脚手架验证使用系统 Node.js 24.10.0 / npm 11.6.0，另已验证 `.nvmrc` 的 Node.js 24.19.0 / npm 11.17.0。
- `npm ci` 从 lockfile 安装 693 个包、审计 694 个包成功。
- `npm run check`：TypeScript、ESLint、20 个测试文件/172 项测试全部通过。
- 完整依赖与 production-only `npm audit --audit-level=low` 均为 0 vulnerabilities。
- Forge renderer/main/preload development bundles 构建并启动通过。
- `electron-forge package` 生成 arm64 `ReqWS.app`，asar 中的 main、preload 和 renderer 产物完整；打包应用启动通过。
- `npm run install:macos` 已在隔离目录和真实 `/Applications` 完成端到端验证；bundle ID、架构、可严格校验的本机 ad-hoc 签名、Hardened Runtime 兼容性、staging 整体替换、可捕获失败恢复和启动存活探针均已验证。
- macOS GUI smoke 已完成；结果、隔离工件和唯一未补齐的私有 HTTPS credential-helper 证据见 `VERIFICATION.md`。

## 运行

```bash
nvm use
npm ci
npm run check
npm start
```

应用目标平台为 macOS。本次已在目标 Mac 验证 Finder、LaunchServices、VS Code/Cursor、SSH Agent、公开 HTTPS transport 和主要状态恢复流程；逐项证据见 `VERIFICATION.md`。

## 本机安装与更新

```bash
nvm use
npm run install:macos
```

默认安装到 `/Applications/ReqWS.app` 并启动。再次执行即从当前源码重新检查、打包并整体替换已安装版本；安装事务不会修改 ReqWS userData。`npm run package:macos` 执行同样的 clean install、检查和 package，但跳过安装与启动。本机 ad-hoc 构建关闭 Hardened Runtime；公开分发必须改用统一 Developer ID 签名、重新启用 Hardened Runtime 并公证。这是本机源码安装脚手架，不是 DMG 或在线自动更新系统。

对脚本进程可捕获的复制、发布或安装后校验错误，脚手架会尽力恢复旧版并报告不完整回滚。`SIGKILL`、系统崩溃或断电不属于自动恢复保证：它们可能留下 `.reqws-{install,backup,failed}-*.app` 或 `/tmp/reqws-install-*.lock`，下一次执行会 fail closed 并要求人工核对精确路径。
