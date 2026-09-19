---
title: ReqWS 使用说明
type: guide
status: active
updated: 2026-09-20
---

# ReqWS 使用说明

本指南帮助 macOS 用户安装 ReqWS，并安全地配置 Git 仓库、创建和维护彼此隔离的多仓库功能工作区。

## 1. ReqWS 会做什么

ReqWS 把同一项需求涉及的多个 Git 仓库分别完整克隆到一个工作区根目录，并让它们使用同一个功能分支。每个仓库都有独立的 `.git`，不会共享 Git worktree 或对象目录。ReqWS 会生成一个由自己管理的 `.code-workspace` 供 VS Code 或 Cursor 使用，并为安装了本版 ReqWS 插件的 GoLand 准备独立入口。

ReqWS 不会执行 pull、merge、rebase、push 或创建 PR/MR，也不会自动删除本地仓库或工作区目录。

## 2. 运行条件

- macOS；Windows 和 Linux 不是当前目标平台。
- Git。缺少 Git 时仍可编辑仓库列表和设置，但不能测试连接、创建工作区或添加仓库。
- 从源码安装时需要 Node.js 24.x、兼容的 npm，以及首次下载依赖和 Electron 二进制的网络连接。
- VS Code、Cursor 和 GoLand 是可选项；Finder 打开目录不依赖它们。GoLand 的受管多仓库视图需要另行从本仓库构建并通过磁盘安装 ReqWS 插件。
- 从源码构建 GoLand 插件还需要 JDK 21；首次构建和兼容验证会下载 Gradle、GoLand SDK 与 verifier IDE，需预留网络和磁盘空间。

Git 认证由系统 Git、SSH Agent、macOS Keychain 或 Git credential helper 完成。ReqWS 不保存账号、密码、Token 或私钥。

## 3. 安装、更新与启动

当前可复现的安装方式是从可信源码构建。在仓库根目录执行：

```bash
nvm use
npm run install:macos
```

也可以执行 `make install`。安装脚本会重建锁定依赖、运行完整检查、为当前 Mac 架构打包并验证应用，然后安装到 `/Applications/ReqWS.app` 并启动。若 `/Applications` 不可写，脚本只在最终安装事务中请求提权；不要运行 `sudo npm run install:macos`。

更新已安装版本时，退出 ReqWS，在目标源码版本上再次执行同一命令。替换应用不会删除仓库目录，也不会迁移或清空用户数据。

默认 `local` 构建会拒绝覆盖含 `Contents/Resources/app-update.yml` 的安装版，包括配置已损坏的情况。需要源码调试时选择独立安装目录，不要删除该文件来绕过保护。设置页已提供“应用更新”区；源码开发和默认本地包会显示禁用原因。长期身份与签名环境已配置，正式签名发布仍待验收，见[实施记录](../changes/macos-self-update/implementation-2026-09-19.md)。

常用变体：

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

完整参数见 `npm run install:macos -- --help`。当前本机构建使用 ad-hoc 签名，没有 Developer ID 和 Apple 公证，不是 Gatekeeper-ready 的公开分发包；只应从可信源码在本机使用。

### 个人签名版本的应用内更新

这条安装路径适用于 macOS Apple silicon（arm64）的个人签名版本。当前代码与签名环境已配置，真实正式发布、两版本升级及未导入私钥的干净用户验收仍待完成，见[实施记录](../changes/macos-self-update/implementation-2026-09-19.md)。下面给出受控试运行步骤，不代表任意 Mac 已完成兼容验证。

#### 首次手动安装

