---
title: ReqWS macOS 自更新技术方案
type: technical-design
status: draft
updated: 2026-09-19
---

# ReqWS macOS 自更新技术方案

本文定义 ReqWS 在不购买 Apple 开发者会员的前提下，通过固定自签名代码签名身份、稳定 Bundle ID、electron-updater 与 GitHub Releases 实现个人自用更新的方案，包括密钥生成、凭据托管、文件级改造与验收。

> **交付范围：仅设计。** 本文中的新文件、配置、脚本接口和代码片段均为待实施内容，不代表仓库已实现；本次不生成真实私钥、不配置 Secrets、不修改工作流、不创建 tag 或 Release。自签名不等于 Developer ID 或苹果公证，是否能在目标 Mac 上完成更新必须以真实测试为准。

## 1. 决策与适用范围

采用以下结构，保留 Electron Forge，不为了使用 electron-updater 迁移到 electron-builder：

```text
一次性：本机生成签名身份 → 公开证书进仓库 → 加密 P12 / 密码进 Environment Secrets
发布端：现有 Forge/Vite 打包 → 固定身份签名 → ZIP → latest-mac.yml → GitHub Release
运行端：main 检查 / 下载 → 用户确认 → Squirrel.Mac 校验旧版身份要求 → 替换并重启
```

方案只覆盖 macOS arm64 的 ReqWS Desktop；保留源码运行 `npm start`、现有本地安装命令以及独立的 GoLand 插件 ZIP。不增加 DMG、Apple Development、Developer ID、notarization、私有更新服务或客户端 GitHub token。不接管插件更新，不改变仓库 clone、workspace manifest、IDE 适配或 Go 工具链边界。

首版采用手动“检查更新 → 下载更新 → 安装并重启”，不做后台定时检查、自动下载、退出即安装、增量更新、灰度或自动降级。先把可验证的自更新闭环做小；这不妨碍以后添加自动检查。

**两个不可省略的限制：**

