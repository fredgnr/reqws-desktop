---
title: Playwright 改造的子代理分工与执行约束
type: technical-design
status: draft
updated: 2026-09-26
---

# Playwright 改造的子代理分工

本文将自动化改造拆成可独立执行和验证的子任务，明确接口、文件和测试资源的归属，避免并行开发破坏真实链路与最终验收。

本文补充[技术方案第 9 节](technical-design.md#9-分阶段实施)，不是已经启动子代理或完成 S0–S4/V 的记录。通用编码模型、reasoning、别名判定和失败处理统一遵守 [Agent 协作指南第 8 节](../../guides/agent-workflow.md#8-开发子代理)，本页不维护第二套白名单。纯翻译仍按专门技能执行。

## 1. 阶段与分工

| 阶段 | 可委派工作与模型预设建议 | 主 Agent 保留的工作与依赖 |
|---|---|---|
| S0 | 两个有界只读 Explorer 可分别核对 Electron 启动/隔离和 Git fixture/原生边界；一般用 `coding-sol-max`，涉及安全设计歧义时用 `coding-astra` | 汇总可行性及实际运行缺口，确定启动、DI、fixture、证据接口；不让多个探索任务提前改共享代码 |
| S1 | 启动/DI Worker 处理共用 bootstrap；fixture Worker 处理 loopback Git、目录/PID 隔离与证据；跨边界部分优先 `coding-astra`，明确的工具实现可用 `coding-sol-max` | 先约定接口再分配互不重叠文件；package/lockfile、Playwright 配置与构建入口由主 Agent 或一个指定所有者统一修改；S1 集成检查通过后开放 S2/S3 |
| S2 | Desktop 场景 Worker 按 D01–D12 的独立测试文件拆分，通常使用 `coding-sol-max`；复杂恢复/并发问题可切换 `coding-astra` | fixture 公共接口已有稳定实现；定位 UI 所需的生产代码修改交回对应文件所有者，不由场景 Worker 越界补丁 |
| S3 | CI/证据 Worker 接入 Electron job、同包 smoke、报告与失败聚合，明确实现用 `coding-sol-max`；门禁和产物一致性审查可用 `coding-astra` | S1 已稳定；与 S2 可并行，但工作流、缓存、构建路径只有一个所有者；全套集成再验证同候选关系 |
| S4 | 本机联动 Worker 扩展现有 Driver 宿主与 Desktop 编排，优先 `coding-astra`；清晰的树断言可用 `coding-sol-max` | 复用专用 profile、显式 ZIP 和现有授权边界；只在获准本机环境运行，IDE 会话资源独占，不进入 CI |
| V | 独立只读 Reviewer 核查真实链路、负向失败、旧手工步骤映射、生产包测试逻辑泄漏；关键审查优先 `coding-astra`，有界证据审查也可用 `coding-sol-max` | 主 Agent 冻结同一候选，统一收集实际测试结果并决定替代项；审查者不改代码或放宽门槛，发现问题交回所有者 |

表中的角色与预设是执行建议，不是每阶段必须凑齐的代理数量。一个独立 Worker 足够时不要额外拆分；只有共享契约稳定、文件可划分且并行有收益时才同时写入。S0 验证和 S1 安全回归不能被后置到 V。

## 2. 文件和资源所有权

以下为拟议任务边界，具体路径按最小实现调整；主 Agent 应在委派前将通配范围收敛到当次实际文件清单。

| 工作域 | 建议归属 | 禁止的交叉写入 |
|---|---|---|
| 共用启动与装配 | 一个所有者负责 `src/main/index.ts`、拟议 bootstrap、`src/main/ipc/create-main-services.ts` 及相应单元测试 | fixture、UI 场景、CI Worker 不自行改同一启动接口 |
| fixture 与证据 | 一个所有者负责 `tests/e2e/fixtures/` 的分配文件；固定实例、Git origin、边界适配器和证据契约 | 场景作者不复制第二套 fake service 或另开测试专用 IPC |
| Desktop 场景 | 按 `tests/e2e/desktop/` 内实际 spec 文件分给 Worker | 不因 locator 不稳定而多人同时修改 renderer；公共 page helper 也指定一个所有者 |
| CI、候选包与依赖 | 一个所有者负责工作流、候选 smoke、workflow 负向测试；主 Agent 统一 package/lockfile 和测试配置 | 不在多个 worker 中同时 npm 安装、打包或修改依赖锁 |
| 本机 IDE 联动 | 一个所有者负责相关 `integrationTest` 宿主和 `scripts/run_local_ide.py` 的获准改动 | 不启动日常 IDE，不共享活跃 profile，不写用户真实工作区 |
| shared/preload 与跨语言契约 | 主 Agent 或一个跨层所有者原子修改涉及的 TS/Kotlin/fixture | 不将 schema 与消费者交给不同 Worker 各自猜测接口 |

只读探索/审查可以并行，但应读取同一稳定候选。独立 worktree 可以隔离构建输出；共享工作树时，`.vite`、`out`、Gradle build 和 Playwright 报告输出也可能相互覆盖，应分开输出或串行调度。每个 Electron 实例独占临时 userData、Git 配置、origin 端口与日志目录。

本机 IDE 专用 profile 由现有锁独占，不能让多个代理同时使用；候选 ZIP 不重建、不改签名后字节。没有 IDE 执行授权时可以完成宿主代码和允许的静态/平台检查，但本机 UI 必须标为未运行，不能改用 CI 启动 IDE 或扩展 Computer Use 来代替授权。

## 3. 委派与交接模板

向子代理提供以下信息即可，不粘贴整仓文档或要求重复读取全部索引：

```text
任务：阶段 / 目标 / 验收断言
基线：候选 commit，以及必须保留的未提交差异
配置：按 Agent 协作指南选择预设；核对宿主有效 model/effort 与依据
读取：必要代码、契约、测试、相关设计章节
写入：本次独占文件清单；共享接口所有者
依赖：哪些契约或 fixture 已冻结，哪些问题必须先交回主 Agent
限制：生产服务/安全边界不变；不提交/推送；不操作真实数据或日常 IDE
验证：直接受影响的实际命令；不可运行时说明环境缺口
返回：文件差异摘要、实际检查结果、风险/未完成项、配置或回退说明
```

子代理如需修改范围外文件，应先请求主 Agent 重新分配所有权；主 Agent 可在原授权范围内完成这种调整，无需为每个内部交接反复询问用户。新增系统权限、真实 IDE/数据或发布动作仍按现有边界处理。

## 4. 集成和停止条件

主 Agent 先检查每份 diff 的文件归属、接口和真实性，再合并到集成候选。各 Worker 只跑直接受影响检查，最终完整基线由主 Agent 统一安排；安全、路径和并发改动仍必须当步验证，不能为了省时间推迟。不要同时运行会写同一产物目录或占用同一 profile 的全量检查。

模型未确认、依赖未冻结、文件被另一 Worker 占用，或 fixture 仍使用用户目录时，不开放对应 Worker 的写入；先解决具体阻塞，其他独立安全工作继续。子代理工具不可用时如实说明，主 Agent 可串行推进允许的工作，不假称已完成独立审查。

V 阶段审查同一源码/候选包、真实 UI 与磁盘/Git 断言、故意失败结果和替代登记。Reviewer 报告不是测试执行证据；主 Agent 的集成修复改变候选后，应按影响补充验证，不能继续引用旧候选的 GUI 通过结论。CI 绿色、本机未运行和 Computer Use 已被替代是三个不同状态。

只有用户授权远端提交时，主 Agent 才提交与推送。用户要求 squash 时保留全部已确认的分支变更、压成相对基线的单一提交并核对远端 HEAD；优先使用带预期旧 SHA 的 lease，工具不支持原子 lease 时明确采用写前/写后回读而不宣称具备原子保护。发现他人并发提交应停止覆盖并先整合。不通过 force-push 覆盖未知改动，不新建重复 PR，不合并到 main、打 tag 或发布。

## 5. 交付检查

交接应能回答：是否确实有并行收益；每项编码委派是否符合通用模型/effort 政策；写入与测试资源是否独占；子任务和最终候选分别跑过哪些检查；哪些本机/UI/独立审查仍缺证据；是否只撤销已有等价自动证据的手工步骤。不要为了满足清单编造代理会话、配置或通过数字。
