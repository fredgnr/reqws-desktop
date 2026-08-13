# ReqWS Desktop MVP 技术方案与界面原型

> 文档版本：1.0  
> 日期：2026-08-12  
> 目标平台：macOS  
> 运行形态：源码运行，`npm ci && npm start`，不生成 DMG、不安装系统服务  
> 交接对象：后续 Codex 实现对话

---

## 0. 一页结论

ReqWS Desktop MVP 是一个仅在本机运行的 macOS Electron 应用，用于把多个独立 Git 仓库组织成“按需求隔离”的 feature workspace。

本版只实现三件事：

1. 维护一份仓库目录：录入仓库名称、Git 地址和默认基线分支。
2. 创建 feature workspace：选择多个仓库，完整 clone 到该需求自己的目录，统一切换到需求分支，并在用户指定目录生成 `.code-workspace` 文件。
3. 搜索和管理 workspace：查看 workspace、增加或移除仓库、在 VS Code 或 Cursor 中打开。

### 冻结的技术决策

| 项目 | 决策 |
|---|---|
| 桌面框架 | Electron Forge + React + TypeScript |
| 前端构建 | Electron Forge Vite TypeScript 模板；固定 lockfile |
| 启动方式 | `npm start` |
| 数据库 | 不使用数据库；本地 JSON 文件 |
| Git 调用 | Node.js `child_process.spawn()`，参数数组，`shell: false` |
| 隔离方式 | 每个 workspace 中完整 `git clone`；禁止 Git worktree |
| 仓库体量假设 | Go 仓库较小，因此不做 shallow clone、共享 object cache 或本地 mirror |
| `.code-workspace` 路径 | 与代码目录分离，用户可指定；使用绝对 repo 路径 |
| IDE | VS Code、Cursor；通过 macOS `/usr/bin/open` 启动 |
| 网络服务 | 无后端服务、无账号、无同步、无遥测 |
| 凭据 | 不保存 Git 凭据；完全使用本机 SSH Agent / Keychain / Git credential helper |

### MVP 明确不做

- DMG、签名、公证、自动更新。
- Git worktree、共享 clone、对象缓存。
- GitHub/GitLab API、创建 PR/MR、推送分支。
- 多人协作、云同步、账号登录。
- Windows/Linux。
- GoLand/IntelliJ 集成。
- 批量 pull、merge、rebase、push、运行测试。
- 自动删除包含未提交代码的目录。

---

## 1. 产品目标与核心术语

### 1.1 产品目标

用户可以用 UI 完成以下流程：

```text
录入仓库
  → 创建需求 workspace
  → 勾选仓库
  → 完整 clone 到需求独立目录
  → 切换到同一个 feature 分支
  → 在指定目录生成 .code-workspace
  → 用 VS Code / Cursor 打开
  → 后续给 workspace 增加或移除仓库
```

### 1.2 核心术语

- **Repository Catalog / 仓库目录**：ReqWS 中保存的可选 Git 仓库清单。
- **Feature Workspace**：以需求或任务为维度创建的一组仓库 clone。
- **Workspace Root / 代码目录**：一个需求所有仓库 clone 的共同父目录。
- **Workspace File Directory**：存放 `.code-workspace` 文件的目录，可以与 Workspace Root 不同。
- **Feature Branch**：该需求在所有选中仓库使用的统一目标分支。
- **Default Branch / 基线分支**：目标 feature 分支不存在时，用来创建分支的远端基线，例如 `main`、`develop`。

### 1.3 物理隔离定义

同一个仓库如果被两个需求使用，必须存在两个完整 clone：

```text
~/Developer/features/FEAT-123/order-api/.git/
~/Developer/features/FEAT-456/order-api/.git/
```

以下方案不满足要求：

- `git worktree`；
- 多个目录指向同一个 `.git`；
- 共享中央工作目录；
- 只在原有仓库中批量切分支。

---

## 2. MVP 用户故事

### US-01：录入仓库

作为用户，我可以录入仓库 Git 地址；程序自动推导仓库名称，我也可以修改名称和默认分支。

### US-02：创建 feature workspace

作为用户，我可以输入 workspace 名称和 feature 分支、选择代码目录与 `.code-workspace` 文件目录，并勾选多个仓库创建工作区。

### US-03：搜索 workspace

作为用户，我可以按 workspace 名称、分支、仓库名称或路径搜索已有 workspace。

### US-04：给 workspace 增加仓库

作为用户，我可以从仓库目录中选择尚未加入的仓库，将其 clone 到现有 workspace，并更新 `.code-workspace`。

### US-05：从 workspace 移除仓库

作为用户，我可以将一个仓库从逻辑 workspace 和 `.code-workspace` 中移除。MVP 默认保留本地 repo 目录，避免误删代码。

### US-06：打开编辑器

作为用户，我可以点击按钮，在 VS Code 或 Cursor 中打开对应 `.code-workspace`。

---

## 3. 功能范围与验收语义

## 3.1 仓库目录

