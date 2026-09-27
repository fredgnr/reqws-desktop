---
title: GoLand 插件 Product Design 原型与实施记录
type: technical-design
status: active
updated: 2026-09-27
---

# Product Design 原型与实施

本轮以旧 Swing 信息层级为基线，在可见交互原型上评审紧凑布局、诊断可读性与字号适配，再落到唯一正式 Compose 内容层。

## 范围与视觉目标

用户先要求重构前后保持一致，随后明确允许适当改善美观性与可用性，不追求一比一复刻。保留右上状态标签、工作区主标题、分支与加载数、横向仓库行以及底部主按钮与两个居中链接。原型是本机 React/Vite 展示样例，不是新的生产 WebView 或运行时分支。

Product Design 的 context preflight 未发现保存上下文。基线来自 S0 的真实 Swing 插件截图与 Git 中已删除的 Panel；只裁出插件区域，完整桌面截图不进入仓库或远端。对照原型位于本次聊天的本地可视化产物，源码身份继续由 Git 记录，不增加文件校验账本。

本轮选择保守优化：

- 工作区标题采用宿主文字样式的 Medium 字重，保留既有字号层级、文字和完整 tooltip/语义。
- 错误、警告或 VCS 诊断采用宿主语义色的细侧边与浅背景，文字最多显示三行；普通摘要仍为单行。全文继续通过 tooltip 和复制诊断取得；提示增加高度不能遮挡底部操作。
- 仓库行以 40dp 为 100% 字号基准，随放大字号增长；长列表仍最多六行后滚动。空列表提示允许换行，避免窄栏或大字号裁字。选中仍只影响本地高亮。
- 状态标签与卡片边框保留宿主主题；没有新增用户文案、状态、业务操作或错误优先级。

这些是用户允许的局部调整，替代本需求包中“诊断始终单行、放大字号仍固定40dp”的视觉约束，不改写 S3 历史验收结论。最低版本影响：保持整个262系列、无上限；继续使用宿主 Compose/Jewel，不增加依赖或平台 API 例外。

## 原型验证

本地原型包含旧图并排对照、明暗主题、455/320/240/160px 宽度、100%/125%/150% 字号、长名称和全部仓库状态。同步、选择、示例 Manifest 和复制诊断是可操作样例；没有连接真实项目或修改真实配置。

初次窄栏检查发现长名称挤占状态宽度和诊断 clamp 的内边距漏字，已在原型修正并重新截图。完整视觉 QA 与原始截图保留在本机原型目录；原型结果不作为生产 Kotlin 或真实 IDE 通过证据。

## 实施与验证状态

Compose 三个生产文件已实现标题 Medium 字重、最多三行的语义色诊断面板、按字号增长的行高和可换行空提示。没有 catalog delta，不启动翻译或修改 i18n baseline。

使用现有测试 JBR 25 执行 `compileKotlin compileTestKotlin composeUiTest --tests com.reqws.goland.ui.ReqwsScreenTest`：26项组件用例，0失败/错误/跳过，报告在本机 `/private/tmp/reqws-product-design-direct-fixed`。新增三个用例检查诊断行数与全文语义、普通摘要紧凑性、200%字号下仓库行/空提示的真实文字边界。文档检查通过28个索引、128个文件。

首次终端默认Java21导致Gradle尝试获取Java25，已停止该次构建并切换到现有JBR25；没有启动IDE。首次直接回归26项中1项新断言失败：桌面paragraph的 `isLineEllipsized` 为false，但截图已显示省略号，`didExceedMaxLines` 为true且末行offset小于原文长度。断言改为验证后两项、三行真实边界和完整语义；原始失败报告保留在 `/private/tmp/reqws-product-design-direct` 与 `reqws-product-design-probe`。

生产与测试固定提交为 `bbbf02b`。在该干净提交运行的完整 `npm run check:goland` 已退出0：39类、364项平台测试，27项组件/环境测试，均0失败/错误/跳过；指定断言失败与空发现探针按预期失败。生产/测试/集成编译、禁用符号、结构/descriptor、326类ZIP隔离检查、最低/代表版2目标及新冻结完整7个API目标全部通过。API原始失败级别与唯一已授权的初始JPS精确例外继续由既有入口裁决，没有增加例外。

原始日志为 `/private/tmp/reqws-product-design-final-check.log`；组件报告 `run-8h9u0tzb/evidence` 记录 `sourceCommit=bbbf02b`、工作区干净；API报告在私有临时目录 `reqws-api-uhz98h9w`。私有候选身份为 `/private/tmp/reqws-product-design-candidate.json`，确切ZIP另存 `/private/tmp/reqws-product-design-bbbf02b.zip`；所有API报告与保留ZIP保持一致。没有将私有截图或原型资产推送到仓库。

