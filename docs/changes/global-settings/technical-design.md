---
title: ReqWS MVP 全局配置页面增量技术方案
type: technical-design
status: active
updated: 2026-08-14
---

# ReqWS MVP 全局配置页面增量技术方案

本文定义 Settings 一级页面、全局默认目录和界面国际化的需求范围、跨进程设计、兼容策略与验收方式。

## 1. 变更结论

ReqWS MVP 新增独立的一级页面：

```text
全局设置 / Settings
```

顶层导航由原来的：

```text
Workspaces
Repositories
```

调整为：

```text
Workspaces
Repositories
Settings
```

原技术方案中的：

> 设置不单独做页面，只在表单中记住用户上次选择的目录。

由本方案废止。

全局设置页面负责管理：

1. 界面语言。
2. 功能工作区默认父目录。
3. VS Code `.code-workspace` 文件默认存放目录。

继续沿用现有的：

* Electron Main
* React Renderer
* preload + typed IPC
* `state.v1.json`
* 原子 JSON 写入
* 静态 i18n JSON

不引入：

* `electron-store`
* 独立设置数据库
* 配置管理框架
* 动态语言包
* 在线配置服务

---

## 2. 页面设计

### 2.1 页面结构

```text
设置
├── 通用
│   └── 界面语言
│
├── 工作区默认设置
│   ├── 功能工作区父目录
│   └── VS Code workspace 文件目录
│
└── 保存设置
```

建议页面布局：

```text
┌────────────────────────────────────────────────────┐
│ 设置                                                │
│                                                    │
│ 通用                                                │
│                                                    │
│ 界面语言                                            │
│ [ 跟随系统                                 ▾ ]      │
│                                                    │
│ 工作区默认设置                                      │
│                                                    │
│ 功能工作区父目录                                    │
│ [ /Users/example/Developer/features       ] [选择] │
│                                                    │
│ VS Code workspace 文件目录                         │
│ [ /Users/example/Developer/vscode-workspaces ] [选择] │
│                                                    │
│                              [保存设置]             │
└────────────────────────────────────────────────────┘
```

MVP 只提供一个保存按钮，不实现：

* 设置搜索
* 多级设置导航
* 自动保存
* 恢复默认值
* 设置导入导出
* 云端同步

---

## 3. 设置项定义

### 3.1 界面语言

支持：

```text
system
zh-CN
en-US
```

页面显示：

| 持久化值     | 中文界面    | 英文界面          |
| -------- | ------- | ------------- |
| `system` | 跟随系统    | Follow system |
| `zh-CN`  | 简体中文    | 简体中文          |
| `en-US`  | English | English       |

`system` 根据 macOS 的：

```ts
app.getPreferredSystemLanguages()
```

解析最终语言。

解析规则保持简单：

```text
首选语言列表中存在 zh-* → zh-CN
其他情况                  → en-US
```

用户保存语言设置后：

1. Main 写入 `state.v1.json`。
2. Main 返回最终生效语言。
3. Renderer 调用 `i18n.changeLanguage()`。
4. 当前页面和其他 React 页面立即切换。
5. 不需要重新启动应用。

---

### 3.2 功能工作区默认父目录

配置名称：

```ts
workspaceParentDirectory
```

用途：

```text
存放按需求创建的物理隔离工作区
```

例如：

```text
~/Developer/features
```

创建名称为：

```text
feat-user-management
```

的工作区时，默认生成：

```text
~/Developer/features/feat-user-management
```

该值只作为创建工作区表单的默认值。

用户仍可以在单次创建过程中选择其他目录，但单次覆盖不会自动修改全局设置。

---

### 3.3 `.code-workspace` 默认存放目录

配置名称：

```ts
workspaceFileDirectory
```

例如：

```text
~/Developer/vscode-workspaces
```

创建：

```text
feat-user-management
```

时，默认生成：

```text
~/Developer/vscode-workspaces/feat-user-management.code-workspace
```

该值同样只作为创建表单默认值。

单次创建时选择其他目录，不反向更新全局设置。

---

## 4. 状态模型

继续使用：

```text
path.join(
  app.getPath("userData"),
  "reqws",
  "state.v1.json",
)
```

建议将全局设置统一放入：

```json
{
  "version": 1,
  "settings": {
    "localePreference": "system",
    "workspaceParentDirectory": "/Users/example/Developer/features",
    "workspaceFileDirectory": "/Users/example/Developer/vscode-workspaces"
  }
}
```

TypeScript 定义：