每个仓库包含：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `name` | 是 | 唯一显示名和默认目录名，例如 `order-api` |
| `url` | 是 | SSH 或 HTTPS Git 地址 |
| `defaultBranch` | 是 | 默认 `main`，允许改为 `master`、`develop` 等 |
| `createdAt` | 系统 | ISO 8601 时间 |
| `updatedAt` | 系统 | ISO 8601 时间 |

行为：

- 输入 URL 后，从最后一段去掉 `.git` 自动推导名称。
- 名称必须在仓库目录中唯一。
- 提供“测试连接”按钮，执行 `git ls-remote --symref <url> HEAD`。
- 测试失败时展示 Git 错误，但允许用户保存；保存不应强制依赖网络。
- 修改仓库 URL 或默认分支不会改写已经创建的 workspace 快照。
- 删除一个正在被 workspace 引用的仓库目录项时应提示；删除目录项不删除任何本地 clone。

## 3.2 创建 feature workspace

创建表单字段：

| 字段 | 必填 | 示例 |
|---|---:|---|
| Workspace 名称 | 是 | `FEAT-123-payment-refund` |
| Feature 分支 | 是 | `feature/FEAT-123` |
| 代码目录 | 是 | `~/Developer/features/FEAT-123-payment-refund` |
| Workspace 文件目录 | 是 | `~/Developer/vscode-workspaces` |
| 仓库选择 | 至少 1 个 | `order-api`、`payment-api` |

默认值：

- Feature 分支：`feature/<workspace-name>`，其中非法 Git 字符替换为 `-`。
- 代码目录：记住用户上次选择的父目录，并拼接 workspace 名称。
- Workspace 文件目录：记住用户上次选择的位置。
- 文件名：`<workspace-name>.code-workspace`。

创建完成后的目录示例：

```text
~/Developer/features/FEAT-123-payment-refund/
├── .reqws/
│   └── workspace.json
├── order-api/
│   └── .git/
└── payment-api/
    └── .git/

~/Developer/vscode-workspaces/
└── FEAT-123-payment-refund.code-workspace
```

### 分支语义

每个仓库 clone 完成后：

1. 执行 `git fetch origin --prune`。
2. 若 `origin/<featureBranch>` 已存在，则创建并跟踪该远端分支。
3. 若远端 feature 分支不存在，则从 `origin/<defaultBranch>` 创建本地 feature 分支。
4. 不自动 push。
5. 默认分支不存在时，该仓库创建失败，整个 workspace 创建失败并回滚 staging 目录。

### 创建原子性

不得直接在最终目录中逐个留下半成品。流程：

```text
校验输入
  → 创建同父目录 staging 文件夹
  → 顺序 clone 和切分支
  → 写入 .reqws/workspace.json
  → staging rename 为最终代码目录
  → 原子写入 .code-workspace
  → 更新全局索引
```

任一步失败：

- 删除 staging 目录；
- 不写入全局 workspace 索引；
- 不覆盖已存在的 `.code-workspace`；
- 在 UI 中显示失败仓库、失败阶段和可复制日志。

仓库较小，MVP 使用**顺序 clone**。不增加并发调度和取消逻辑。

## 3.3 搜索与 workspace 列表

列表支持搜索：

- workspace 名称；
- feature 分支；
- 包含的 repo 名称；
- 代码目录；
- `.code-workspace` 路径。

每行至少显示：

- 名称；
- feature 分支；
- repo 数量与 repo 标签；
- 代码目录；
- 更新时间；
- VS Code 按钮；
- Cursor 按钮；
- 详情按钮。

状态：

- `Ready`：代码目录、manifest 和 workspace 文件均存在。
- `Missing`：任一关键路径丢失。
- `Error`：上次增减 repo 操作失败。

MVP 不持续轮询 Git 状态，不在列表上计算 ahead/behind/dirty。

## 3.4 增加仓库

在 workspace 详情中选择尚未加入的 catalog repo：

1. 目标目录是 `<workspaceRoot>/<repo.name>`。
2. 若目录不存在：clone、切换到 workspace 的 feature 分支。
3. 若目录已经是 Git 仓库且 `origin` URL 与 catalog URL 匹配：复用该目录，并确保切换到 feature 分支。
4. 若目录存在但不是匹配仓库：拒绝操作。
5. Git 操作成功后，先原子更新 workspace manifest，再原子重写 `.code-workspace`，最后更新全局索引。
6. 失败时不得把 repo 写入 manifest 或 workspace 文件。

## 3.5 移除仓库

MVP 的“移除”语义：

- 从 `.reqws/workspace.json` 中删除；
- 从 `.code-workspace` 的 `folders` 中删除；
- 更新全局索引；
- **保留本地 repo 文件夹**；
- UI 明确提示“本地目录仍保留，可在 Finder 中手动处理”。

这样既满足 workspace 增减，又不会因 MVP 删除逻辑误伤未提交代码。

物理删除 repo 文件夹属于后续版本。

## 3.6 打开 VS Code / Cursor

首选 macOS 原生启动方式，无需要求用户安装 shell command：