独立Reviewer `/root/product_design_review` 采用 `requested_profile=astra`、`effective_model=gpt-6-astra`、`effective_reasoning=xhigh`，由宿主工具目录及显式参数核验为verified、非继承。对固定提交、原型、原始26项直接组件以及最终364平台/27组件、负探针和候选身份进行只读审查，未发现需要修复的代码问题。同一Reviewer进一步核对冻结清单、完整7份原始Verifier结果与同一ZIP身份，确认全部已完成、无遗漏或新增API问题，本地自动门禁审查范围无剩余阻塞。

后续文档提交不改变上述生产/测试输入或ZIP。远端结果以[现有PR #29](https://github.com/fredgnr/reqws-desktop/pull/29)的对应提交检查为准；这里的通过结论仅覆盖上述本地候选，不借交接时 `6f6fa4e` 的绿CI证明新代码。

本轮原生 IDE 最终验收尚未授权启动，Compose、受影响 legacy 及 Desktop→GoLand 临时工作区联动继续待运行；旧ZIP结果不能借给本轮新候选。VoiceOver 未运行，按用户要求豁免。S4/G4不因此变为通过。

## 原生验收中的后续修复

2026-09-27 用户重新授权专用环境的前台验收。原 `bbbf02b` 的基本 Compose 宿主套件在私有目录 `reqws-compose-host-fris8a3k` 通过3测试、4进程正常退出及20次Content循环；实际明暗/100%与125%截图已检查。但它不覆盖新增真实字号与长全文交互，因此不能作为G4。

补充原生探针保持IDE scale=1，将实际Label字体从13改为26：标题高度19→34，仓库行仍40→40（`reqws-compose-host-o6fp5chi`），证实原行高只响应Density.fontScale，遗漏宿主TextStyle变化。行高现取原字号缩放最小值与实际文字高度加留白的较大值；恒定Density下的组件回归接续该场景。后续真实记录 `reqws-compose-host-j2g5qwwv` 已观察到行高40→54，但该轮其他场景未通过，不能报完整成功。

长tooltip也发现真实交互缺陷：默认Jewel Tooltip在鼠标离开触发源进入弹层时关闭，使240dp有界全文无法实际滚动。已改用公开宿主Jewel Popup，保留宿主tooltip颜色、边框、圆角、内边距和显示延迟；触发源与弹层共同维护悬停，留出跨越间隙的关闭延迟。组件回归覆盖进入弹层、真实指针滚轮和离开关闭；原生全文尾段仍需逐图核验。没有独立运行时、生产测试端口、文案/catalog变化或新API例外，兼容下限仍为整个262系列。

测试侧现补充280px窄栏、真实13/26字号、明暗主题、完整18字段诊断复制、tooltip真实滚轮尾段截图、8行列表的实际视口与滚动位移、同字号700px/280px空提示高度对照。专用fixture原字节与测试设置在finally恢复。原生首轮错误定位合并语义子节点、Compose虚拟组件无AWT Window祖先的滚轮错误和已失败的原始报告均保留，未作为产品通过证据。动态重载期间固定IDE可能临时注销Islands编辑器配色并报错；该独立场景改在内置浅色主题执行，仍保留全部IDE错误门禁，并在finally恢复原主题。

这份后续修复候选尚待完整自动检查、最终原生三套验收与固定提交独立审查；S4/G4继续未通过。VoiceOver仍为用户豁免，性能预算不作为门禁。

## 后台键盘修复与本轮恢复点

生产提交 `3548d9c` 的新弹层在 `reqws-compose-host-s9g503mr` 已能接受真实指针进入及滚轮，基准字号尾段截图可见完整结尾，但复制操作被尚未关闭的弹层遮挡；该轮另有 Marketplace TLS EOF，整体失败。测试提交 `1116219` 增加 Escape、外点、焦点和新鲜剪贴板断言；`reqws-compose-host-nwhd_ipr` 随后确认真实 Escape 未关闭弹层。该轮生命周期和动态重载通过，但输入失败，不能记为完整原生通过。四个进程已退出，专用 active-session 标记已清除；约60分钟前台时段于2026-09-27 07:14 UTC结束，此后没有再启动原生IDE。

后台修复固定为 `b86134b`：Screen 内的 tooltip 控制器只在内部焦点收到 KeyDown Escape 且确有可见弹层时消费按键；关闭前清除登记，销毁时按 owner 释放，避免旧回调影响新弹层。保留公开宿主 Popup 的外点和键盘关闭路径。新增组件回归验证复制按钮焦点保留、无弹层时 Escape 继续传播及禁用后的登记清理。它不证明编辑器等 ReqWS 外部焦点场景，也不替代真实宿主复验。

干净 `b86134b` 的完整 `check:goland` 已退出0：39类364项平台测试，29组件加1环境共30项，均0失败/错误/跳过；指定断言与空发现探针按预期失败。生产/测试/集成编译、332类产物隔离、禁用符号、descriptor、最低/代表版2目标和完整7个API目标全部通过。日志为 `/private/tmp/reqws-native-final-check.log`，组件原始报告 `run-91ehucmh/evidence`，API私有目录 `reqws-api-_s5wku_c`。固定候选保存为 `/private/tmp/reqws-native-final-b86134b.zip`，身份记录 `/private/tmp/reqws-native-final-candidate.json`；保留ZIP、构建ZIP和九份API报告身份一致。

独立 Reviewer `/root/native_acceptance_review` 使用经宿主显式参数核验的 `gpt-6-astra/xhigh`（非继承），复核固定代码、原始XML、失败探针、九份API报告及ZIP；未发现本轮源码或本地自动门禁范围内的剩余阻塞。对应[代码CI 36302813633](https://github.com/fredgnr/reqws-desktop/actions/runs/36302813633)已全部成功。后续文档提交不改变这些生产/测试输入；最终PR检查仍以相应HEAD为准。

新 `b86134b` ZIP尚未启动原生验收。下一次获准时段须串行执行完整Compose宿主（包括实际Escape、13/26字号、280px窄栏、全文尾段、滚动和空提示）、legacy三组九进程、Desktop四组九进程，再审查该候选原始报告。不得沿用上述旧ZIP通过片段。S4/G4保持未通过；VoiceOver仍为用户豁免，性能预算不作为门禁。

## 继续验收时的环境阻断

用户于2026-09-27 07:44 UTC重新授权前台时段。同一生产ZIP `b86134b`、文档HEAD `2dafa88` 在 `reqws-compose-host-vi3pndej` 的完整Compose宿主运行结果为3项、2失败、0跳过；4个进程全部退出，生命周期用例通过，专用active-session标记已移除。

原始记录确认基准字号明暗两态的Escape关闭、焦点保留及18字段复制；13→26字号时行高40→54。大字号浅色的“外点关闭”失败经截图确认是测试点击了弹层覆盖下的仓库计数，并非有效外点。测试已改为按实际文字区域加保守24px边距排除弹层覆盖，从候选元素中选择不相交目标并记录边界。该测试修正仅编译通过，尚未重跑。另一个重要缺口是015尾段截图仍显示前半文：真实滚轮是否命中可见滚动区域及完整末尾继续待核实，不能把全文语义或截图存在记为全文可读。自动事件现明确区分 `tooltipText=complete` 和 `tooltipTail=manual-review`；长列表和空提示尚未执行到。

动态启用/禁用动作本身完成，但Installed页面请求 [Marketplace元数据](https://plugins.jetbrains.com/files/34389/1177315/meta.json) 时反复发生 `SSLHandshakeException / EOFException`，随后成为IDE未处理错误，门禁正确判失败。只读SDK审查未找到可可靠停止该详情请求的公开离线开关；不屏蔽IDE错误、不绕过TLS、不用内部API或加载mock替代实际重载。继续该验收需要用户检查当前网络/代理到JetBrains Marketplace的连接。

本轮没有新的生产代码或ZIP变化。修正后的集成测试编译记录为 `/private/tmp/reqws-native-outside-target-compile.log`；原始失败报告、截图与候选均保留。独立Reviewer已核对失败范围及测试修正，未将其判为G4通过。用户协助前保存恢复点并暂停，VoiceOver继续豁免。


## 网络恢复后的测试设施修正

用户提供的元数据与同一 JBR 后台两次成功 HTTPS 响应一致；文件未写入 IDE 缓存，也未绕过 TLS。用户于 08:13 UTC 授权约45分钟前台时段。生产 ZIP 仍为 `b86134b`，测试 HEAD `5cedc7e` 的 CI 已通过。

`reqws-compose-host-dskf8et1` 原始结果为3项、1失败、0跳过，4个进程正常退出。动态重载和20轮 Content 已通过；输入在长列表末行断言失败，015截图仍未显示大字号 tooltip 末尾。独立审查还发现一次重开弹层的 present 早于非零布局，因此不把该零尺寸外点记录当有效几何证据。`reqws-local-ide-2820ykoq` 的 legacy 3项、9进程全部通过；独立 Reviewer 已核对 XML、进程序列与同一 ZIP。

`reqws-local-ide-728zmyqq` 的 Desktop 四项在内容图采集时失败，4个已启动进程均退出。真实 Compose 的 Metal 纹理不能由旧 Swing root pane printAll 绘入 BufferedImage，报 Unsupported graphics configuration。该轮不证明四组联动通过。

本轮仅修正测试设施：滚动观察使用标准 Java Accessibility 的只读纵向范围，等待非零稳定布局，以实际滚轮正/反方向探测位移并验证到达末尾；不调用语义滚动或产品同步。Desktop 采图改为标准 Robot 直接读取专用 IDE root pane 的实际屏幕区域，前后检查 active、几何和项目/PID，绝不为采图调用 focus。v8/schema2 拒绝旧图及缺少身份/active 记录的证据，继续完整 PNG 校验；仍需逐图检查实际内容与遮挡。

集成测试编译和25项报告校验测试已通过，文档检查通过28个索引、128个文件。这些结果不替代修正后的原生执行。没有生产变更、catalog delta、新依赖或 API 例外；最低范围仍为整个262系列。Compose滚动与空提示、Desktop新采图和完整联动继续待复测，G4未通过。


测试提交 `f5b62f9` 的只读审查确认新观察路径未削减门禁。随后 `reqws-compose-host-c8c5_mv1` 为3项、2失败：真实输入在第二次 Shift+Tab 的焦点断言提前失败，未到达新滚动代码；生命周期在 Tool Window 异步注册完成前读取到 null，未开始20轮循环。动态重载通过，3个已启动进程全部正常退出。键盘日志含两组完整事件，暂不据此修改产品或认定缺少Shift；原路径保留复测。生命周期测试增加公开注册状态的有界等待，继续在创建Content前验证后台同步，未放宽懒创建和释放断言。


`reqws-local-ide-ysd3uj_9` 在 `6e271d0` 的新采图已真实运行：选择/冷启动及信任两项通过；用户覆盖和损坏输入两项在旧 getAllTexts 观察处失败，共7个进程正常退出。20张区域图已生成，独立审阅前19张确认身份/阶段和真实Compose纹理；信任图的IDE通知遮住底部操作，因此只支持树与加载状态。失败界面实际已显示用户目录说明与错误，旧Swing文本采集未取得Compose合并语义。测试观察现改用公开Driver树的实际 text/contentdescription 属性，唯一标签匹配和安全XML解析；保留完整状态/稳定码、截图及原有领域断言，不用预期文字伪造观察值。尚待新提交全套复测。


`reqws-compose-host-ybr_f_eb` 对 `68bef77` 的重试为3项、1失败，4个进程正常退出：原键盘路径、重载和20轮生命周期通过。输入到滚动观察时，Driver不能桥接声明为Dimension的返回值，尚未检验可访问子树或发送滚轮；这不是产品滚动失败或VoiceOver依赖的证据。测试代理改读已有支持的Rectangle，并用标准Number远程接口读取数值，不改变生产行为，继续待原生复测。


## 本时段结束与下一次复测

`reqws-local-ide-cl9ubc8x` 在 `c6fb287` 的最新结果为4项、1失败、0跳过，8个进程全部正常退出；独立Desktop进程登记也确认停止，专用active-session标记为空。选择/冷恢复、真实信任和损坏输入/恢复三项通过，4条真实错误UI观察以及33张区域图已生成。用户目录覆盖仍停在文字观察，后续普通项目进程未运行，故整套Desktop未通过。

只读SDK审查确认父仓库行不会合并独立Status节点的text；属性大小写和XML换行保持正常，不能将失败归因于格式丢失。测试改为精确仓库ID/完整名称加独立状态节点的完整text，继续检查父行全文description，保存两个实际属性map；不使用宽松子串代替可见状态，不改生产代码。该修正尚待真实复测。

前台操作已于08:55 UTC收束，本时段没有再启动IDE。剩余是修正后的完整Compose字号/滚动/末尾/空提示、Desktop完整四项/九进程及最终证据审查。VoiceOver依旧豁免；当前ZIP、whole-262兼容下限、全部未豁免门禁均保持不变。下一次原生执行需新的用户前台确认。


最新独立复核已覆盖 `ce729eb` 的完整名称/独立状态精确断言及编译原始日志，未发现放宽覆盖。本轮 `cl9ubc8x` 的33/33张区域图与身份侧车已逐图核验，候选副本一致、IDE与Desktop退出确认完整。局限包括IDE通知遮挡部分底部操作、单张Dock悬停标签位于状态栏，以及一张损坏manifest的projection图显示索引等待占位；独立错误UI图仍可证明Error与稳定码，不能把占位图称为Compose完整显示。最新汇总和剩余范围以[S4当前记录](s4-verification.md#当前冻结候选与剩余原生验收)为准。


用户随后明确要求直接修复未通过测试、不关注CI，并于09:06 UTC确认新的约25分钟前台时段。`reqws-compose-host-mogytuds`已成功定位原生滚动对象，但数值读取因Float按值返回、接口误声明为远程Number而失败，仍未发滚轮；3项1失败、4进程退出，重载和20周期通过。固定Driver字节码确认Float/Double按值传输而Dimension不是，测试恢复Number?和本地toDouble，保留Rectangle尺寸适配。此前将两类返回值一并视作远程对象的判断已纠正；生产ZIP未变。


`reqws-compose-host-0752sqqw` 已读取真实滚动范围（基准全文当前0/最大0），数值适配通过。输入随后在重开弹层的稳定边界等待失败；3项1失败、4进程退出，重载/20周期通过。SDK确认present每次查新树但bounds可能复用仍声称showing的旧语义节点；测试在重开后重新创建selector再检查稳定非零边界，保留实际外点和关闭断言。该最小修正未改变生产弹层行为，仍需实跑确认。


`reqws-compose-host-xy_qr_fe` 为3项1失败、4进程退出；数值已正常返回，基准全文完整可见但max=0不构成滚动证据。输入停在弹层重开前，尚未执行fresh selector。观察到测试的无溢出分支提前返回，跳过原有“实际指针进入弹层”动作；现无论是否需要滚轮都先进入并记录真实落点，Escape后确认指针已在触发源外稳定停留，再重新移入。继续保留实际重开/稳定边界/外点关闭断言，不更改生产事件处理。该修正待复测。


## Desktop完整通过与剩余列表边界

`f2361ff`、同一生产ZIP的 `reqws-local-ide-3os5wgqt` 已完整通过：4项、0失败/跳过，9进程正常退出；v8有32投影、5落盘、4错误UI和ordinary证据，共37张区域图。独立Reviewer逐图核对37/37、侧车、XML、进程序列与安装副本，确认该范围通过。部分原生通知/Dock标签仍限制底部可见范围，但未挡本次树、加载数和错误码；本轮没有此前索引占位图缺口。无需因无关Compose输入测试修正重跑这份输入未变的Desktop证据。

同一HEAD的 `reqws-compose-host-7awg84j_` 为3项、2失败、0跳过，4进程正常退出。大字号浅色提示已用真实负方向滚轮从0滚到286/max286，015图实际显示完整末尾；外部body也到360，内部列表到其1000/max1000，但末行完整视口断言失败。现有失败图发生在finally恢复之后，不能代替失败前的大字号列表边界。另一失败是动态重载的Marketplace TLS EOF；保留全部IDE错误门禁，不屏蔽该环境错误。

测试进一步改为每次从当前语义树读取稳定边界和滚动后列表视口，避免复用可能脱离布局的Driver节点；并在几何断言之前保留实际滚动后截图、记录首末行和视口原始边界。严格包含与行高/位移断言未放宽。这消除已知缓存风险并补足诊断，尚不能断言末行问题已解决。大字号深色、完整列表/空提示及本轮全部Compose场景仍待通过，G4未通过。

本约25分钟前台时段已结束，测试进程全部退出、设置恢复，没有启动下一轮IDE。用户要求不关注CI继续有效。


后台补充独立审查确认：固定Compose的Lazy列表可访问范围是索引估算值（首个可见索引×500加行内偏移，能继续滚动时max=current+100），`1000/max1000`不能解释为1000像素，也不能替代完整可见性。列表方向探测可能让已到底的外层正文回滚，现增加探测前后正文范围、末行四边差值，并用本轮实际完整行高54作为最小高度；不再用固定50容忍少量裁剪。该诊断尚待实际执行，不预断生产布局缺陷。新增 `--allow-input --input-probe` 固定只跑输入/样式方法，精确1项/1进程、独立报告范围且完整校验器拒绝；9项本地报告测试通过。最终验收仍要求完整Compose套件，Marketplace未处理错误门禁保持不变。