```ts
export type SupportedLocale =
  | "zh-CN"
  | "en-US";

export type LocalePreference =
  | "system"
  | SupportedLocale;

export interface GlobalSettings {
  localePreference: LocalePreference;
  workspaceParentDirectory: string | null;
  workspaceFileDirectory: string | null;
}

export interface ResolvedGlobalSettings
  extends GlobalSettings {
  effectiveLocale: SupportedLocale;
}
```

其中：

```text
localePreference
```

表示用户选择；

```text
effectiveLocale
```

表示应用当前实际使用的语言。

`effectiveLocale` 由 Main 计算，只返回给 Renderer，不写入配置文件。

---

## 5. 旧状态兼容

`settings` 或新增字段不存在时，读取逻辑必须提供默认值：

```ts
const defaultSettings: GlobalSettings = {
  localePreference: "system",
  workspaceParentDirectory: null,
  workspaceFileDirectory: null,
};
```

兼容规则：

1. `settings` 不存在时使用默认值。
2. `localePreference` 不合法时回退为 `system`。
3. 路径不是字符串时回退为 `null`。
4. 旧版本已经记录的“最近使用目录”可以在首次读取时迁移为对应的全局默认目录。
5. 不因新增可选字段提升 `state.v1.json` 的版本号。
6. 不允许状态字段错误阻止应用启动。

状态写入继续使用：

```text
同目录临时文件
    ↓
完整写入并关闭
    ↓
原子 rename
```

---

## 6. Main 进程设计

新增一个集中式服务：

```text
src/main/services/settings-service.ts
```

职责：

* 读取全局设置。
* 补齐默认值。
* 兼容旧状态。
* 校验保存请求。
* 解析系统语言。
* 原子写入设置。
* 返回最终生效设置。

建议接口：

```ts
export interface SettingsService {
  get(): Promise<ResolvedGlobalSettings>;

  save(
    settings: GlobalSettings,
  ): Promise<ResolvedGlobalSettings>;
}
```

实现示意：

```ts
export class DefaultSettingsService
  implements SettingsService {
  async get(): Promise<ResolvedGlobalSettings> {
    const state = await stateRepository.read();

    const settings = normalizeSettings(
      state.settings,
    );

    return {
      ...settings,
      effectiveLocale:
        resolveEffectiveLocale(
          settings.localePreference,
        ),
    };
  }

  async save(
    input: GlobalSettings,
  ): Promise<ResolvedGlobalSettings> {
    const settings =
      await validateSettings(input);

    await stateRepository.update((state) => ({
      ...state,
      settings,
    }));

    return {
      ...settings,
      effectiveLocale:
        resolveEffectiveLocale(
          settings.localePreference,
        ),
    };
  }
}
```

所有设置更新必须通过现有的集中式 state repository 完成，不能在 IPC handler 中直接使用 `fs.writeFile()`。

---

## 7. IPC 设计

上一版单独设计的：

```text
locale:get
locale:set-preference
```

不再需要。

统一改为：

```text
settings:get
settings:save
```

### 7.1 `settings:get`

请求：

```ts
undefined
```

响应：

```ts
interface ResolvedGlobalSettings {
  localePreference:
    | "system"
    | "zh-CN"
    | "en-US";

  effectiveLocale:
    | "zh-CN"
    | "en-US";

  workspaceParentDirectory:
    | string
    | null;

  workspaceFileDirectory:
    | string
    | null;
}
```

### 7.2 `settings:save`

请求：

```ts
interface SaveGlobalSettingsRequest {
  localePreference:
    | "system"
    | "zh-CN"
    | "en-US";

  workspaceParentDirectory:
    | string
    | null;

  workspaceFileDirectory:
    | string
    | null;
}
```

响应同 `settings:get`。

Main 必须重新验证所有字段，不能信任 Renderer。

---

## 8. Preload API

在现有：

```ts
window.reqws
```

下增加：

```ts
interface ReqwsSettingsApi {
  get(): Promise<ResolvedGlobalSettings>;

  save(
    settings: GlobalSettings,
  ): Promise<ResolvedGlobalSettings>;
}
```

暴露形式：

```ts
contextBridge.exposeInMainWorld(
  "reqws",
  {
    settings: {
      get: () =>
        ipcRenderer.invoke(
          "settings:get",
        ),

      save: (
        settings: GlobalSettings,
      ) =>
        ipcRenderer.invoke(
          "settings:save",
          settings,
        ),
    },

    // 保留现有其他窄化 API
  },
);
```

不暴露：

```text
ipcRenderer
任意 channel 名称
fs
path
Electron 原始 API
state.v1.json 文件路径
```