```text
/usr/bin/open -a "Visual Studio Code" <workspaceFilePath>
/usr/bin/open -a "Cursor" <workspaceFilePath>
```

执行方式必须是：

```ts
spawn('/usr/bin/open', ['-a', appName, workspaceFilePath], {
  shell: false,
});
```

行为：

- 启动前检查 `.code-workspace` 是否存在。
- 检查标准应用路径：`/Applications` 和 `~/Applications`。
- 应用不存在时禁用对应按钮并提示安装。
- VS Code 可以直接打开 `.code-workspace`。
- Cursor 当前不同版本的 shell command 行为可能有差异，因此 MVP 不依赖 `cursor` CLI；首选 macOS `open -a Cursor`。
- 如果 Cursor 不能正确处理 workspace 文件，详情页提供“在 Cursor 中打开代码根目录”的回退动作，调用 `open -a Cursor <workspaceRoot>`。

---

## 4. `.code-workspace` 文件规范

因为 workspace 文件可被放在代码目录之外，MVP 始终写入**绝对路径**：

```json
{
  "folders": [
    {
      "name": "order-api",
      "path": "/Users/rose/Developer/features/FEAT-123/order-api"
    },
    {
      "name": "payment-api",
      "path": "/Users/rose/Developer/features/FEAT-123/payment-api"
    }
  ],
  "extensions": {
    "recommendations": [
      "golang.go"
    ]
  }
}
```

写入规则：

- 输出标准 JSON，不写 JSONC 注释。
- repo 顺序与 manifest 中顺序一致。
- 路径使用 Node `path.resolve()` 产生绝对路径。
- 采用临时文件 + rename 原子替换。
- 只管理 `folders` 和 `extensions.recommendations`。
- MVP 不尝试合并用户手工写入的复杂 workspace settings；文件头部不加“请勿编辑”注释，因为 JSON 不支持注释。
- UI 应提示该文件由 ReqWS 管理，手工修改可能在增减 repo 时被覆盖。

---

## 5. 数据模型与持久化

## 5.1 全局存储位置

使用：

```ts
path.join(app.getPath('userData'), 'reqws', 'state.v1.json')
```

在 macOS 上，该文件位于应用自己的 `~/Library/Application Support/...` 目录下。

不把数据写入源码仓库，不使用 `localStorage` 作为主存储。

## 5.2 全局状态示例

```json
{
  "schemaVersion": 1,
  "settings": {
    "lastWorkspaceParentDirectory": "/Users/rose/Developer/features",
    "lastWorkspaceFileDirectory": "/Users/rose/Developer/vscode-workspaces"
  },
  "repositories": [
    {
      "id": "repo_01J...",
      "name": "order-api",
      "url": "git@gitlab.example.com:order/order-api.git",
      "defaultBranch": "main",
      "createdAt": "2026-08-12T07:00:00.000Z",
      "updatedAt": "2026-08-12T07:00:00.000Z"
    }
  ],
  "workspaces": [
    {
      "id": "ws_01J...",
      "name": "FEAT-123-payment-refund",
      "featureBranch": "feature/FEAT-123",
      "rootPath": "/Users/rose/Developer/features/FEAT-123-payment-refund",
      "workspaceFilePath": "/Users/rose/Developer/vscode-workspaces/FEAT-123-payment-refund.code-workspace",
      "repositoryNames": ["order-api", "payment-api"],
      "status": "ready",
      "createdAt": "2026-08-12T07:10:00.000Z",
      "updatedAt": "2026-08-12T07:10:00.000Z"
    }
  ]
}
```

全局文件是快速索引，不保存 Git 凭据。

## 5.3 workspace manifest

每个代码根目录内保存：

```text
<workspaceRoot>/.reqws/workspace.json
```

示例：

```json
{
  "schemaVersion": 1,
  "id": "ws_01J...",
  "name": "FEAT-123-payment-refund",
  "featureBranch": "feature/FEAT-123",
  "rootPath": "/Users/rose/Developer/features/FEAT-123-payment-refund",
  "workspaceFilePath": "/Users/rose/Developer/vscode-workspaces/FEAT-123-payment-refund.code-workspace",
  "repositories": [
    {
      "catalogRepositoryId": "repo_01J...",
      "name": "order-api",
      "url": "git@gitlab.example.com:order/order-api.git",
      "defaultBranch": "main",
      "relativePath": "order-api"
    }
  ],
  "createdAt": "2026-08-12T07:10:00.000Z",
  "updatedAt": "2026-08-12T07:10:00.000Z"
}
```

manifest 保存仓库快照，因此以后修改 catalog URL 不会改变旧 workspace 的事实记录。

## 5.4 原子写入

所有 JSON 写入统一使用：

```text
写入同目录的 .tmp 文件
  → flush/close
  → rename 覆盖目标文件
```

若 JSON 解析失败：

- 不覆盖损坏文件；
- UI 显示明确错误；
- 可把原文件复制为 `.corrupt-<timestamp>` 后由用户决定是否恢复。

---

## 6. 技术架构