1. 从[项目 Releases](https://github.com/fredgnr/reqws-desktop/releases)选择明确标注固定证书签名、内置更新器的 `ReqWS-<版本>-macos-arm64.zip`，按该版本发布说明核对 ZIP 校验信息。旧 ad-hoc 版本不能自动迁移，默认源码安装命令也不会生成支持自更新的个人签名包。
2. 退出已有 ReqWS，备份 `~/Library/Application Support/ReqWS` 等应用数据；解压后只将 `ReqWS.app` 替换到正常 Applications 目录，不删除用户数据或 workspace。
3. 优先选择自己可写的 `~/Applications/ReqWS.app`；也可使用当前用户对 App 和父目录均可写的 `/Applications/ReqWS.app`。保留应用名称，在这个安装位置启动，避免从 ZIP、Downloads 或临时目录尝试应用内更新。

#### 安装与信任公开证书

若维护者针对该试运行版本要求用户级代码签名信任，可按下列步骤配置。是否在所有目标 Mac 上都需要这一步仍待[干净用户验收](../changes/macos-self-update/technical-design.md#9-最小验证计划与发布门禁)；已经能够正常更新时不必额外添加信任。

1. 在 GitHub 源码页面切换到与安装包相同的版本 tag，从[公开 CER 路径](../../build/certificates/reqws-signing.cer)下载原始 `reqws-signing.cer` 文件。确认来源、有效期及 SHA-256 指纹与维护者通过可信渠道提供的信息一致；名称 `ReqWS Personal Code Signing` 相同不代表是同一证书。没有可核对的信息时先联系维护者。
2. 通过 Spotlight 搜索并打开“钥匙串访问”，在左侧选中“登录（login）”钥匙串，将 `.cer` 拖入窗口导入。只导入公开证书，不选择“系统”钥匙串，不导入 `.p12` 或私钥。[Apple：将证书添加到钥匙串](https://support.apple.com/zh-cn/guide/keychain-access/kyca2431/mac)。
3. 在“证书”分类中找到并双击 `ReqWS Personal Code Signing`，再次核对其详细信息，展开“信任”。仅把“代码签名（Code Signing）”改为“始终信任（Always Trust）”，其他用途保留默认；不要在顶部“使用此证书时”选择统一的“始终信任”，也不要额外信任 SSL、邮件等用途。[Apple：更改证书信任设置](https://support.apple.com/zh-cn/guide/keychain-access/kyca11871/mac)、[各用途的信任策略](https://support.apple.com/zh-cn/guide/keychain-access/mchlp2824/11.0/mac/26)。
4. 关闭证书详情窗口，按 macOS 弹窗完成本机授权；重新打开该证书，确认“代码签名”设置已保存，再重启 ReqWS 重试。

需要从终端查看公开证书信息时，可对下载的文件执行以下只读命令，再与可信的维护者信息比较；命令输出自身不是可信来源的证明：

```bash
/usr/bin/openssl x509 -inform DER -in "/实际下载路径/reqws-signing.cer" \
  -noout -subject -dates -fingerprint -sha256
```

此操作信任这张证书签署的代码，请在核实来源后进行。运行 ReqWS 无需维护者的 P12、私钥、密码或私有备份仓库访问权。证书更换也不是普通版本更新，不要因更新失败而改为信任另一张同名证书。

#### 首次启动与后续更新

自签名不等于 Developer ID 或 Apple 公证。若首次启动显示未知开发者提示，在核实该 App 后，打开“系统设置 → 隐私与安全性”，在安全性区域按提示为 ReqWS 选择“仍要打开”并完成本机授权；不同 macOS 版本的按钮文案可能略有差异。[Apple：打开来自未知开发者的 App](https://support.apple.com/zh-cn/guide/mac-help/mh40616/mac)。不要全局关闭 Gatekeeper，也不要把批量删除隔离属性当作常规安装步骤；若提示应用损坏、恶意软件或签名不匹配，应停止并向维护者核实。

在设置页依次选择“检查更新”“下载更新”，下载完成后确认“安装并重启”。下载完成只表示文件已下载，原生代码签名在安装时验证。应用不会后台检查、自动下载或在普通退出时安装。Git、工作区或状态写入仍在进行时，安装会提示忙碌；等待完成后重试。下载或网络失败可以重新检查，原生安装失败后按提示重启再试，错误不会清除业务数据。更新只替换 Desktop App，GoLand 插件继续单独安装。

“此本地构建未配置更新源”表示当前包没有启用更新，导入证书不能改变这一点；请手动安装维护者提供的正确版本。证书信任和首次启动放行也不能修复更新包篡改或身份不一致，不应通过扩大信任重试这些错误。

## 4. 首次使用前配置 Git

### SSH 仓库

ReqWS 会让 Git 继承正常的 `HOME` 和 `SSH_AUTH_SOCK`，因此可以使用 `~/.ssh/config`、`known_hosts` 和 SSH Agent。带口令的私钥应先在终端加载：

```bash
ssh-add ~/.ssh/id_ed25519
```

首次连接某个主机时，也应先在终端完成 host key 确认。ReqWS 的 Git 操作是非交互式的，不能在应用窗口中回答密码或 host key 提示。

### HTTPS 仓库

先用系统 Git 配置可用的 credential helper。仓库地址只能保存不含凭据的 HTTPS URL；不要把用户名、密码或 Token 写进 URL、query 或 fragment。

支持的地址形式包括：

```text
https://example.com/team/repository.git
ssh://git@example.com/team/repository.git
git@example.com:team/repository.git
```

本地路径、`file://`、明文 HTTP、Git remote-helper 语法及带凭据的地址会被拒绝。

升级后若旧目录中存在只符合早期校验规则的地址，ReqWS 会保留并展示该记录，不会因此重置整份状态；但在把地址改成上述当前格式前，不能将它写入新的 workspace manifest。

## 5. 设置语言和默认目录

打开一级导航中的“设置”：

1. “界面语言”可选“跟随系统”“简体中文”或 “English”。保存后当前窗口立即切换，重启后继续生效。
2. “工作区默认父目录”用于预填新工作区的代码父目录。
3. “`.code-workspace` 文件存放目录”用于预填管理文件的目录。
4. 目录可以留空；留空时每次创建工作区都必须选择。

设置页只能通过系统目录选择器填写路径。已保存目录若后来被删除或变得不可访问，设置页会要求重新选择。默认目录只影响以后打开的创建表单；在单次创建中换目录不会反写全局设置。

## 6. 管理仓库目录

进入“仓库”，选择“添加仓库”：

1. 输入不含凭据的 HTTPS 或 SSH 地址。
2. 名称会从地址自动推导，也可以在保存前修改；名称将成为工作区内的目录名。
3. 确认默认分支，例如 `main`。这里的值必须与远端实际分支一致。
4. 可选择“测试连接”。测试会执行只读远端查询并显示远端默认分支；失败不会清空表单，也不会阻止保存配置。
5. 选择“添加仓库”保存。此时只写入仓库目录，不会立即 clone。

仓库列表可以按名称、URL 和默认分支搜索。编辑仓库地址或默认分支只影响以后创建或添加的仓库，不会改写已有工作区中的 clone。

删除仓库记录只会把它从可选目录移除：

- 不删除任何本地 clone。
- 不修改已创建工作区的 manifest 或 `.code-workspace`。
- 若仍被工作区引用，确认框会列出相关工作区。

## 7. 创建功能工作区

至少保存一个仓库后，进入“工作区”并选择“创建工作区”：

1. 输入工作区名称。名称会用于生成安全的目录名和 `.code-workspace` 文件名。
2. 检查功能分支。默认值是 `feature/<工作区名称 slug>`，可以改为其他合法 Git 分支名。
3. 选择代码父目录。ReqWS 会在其下拼接工作区名称；也可直接输入最终绝对路径。
4. 选择现有的 `.code-workspace` 存放目录。
5. 搜索并勾选至少一个仓库。过滤列表不会清除已经勾选的项目。
6. 选择“创建工作区”，并保持应用运行直到操作结束。

此前保存的默认目录若已被删除或不可访问，对应目录字段会显示警告并要求重新选择。为本次创建选择替代目录后警告会消失，但该选择不会自动写回“设置”中的全局默认值。

最终代码目录和 `.code-workspace` 文件必须尚不存在；ReqWS 不会覆盖它们。所选仓库按顺序处理，每个仓库的分支规则相同：

1. 使用已有的本地功能分支；
2. 否则跟踪已有的远端功能分支；
3. 否则从该仓库配置的远端默认分支新建功能分支。

如果远端默认分支不存在、地址不可达或分支名非法，创建会失败。最终目录公开前的临时 staging 会被清理；如果完整目录或 workspace 文件已经公开后才发生 state 写入错误，ReqWS 会保留这些工件并在错误详情中给出恢复路径，不会冒险自动删除。

## 8. 打开和维护工作区

工作区列表可按名称、分支、仓库名、代码路径或 workspace 文件路径搜索。操作按钮包括：

- “VS Code”：打开生成的 `.code-workspace`。
- 列表中的“Cursor”：在新的 Cursor IDE 窗口中打开 `.code-workspace`；详情中的 Cursor 菜单分别提供“打开工作区文件”和“用 Cursor 打开代码目录”。即使 Cursor 当前显示的是 Agents Window，也会交给 IDE 打开。
- “GoLand”：重新确认工作区为“就绪”、root 与 manifest 有效后，准备并打开 `<workspace>/.reqws/ide/goland` 独立入口；详情底部的“保存并打开 GoLand”先保存当前加载选择。
- “在 Finder 中显示”：定位代码根目录。

编辑器未安装或工作区不是“就绪”状态时，相应按钮会禁用。

ReqWS 使用 Cursor 应用 bundle 内置的 editor CLI，不要求另外安装 PATH 中的 `cursor` shell command。若旧版或非标准 Cursor bundle 缺少内置 CLI，ReqWS 会回退到 macOS LaunchServices；此时无法保证已经打开的 Agents Window 会正确接收 workspace，建议更新或重新安装官方 Cursor 应用。

### 在 GoLand 中使用受管工作区

当前插件只提供本地磁盘安装，不通过 JetBrains Marketplace、ReqWS Desktop 或自动更新器分发。从可信源码构建 ZIP：

```bash
npm run package:goland
```

产物位于 `integrations/goland/build/distributions/`，唯一目标为 GO-262.9437.286。安装后从 ReqWS 工作区列表或详情打开独立入口；普通 workspace root 不触发本版受管模型。Desktop 保存成功不代表插件已安装或 IDE 已同步，请在 GoLand 的 ReqWS 面板确认状态。

插件会自动同步已选择的仓库内容，但不会自动增删 Git Roots。需要调整 Git 配置时，可在 **GoLand Settings → Version Control → Directory Mappings** 手动处理；未加载仓库与额外 mappings 仍属于用户配置。配置事件会自动刷新插件状态，必要时使用 `Sync Now` 重新检查，该动作不会修改 Directory Mappings。

完整的磁盘安装、首次信任、界面分区、状态、`Sync Now`、`Open Manifest File`、`Copy Diagnostics`、逻辑移除/重加和故障恢复步骤见[GoLand 插件使用指南](goland-plugin-guide.md)。

按次验证及候选范围见[工作加载集合需求包](../changes/goland-workspace-loading/README.md)。本节描述源码中的操作入口，不代表已有签名插件或公开发布资产。

详情中的**加载规则**和**工作区文件**可展开查看说明与完整路径；**管理工作区**提供成员增删及遗忘记录。详情保留以下维护能力：

- 添加仓库：clone 新仓库并切换到工作区功能分支，然后更新 manifest 和 `.code-workspace`。
- 移除仓库：只更新 manifest 和 `.code-workspace`，保留磁盘上的仓库目录。
- 重新生成工作区文件：根据 manifest 覆盖生成 `.code-workspace`；手工加入其中的 settings 不会保留。
- 移除工作区记录：只从 ReqWS 索引中遗忘该工作区，代码目录、manifest 和 `.code-workspace` 全部保留。

工作区 Git 变更在同一应用实例中串行执行。操作窗口打开期间不要退出 ReqWS。

## 9. 状态与恢复

| 状态 | 含义 | 建议操作 |
|---|---|---|
| 就绪 | 代码目录、manifest 和 `.code-workspace` 均存在，且没有未处理的变更错误。 | 可以打开、添加或移除仓库。 |
| 路径缺失 | 代码目录、manifest 或 workspace 文件至少一项不存在；详情会列出具体缺失项。 | 先查看详情；若仅 workspace 文件缺失且 root、manifest 有效，可重新生成。不要创建同名新目录覆盖现场。 |
| 异常 | 上一次工作区变更失败，或一致性校验发现问题。 | 打开详情并复制错误日志；核对磁盘工件后再尝试重新生成，避免手工改 manifest。 |

常见问题：

- `GIT_NOT_FOUND`：安装 Git，确认从 Finder/LaunchServices 启动的应用也能访问 Git，再刷新。
- `REPOSITORY_UNREACHABLE` 或 `CLONE_FAILED`：先在终端用相同 URL 验证凭据、网络和 host key。
- `DEFAULT_BRANCH_NOT_FOUND`：修正仓库目录中的默认分支，再重新创建。
- `WORKSPACE_ROOT_EXISTS` 或 `WORKSPACE_FILE_EXISTS`：选择新的名称或位置；ReqWS 不覆盖现有内容。若只是旧索引仍占用路径，先确认磁盘内容，再移除旧工作区记录。
- 设置目录不可用：在“设置”中重新选择已经存在且可访问的目录。
- state 损坏：ReqWS 会保留带时间戳的 `.corrupt-*` 副本并报告错误；不要覆盖该副本，先保存错误日志和现场文件。

错误面板会显示稳定错误码、阶段和技术信息，并提供复制日志按钮。报告问题时一并提供错误码、操作阶段、应用版本和可脱敏的日志；不要提交仓库凭据或私钥。

## 10. 数据位置与备份

macOS 上的典型全局状态位置是：

```text
~/Library/Application Support/ReqWS/reqws/state.v1.json
```

每个工作区根目录包含：

```text
<workspace-root>/.reqws/workspace.json
<workspace-root>/<repository-name>/.git/
```

`.code-workspace` 可以位于另一个目录。备份或迁移前先退出 ReqWS，并同时保留全局 state、工作区根目录和对应的 `.code-workspace`。当前版本没有自动导入、云同步或跨机器迁移流程；不要只复制 state 后假定路径会自动重映射。

## 11. 当前限制

- 仅支持 macOS，且没有 Windows/Linux 构建。
- clone 不支持取消、并发、浅克隆或字节级进度。
- 不提供 pull、merge、rebase、push、PR/MR、测试运行器或 Git worktree。
- `.code-workspace` 由 ReqWS 整体维护，不合并手工 settings。
- Cursor 正常路径会为每次打开操作新建一个 IDE 窗口；旧版或非标准 bundle 缺少内置 CLI 时只能降级为 LaunchServices 打开。
- GoLand 插件仅支持本地 macOS GoLand，采用 `since-build: 261`，当前不签名、不发布到 Marketplace，也没有自动安装或更新；当前源码候选的 261/262 Plugin Verifier 均为 `Compatible`，真实 GUI 仍须按 exact commit 验收。
- GoLand 插件不生成或修改 `go.work`，也不提供从 IDE 回写 ReqWS、仓库增删或分支操作。
- GoLand 插件只读 VCS Directory Mappings；Git Roots 由用户在 GoLand Settings 中手动维护。旧开发候选留下的 VCS ownership/lock 文件不会自动迁移或清理。
- 逻辑移除和遗忘操作有意保留磁盘文件；需要删除时由用户在核对路径后自行处理。
- 默认本机构建采用 ad-hoc 签名并禁用更新；个人签名版本提供手动应用内更新，没有 Developer ID、公证或 DMG。

## 12. 依据与进一步资料

- [Cursor IDE 工作区启动](../changes/cursor-ide-launch/README.md)记录 Agents Window 兼容修复、启动策略和当前验证证据。
- [GoLand 插件支持](../changes/goland-plugin-support/README.md)记录 manifest 契约、磁盘安装插件、自动项目模型与只读 VCS/手动 Directory Mappings 设计，以及仍待完成的验证。
- [全局设置需求包](../changes/global-settings/README.md)记录语言和默认目录的当前设计、验收范围与验证证据。
- [MVP 实现快照](../changes/mvp/README.md)保存初始需求覆盖、交付和验证历史；其状态为 archived，不代替当前代码和测试。
- [历史参考](../reference/README.md)保存冻结的原始方案与原型，只用于追溯来源。
- 其他当前需求、技术决策和验证证据从[文档总索引](../README.md)继续查找；若没有 active 设计，操作现状以代码和自动化测试复核。