目录选择继续复用现有窄化的目录选择 IPC。

例如：

```ts
window.reqws.dialog.selectDirectory()
```

---

## 9. Renderer 页面设计

建议文件：

```text
src/renderer/pages/settings/
├── SettingsPage.tsx
├── GeneralSettingsSection.tsx
├── WorkspaceDefaultsSection.tsx
└── useSettingsForm.ts
```

不引入新的表单库。

使用普通 React state：

```ts
interface SettingsFormState {
  localePreference: LocalePreference;
  workspaceParentDirectory: string | null;
  workspaceFileDirectory: string | null;
}
```

页面加载流程：

```text
进入 Settings 页面
        ↓
window.reqws.settings.get()
        ↓
初始化本地表单状态
        ↓
用户修改字段
        ↓
点击“保存设置”
        ↓
window.reqws.settings.save()
        ↓
保存成功
        ↓
i18n.changeLanguage(effectiveLocale)
        ↓
显示保存成功提示
```

按钮状态：

```text
初始加载中          → 禁用保存
没有修改            → 禁用保存
正在保存            → 禁用所有输入
保存成功            → 清除 dirty 状态
保存失败            → 保留用户输入并显示错误
```

---

## 10. 目录选择

目录输入框采用：

```text
只读输入框 + 选择目录按钮
```

不允许用户直接编辑路径字符串。

这样可以避免处理：

* `~` 展开
* 相对路径
* 路径拼写错误
* 不存在的路径
* 不同路径分隔符
* 手工粘贴非法内容

用户点击“选择”后调用现有目录选择器。

允许目录值为空。为空时，创建工作区页面要求用户在创建前选择目录。

Main 保存时仍须校验：

```text
值为 null
或
绝对路径 + 已存在 + 类型为目录
```

目录写权限仍需在真正创建工作区前再次检查，因为保存设置后权限可能发生变化。

---

## 11. 创建工作区页面联动

创建工作区页面初始化时调用：

```ts
const settings =
  await window.reqws.settings.get();
```

并填充：

```ts
form.workspaceParentDirectory =
  settings.workspaceParentDirectory;

form.workspaceFileDirectory =
  settings.workspaceFileDirectory;
```

目录解析规则：

```text
全局默认目录存在
    → 预填表单

全局默认目录为空
    → 表单为空，用户必须选择

全局目录已被删除或不可用
    → 显示目录不可用，要求重新选择
```

创建表单中的临时修改只影响当前工作区创建，不更新全局设置。

这样全局设置具有稳定、可预测的语义。

---

## 12. i18n 初始化调整

应用启动时不再调用单独的：

```ts
window.reqws.locale.get()
```

改为：

```ts
window.reqws.settings.get()
```

初始化流程：

```ts
async function bootstrap(): Promise<void> {
  const settings =
    await window.reqws.settings.get();

  await initializeI18n(
    settings.effectiveLocale,
  );

  createRoot(
    document.getElementById("root")!,
  ).render(<App />);
}

void bootstrap();
```

必须在 React 首次渲染前完成语言初始化，避免：

```text
先显示英文
    ↓
再切换中文
```

造成界面闪烁。

---

## 13. 设置页面翻译 key

在 `zh-CN.json` 增加：

```json
{
  "navigation": {
    "settings": "设置"
  },
  "settings": {
    "title": "设置",
    "generalSection": "通用",
    "language": {
      "label": "界面语言",
      "description": "选择 ReqWS 使用的界面语言",
      "system": "跟随系统",
      "zhCN": "简体中文",
      "enUS": "English"
    },
    "workspaceSection": "工作区默认设置",
    "workspaceParentDirectory": {
      "label": "功能工作区父目录",
      "description": "新功能工作区默认创建在此目录下"
    },
    "workspaceFileDirectory": {
      "label": "VS Code workspace 文件目录",
      "description": "用于存放自动生成的 .code-workspace 文件"
    },
    "selectDirectory": "选择",
    "save": "保存设置",
    "saving": "正在保存…",
    "saved": "设置已保存"
  },
  "errors": {
    "settingsLoadFailed": "无法加载设置",
    "settingsSaveFailed": "无法保存设置",
    "settingsDirectoryInvalid": "所选目录无效或已经不存在"
  }
}
```

英文翻译应基于中文源文案生成并经过指定翻译 subagent 复核，避免只复制中文占位文本。

按照仓库内可复现的 i18n 流程执行：