```mermaid
flowchart LR
    UI[React Renderer] -->|Typed API| PRELOAD[Preload / contextBridge]
    PRELOAD -->|ipcRenderer.invoke| IPC[Electron Main IPC]
    IPC --> REPO[Repository Service]
    IPC --> WS[Workspace Service]
    IPC --> EDITOR[Editor Launcher]
    IPC --> DIALOG[Native Dialog Service]
    REPO --> STORE[Atomic JSON Store]
    WS --> STORE
    WS --> GIT[Git Process Runner]
    WS --> FILES[File / Manifest / Workspace Writer]
    EDITOR --> OPEN[/usr/bin/open]
    GIT --> BIN[/usr/bin/git or resolved git]
```

## 6.1 Renderer

职责：

- 表单、搜索、列表、详情抽屉、确认弹窗、进度展示。
- 不直接访问 Node.js、文件系统和 Git。
- 不拼接 shell 命令。

建议：

- React + TypeScript。
- React Context + `useReducer` 即可，不引入 Redux。
- 普通 CSS/CSS Modules，不引入大型 UI 框架。
- 可选使用 `lucide-react` 作为图标库。

## 6.2 Preload

只暴露窄接口：

```ts
interface ReqwsAPI {
  repositories: {
    list(): Promise<Repository[]>;
    create(input: CreateRepositoryInput): Promise<Repository>;
    update(input: UpdateRepositoryInput): Promise<Repository>;
    remove(id: string): Promise<void>;
    testConnection(input: TestRepositoryInput): Promise<TestRepositoryResult>;
  };
  workspaces: {
    list(): Promise<WorkspaceSummary[]>;
    get(id: string): Promise<WorkspaceDetail>;
    create(input: CreateWorkspaceInput): Promise<WorkspaceDetail>;
    addRepository(input: AddWorkspaceRepositoryInput): Promise<WorkspaceDetail>;
    removeRepository(input: RemoveWorkspaceRepositoryInput): Promise<WorkspaceDetail>;
    forget(id: string): Promise<void>;
  };
  dialogs: {
    selectDirectory(input: SelectDirectoryInput): Promise<string | null>;
  };
  editors: {
    getAvailability(): Promise<EditorAvailability>;
    openVSCode(workspaceId: string): Promise<void>;
    openCursor(workspaceId: string): Promise<void>;
    openCursorRoot(workspaceId: string): Promise<void>;
    revealInFinder(workspaceId: string): Promise<void>;
  };
}
```

禁止把 `ipcRenderer`、`fs`、`spawn` 或任意命令执行器直接暴露给 renderer。

## 6.3 Main process

职责：

- 窗口生命周期。
- 注册 IPC handler。
- 输入校验。
- Git 和文件系统操作。
- 持久化。
- 路径选择原生对话框。
- VS Code/Cursor/Finder 启动。

Electron 窗口最低安全配置：

```ts
new BrowserWindow({
  width: 1280,
  height: 820,
  minWidth: 1040,
  minHeight: 680,
  webPreferences: {
    preload: PRELOAD_ENTRY,
    nodeIntegration: false,
    contextIsolation: true,
    sandbox: true,
  },
});
```

并限制页面跳转、新窗口创建和外部 URL。

---

## 7. 推荐工程目录

```text
reqws-desktop/
├── package.json
├── package-lock.json
├── forge.config.ts
├── vite.main.config.ts
├── vite.preload.config.ts
├── vite.renderer.config.ts
├── tsconfig.json
├── src/
│   ├── main/
│   │   ├── index.ts
│   │   ├── create-window.ts
│   │   ├── ipc/
│   │   │   ├── register-ipc.ts
│   │   │   ├── repository-handlers.ts
│   │   │   ├── workspace-handlers.ts
│   │   │   └── editor-handlers.ts
│   │   └── services/
│   │       ├── atomic-json-store.ts
│   │       ├── repository-service.ts
│   │       ├── workspace-service.ts
│   │       ├── git-runner.ts
│   │       ├── workspace-file-writer.ts
│   │       ├── editor-launcher.ts
│   │       └── path-service.ts
│   ├── preload/
│   │   ├── index.ts
│   │   └── global.d.ts
│   ├── renderer/
│   │   ├── index.html
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── pages/
│   │   │   ├── WorkspacesPage.tsx
│   │   │   └── RepositoriesPage.tsx
│   │   ├── components/
│   │   │   ├── CreateWorkspaceDialog.tsx
│   │   │   ├── WorkspaceDetailDrawer.tsx
│   │   │   ├── RepositoryDialog.tsx
│   │   │   ├── DirectoryPickerField.tsx
│   │   │   ├── OperationDialog.tsx
│   │   │   └── Toast.tsx
│   │   └── styles/
│   │       └── app.css
│   └── shared/
│       ├── types.ts
│       ├── schemas.ts
│       ├── ipc-channels.ts
│       └── errors.ts
└── tests/
    ├── unit/
    └── integration/
```

---

## 8. IPC 合约

所有入参使用 Zod 或等价 schema 在 main process 再校验一次。

