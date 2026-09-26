---
title: ReqWS 安装与更新
type: guide
status: active
updated: 2026-09-26
---

# ReqWS 安装与更新

本指南帮助你选对 Desktop 安装包、完成首次启动，并区分应用更新、插件更新和源码运行。

## 选择适合你的安装方式

| 你的目的 | 选择 | 需要准备 |
|---|---|---|
| 直接使用 ReqWS | [Releases 中的 Desktop ZIP](https://github.com/fredgnr/reqws-desktop/releases/latest) | macOS Apple silicon、Git；不需要 Node.js 或 JDK。 |
| 自己构建本地应用 | [源码安装](#从源码安装) | macOS、Git、Node.js 24、npm，以及下载依赖的网络。 |
| 修改 ReqWS 的代码 | [开发运行](development-guide.md#1-环境准备) | 同上，并使用开发指南中的隔离与验证流程。 |
| 在 GoLand 中按需加载仓库 | [独立插件安装](goland-plugin-guide.md#安装插件) | 已安装 GoLand，并选择与其兼容的插件 ZIP。 |

截至 2026-09-26，[v0.1.7 发布页](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.7)已提供 Desktop arm64 ZIP 和独立 GoLand 插件 ZIP。后续版本以 Releases 的发布说明及资产为准，不从分支版本号推断已发布内容。

## 下载并安装 Desktop

1. 打开 [Releases](https://github.com/fredgnr/reqws-desktop/releases/latest)，展开 **Assets**。
2. 选择 `ReqWS-<版本>-macos-arm64.zip`。名字中的 **macos-arm64** 表示 Desktop；`goland-plugin.zip` 是 IDE 插件，不能作为 Mac App 打开。GitHub 自动生成的 `Source code` 也不是应用安装包。
3. 同一发布页中的 `SHA256SUMS` 用于核对下载文件。`latest-mac.yml` 是更新器使用的元数据，不需要手动打开或安装。
4. 退出正在运行的 ReqWS；若已有数据，按[备份说明](user-guide.md#数据位置备份与恢复)保存。解压 ZIP，把 **ReqWS.app** 放到自己可写的 `~/Applications/`，或具备写权限的 `/Applications/`。
5. 从安装位置双击打开 ReqWS。不要直接在 ZIP、临时目录或 Downloads 中运行并尝试更新。

```text
下载资产                           你要做的事
ReqWS-<版本>-macos-arm64.zip   →    解压 → Applications/ReqWS.app
ReqWS-<版本>-goland-plugin.zip →    在 GoLand 的 Plugins 中安装
SHA256SUMS                    →    核对同版本下载文件
latest-mac.yml                →    由应用更新器读取
```

**你应该看到：** ReqWS 左侧有“工作区、仓库、设置”三个入口。首次使用时列表为空是正常情况，接着按[图解教程](user-guide.md#第一次创建工作区)登记仓库。

### macOS 首次打开提示

当前发布页说明 Desktop 使用固定的个人自签名证书，尚无 Developer ID 签名和 Apple 公证。首次打开可能被 macOS 拦截；这不意味着应关闭系统安全保护。[签名说明见 v0.1.7 发布页](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.7)。

在你已核实来源、版本和签名后，可按 macOS 提示进入 **系统设置 → 隐私与安全性**，仅为 ReqWS 选择“仍要打开”并完成授权。按钮文案随系统版本变化，详见 [Apple 的官方步骤](https://support.apple.com/zh-cn/guide/mac-help/mh40616/mac)。

若提示应用损坏、恶意软件或签名不一致，先停止并核实下载，不通过信任陌生证书、全局关闭 Gatekeeper 或批量删除隔离属性解决。

## 安装与信任公开证书

只在目标版本或目标 Mac 的安装/更新过程确实需要额外代码签名信任时操作；已经正常运行和更新时，无需扩大信任。**导入证书不会让一个未配置更新器的本地构建突然具备自更新能力。**

1. 在源码页面选择与安装包相同的版本 tag，下载 `build/certificates/reqws-signing.cer` 的原始文件。例如 [v0.1.7 的公开 CER](https://github.com/fredgnr/reqws-desktop/blob/v0.1.7/build/certificates/reqws-signing.cer)。核对有效期和维护者通过可信渠道提供的 SHA-256 指纹；名称相同不代表证书相同。
2. 用 Spotlight 打开**钥匙串访问**，选择**登录（login）**钥匙串，导入公开 `.cer`。[Apple：添加证书](https://support.apple.com/zh-cn/guide/keychain-access/kyca2431/mac)。
3. 找到 `ReqWS Personal Code Signing`，双击并核对详情，展开**信任**。
4. 仅将**代码签名（Code Signing）**设为**始终信任**，其余用途保持默认；不要把所有用途统一设为始终信任。关闭窗口并按系统提示授权，再打开确认设置已保存。[Apple：更改证书信任设置](https://support.apple.com/zh-cn/guide/keychain-access/kyca11871/mac)。
5. 重启 ReqWS，再尝试原来的操作。证书信任不等于 Apple 公证，也不能修复被篡改或签名身份不一致的包。

普通用户只需要公开 CER，**不需要 P12、私钥、维护者密码或私有备份仓库权限**。如果无法核对证书身份，应先联系维护者。

## 个人签名版本的应用内更新

打开“设置”，找到**应用更新**：

```text
检查更新 → 发现更高版本 → 下载更新 → 下载完成 → 安装并重启
```

1. 点击**检查更新**。没有更高版本时，保留当前版本继续使用。
2. 有可用版本时，阅读版本信息并点击**下载更新**。
3. 下载完成后，保存工作，再选择**安装并重启**。签名在原生安装阶段验证，下载完成不等于验证通过。
4. 若提示操作忙碌，等待 clone、工作区或状态写入完成后重试。

应用不会后台自动检查或下载，也不会在普通退出时自动安装。下载失败可以重新检查；原生安装失败按提示重启后再试。只替换 Desktop App，不删除工作区或用户数据；GoLand 插件需单独更新。

应用和父目录都应对当前用户可写。若“应用更新”显示“本地构建未配置更新源”或“开发模式下无法检查更新”，说明该构建未启用更新。请选择合适的发布包，不删除 `app-update.yml` 或其他配置来绕过保护。

## 从源码安装

已安装 Git、Node.js 24 和 npm 后，取得可信源码：

```bash
git clone https://github.com/fredgnr/reqws-desktop.git
cd reqws-desktop
nvm use
npm run install:macos
```

`nvm use` 要求已安装 nvm，也可以自行准备 Node.js 24。安装脚本会安装锁定依赖、运行检查、为当前 Mac 架构打包验证，然后安装并启动应用。默认目标是 `/Applications/ReqWS.app`；仅在最终安装事务需要时请求提权，不要执行 `sudo npm run install:macos`。

| 需要 | 命令 |
|---|---|
| 安装到当前用户目录 | `REQWS_APPLICATIONS_DIR="$HOME/Applications" npm run install:macos` |
| 安装后不启动 | `npm run install:macos -- --no-launch` |
| 只查看计划 | `npm run install:macos -- --dry-run` |
| 打包但不安装 | `npm run package:macos` |
| 查看参数 | `npm run install:macos -- --help` |

默认本地构建采用 ad-hoc 签名、不启用应用内更新；它与个人签名发布包不同。默认 `local` 安装会拒绝覆盖含更新配置的安装版，即使配置已损坏也不会绕过。源码调试应选择独立安装目录，但不同 App 目录不等于用户数据已经隔离；详见[开发指南](development-guide.md#10-调试与安全操作)。

更新本地构建时，退出 ReqWS，在目标源码版本上重跑安装命令。替换 App 不等于移动或重新导入工作区。

返回[图解教程](user-guide.md) · [GoLand 插件指南](goland-plugin-guide.md) · [指南目录](README.md)。