```text
修改 zh-CN.json
    ↓
npm run i18n:scan
    ↓
以 GPT-5.6 Sol/Pro、reasoning high 或更高调用翻译 subagent
    ↓
subagent 只返回结构化翻译，不直接改文件
    ↓
主 Agent 校验 key、中文源文案、占位符、复数与术语并写入 en-US.json
    ↓
npm run i18n:apply
    ↓
npm run i18n:check
```

详细门禁和结构化输出契约以项目级 [`reqws-i18n` Skill](../../../.agents/skills/reqws-i18n/SKILL.md) 为准。翻译 subagent 必须显式使用 GPT-5.6 Sol 或 Pro，reasoning 不低于 `high`；模型或推理级别不可用时停止流程，不允许由主 Agent 自行翻译、降级到其他模型或更新同步基线。

`npm run i18n:apply` 会在 key、占位符和源码引用校验通过后更新同步基线；`npm run i18n:check` 确认提交内容与该基线一致。中文 key 已存在但源文案发生变化、复数形式变化或占位符变化，也必须重新触发翻译审查。

设置页面不需要单独建立第二套翻译流程。

---

## 14. 路由和导航

在现有 React 页面状态或路由中增加：

```ts
type AppPage =
  | "workspaces"
  | "repositories"
  | "settings";
```

MVP 不需要为了三个页面引入新的路由库。

如果当前项目已经使用 React Router，则增加：

```text
/workspaces
/repositories
/settings
```

如果当前项目没有路由库，则继续使用现有顶层状态切页，避免为了设置页面增加依赖。

设置页应为独立一级页面，而不是：

* 模态框
* 创建工作区表单中的折叠区域
* 语言下拉快捷菜单
* 单独的系统原生窗口

---

## 15. 错误处理

建议增加稳定错误码：

```text
SETTINGS_INVALID_LOCALE
SETTINGS_DIRECTORY_NOT_FOUND
SETTINGS_DIRECTORY_NOT_DIRECTORY
SETTINGS_READ_FAILED
SETTINGS_WRITE_FAILED
```

Main 返回错误码和技术诊断信息。

Renderer 负责将错误码映射为本地化文案。

例如：

```ts
const settingsErrorKeys = {
  SETTINGS_INVALID_LOCALE:
    "errors.settingsSaveFailed",

  SETTINGS_DIRECTORY_NOT_FOUND:
    "errors.settingsDirectoryInvalid",

  SETTINGS_DIRECTORY_NOT_DIRECTORY:
    "errors.settingsDirectoryInvalid",

  SETTINGS_READ_FAILED:
    "errors.settingsLoadFailed",

  SETTINGS_WRITE_FAILED:
    "errors.settingsSaveFailed",
} as const;
```

文件路径和底层异常可以写入日志，但不应直接完整展示给普通用户。

Renderer 必须保留跨进程错误中的稳定 `code`、`message`、`stage`、`detail` 和 `repositoryName`。Toast 至少显示“稳定错误码 · 本地化消息”；需要操作诊断的页面使用统一错误面板显示阶段、可展开技术信息和复制日志入口，不能把结构化错误提前压平成字符串。

工作区状态和操作进度只用稳定枚举跨进程表达需要本地化的语义：

```ts
type WorkspaceArtifact =
  | "workspace-root"
  | "manifest"
  | "workspace-file";

type OperationRollbackReason =
  | "CLEANING_STAGING"
  | "RETAINING_PUBLISHED_ARTIFACTS";
```

`WorkspaceSummary` 和 `WorkspaceDetail` 在路径缺失时返回 `missingArtifacts`，Renderer 将每项翻译后列出；旧 payload 没有该字段时才回退到通用提示。`OperationProgress` 在 `rolling-back` 阶段返回可选 `rollbackReason`，Renderer 优先按该枚举区分“清理未发布 staging”和“保留已发布工件供恢复”，不能只按阶段显示可能与 Main 实际动作相反的文案。原始 `message` 和 `statusDetail` 仅保留为兼容或诊断信息，不直接作为本地化 UI 的事实源。

---

## 16. 自动测试

### 16.1 Settings Service

覆盖：

1. 缺失 `settings` 时返回默认值。
2. 缺失语言字段时返回 `system`。
3. 非法语言值回退为 `system`。
4. 中文系统语言解析为 `zh-CN`。
5. 非中文系统语言解析为 `en-US`。
6. 合法设置可以保存并重新读取。
7. 保存后不会覆盖 state 中的仓库和工作区数据。
8. 状态写入失败时原文件保持完整。
9. 非目录路径被拒绝。
10. 相对路径被拒绝。