| Channel | 类型 | 作用 |
|---|---|---|
| `repositories:list` | invoke | 列出仓库目录 |
| `repositories:create` | invoke | 新增仓库 |
| `repositories:update` | invoke | 修改仓库 |
| `repositories:remove` | invoke | 删除 catalog 项 |
| `repositories:test` | invoke | `git ls-remote` 测试 |
| `workspaces:list` | invoke | 列出 workspace 索引 |
| `workspaces:get` | invoke | 读取 manifest 并返回详情 |
| `workspaces:create` | invoke | 创建完整 workspace |
| `workspaces:add-repository` | invoke | 给已有 workspace 加 repo |
| `workspaces:remove-repository` | invoke | 从 workspace 逻辑移除 repo |
| `workspaces:forget` | invoke | 仅从全局索引移除，不删除磁盘文件 |
| `dialogs:select-directory` | invoke | macOS 原生目录选择器 |
| `editors:availability` | invoke | 检查 VS Code/Cursor 是否存在 |
| `editors:open-vscode` | invoke | 打开 workspace 文件 |
| `editors:open-cursor` | invoke | 打开 workspace 文件 |
| `editors:open-cursor-root` | invoke | Cursor 回退打开代码根目录 |
| `editors:reveal-in-finder` | invoke | Finder 显示目录 |
| `operation:progress` | main → renderer | 进度事件 |

`operation:progress` 示例：

```ts
interface OperationProgress {
  operationId: string;
  kind: 'create-workspace' | 'add-repository' | 'test-repository';
  stage: 'validating' | 'cloning' | 'fetching' | 'switching' | 'writing' | 'done';
  repositoryName?: string;
  current: number;
  total: number;
  message: string;
}
```

---

## 9. Git 执行规范

## 9.1 Git 可执行文件

启动时按顺序解析：

1. `git` from `PATH`；
2. `/usr/bin/git`；
3. `/opt/homebrew/bin/git`；
4. `/usr/local/bin/git`。

通过 `git --version` 验证。

未找到 Git 时应用仍可打开仓库列表，但禁用测试连接、创建和增仓库，并显示安装提示。

## 9.2 统一 runner

```ts
interface GitRunOptions {
  cwd?: string;
  timeoutMs?: number;
  env?: NodeJS.ProcessEnv;
  onStdout?: (chunk: string) => void;
  onStderr?: (chunk: string) => void;
}

interface GitRunResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}
```

约束：

- 使用 `spawn(gitPath, args, { shell: false })`。
- 不使用 `exec()` 拼接字符串。
- URL、分支、路径全部作为独立参数。
- stdout/stderr 日志设内存上限，例如每路 1 MiB；UI 中只保留最近内容。
- `git ls-remote` 超时 30 秒；clone 默认不设短超时。
- 不修改用户全局 Git 配置。
- 不读取或保存密码。

## 9.3 分支名校验

在实际操作前执行：

```text
git check-ref-format --branch <featureBranch>
```

只依赖 Git 自身校验，不手写不完整的正则表达式。

---

## 10. 关键操作算法

## 10.1 创建 workspace

伪代码：

```ts
async function createWorkspace(input: CreateWorkspaceInput) {
  validateInput(input);
  ensureFinalRootDoesNotExist(input.rootPath);
  ensureWorkspaceFileDoesNotExist(input.workspaceFilePath);
  await validateBranch(input.featureBranch);

  const stagingRoot = createSiblingStagingDirectory(input.rootPath);

  try {
    const snapshots = [];

    for (const repo of input.repositories) {
      const repoPath = path.join(stagingRoot, repo.name);
      await git.clone(repo.url, repoPath);
      await checkoutFeatureBranch(repoPath, repo.defaultBranch, input.featureBranch);
      snapshots.push(toWorkspaceRepositorySnapshot(repo));
    }

    const manifest = buildManifest(input, snapshots, input.rootPath);
    await writeManifest(path.join(stagingRoot, '.reqws', 'workspace.json'), manifest);
    await fs.rename(stagingRoot, input.rootPath);
    await writeCodeWorkspaceAtomically(input.workspaceFilePath, manifest.repositories);
    await stateStore.addWorkspace(toWorkspaceSummary(manifest));

    return manifest;
  } catch (error) {
    await safeRemoveStaging(stagingRoot);
    throw normalizeReqwsError(error);
  }
}
```

注意：manifest 中记录的是最终 `rootPath`，不是 staging 路径。

## 10.2 Checkout feature branch

```ts
async function checkoutFeatureBranch(repoPath, defaultBranch, featureBranch) {
  await git(repoPath, ['fetch', 'origin', '--prune']);

  if (await refExists(repoPath, `refs/remotes/origin/${featureBranch}`)) {
    await git(repoPath, [
      'switch', '--track', '-c', featureBranch, `origin/${featureBranch}`,
    ]);
    return;
  }

  assert(await refExists(repoPath, `refs/remotes/origin/${defaultBranch}`));
  await git(repoPath, [
    'switch', '-c', featureBranch, `origin/${defaultBranch}`,
  ]);
}
```