- 现有 ad-hoc 版本没有更新器，不能凭新增 Release 自动变成可更新版本。首个固定证书签名且内置更新器的版本必须手动安装；下一个版本开始才能验收应用内更新。
- 自签名签名有效性、旧版与新版身份兼容性、Gatekeeper 放行是三个不同问题。首次可信安装可能需要系统设置中“仍要打开”；不能承诺所有后续系统版本都无需再次交互。[Apple TN2206](https://developer.apple.com/library/archive/technotes/tn2206/_index.html)、[Apple 安全打开应用说明](https://support.apple.com/en-us/102445)。

## 2. 已核对的仓库基线

调研基线为 `main` 的提交 `701b8bab5b3bec75b2fcabbf45e6c43fa88c7245`，提交说明为 `chore: release v0.1.2`。后续实施前重新检查分支差异，不将此处快照当作永久现状。

| 已有文件 | 基线事实与本方案的连接点 |
|---|---|
| [package.json](../../../package.json) | 版本 0.1.2；Node 24；Electron 43.4.0、Forge 7.11.2、Vite 8.2.1；没有 electron-updater 或 electron-builder。 |
| [forge.config.ts](../../../forge.config.ts) | `appBundleId: com.reqws.desktop`、产品名 ReqWS、ASAR；现有签名为 `identity: '-'`，本地 profile 关闭 Hardened Runtime；没有 maker。 |
| [vite.main.config.mts](../../../vite.main.config.mts) | 当前为空 Vite 配置；新增更新器后必须验证生产打包的依赖解析。 |
| [scripts/install-macos.mts](../../../scripts/install-macos.mts) | 已有打包、签名验证、受控安装和失败恢复；支持本地 arm64/x64 参数，不能据此误认为正式 Release 提供 x64。 |
| [.github/workflows/release.yml](../../../.github/workflows/release.yml) | 正式 Desktop 只打包 arm64；校验 tag/版本/default-branch ancestry；独立构建插件；先 draft、校验资产后发布；当前桌面 ZIP 为 ad-hoc。 |
| [src/main/index.ts](../../../src/main/index.ts) | `app.whenReady()` 后创建服务、注册 IPC、创建窗口；已有 single-instance lock，并提前 `app.setName('ReqWS')`。 |
| [create-main-services.ts](../../../src/main/ipc/create-main-services.ts) | 持久状态位于 `app.getPath('userData')/reqws/state.v1.json`；存在 workspace mutation coordinator，但目前没有应用更新服务。 |
| [register-ipc.ts](../../../src/main/ipc/register-ipc.ts)、[preload](../../../src/preload/index.ts) | 通过集中、可释放的 IPC 注册与 `window.reqws` typed bridge 暴露能力。 |
| [Settings 目录](../../../src/renderer/pages/settings)、[语言目录](../../../src/renderer/locales) | 作为更新入口与本地化改造位置；不新建平行设置系统。 |

已有发布设计见 [GitHub Actions CI 与 Release](../github-actions-ci-release/README.md)。本方案增加新的个人签名更新 profile，不把历史 ad-hoc Release 的限制描述改写成“已支持自更新”。

## 3. 签名与更新信任模型

### 3.1 固定的是什么

固定以下身份：`com.reqws.desktop`、`ReqWS` 产品名、发布证书及其私钥、发布源 `fredgnr/reqws-desktop`。保持 `app.setName('ReqWS')` 和现有 userData 路径，更新应用不得搬迁或删除用户数据与 workspace。

“自签名”指持久保存私钥的真实 X.509 代码签名证书，**不是** `codesign -s -` 的 ad-hoc 签名，也不是 Git/SSH commit 签名或 GitHub Secrets API 的加密公钥。证书内已经包含公开密钥；无需另造第二套更新签名密钥。

Squirrel.Mac 从旧版读取 Designated Requirement（DR），再验证新版满足该要求；Apple 允许 DR 表达非 Apple 的签名身份。因此此路线在机制上可行，但不等于当前 ReqWS 已经通过运行验证。Squirrel 校验源码见 [SQRLCodeSignature.m](https://github.com/Squirrel/Squirrel.Mac/blob/HEAD/Squirrel/SQRLCodeSignature.m)；身份规则见 [TN2206](https://developer.apple.com/library/archive/technotes/tn2206/_index.html)。验收时以 Electron 43.4.0 实际携带的实现与真实升级结果为准，不能用上游 HEAD 代替目标二进制证据。

默认不自定义宽松 DR，不用仅 Bundle ID、仅证书 CN 或“任意自签证书”作为授权条件。对当前应用和升级包验证实际 DR，并测试另一张同名证书也被拒绝。

### 3.2 公私钥的 GitHub 托管边界

| 内容 | 位置 | 用途 |
|---|---|---|
| `reqws-signing.cer`，DER 格式公开证书 | 待新增 `build/certificates/` | 构建时核对固定身份；可公开，不含私钥。 |
| SHA-256 证书指纹 | `macos-release` Environment Variable：`MAC_SIGNING_CERT_SHA256` | 维护者首次配置的身份 pin；格式统一为不含冒号的大写十六进制。不是源文件 checksum 清单。 |
| 加密 `.p12` 的 Base64 | Environment Secret：`MAC_SIGNING_P12_BASE64` | 提供证书及私钥给授权签名 job。 |
| `.p12` 密码 | Environment Secret：`MAC_SIGNING_P12_PASSWORD` | 解密 P12；不与 P12 文件一同进仓库或产物。 |
| 签名后的应用 ZIP、更新元数据 | GitHub Releases | 客户端匿名下载；不得附带私钥、P12 或运行所需凭据。 |
| 第二份加密 P12 与密码恢复材料 | GitHub 之外的受保护备份 | Secrets 不是可随时读回私钥的备份接口。 |

Base64 不是加密。Secrets 会在获授权的 runner 中解密，持有凭据的工作流和依赖代码仍可使用或窃取私钥；它不是 HSM。把两个 Secret 拆开也不构成双人密钥授权。[GitHub Secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)、[工作流安全](https://docs.github.com/en/actions/reference/security/secure-use)。

客户端不能每次从 Release 下载一个“最新公钥”然后无条件信任。公开证书用于审核与恢复，正常升级的信任起点是已安装旧版及其 DR，而不是与更新包同时被替换的元数据。ZIP 的 SHA-512 只负责传输完整性，不能代替代码签名身份验证。

## 4. 一次性生成密钥与证书

以下步骤由维护者在自己的 Mac 上操作；不需要 Apple 账户或付费会员，不在 CI 每次重新生成。

### 4.1 在钥匙串访问中创建固定身份

1. 用 Spotlight 打开“钥匙串访问”，选择自己的 login 钥匙串。进入“钥匙串访问 → 证书助理 → 创建证书”。
2. 名称使用 `ReqWS Personal Code Signing`；身份类型选择“自签名根证书”；证书类型选择“代码签名”，不要选择 SSL 或邮件证书。
3. 允许覆盖默认值，选 RSA 4096 位、SHA-256；明确设置并记录有效期，例如个人使用选择 3650 天。确认用于数字签名与 Code Signing 的用途；不为此证书配置 TLS 信任。
4. 创建后，在“我的证书”下确认该证书可展开，下面存在对应私钥。单独一个 `.cer` 没有私钥，不能签署后续版本。
5. 打开证书的信任设置，仅将“代码签名”设为信任；其他用途保留系统默认。只信任自己刚创建并核对过的身份。

入口、密钥参数覆盖与 RSA 位数说明见 [Apple 自签证书指南](https://support.apple.com/guide/keychain-access/create-self-signed-certificates-kyca8916/mac)，用途限定见 [Apple 证书信任设置](https://support.apple.com/guide/keychain-access/change-the-trust-settings-of-a-certificate-kyca11871/mac)。界面具体字段因 macOS 版本而异；最终以导出的证书扩展和本机签名测试为准。

### 4.2 导出私钥备份和公开部分

先创建仓库以外的私密目录：

```bash
umask 077
mkdir -p "$HOME/.reqws-signing"
chmod 700 "$HOME/.reqws-signing"
```

在“我的证书”中选择包含私钥的完整签名身份，导出密码保护的 `reqws-signing.p12` 到这个目录。密码使用密码管理器生成，不写入 shell 命令字面量。若导出界面不能选择 P12，先确认选择的是带私钥的身份，而不是只有公钥的证书。[Apple 导入与导出](https://support.apple.com/guide/keychain-access/import-and-export-keychain-items-kyca35961/mac)。

再导出公开证书为 `reqws-signing.cer`（DER）。以下命令仅读取公开部分：

```bash
openssl x509 -inform DER \
  -in "$HOME/.reqws-signing/reqws-signing.cer" \
  -noout -subject -issuer -dates -fingerprint -sha256

# 可选：仅为查看或备份导出独立公钥；应用自更新不需要额外加载它。
openssl x509 -inform DER \
  -in "$HOME/.reqws-signing/reqws-signing.cer" \
  -pubkey -noout > "$HOME/.reqws-signing/reqws-signing-public.pem"

security find-identity -v -p codesigning \
  "$HOME/Library/Keychains/login.keychain-db"
```

必要时运行 `openssl x509 -inform DER -in "$HOME/.reqws-signing/reqws-signing.cer" -noout -text`，检查 Extended Key Usage 包含 Code Signing。核对证书用途、有效期和签名身份，再复制**公开 CER** 到待新增的 `build/certificates/reqws-signing.cer`。实际指纹记录到 Environment Variable，不在本文粘贴计算结果；公开证书变更本身由 Git diff 审核。带私钥 P12 永不进入 Git，包括私有仓库、Release、Actions Artifact 和缓存。

后续 `.gitignore` 至少新增 `*.p12`、`*.pfx`、`*.key`、`*.keychain`、`*.keychain-db`；不要笼统忽略所有 PEM/CER，以免公开证书无法审查。Git ignore 不是防泄漏边界，发布前还需要精确资产 allowlist。

### 4.3 配置 GitHub Environment

在仓库 Settings → Environments 手动建立 `macos-release`。当前仓库公开；GitHub 官方允许公开仓库在免费计划下使用环境 Secrets 与审批规则，但仓库改为私有时要重新检查计划限制。[环境文档](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)。

环境设置：添加 §3.2 的两个 Secret 和一个 Variable；Deployment branches and tags 只允许选定的发布 tag，例如 `v*`；加维护者审批。单人项目由自己批准时不要启用 Prevent self-review，但这只是一次手动放行，并非独立二人审查。还需保护 main、发布 tag 与工作流修改；tag 名称匹配本身不能证明代码可信。

可选使用已经登录的 GitHub CLI 上传 Secrets（不会回显原文；不代替环境创建与审批规则配置）：

```bash
# 直接通过 stdin 上传，避免 Base64 私钥进入终端历史或剪贴板。
base64 -i "$HOME/.reqws-signing/reqws-signing.p12" | tr -d '\n' | \
  gh secret set MAC_SIGNING_P12_BASE64 \
    --repo fredgnr/reqws-desktop --env macos-release

# 交互输入 P12 密码；不要使用 --body '明文密码'。
gh secret set MAC_SIGNING_P12_PASSWORD \
  --repo fredgnr/reqws-desktop --env macos-release

gh variable set MAC_SIGNING_CERT_SHA256 \
  --repo fredgnr/reqws-desktop --env macos-release
```

GitHub 对单个 Secret 有大小限制；上传前检查 Base64 后大小，不能把超限私钥文件退回提交 Git。不要把同一私钥再复制成 Repository Secret，绕开 Environment 限制。CLI 参数依据 [gh secret set](https://cli.github.com/manual/gh_secret_set)、[gh variable set](https://cli.github.com/manual/gh_variable_set)。

## 5. 保留 Forge 的发布签名实现

### 5.1 分离两个 profile

| Profile | 用途 | 签名 / 更新行为 |
|---|---|---|
| `local`，默认 | `npm start`、本地 package/install、普通 PR CI | 保持当前 ad-hoc 配置；没有发布 feed 文件；不允许应用内更新。 |
| `personal-release`，显式选择 | 通过审批的发布 job、维护者的受控验证 | 固定证书、完整嵌套签名、发布 feed 文件；缺失凭据或身份不符立即失败。 |

拟新增 `REQWS_BUILD_PROFILE=personal-release`、`REQWS_SIGNING_IDENTITY`、`REQWS_SIGNING_KEYCHAIN` 三个构建变量。仅允许两个明确 profile；未知值报错，发布不能回落到 local。发布流程**不使用** electron-builder 的 `CSC_LINK` / `CSC_KEY_PASSWORD` 接口，而是将 Secrets 导入钥匙串后，通过 Forge 的 `packagerConfig.osxSign` 使用；不能照搬上一轮讨论中针对 electron-builder 的两行配置就认为 Forge 已接入。

签名片段示意（必须与现有 Forge 配置合并，不替换 Vite plugin / ASAR / Bundle ID）：

```ts
// 仅 personal-release 分支；先校验变量非空、名称唯一与证书 pin。
osxSign: {
  identity: signingIdentity,
  keychain: signingKeychain,
  identityValidation: true,
  preAutoEntitlements: false,
  preEmbedProvisioningProfile: false,
  optionsForFile: () => ({
    hardenedRuntime: true,
    timestamp: 'none',
    entitlements: releaseEntitlementsPath,
  }),
},
```

初始 release entitlement 只考虑 Electron 所需的 `com.apple.security.cs.allow-jit`，以及无 Apple Team ID 的签名链可能需要的 `com.apple.security.cs.disable-library-validation`。禁用库验证会降低该项保护，必须显式记录，不能把它描述成“获得 Apple 信任”。不启用 App Sandbox、不添加 `get-task-allow` 或无关设备权限；是否需要额外 JIT 权限以 Electron 43.4.0 的实际测试为准。对 framework/动态库与 helper 的文件级配置使用所锁定的 osx-sign API，完整嵌套签名，不以 `codesign --deep --sign` 一条命令替代。[Electron 签名说明](https://www.electronjs.org/docs/latest/tutorial/code-signing)、[Electron Hardened Runtime 配置说明](https://www.electron.build/docs/features/code-signing/code-signing-mac)。

保留 local profile 原有 Hardened Runtime 行为。personal-release 如果无法在目标机器运行，先阻止发布并排查签名/entitlement，不自动改成关闭所有保护或跳过校验。`timestamp: 'none'` 是本个人方案的显式选择；长期使用不代表证书永不过期，见 §10。

### 5.2 签名前注入更新配置

拟新增 `build/update/app-update.yml`，只通过 personal-release 的 `packagerConfig.extraResource` 复制到 `.app/Contents/Resources/app-update.yml`，并确认最终位置不是多一层 `update/` 目录：

```yaml
provider: github
owner: fredgnr
repo: reqws-desktop
private: false
updaterCacheDirName: reqws-desktop-updater
```

这是 Forge 方案自己维护的配置，不是 electron-builder 自动生成物。必须在最外层应用签名前存在，签名后不能再写入、替换或补丁修改该文件。local profile 不复制它。

main 在创建更新器前检查 `app.isPackaged`、`process.platform === 'darwin'`、`process.arch === 'arm64'`，并解析、严格验证该配置的固定字段。缺失配置表示 local build，返回 disabled；配置损坏或含其他 provider/repo/token 时禁用更新并报告配置错误，不影响 workspace 功能。不得由 renderer、任意 URL、普通环境变量或用户目录中的同名文件覆盖受信任 feed。开发态不设置 `forceDevUpdateConfig`。

### 5.3 CI 临时钥匙串和身份核对

拟新增 `scripts/with-macos-signing.mjs`，提供如下接口：

```bash
# 待实施命令；运行前需 npm ci，且 Secrets 只暴露到这个受保护步骤。
node scripts/with-macos-signing.mjs -- \
  npm run package:macos -- --skip-ci --skip-check --arch arm64
```

wrapper 负责在 `try/finally` 中完成下列动作，而不是让构建脚本自由读取全部长期秘密：

| 次序 | 必须实现的行为 |
|---|---|
| 1 | 仅在 macOS、明确 personal-release profile、受控发布上下文运行；检查所需 Secrets，创建权限 0700 的随机 `$RUNNER_TEMP` 子目录。 |
| 2 | Base64 解码 P12 到该目录，文件权限 0600；生成本次随机 keychain password 并 mask；不复用 P12 密码作为 keychain 密码。 |
| 3 | 保存原钥匙串搜索列表；创建、解锁临时 keychain；以参数数组执行 `security import`，授权 `/usr/bin/codesign`，不使用 `-A` 全程序授权。 |
| 4 | 使用临时 keychain 的密码执行 `set-key-partition-list -S apple-tool:,apple: -s -k ...`，并将临时 keychain 加入搜索列表而非盲目覆盖原列表。 |
| 5 | 核对仓库公开 CER 的 SHA-256 与 Environment pin，核对 P12 中证书与该 CER 一致；只允许这一签名身份。 |
| 6 | 在一次性 GitHub-hosted runner 上，按 Code Signing policy 导入对该自签证书的信任；确认 `security find-identity -v -p codesigning <keychain>` 能发现它。导入 P12 不等于证书已受信任。 |
| 7 | 以只包含临时钥匙串路径与公开 identity 的子进程环境执行 Forge；从子进程环境移除 Base64/P12 密码及无关发布 token；签名任务仍可使用已解锁私钥，不宣称密钥不可窃取。 |
| 8 | finally 清理 P12、临时 keychain 和本次新增的证书信任，恢复搜索列表；中断清理另有 job 的 `always()` 兜底，不能收集整个临时目录为 artifact。 |

核心系统命令应形如以下片段；变量均由 wrapper 生成或严格校验，不能复用现有会打印完整命令行的普通 runner 处理密码参数：

```bash
security import "$P12_PATH" -k "$KEYCHAIN_PATH" \
  -P "$P12_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple: -s \
  -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

# 仅一次性受保护 macOS runner；只信任 codeSign，不修改 SSL 信任。
# 本机用钥匙串 GUI 的用户级信任，不盲目执行这里的管理员域命令。
sudo security add-trusted-cert -d -r trustRoot -p codeSign \
  -k "$KEYCHAIN_PATH" "$CERT_PATH"
security find-identity -v -p codesigning "$KEYCHAIN_PATH"

# 清理时：还必须删除 keychain、P12，并恢复原搜索列表。
sudo security remove-trusted-cert -d "$CERT_PATH"
```

具体 trust-setting 命令必须先在选择的 runner 上做一次验证；交互授权或信任导入失败就中止，不能使用只为测试而存在的“接受不受信任身份”开关。GitHub 官方的 P12/keychain 操作参考 [macOS runner 签名指南](https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications)；这里额外加入的是自签证书信任和固定身份校验。

## 6. 应用代码改造

### 6.1 依赖与打包

初始依赖基线选择已发布的 `electron-updater@6.8.9`，不是声称它永远是最新版本；依据 [该版本 Release](https://github.com/electron-userland/electron-builder/releases/tag/electron-updater%406.8.9) 及 [稳定发行树中的 MacUpdater](https://github.com/electron-userland/electron-builder/blob/electron-builder%4026.16.1/packages/electron-updater/src/MacUpdater.ts)。实施时核对实际 npm 包并固定 lockfile，不使用 `^`、`~` 或预发布 7.x API。

```bash
npm view electron-updater@6.8.9 version dist.integrity
npm install --save-exact electron-updater@6.8.9
```

放在 `dependencies`，不能只在 `devDependencies`。不新增 electron-builder，不顺便升级 Electron/Forge/Vite。先沿用现有 main Vite bundle 路线，验证 CommonJS 转换和传递依赖均可在 ASAR 运行；若必须 externalize，则同时实现生产依赖的打包，不能只把 `electron-updater` 加进 external 而遗漏 node_modules。签名前检查最终包不含 development-only 或秘密文件；真实离线启动必须没有 `MODULE_NOT_FOUND`，也不依赖仓库原始 node_modules。[Forge Vite 文档](https://www.electronforge.io/config/plugins/vite)。

### 6.2 文件级变更清单

下表是实施范围，不是本次 PR 已修改的文件清单；“新增”路径尚不存在。

| 文件 / 位置 | 待实现变更 |
|---|---|
| `package.json`、`package-lock.json` | 固定 updater 运行依赖；按需要新增发布验证命令；文档 PR 不提升应用版本。 |
| `forge.config.ts` | 显式 local/personal-release profile、钥匙串、entitlements、签名前复制配置。 |
| `build/update/app-update.yml`、`build/certificates/reqws-signing.cer`、`build/entitlements.personal-release.plist`（新增） | 固定公开 feed、公开证书、个人发布权限；不包含私钥。 |
| `scripts/with-macos-signing.mjs`、`scripts/prepare-macos-update.mjs`（新增） | 临时签名环境；完成 ZIP 后生成并验证元数据。 |
| `scripts/install-macos.mts` | `validateAppBundle` 增加 profile-specific 的证书身份校验；禁止 local ad-hoc 包默认覆盖已启用更新的正式安装。保留原事务、路径与运行中检查。 |
| `src/main/services/update-service.ts`（新增） | updater adapter、状态机、单飞请求、下载、安装准入、错误归一化与日志。 |
| `src/main/services/application-activity-gate.ts`（新增） | 协调未完成的 Git/workspace/持久化操作与更新安装，避免检查后又开始写入。 |
| `src/main/ipc/update-handlers.ts`（新增） | 固定能力 IPC、请求验证、错误映射、可信窗口限制。 |
| `src/main/index.ts`、`src/main/ipc/create-main-services.ts`、`src/main/ipc/register-ipc.ts` | ready 后创建一次；注入可测试依赖；注册/释放更新 handler、监听器；不破坏单实例。 |
| `src/shared/types.ts`、`src/shared/ipc-channels.ts`、`src/shared/errors.ts` 及对应输入 schema | 更新状态、固定通道、结构化错误、Zod 校验；不能只加 renderer 按钮。 |
| `src/preload/index.ts` | 增加 typed `updates` API；事件订阅返回 unsubscribe，不暴露 ipcRenderer / native updater。 |
| `src/renderer/pages/settings/`、`src/renderer/locales/` | Settings 中增加更新区；遵守现有 i18n skill 和校验流程，不在本次 Markdown PR 新增翻译。 |
| `.github/workflows/release.yml`、`tests/workflows/` | 受保护签名 job、更新资产 allowlist、draft 验证、缺证书失败与 no-secret-PR 回归；保留插件 job。 |
| 受影响 unit / integration / renderer 测试 | 只覆盖 ReqWS 新增逻辑和集成边界，见 §9；不增加 GoLand 原生能力重复测试。 |

### 6.3 main/preload/shared 契约

在现有 `ReqwsAPI` 中新增以下接口，状态快照由 Zod 验证后跨进程传输：

```ts
interface UpdatesAPI {
  getState(): Promise<UpdateState>;
  check(): Promise<UpdateState>;
  download(): Promise<UpdateState>;
  install(): Promise<void>;
  onStateChanged(listener: (state: UpdateState) => void): () => void;
}

type UpdatePhase =
  | 'disabled' | 'idle' | 'checking' | 'available' | 'not-available'
  | 'downloading' | 'downloaded' | 'installing' | 'error';

interface UpdateState {
  phase: UpdatePhase;
  currentVersion: string;
  nextVersion?: string;
  percent?: number;
  reason?: string; // 实施时收紧为可本地化的枚举，而不是远端任意文本。
  errorCode?: string;
}
```

对应通道为 `updates:get-state`、`updates:check`、`updates:download`、`updates:install`、`updates:state-changed`。沿用现有 `IpcResult` 错误封装；无参方法也验证没有多余参数并检查可信 sender。renderer 不能传入版本、下载 URL、证书、路径、shell 命令或 token。事件不传递 Electron event 对象；每个页面卸载清理自己的订阅。首次挂载先订阅再读取快照，或使用单调 revision 避免错过状态。

初始化参数片段（不是完整 service 实现）：

```ts
import { MacUpdater } from 'electron-updater';

// 仅在 app.whenReady() 后且 platform/profile/feed 校验成功后创建。
const updater = new MacUpdater();
updater.autoDownload = false;
updater.autoInstallOnAppQuit = false;
updater.autoRunAppAfterInstall = true;
updater.allowPrerelease = false;
updater.allowDowngrade = false;
updater.disableDifferentialDownload = true;
```

使用 `checkForUpdates()`，不使用会自动通知/下载的快捷调用。手动 `downloadUpdate()`；只在安装确认和准入通过后调用 `quitAndInstall()`。不使用 7.x 的 `autoInstallEvent`、`installPendingUpdateIfAvailable`，更不能混入 Windows 的安装选项。不要手工调用 native updater 的远程 `setFeedURL` 与 electron-updater 竞争。

**下载完不等于原生签名验证完。** 在上述 6.8.9 的 macOS 手动模式下，electron-updater 的 `update-downloaded` 可以发生在 Squirrel 取包并做原生校验之前。UI 只能显示“已下载，安装时验证”，不能显示“已通过代码签名验证”。签名失败需要通过 updater 的 error 留在旧应用，不应主动 `app.quit()`。依据为上述稳定树 `MacUpdater.updateDownloaded` / `quitAndInstall`。

### 6.4 安装准入与失败恢复

业务原则：更新失败不能导致 ReqWS 无法管理 workspace，也不能中断正在 clone、fetch 或写 manifest/state 的任务。

- check/download 分别 single-flight，重复点击共享请求或返回 busy；版本变化清除旧 available/downloaded 状态，超时和网络错误回到可重试状态，捕获 Promise rejection。
- 所有需要保护的 Git/workspace 操作和持久化写入从进入队列到 finally 完成都持有 activity lease；不依据 renderer 的按钮 disabled 状态判断是否空闲。
- `install` 原子地尝试取得独占 shutdown lease：有活动或排队任务则返回 `UPDATE_BUSY`；成功后禁止新任务进入，等待已有持久化 flush，之后才调用 updater。不得用“先读计数、再 await、最后关门”的有竞态流程。
- 检查安装位置为正常、可写的受支持位置：首版支持已由用户安装的 `/Applications/ReqWS.app` 或 `~/Applications/ReqWS.app`。拒绝从 Downloads、`out/`、只读卷、临时/转置路径直接自更新；不主动 sudo、不关闭 Gatekeeper、不在更新时清除 quarantine。
- native 校验失败释放本轮业务冻结并保留旧版；对 macOS updater 原生事件处理采用可释放适配器，防止连续调用 `quitAndInstall()` 累积旧监听器。若无法证明失败后的实例可安全复用，提示重启应用后重试，而不是在失败实例上无限重入。
- 日志只保存有限大小、可定位的错误码与阶段，不记录 Git 凭据、请求认证头、完整 query URL 或 P12。`UPDATE_SIGNATURE_INVALID` 与 `UPDATE_DOWNLOAD_FAILED` 分开，禁止失败后绕过校验安装。

Settings 显示当前版本、状态、目标版本、下载进度与明确操作按钮。Release notes 按纯文本或经严格安全处理的 Markdown 显示，不能直接注入远端 HTML。不改变现有设置持久化 schema；首版无需自动检查开关。

## 7. ZIP 与 GitHub Release 改造

### 7.1 精确资产契约

延续现有文件名；正式 Release 由原来的三个资产扩展为四个：

```text
ReqWS-<version>-macos-arm64.zip
ReqWS-<version>-goland-plugin.zip
latest-mac.yml
SHA256SUMS
```

`latest-mac.yml` 的 `files` **只能**引用 Desktop ZIP，绝不能因插件 ZIP 同时存在而选中插件。`SHA256SUMS` 覆盖两个 ZIP 及 `latest-mac.yml`，不把它自身列入摘要。中间 `.sha256` 文件可继续用于已有 pipeline，但不扩大最终公开资产集合。

metadata 形状如下，占位值只能由脚本计算，不能发布本示例：

```yaml
version: '<与 tag/package/plist 一致的版本>'
files:
  - url: 'ReqWS-<version>-macos-arm64.zip'
    sha512: '<对最终 ZIP 计算 SHA-512 后的 Base64，不是十六进制>'
    size: 123456 # 示例；必须替换为实际字节数
path: 'ReqWS-<version>-macos-arm64.zip'
sha512: '<与 files[0].sha512 一致>'
releaseDate: '<UTC ISO-8601>'
```

保留 legacy `path/sha512` 可简化兼容，但以 `files[]` 为主且二者必须一致。不填写伪造的 `minimumSystemVersion`；以后需要时必须使用 updater 所要求的内核版本语义。ZIP 是必需的更新输入，DMG 不是本手工 Forge 集成必须添加的安装包装；官方“默认 dmg+zip”描述的是 electron-builder 的默认 target，不是要求 ReqWS 改成 DMG。[自动更新文档](https://www.electron.build/docs/features/auto-update)、[MacUpdater ZIP 选择源码](https://github.com/electron-userland/electron-builder/blob/electron-builder%4026.16.1/packages/electron-updater/src/MacUpdater.ts)。

### 7.2 生成顺序必须固定

```text
写入 feed / entitlement / 版本等最终内容
  → 对嵌套组件及外层 App 完成签名
  → 验证固定证书、Bundle ID、版本、arm64 与配置
  → ditto 生成 ZIP（保留现有 --sequesterRsrc --keepParent）
  → 解压到新目录，再次验证签名、身份、配置与架构
  → 计算最终 ZIP 的 SHA-512(Base64) / size，生成 latest-mac.yml
  → 校验 metadata 与最终 ZIP、生成 SHA256SUMS
  → 上传 draft，核对精确资产集及实际下载字节，最后公开
```

拟新增的 `prepare-macos-update.mjs` 使用 Node crypto 流式计算 SHA-512，`digest('base64')`；文件名从经过严格 semver 验证的版本构造。禁止路径分隔符、任意远端 URL、绝对路径、重复或额外 `files`。可将 JSON 作为 YAML 1.2 的合法子集写入 `.yml`，但必须通过所锁定 updater 的解析路径做契约测试，避免另引 YAML 序列化依赖。签名后不能重新打包不同 ZIP 却沿用旧元数据。

### 7.3 工作流与权限

保留现有 `validate`、`checks`、Desktop package、`goland-plugin`、`publish` 的依赖门禁以及 Action SHA pins；不减少插件校验、tag/default-branch/version 校验或 draft ownership cleanup。

Desktop package job 引用 `environment: macos-release`，保持 `contents: read`；`npm ci` 和一般测试在暴露 Secrets 前完成。只在签名 wrapper 的单个 step 传入两个 Secret 与证书 pin；不得设置 job 级的私钥环境。主流程传 `REQWS_BUILD_PROFILE=personal-release`，验证 helper 应确认本轮产物确实不是 ad-hoc。

publish job 继续只有它持有 `contents: write`，没有私钥；保留 GitHub 自带短期 token，不创建长期 PAT。增加 `latest-mac.yml` 到上传和 allowlist 校验；通过对 draft 资产重新下载验证 checksum，而不只检查大小非零，再公开同一份字节。禁止覆盖现有 Release/同名资产或重打已有 tag。新版本需要新 tag。

PR 的 CI 没有环境、没有 Secrets、没有签名发布权限。普通测试可以用一次性的测试证书做隔离安全用例，但不能访问正式身份。禁止 `pull_request_target` checkout 不可信 PR 后运行持密钥 job；签名与发布依赖的工作流、脚本、Actions 和锁定依赖均属于受信任计算范围。

**draft 不能被匿名 updater 当作正式更新源。** 元数据静态验证和维护者用 CLI 下载 draft，不等于真实客户端检查升级。§9 的受控 GitHub 实测需要另行授权发布可访问的测试/个人试运行版本，不向客户端塞 token 绕过。

## 8. 首次迁移与日常使用

### 8.1 Bootstrap

首个内置 updater 的版本记为 R1，下一个版本记为 R2；实施时选择大于现有版本且未使用的版本号，不在本文预占 tag。

维护者从受控发布取得 R1 的 ZIP，核对公开证书 pin 和实际代码签名，退出已有 ReqWS，保留 userData 备份，手动替换安装 `.app`。不把旧的 ad-hoc 本地安装脚本当成“下载官方签名包安装器”；`npm run install:macos` 默认仍然会重新生成 local 包。

优先用自己有写权限的正常 Applications 目录；使用 Finder 的替换操作只针对 `ReqWS.app`，不得删除 `~/Library/Application Support/ReqWS` 或任何 workspace。首次被 Gatekeeper 拦截时按 Apple 当前指导在系统设置中单独批准这个确认可信的应用，不全局禁用安全检查。不应以自动 `xattr` 清理作为发行的常规解决方案。

运行机器不需要私钥，也不应导入 P12。是否还需要给公开证书设置 Code Signing 用户信任，要在未导入签名身份的干净用户环境中验证；不预设“必须安装根证书”，也不承诺“一次放行后所有环境永远不提示”。

### 8.2 日常升级

之后发布 R2：维护者正常提交代码、提升 package/lockfile 版本、经过检查和发布授权，创建未使用的 tag；审批签名环境后发布。R1 在 Settings 检查到 R2，手动下载并安装重启。更新只替换 App，不替换状态文件、仓库或插件。

本地调试与正式安装不要同时运行以争用相同 single-instance/userData；默认 local 安装命令应拒绝覆盖一个含正式更新配置的目标 App。需要本地实验时使用独立目录和隔离测试数据，不把正式安装改回 ad-hoc 后期望其继续更新。

## 9. 最小验证计划与发布门禁

测试围绕 ReqWS 新增逻辑、元数据契约、签名和真实升级；不重复验证 GoLand 原生 Git、Go Modules 或无关系统功能。每项保留实际命令、系统/架构、提交和 Release/run 标识；产物摘要留在测试日志/Release，遵守仓库证据规范，不在正文维护源文件 checksum 清单。

| ID | 检查 | 通过条件 |
|---|---|---|
| U1 | unit / IPC / renderer | profile 禁用、状态转换、重复请求、错误映射、订阅释放；renderer 不能指定 URL/路径或跨越 main 校验。 |
| U2 | 业务互斥 | clone/fetch、排队 workspace 操作与 state 写入期间禁止安装；安装准入后新操作被阻止；失败释放冻结，无 TOCTOU 窗口。 |
| U3 | 依赖与打包 | 目标 `.app` 可离线运行，updater 及传递依赖可解析；保留 userData 路径，local 模式无需证书且不能更新。 |
| U4 | 元数据与发布回归 | SHA-512(Base64)、size、tag/package/plist/metadata 一致；插件 ZIP 不进更新 feed；缺项、多项、路径注入和摘要错误均拒绝；draft 无验证不公开。 |
| U5 | 正式签名静态门禁 | 缺失/错误证书、另一张同名证书、ad-hoc 回退均使 release 失败；解压后嵌套签名有效，新版满足旧版 DR。 |
| U6 | 真实 macOS 两版本升级 | 在维护者实际使用的 arm64 macOS 上，R1→R2 完整检查、下载、安装、重启，确认版本与业务数据保留；仅修改 `app.getVersion` 返回值不算。 |
| U7 | 原生安全负向 | 用相同 Bundle ID 但不同证书、签名后被修改的 App，且重新计算正确 ZIP hash，验证原生签名仍拒绝；不能只测下载摘要失败。 |
| U8 | 失败与系统边界 | 网络中断、下载错误、非受支持目录、签名失败、重复安装；下载后普通退出不安装；旧应用仍可用、不清数据、不强杀 Git。 |
| U9 | 凭据与首次使用 | 干净用户未持私钥的首次启动与更新；需要什么手动信任如实记录；Artifacts/Release 无 P12、密码或私钥，临时 keychain/trust 清理成功。 |

顺序：先完成隔离的两版本 PoC（单独测试 Bundle ID、测试身份、独立 userData 和测试 feed），再验证正式候选的固定身份与 metadata，最后经授权完成真实 GitHub R1→R2 个人试运行。隔离 PoC 不能冒充生产 feed、生产身份或干净用户验证。

正向 DR 检查可用以下只读脚本；对 U7 的错误签名候选也执行同样检查并预期失败：

```bash
#!/bin/bash
set -euo pipefail
: "${OLD_APP:?请指定已安装旧版 App 的绝对路径}"
: "${NEW_APP:?请指定解压后新版 App 的绝对路径}"
old_requirement="$(/usr/bin/codesign -d -r- "$OLD_APP" 2>&1 | \
  /usr/bin/sed -n -e 's/^designated => //p' -e 's/^# designated => //p')"
[[ -n "$old_requirement" ]] || { echo '无法取得旧版 DR' >&2; exit 1; }
/usr/bin/codesign --verify --deep --strict --all-architectures \
  --verbose=2 -R="$old_requirement" "$NEW_APP"
```

此外对应用和所有嵌套 Mach-O 的签名身份抽取 CER，核对固定证书 pin；`codesign --verify` 单独成功只能说明自身签名有效，不能排除“错身份但签名有效”。上述 CLI 检查只是静态预检，不能代替 Squirrel 原生错误负向和实际重启。

实施改动后的基础命令：`npm run check`、`python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`，加新增精确 selector。真实签名、Gatekeeper、安装/重启不可由 Linux mock 或打包成功替代。不因本任务给 GoLand 加新测试范围，保留既有 release pipeline 的插件检查即可。

**发布结论：** 纯文档合并不是自更新 GO。U6/U7/U9 未完成前不得宣称可安全自更新；无法通过时保留可信 ZIP 的手动安装方式，不修改验证逻辑放行。安装失败时可能需要手动恢复已知良好的签名 App，不承诺无条件、全系统的自动回滚。

## 10. 长期维护、回滚与密钥事故

证书与私钥固定保存，每次发布检查实际有效期；距到期 90 天以内给维护者告警并安排迁移。自签证书过期并不简单等价于所有已签程序立刻失效，但构建发现、信任策略、DR、时间戳及系统版本会影响结果；不能以旧程序目前能开为依据忽略续签验证。

换证书不是“同名重建”。即使保留私钥重新发证，证书内容/指纹改变也可能不满足旧版 DR。首版不实现自动轮换；安全的保底是停用旧更新源并通过可信手动安装新身份版本，再重新完成两版本验证。以后如要桥接迁移，另立设计，验证旧客户端实际接受路径，不在此处放宽 DR。

应用功能回退优先发布**更高版本号**的修复版本，不让旧版本号覆盖新 tag 或允许自动 downgrade。保留已知良好 ZIP 与加密数据备份；手动降级前确认状态 schema 兼容，不恢复/覆盖用户仓库。本首版不改变 `state.v1.json` schema。

私钥泄露时暂停签名/发布、检查工作流与 Actions 访问、保存审计信息、停止受影响更新渠道并用新身份重新 bootstrap。删除 Secret、改 P12 密码不能让泄露副本失效；自签身份没有可替代上述处置的 Apple 撤销服务。既有客户端也不一定自动知道泄露，需可信渠道手动通知维护者。

## 11. 建议实施拆分

| 阶段 | 交付与退出条件 |
|---|---|
| S1：签名与包协议 | 实现 profile、临时 keychain wrapper、公开证书/Secrets 配置说明、元数据生成与校验、最小 updater 打包 PoC；通过 U3/U4/U5/U7 的可执行部分，真实 Mac 跑通隔离两版本。未通过不继续扩大 UI。 |
| S2：业务集成与发布 | 接入 typed IPC、Settings、activity gate、错误恢复及真实 Release 流程；补 U1/U2/U8；维护者配置真实凭据并授权受控发布后，完成 U6/U7/U9。 |
| 最终验收 | 针对同一最终候选重新执行受影响检查与真实试运行，记录实际结果；再更新当前安装/发布指南，不能引用 S1 的旧包作为最终证据。 |

S1/S2 可在一个实施 PR 中逐步完成，不要求为章节拆出多个 PR。本次只交付此设计和最近一级索引；现有当前操作指南与历史证据不改写。所有真实凭据配置、目标 Mac 安装及 tag/Release 发布属于后续明确授权的操作。
