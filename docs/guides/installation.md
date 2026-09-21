---
title: ReqWS 安装与更新
type: guide
status: active
updated: 2026-09-21
---

# ReqWS 安装与更新

只想使用 ReqWS，下载 Release 安装包即可，不需要先搭建开发环境。Desktop 和 GoLand 插件是两个独立安装包；源码运行另见本文后半部分。

## 先选对文件

在[项目 Releases](https://github.com/fredgnr/reqws-desktop/releases)打开要安装的版本，阅读发布说明和 Assets：

| 文件 | 用途 |
|---|---|
| `ReqWS-<版本>-macos-arm64.zip` | Desktop App，当前发布包面向 Apple silicon Mac。 |
| `ReqWS-<版本>-goland-plugin.zip` | 独立 GoLand 插件，在 GoLand 中安装，不要当成 App 解压运行。 |
| `SHA256SUMS` | 同一版本的文件校验信息。 |
| `latest-mac.yml` | Desktop 更新器使用的元数据，用户无需手工安装。 |
| GitHub 自动生成的 Source code ZIP / tar.gz | 源码，不是可直接运行的应用。 |

核对时，[v0.1.5 Release](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.5)已经提供上述 App、签名插件、校验文件和更新元数据。以后安装其他版本，以该版本实际附件和说明为准，不要把 `main` 的功能说明当成旧安装包的能力。

当前没有 Windows/Linux 应用。Intel Mac 没有这份 arm64 Release 对应的安装包，不要下载后强行运行；源码安装的环境要求见下方。

## 安装 Desktop

1. 从可信的 Release 下载 App ZIP，按同一版本的 `SHA256SUMS` 核对文件。不要下载来源不明的同名包。
2. 退出已有 ReqWS。已有数据需要备份时，按[数据位置说明](user-guide.md#数据在哪里)保留应用数据和工作区；更换 App 不需要删除它们。
3. 解压 ZIP，将 `ReqWS.app` 放到 `~/Applications/ReqWS.app`，或当前用户对 App 和父目录均有写权限的 `/Applications/ReqWS.app`。不要改 App 名称，也不要从 ZIP、Downloads 或临时目录直接做应用内更新。
4. 从安装位置启动。当前个人签名版不是 Developer ID 签名或 Apple 公证版本，首次启动可能需要你在 macOS 中单独确认。

**首次启动被拦截时**，先核实来源。对于“未知开发者”提示，可按“系统设置 → 隐私与安全性”中的提示，仅为 ReqWS 选择“仍要打开”。不同 macOS 版本的文案可能不同，参见 [Apple 的操作说明](https://support.apple.com/zh-cn/guide/mac-help/mh40616/mac)。

不要全局关闭 Gatekeeper，也不要把批量删除隔离属性当作常规步骤。若提示应用损坏、恶意软件、签名无效或身份不匹配，停止安装并向维护者核实，不要用扩大信任来绕过。

<!-- docs-asset: macos-first-open -->

启动后进入 **仓库** 页面即可开始。[Desktop 使用指南](user-guide.md#添加仓库)介绍 Git 准备、添加仓库和创建工作区。

## 安装 GoLand 插件

插件不会随 Desktop 自动安装。需要 GoLand 集成时，下载独立插件 ZIP，再按[插件安装步骤](goland-plugin-guide.md#安装插件)操作。

当前唯一目标是 **GoLand 2026.2.1.1 / GO-262.9437.286**。Release 插件使用作者签名；是否能从 Marketplace 安装或更新，取决于实际审核结果。v0.1.5 的发布说明标明 Marketplace 模式为 `bootstrap`，不能据此宣称已经上架。

## 个人签名版本的应用内更新

只有使用固定证书签名、内置更新器的 macOS arm64 版本支持此流程。**旧 ad-hoc 包和默认源码构建不能靠导入证书变成更新版**，首次需要手动安装正确的 Release App。

在 Desktop 的 **设置 → 应用更新** 中：

1. 点击 **检查更新**。发布了更高版本时，页面会显示可用版本；没有新版本时，不会出现可下载更新。
2. 点击 **下载更新**。等待下载完成；“已下载”不等于签名已验证。
3. 保存当前工作，点击 **安装并重启**并确认。原生安装阶段会验证代码签名；工作区和应用数据保留。

<!-- docs-asset: desktop-update -->

ReqWS 不会在后台自动检查、自动下载，也不会在普通退出时自动安装。Git、工作区或状态写入仍在进行时，安装会提示忙碌，待操作结束后重试。

网络或下载失败后可以重新检查。原生安装失败且页面要求重启时，先重启 ReqWS 再重试。更新只替换 Desktop，不更新 GoLand 插件。

| 页面提示 | 含义和处理 |
|---|---|
| 此本地构建未配置更新源 | 当前包不支持更新。需要手动安装正确的 Release，导入证书不能修复。 |
| 开发模式下无法检查更新 | 正在运行源码开发实例，不是已安装的更新版。 |
| 应用内更新仅支持 macOS Apple silicon（arm64） | 当前平台不满足更新条件。 |
| 更新配置无效 | 先保留错误信息并联系维护者，其他工作区功能仍可使用；不要删配置绕过保护。 |
| 签名无效、证书不匹配或应用损坏 | 停止安装并核实包和签名身份，不要改为信任另一张同名证书。 |

发布附件已经存在，不等于所有目标 Mac 的首次安装、证书信任和跨版本更新都已验证。本页没有新增这些验收结论；具体已完成范围见[自更新实施记录](../changes/macos-self-update/implementation-2026-09-19.md)。

## 安装与信任公开证书

只有维护者针对已核实的个人签名版本要求用户级代码签名信任时，才做这一步。已经能正常更新时，不必额外添加信任。**代码签名信任不等于 Apple 公证，也不能代替首次启动的 Gatekeeper 确认。**

1. 在源码页面切换到与 App 相同的版本 tag，从[公开证书路径](../../build/certificates/reqws-signing.cer)下载原始 `reqws-signing.cer`。核对来源、有效期，以及维护者通过可信渠道提供的 SHA-256 指纹；同名不代表同一张证书。没有可核对的信息时先联系维护者。
2. 打开“钥匙串访问”，选择 **登录（login）**钥匙串，将 `.cer` 导入。只导入公开证书，不选择系统钥匙串，也不导入 P12 或私钥。参见 [Apple：添加证书](https://support.apple.com/zh-cn/guide/keychain-access/kyca2431/mac)。
3. 找到并双击 `ReqWS Personal Code Signing`，再次核对信息，展开“信任”。只将 **代码签名（Code Signing）**设为 **始终信任（Always Trust）**，其他用途保持默认；不要在顶部把所有用途一起改为始终信任。参见 [Apple：信任设置](https://support.apple.com/zh-cn/guide/keychain-access/kyca11871/mac)和[信任策略](https://support.apple.com/zh-cn/guide/keychain-access/mchlp2824/11.0/mac/26)。
4. 关闭详情并按 macOS 提示授权。重新打开证书确认设置保存，再重启 ReqWS 重试。

<!-- docs-asset: certificate-trust -->

需要只读查看下载证书的信息时，可执行：

```bash
/usr/bin/openssl x509 -inform DER -in "/实际下载路径/reqws-signing.cer" \
  -noout -subject -dates -fingerprint -sha256
```

命令输出本身不能证明来源可信。添加信任会信任这张证书签署的代码，必须先核对身份；运行 ReqWS 不需要维护者的密码、P12、私钥或私有备份仓库访问权。是否在某台目标 Mac 上需要额外信任，要按实际验收判断；证书更换也不能当作普通版本更新处理。

## 从源码安装或运行

源码安装需要 macOS、Node.js 24、npm、Git，以及首次下载依赖和 Electron 的网络连接。使用 Release App 不需要 Node.js、npm 或 JDK，但仓库操作仍需要系统 Git；构建 GoLand 插件则另需 JDK 25。

首次取得可信源码后，在仓库根目录执行。下面的 `nvm use` 适用于已经安装 nvm 的环境；使用其他方式准备 Node.js 24 时可略过该行。

```bash
git clone https://github.com/fredgnr/reqws-desktop.git
cd reqws-desktop
nvm use
npm run install:macos
```

已有 checkout 不要重复 clone。可以先切到要构建的可信版本，再执行安装命令。也可以使用 `make install`。安装脚本会重建锁定依赖、运行检查、按当前 Mac 架构打包验证，然后安装到 `/Applications/ReqWS.app` 并启动；这不是对所有架构均已验收的声明。

`/Applications` 不可写时，脚本只在最后的安装步骤请求提权。**不要使用 `sudo npm run install:macos`。** 常用参数保持如下：

```bash
# 只生成和验证 .app，不安装
npm run package:macos

# 安装后不启动
npm run install:macos -- --no-launch

# 仅查看将执行的步骤
npm run install:macos -- --dry-run

# 安装到当前用户的 Applications 目录
REQWS_APPLICATIONS_DIR="$HOME/Applications" npm run install:macos
```

完整参数见 `npm run install:macos -- --help`。默认本地包使用 ad-hoc 签名，不启用更新，也未经 Apple 公证，仅应从可信源码构建使用。

更新默认本地安装版时，退出 App，在目标源码版本上重新执行同一安装命令。默认 `local` 构建会拒绝覆盖带 `Contents/Resources/app-update.yml` 的安装版，即使其中配置已损坏；需要源码调试时用独立安装目录，不要删除该文件绕过保护。替换 App 不会迁移或清空用户数据。

只运行开发实例、不安装 App：

```bash
nvm use
npm ci
npm start
```

ReqWS 同时只运行一个实例。已有 App 未退出时，新进程可能只是聚焦已有窗口；先退出原实例再调试。更多命令和开发约束见[开发指南](development-guide.md)。