实际实现还要处理 clone 后本地 feature 分支已经存在的情况：存在则直接 `git switch <featureBranch>`。

## 10.3 增加 repo

```text
确认 workspace Ready
  → 确认 catalog repo 尚未在 manifest 中
  → 检查目标目录
  → 不存在则 clone；匹配的现有 clone 则复用
  → 切换 feature branch
  → 原子更新 manifest
  → 原子更新 .code-workspace
  → 更新全局索引
```

manifest 与 `.code-workspace` 任一写入失败时，保留 clone，但把该操作标记为 Error，并允许用户重试“同步 workspace 文件”。不得静默声称成功。

## 10.4 移除 repo

```text
读取 workspace manifest
  → 删除指定 repo entry
  → 原子写 manifest
  → 原子重写 .code-workspace
  → 更新全局索引
  → 告知本地 repo 目录仍保留
```

MVP 不执行 `rm -rf`。

---

## 11. 路径与文件安全

- Workspace 名称只用于显示和默认文件名；文件名须经过 slug 化。
- 用户选择的是绝对目录；禁止通过相对路径逃逸。
- 仓库名称不能包含 `/`、`\`、空字符串、`.` 或 `..`。
- `path.join(root, repoName)` 后必须验证结果仍在 root 内。
- 最终代码目录已经存在时禁止创建，避免覆盖。
- `.code-workspace` 已存在时禁止覆盖；用户必须修改名称或目录。
- 符号链接路径不用于绕过父目录检查；关键写入前使用 `realpath`/父目录检查。
- 不从 renderer 接收任意“要执行的命令”。
- 错误日志可以展示 Git stderr，但不要把环境变量整体打印出来。

---

## 12. UI 信息架构

应用只保留两个一级页面：

```text
Workspaces
Repositories
```

设置不单独做页面，只在表单中记住用户上次选择的目录。

### 12.1 Workspaces 页面

```text
┌──────────────────────────────────────────────────────────────────────┐
│ ReqWS                      Workspaces                  [+ 创建]      │
├───────────────┬──────────────────────────────────────────────────────┤
│ Workspaces    │ [搜索名称、分支、仓库、路径...]                      │
│ Repositories  │                                                      │
│               │ FEAT-123-payment-refund        Ready                 │
│               │ feature/FEAT-123                                      │
│               │ order-api  payment-api                                │
│               │ ~/Developer/features/FEAT-123                         │
│               │ [VS Code] [Cursor] [详情]                             │
│               │                                                      │
│               │ FEAT-456-order-filter         Missing                │
│               │ feature/FEAT-456                                      │
│               │ order-api  gateway-api                                │
│               │ [VS Code] [Cursor] [详情]                             │
└───────────────┴──────────────────────────────────────────────────────┘
```

### 12.2 创建 Workspace 对话框

```text
┌──────────────────── 创建 Feature Workspace ─────────────────────────┐
│ Workspace 名称 *                                                     │
│ [ FEAT-123-payment-refund                                      ]      │
│                                                                      │
│ Feature 分支 *                                                       │
│ [ feature/FEAT-123                                             ]      │
│                                                                      │
│ 代码目录 *                                     [选择...]             │
│ [ /Users/rose/Developer/features/FEAT-123-payment-refund      ]      │
│                                                                      │
│ Workspace 文件目录 *                           [选择...]             │
│ [ /Users/rose/Developer/vscode-workspaces                       ]      │
│ 将生成：FEAT-123-payment-refund.code-workspace                       │
│                                                                      │
│ 选择仓库                                               [搜索...]     │
│ [x] order-api       main       git@.../order-api.git                  │
│ [x] payment-api     main       git@.../payment-api.git                │
│ [ ] account-sdk     develop    git@.../account-sdk.git                │
│                                                                      │
│                                      [取消] [创建 Workspace]          │
└──────────────────────────────────────────────────────────────────────┘
```

### 12.3 Workspace 详情抽屉

```text
┌──────────────────────────── Workspace 详情 ──────────────────────────┐
│ FEAT-123-payment-refund                                  Ready        │
│ feature/FEAT-123                                                     │
│                                                                      │
│ 代码目录       /Users/rose/Developer/features/FEAT-123               │
│ Workspace 文件 /Users/rose/.../FEAT-123.code-workspace               │
│ [在 Finder 中显示] [VS Code] [Cursor] [Cursor 打开根目录]             │
│                                                                      │
│ Repositories                                          [+ 增加仓库]   │
│ order-api       feature/FEAT-123        [从 Workspace 移除]           │
│ payment-api     feature/FEAT-123        [从 Workspace 移除]           │
│                                                                      │
│ 提示：移除不会删除本地仓库目录。                                     │
└──────────────────────────────────────────────────────────────────────┘
```

### 12.4 Repositories 页面

```text
┌──────────────────────────────────────────────────────────────────────┐
│ Repositories                                      [+ 录入仓库]       │
│ [搜索名称或 Git 地址...]                                             │
│                                                                      │
│ order-api      git@gitlab.../order-api.git      main      [编辑]     │
│ payment-api    git@gitlab.../payment-api.git    main      [编辑]     │
│ account-sdk    git@gitlab.../account-sdk.git    develop   [编辑]     │
└──────────────────────────────────────────────────────────────────────┘
```

### 12.5 视觉规范

- 使用 macOS 风格浅色界面。
- 左侧固定导航，右侧内容区。
- 主要色只用于主按钮与选中状态。
- 错误使用明确文字，不只依赖颜色。
- 路径使用等宽字体并允许复制。
- 危险操作必须二次确认。
- Git 长操作使用模态进度框，避免用户重复提交。

本交付附带可交互的静态 HTML 原型：`reqws-desktop-mvp-ui-prototype.html`。

---

## 13. 错误模型

定义稳定错误码，renderer 不依赖 Git 原始文本判断逻辑：

| 错误码 | 场景 |
|---|---|
| `GIT_NOT_FOUND` | 找不到 Git |
| `INVALID_REPOSITORY_NAME` | repo 名称非法 |
| `DUPLICATE_REPOSITORY_NAME` | 名称重复 |
| `INVALID_BRANCH_NAME` | Git 分支名非法 |
| `REPOSITORY_UNREACHABLE` | `ls-remote` 失败 |
| `WORKSPACE_ROOT_EXISTS` | 代码目录已存在 |
| `WORKSPACE_FILE_EXISTS` | `.code-workspace` 已存在 |
| `CLONE_FAILED` | clone 失败 |
| `DEFAULT_BRANCH_NOT_FOUND` | 远端基线不存在 |
| `FEATURE_BRANCH_CHECKOUT_FAILED` | 切分支失败 |
| `WORKSPACE_NOT_FOUND` | 索引或 manifest 不存在 |
| `WORKSPACE_PATH_MISSING` | 磁盘路径丢失 |
| `REPOSITORY_PATH_CONFLICT` | 目标目录存在但不是匹配 repo |
| `EDITOR_NOT_FOUND` | VS Code/Cursor 未安装 |
| `STATE_WRITE_FAILED` | 全局状态写入失败 |
| `MANIFEST_WRITE_FAILED` | manifest 写入失败 |
| `WORKSPACE_FILE_WRITE_FAILED` | workspace 文件写入失败 |

统一返回：

```ts
interface ReqwsErrorPayload {
  code: string;
  message: string;
  detail?: string;
  repositoryName?: string;
  stage?: string;
}
```

`detail` 可包含经过长度限制的 Git stderr，用于复制排查。

---

## 14. 启动与开发脚本

要求使用 Node.js 24 LTS，并提交 `.nvmrc`：

```text
24
```

`package.json` 最低脚本：

```json
{
  "scripts": {
    "start": "electron-forge start",
    "typecheck": "tsc --noEmit",
    "lint": "eslint .",
    "test": "vitest run",
    "test:watch": "vitest",
    "check": "npm run typecheck && npm run lint && npm test"
  }
}
```

本地运行：

```bash
nvm use
npm ci
npm start
```

本项目不要求：

```bash
npm run make
npm run package
```

---

## 15. 测试策略

## 15.1 单元测试

至少覆盖：

- 从 HTTPS/SSH URL 推导 repo 名称。
- repo 名称和 workspace 文件名 slug 化。
- 路径逃逸检查。
- `.code-workspace` JSON 生成。
- manifest 增减 repo。
- 全局 state 原子写入。
- Git 错误映射到稳定错误码。
- 搜索对名称、branch、repo 和路径的匹配。

## 15.2 Git 集成测试

测试不得依赖公网。测试中创建临时本地 bare repo：

```text
origin-order.git
origin-payment.git
```

验证：

1. 从默认分支创建新的 feature 分支。
2. 远端 feature 分支存在时正确 tracking。
3. 默认分支不存在时失败并清理 staging。
4. 给已有 workspace 增加 repo 后 manifest 和 workspace 文件同步。
5. 移除 repo 后本地 repo 目录仍存在。

## 15.3 Renderer 测试

- 新增 repo 表单校验。
- 创建 workspace 至少选择一个 repo。
- 搜索过滤。
- 详情抽屉增减 repo。
- Editor 不存在时按钮禁用。
- 操作进行中阻止重复提交。

## 15.4 手工 macOS Smoke

在真实 macOS 上验证：

- SSH Git 地址可使用本机 SSH Agent clone。
- HTTPS Git 地址可使用系统 Git credential helper。
- 原生目录选择器可创建/选择目录。
- VS Code 打开 `.code-workspace` 后显示所有 repo root。
- Cursor 打开 `.code-workspace`；不兼容时回退打开 root。
- 应用退出重开后 repo 和 workspace 索引仍存在。

---

## 16. Definition of Done / 验收清单

- [ ] `npm ci && npm start` 可以在 macOS 打开应用。
- [ ] 不需要 DMG、签名或 `/Applications` 安装。
- [ ] 可以新增、编辑、删除仓库目录项。
- [ ] Git URL 能自动推导可编辑的 repo 名称。
- [ ] 可以选择至少一个 repo 创建 workspace。
- [ ] 用户可分别指定代码目录和 `.code-workspace` 文件目录。
- [ ] 每个选中仓库都是 workspace 目录下的完整 clone，并拥有独立 `.git`。
- [ ] 远端 feature 分支存在时正确 tracking；不存在时从 catalog 默认分支创建。
- [ ] 任一 clone/checkout 失败时不登记半成品 workspace。
- [ ] `.code-workspace` 使用绝对路径并包含全部已加入 repo。
- [ ] Workspace 页面支持按名称、分支、repo、路径搜索。
- [ ] 可以给已有 workspace 增加 repo。
- [ ] 可以从 workspace 移除 repo，且默认不删除本地目录。
- [ ] 增减 repo 后 manifest、全局索引和 `.code-workspace` 保持一致。
- [ ] 可以打开 VS Code。
- [ ] 可以打开 Cursor；同时提供打开代码根目录的回退入口。
- [ ] Renderer 没有 Node integration，所有本地能力经 preload 窄接口调用。
- [ ] 所有进程执行使用参数数组且 `shell: false`。
- [ ] 核心单元测试和本地 bare repo 集成测试通过。
- [ ] `npm run check` 全绿。

---

## 17. 推荐实施顺序

### 阶段 1：工程骨架

- Electron Forge Vite TypeScript。
- React renderer。
- preload typed API。
- 两个空页面和导航。
- `npm start`、typecheck、lint、test。

### 阶段 2：本地存储和仓库目录

- 原子 JSON store。
- repository CRUD。
- repo 名称推导。
- `git ls-remote` 测试。
- Repositories 页面。

### 阶段 3：创建 workspace

- 目录选择器。
- Git runner。
- staging + rollback。
- clone 和 branch 语义。
- manifest 与 `.code-workspace` 写入。
- 创建进度 UI。

### 阶段 4：列表、搜索与打开编辑器

- workspace 索引。
- 搜索。
- 路径完整性状态。
- VS Code/Cursor/Finder launcher。

### 阶段 5：增减 repo

- Workspace 详情抽屉。
- 增加 repo。
- 逻辑移除 repo，保留磁盘目录。
- 三份状态同步与错误恢复。

### 阶段 6：测试和收尾

- 单元测试。
- 本地 bare repo 集成测试。
- macOS smoke。
- README 运行说明。

---

## 18. Codex 实现时不得擅自改变的约束

1. 不把方案改成 Web 服务或浏览器页面。
2. 不引入 DMG/打包工作作为完成条件。
3. 不使用 Git worktree。
4. 不使用共享 `.git`、共享 clone 或 mirror 优化。
5. 不自动 push 分支。
6. 不保存 token、密码或 SSH key。
7. 不让 renderer 获得完整 Node、fs、shell 或 `ipcRenderer` 权限。
8. 不使用 `exec()` 拼接用户输入。
9. 不在 MVP 中删除被移除 repo 的本地目录。
10. 不增加 GitHub/GitLab API、PR/MR、GoLand、Windows/Linux 等范围。
11. 不在 workspace 创建失败后留下已登记的半成品记录。
12. 不覆盖用户已经存在的代码目录或 `.code-workspace` 文件。

---

## 19. 可后续演进但不属于本次实现的能力

- 安全删除 clone：检查 dirty、upstream 和未 push commit 后再允许删除。
- 批量 fetch/pull/push。
- 每个 repo 使用不同 feature branch 或基线。
- Repo 标签与分组。
- Go `go.work` 自动生成。
- GoLand/IntelliJ 打开。
- Clone 并发与取消。
- 本地 Git object cache。
- 导入/导出 repository catalog。
- 自动扫描磁盘恢复 workspace 索引。
- 菜单栏、最近 workspace、快捷键。
- 打包、签名、公证与自动更新。

---

## 20. 参考资料

以下只用于确认底层能力和安全边界，产品行为以本文档冻结的约束为准：

- Electron Process Model: https://www.electronjs.org/docs/latest/tutorial/process-model
- Electron Context Isolation: https://www.electronjs.org/docs/latest/tutorial/context-isolation
- Electron Security Checklist: https://www.electronjs.org/docs/latest/tutorial/security
- Electron `dialog`: https://www.electronjs.org/docs/latest/api/dialog
- Electron `app.getPath('userData')`: https://www.electronjs.org/docs/latest/api/app
- Electron Forge Vite + TypeScript: https://www.electronforge.io/templates/vite-%2B-typescript
- Node.js `child_process`: https://nodejs.org/api/child_process.html
- Node.js release status: https://nodejs.org/en/about/previous-releases
- VS Code Multi-root Workspaces: https://code.visualstudio.com/docs/editing/workspaces/multi-root-workspaces
- VS Code CLI: https://code.visualstudio.com/docs/configure/command-line
- Cursor Quickstart: https://docs.cursor.com/en/get-started/quickstart