### 16.2 IPC

覆盖：

1. `settings:get` 返回标准化结果。
2. `settings:save` 接受合法完整配置。
3. 拒绝未知语言。
4. 拒绝额外危险字段。
5. Renderer 无法指定任意状态文件路径。
6. Renderer 无法调用任意 IPC channel。

### 16.3 React

覆盖：

1. 设置页面正确加载当前值。
2. 未修改时保存按钮禁用。
3. 选择语言后页面进入 dirty 状态。
4. 保存成功后调用 `i18n.changeLanguage()`。
5. 保存失败时保留表单内容。
6. 目录选择器结果正确填入表单。
7. 创建工作区表单使用全局默认目录。
8. 创建表单中的单次路径覆盖不会修改全局设置。
9. App 级错误 Toast 保留稳定错误码和对应的本地化消息。
10. Settings 保存或选目录失败时保留错误码、阶段和可复制技术诊断。
11. 工作区详情使用中英文准确列出缺失的 root、manifest 或 `.code-workspace`。
12. 创建失败在未发布和已发布两种回滚分支返回不同稳定原因，Renderer 显示对应文案。
13. 已失效的全局默认目录在创建对话框中显示与字段关联的警告；选择单次替代目录后清除警告且不保存全局设置。

### 16.4 i18n

继续执行：

```bash
npm run i18n:check
```

确保：

* `zh-CN` 和 `en-US` key 完全一致。
* 设置页面不存在遗漏翻译。
* 占位符保持一致。
* 中文源文案修改后英文翻译会被标记为过期。
* 缺失工件枚举和回滚原因枚举都有中英文映射。
* 翻译审查由满足模型门禁的指定 subagent 产生结构化结果，再由主 Agent 写回英文资源。

---

## 17. 手工验收

完成后应验证：

1. 顶层导航出现“设置”。
2. 点击后进入独立设置页面。
3. 初次启动默认跟随 macOS 系统语言。
4. 选择 English 并保存后，全部 React 页面立即切换为英文。
5. 重新启动后仍保持 English。
6. 切换回“跟随系统”后立即应用系统语言。
7. 设置工作区父目录后，新建工作区表单自动预填该目录。
8. 设置 `.code-workspace` 目录后，创建表单自动预填该目录。
9. 在创建表单中单次修改路径不会改动全局设置。
10. 旧版 `state.v1.json` 可以正常加载。
11. 保存设置不会影响已经录入的仓库和工作区。
12. 非法或已经删除的目录不会导致应用崩溃。
13. 设置页面新增中文源文案后，英文翻译已生成、复核且通过 i18n 一致性检查。
14. `npm run i18n:check` 和现有 CI 全部通过。
15. 列表或详情刷新失败时，Toast 同时显示稳定错误码与本地化消息。
16. Settings 保存失败时，页面可查看并复制错误码、阶段和技术诊断。
17. 工作区缺失路径时，详情准确列出缺失的具体工件，并随界面语言切换。
18. 创建失败时，进度分别准确说明正在清理未发布 staging 或保留已发布工件。
19. 已保存默认目录失效后打开创建对话框，会显示字段级警告；选择替代目录后警告消失且全局设置不变。

---

## 18. 实施顺序

按以下顺序实现：

1. 扩展 `state.v1.json` 中的 `settings` 模型。
2. 增加 `SettingsService` 和状态标准化逻辑。
3. 增加 `settings:get`、`settings:save` typed IPC。
4. 在 preload 暴露窄化的 `settings` API。
5. 将 i18n 启动逻辑改为读取全局设置。
6. 增加 Settings 一级导航。
7. 实现设置页面和目录选择。
8. 将创建工作区表单接入全局默认目录。
9. 增加设置页面中文源文案。
10. 生成并复核英文翻译，运行 `npm run i18n:apply` 更新同步基线。
11. 增加 service、IPC、React 和兼容性测试。
12. 在现有 CI 中运行 `npm run i18n:check`。

---

## 19. 最终实现边界

本轮最终采用：

```text
一个全局设置页面
一个 settings 对象
一个 SettingsService
两个 typed IPC 方法
一个保存按钮
两个目录选择器
一个语言选择器
现有 state.v1.json
现有静态 i18n 方案
仓库内 i18n 校验与同步基线脚本
```

不建立通用配置平台，也不为未来不确定的配置提前增加插件机制。

该方案能够满足 ReqWS MVP 的全局配置需求，同时保持 Electron Main、preload、React Renderer 和本地状态之间的职责清晰。
