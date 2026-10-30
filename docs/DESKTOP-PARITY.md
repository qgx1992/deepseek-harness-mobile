# 桌面端 → 移动端功能对照

本文回答一个问题：桌面版 DeepSeek Harness 里还有哪些功能值得、并且能够搬到 DSH Mobile 上。

## 采样口径

| 侧 | 版本 | 取材 |
| --- | --- | --- |
| 桌面 | DeepSeek Harness **0.2.1-rc.5**（Electron，`resources/app.asar`） | profile `desktop` 实际装载的 23 个 bundle；asar 内 58 个 `dsh-client-ui-*` 客户端插件、主机侧 `dsh-api-*` / `dsh-experimental-*` 包；逐个读 README 与 `lib/types/*.js` 的 Remote 声明 |
| 移动 | DSH Mobile **0.19.0** | 协议基线 `0.2.1-alpha.1`（commit `5badb15`）；`app/src/main/java/com/qgx1992/dshmobile/`、`core/src/main/kotlin/` 全量比对 |

两侧不是同一个协议版本：桌面 rc.5 已经长出 `speech`、`pluginMarket`、`agentTeam`、`workflow/*` 这些 alpha.1 里没有的面。移动端接入时必须沿用现有不变量——未知投影/事件不崩（`Unknown*`），能力缺失走 `capability-unavailable` 降级隐藏，而不是假设主机一定有。

---

## 一、结论：值得移植的 12 项

优先级 P0 = 用户天天用、移动端缺了明显别扭；P1 = 明显增值；P2 = 有更好、没有也行。工作量 S < 半天，M 1–2 天，L 3–5 天。

| # | 功能 | 桌面实现 | 主机契约 | 移动端落点 | 量 |
| --- | --- | --- | --- | --- | --- |
| P0-1 | 语音输入 | `dsh-experimental-client-ui-voice-input`：模型选择器与 Send 之间的麦克风 → 录音条（电平/Cancel/Stop）→ 转写插入草稿 | `speech/catalog`、`speech/transcribe`、`speech/prepare`、`speech/cancelPreparation`、`speech/configure`、`speech/follow` | 新 `ui/components/VoiceInput.kt` + `Composer.kt` 接入 + `RECORD_AUDIO` | M |
| P0-2 | Agent Teams 面板 | `dsh-experimental-client-ui-agent-team`：会话头按钮 → 成员 roster + 只读任务板 + 跳队友会话 | Lead session 的 `agentTeam` projection（结构见下） | `ProjectionDtos.kt` + `SessionStore.kt:581` 一行 + 新 `AgentTeamSheet.kt` | M |
| P0-3 | 插件市场 Discover | `dsh-client-ui-plugin-market`：Plugins 页第二个标签，索引源/搜索/过滤/排序/条目页/安装 | `pluginMarket/listIndexes`、`pluginMarket/search`、`pluginMarket/setIndexes`；安装复用 `pluginManager/installBundle` | `Harness021Api.kt` + `PluginManagerScreen.kt` 加 Tab | M |
| P0-4 | 主机设置编辑器 | `settings-*` 一族：general / models / agent-loop / shell / subagent / web-search / session-log / plugin-inventory | `settings/describe`、`settings/mutate`、`settings/update`、`credentials/*`、`llm/*` —— **移动端 core 已写好，app 层零调用** | 新 `ui/screens/settings/HostSettingsScreen.kt` | L |
| P1-5 | 密钥遮罩 | `dsh-client-ui-secret-mask`：发送文本里识别到的凭据保留前 8 字符，其余打码，可逐值揭示；只改绘制 | 无（纯客户端） | `ui/components/Markdown.kt` 旁路 + 气泡渲染 | S |
| P1-6 | 工作流运行节点 | `dsh-client-ui-workflow-run`：durable workflow run 作独立节点，phases → members 两级展开 | `dsh-workflow` 的 `workflow/*` 事件 | `EventFold` 聚合 + 新 `WorkflowNode.kt`（现只有 `ChatLabels.workflowStatusLabel:55` 状态标签） | M |
| P1-7 | 用量/成本 | `dsh-token-meter`、`dsh-session-stats`、`dsh-cost-meter` | 投影 `tokenUsage` / `contextPressure` / `contextBreakdown` / `sessionStats`（移动端**已有**） | 只补 UI：`DetailsPanel` 统计卡；金额需定价表，可选 | S |
| P1-8 | 分享 / Open In | `dsh-client-ui-open-in-app`：会话头分体按钮用记忆应用打开工作区目录 | 移动端已有 `session/openWorkspacePath`（在桌面端打开） | 文件预览页加「分享 / 用其他应用打开」（FileProvider + ACTION_VIEW/SEND） | S–M |
| P2-9 | 原始日志检查器 | `dsh-experimental-session-inspector`：原始 session 日志 + group/node 表 | 无（事件账本已有） | `TrajectoryTab` 加「原始 JSON」开关（`JsonDisclosure` 已存在） | S |
| P2-10 | 逐文件 diff 审查 | `dsh-client-ui-deliverables`：改动文件卡 → 逐文件 unified/split hunk 视图，hunk 从工具调用 patch 文本解析 | 无 diff API（`workspace/changes` 只给 `{turn}` 文件名） | 从 tool-call 参数解析 `apply_patch`/unified 两种格式，渲染 unified | M |
| P2-11 | 会话分组/排序 | `dsh-client-ui-sidebar`：多级树、搜索、分组、状态点 | 无 | 抽屉加「按状态/时间分组」排序项 | S |
| P2-12 | MCP 只读状态 | `dsh-mcp-client` / `dsh-mcp-resources` | 移动端无 MCP 命名空间 | 只读展示 settings 里的 MCP 配置 | S |

---

## 二、逐项实现方案

### P0-1 语音输入 —— 已落地（0.19.0）

> 落地形态：`core/.../core/audio/WavWriter.kt`（44 字节 RIFF 头）、`app/.../ui/media/VoiceRecorder.kt`（`AudioRecord` 16 kHz 单声道 PCM16 + 100 ms 电平块）、`app/.../ui/components/VoiceInput.kt`（`rememberVoiceInput` / `VoiceRecordingBar`）、`Composer.kt` 的 `voice` 参数、`SessionStore.transcribeSpeech`（base64 与 provider 选择都在这里，UI 不碰 wire 形状）。与原计划的差异：麦克风是「点在 Send 左边」而非「模型选择器与 Send 之间」——手机上没有模型选择器那一行。

**桌面行为**：麦克风按钮位于模型选择器与 Send 之间。点开是录音工具条，带实时电平、Cancel/Stop；停止后把整段录音交给主机识别，转写文本插入草稿而不是直接发送。识别 provider 与语言偏好存在插件设置里。

**主机契约**（`dsh-experimental-api-speech-to-text`，namespace `speech`）：

| 方法 | 参数 | 返回 / 说明 |
| --- | --- | --- |
| `speech/catalog` | — | provider 快照 + 默认 provider + 偏好 + 限制 `maxAudioBytes`（默认 4 MiB）、`maxDurationSeconds`（默认 120） |
| `speech/transcribe` | `{audioBase64, providerId?, language?}` | 转写文本；**不写 session 事件**。音频必须是 canonical base64（非 URL-safe、无换行）的 WAV；错误码 `speech/invalid-audio`、`speech/transcription-failed` |
| `speech/prepare` | `providerId, options` | 启动/加入主机侧准备任务（预热识别器） |
| `speech/cancelPreparation` | `providerId` | 取消准备 |
| `speech/configure` | `patch` | 持久化识别偏好 |
| `speech/follow` | —（stream） | provider 就绪快照流 |

**移动端落点**：

- `core/src/main/kotlin/.../core/wire/Harness021Api.kt`：加 `speechCatalog()` / `speechTranscribe(...)`（沿用 `featureCall`）。
- `core/.../core/wire/dto/SpeechDtos.kt`：`SpeechCatalog`、`SpeechTranscribeRequest`、`SpeechTranscribeResult`。
- `app/.../ui/components/VoiceInput.kt`：`AudioRecord` 16 kHz 单声道 PCM16 → 手写 44 字节 WAV 头 → `Base64.getEncoder()`（canonical，不用 `getUrlEncoder`、不折行）。
- `app/.../ui/screens/main/Composer.kt`：按钮挂在模型选择器与发送键之间；录音条复用 `DsButton`/`StateDot` 的动效组件。
- `AndroidManifest.xml`：`RECORD_AUDIO`（运行时申请；`CAMERA`/`POST_NOTIFICATIONS` 已有先例）。
- `app/.../data/FeatureAvailability.kt:10`：`HarnessFeature` 加 `SPEECH`，主机没装插件时（`capability-unavailable`）隐藏按钮。

**双路策略**（建议都做）：主机装了 speech 插件就走 `speech/transcribe`（和桌面同款识别、同款语言）；没装时回退 Android `SpeechRecognizer`（零主机依赖、离线可用，中文识别质量取决于系统）。两条路都在 Composer 里，失败不阻塞打字。

**坑**：

- base64 体积膨胀 4/3，4 MiB 音频 ≈ 5.6 MiB JSON。局域网直连没问题，Relay 下要确认 mux 帧/HTTP 包上限；超限时提示"录音过长"而不是静默失败。
- 录音必须单声道 WAV；`AudioRecord` 的 `read()` 返回值要按实际字节累计，别按缓冲区长度算。
- 主机侧 `validateWave` 会校验时长，超 `maxDurationSeconds` 直接报 `speech/invalid-audio`，客户端应提前按 catalog 的限额截断。

### P0-2 Agent Teams 面板 —— 已落地（0.19.0）

> 落地形态：`AgentTeamView`/`TeamMemberView`/`TeamTaskView`（`ProjectionDtos.kt`）、`SessionStore.agentTeam`（一行 `projectionOf(..., "agentTeam")`）、`AgentTeamSheet.kt` + `TeamPresentation.kt`、`ChatTopBar` 的 team chip（`teamCount`/`onOpenTeam`）。只读，理由见该文件头注释。

**桌面行为**：会话头部 Agent Teams 按钮，打开后是成员 roster + 只读共享任务板；点成员跳进那个队友的会话。读的是 Lead session 的 `agentTeam` projection，Host 侧投影帧实时更新。

**投影结构**（`dsh-experimental-agent-team/lib/types/projection.js`，`strict` 校验）：

```text
agentTeam = {
  members: [ { id, name, role: "lead"|"teammate",
               phase: "provisioning"|"active"|"failed", error? } ],
  tasks:   [ { id: "task-N", revision, subject, description,
               status: "pending"|"in_progress"|"completed"|"deleted",
               blockedBy: [taskId], writeScopes: [string],
               ownerName?, ready: bool, writeScopeWarnings: [string] } ],
  failure?: string
}
```

`deleted` 任务在投影里已被过滤；`failure` 一旦出现就是终态。lead 恒为 `members[0]`。

**移动端落点**：

- `core/.../core/wire/dto/ProjectionDtos.kt`：`AgentTeamView` / `AgentTeamMemberView` / `AgentTeamTaskView`（`ignoreUnknownKeys` 已有，字段缺失一律可空）。
- `app/.../data/SessionStore.kt:581` 一带：`val agentTeam: StateFlow<AgentTeamView?> = projectionOf(AgentTeamView.serializer(), "agentTeam")`——和 `tokenUsage`（:533）、`turnOutline`（:556）同一模式，一行。
- 新 `app/.../ui/screens/main/AgentTeamSheet.kt`：`FeaturePage` + 两个 section（Members / Tasks）；任务行显示 `subject`、状态点、`ownerName`、`blockedBy` 折叠。
- `ChatTopBar.kt`：仅当 `agentTeam != null` 时显示入口（没有 Team 的会话不出现空按钮）。
- 队友跳转：复用 `SheetSubagents` 已有的 subagent 会话路由（`subagent/list`、`subagent/prompt`、`subagent/interrupt` 都在 `Harness021Api.kt`）。

**坑**：这个面在桌面是**只读**的——发消息、改任务板仍由 Lead 的 `team_task_*` 工具完成。移动端不要做写入控件，否则会和 Lead 的 CAS 冲突。只有 Lead session 有该投影，队友 session 没有。

### P0-3 插件市场 Discover —— 已落地（0.19.0）

> 落地形态：`SpeechDtos.kt` 同级的 `PluginMarketDtos.kt`（含 `LocalizedText` 序列化器与 `MarketQuery`/`MarketSort` 的严格 JSON 构造）、`Harness021Api.kt` 的 `marketIndexes/marketSearch/marketSetIndexes`、`PluginMarketScreen.kt`（592 行）+ `MarketPresentation.kt`、`PluginManagerScreen.kt` 的 Installed/Discover 双标签（发现页是内联的 `PluginMarketContent`，不叠第二个 sheet）。加索引源用「合并可用源」而非整表替换。

**桌面行为**：Plugins 页在 Installed 之后是 Discover：列索引里的 bundle，管理索引源、切换市场、搜索、按 category/label 过滤、排序、打开条目页、确认后安装。

**主机契约**（`dsh-plugin-market`，namespace `pluginMarket`）：

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `pluginMarket/listIndexes` | — | 已配置索引源（含每个源的 `error`） |
| `pluginMarket/search` | `url, {text?, category?, labels?, sort?, offset, limit≤100, refresh?, fetchedAt?}` | `{url, title, description?, metadata{categories,labels}, fetchedAt, entries[], total, nextOffset}` |
| `pluginMarket/setIndexes` | `[url...]` | 保存索引源列表（重复/非法 URL 报 `plugin-market/invalid-index-url`） |

错误码族：`plugin-market/{invalid-index-url, invalid-query, unknown-index, fetch-failed, invalid-index, settings-unavailable}`。安装不需要新契约——`pluginManager/installBundle` + `pluginManager/waitForInstall` 移动端已经实现（`Harness021Api.kt:46,72`）。

**移动端落点**：

- `Harness021Api.kt`：`pluginMarketIndexes()`、`pluginMarketSearch(url, query)`、`pluginMarketSetIndexes(urls)`。
- 新 `core/.../dto/PluginMarketDtos.kt`。
- `PluginManagerScreen.kt`：现在是单列表 + 操作区，改成 `DsSegmented` 两个标签（已安装 / 发现）；发现页顶部是索引源选择 + 搜索框（复用 `ChatSearchField`），列表项显示名称、版本、描述、category/label chip、已装标记；点条目进详情，底部"安装"走现有安装进度 UI。

**坑**：条目上的"已安装/兼容性/安装参数"是主机 annotator 算好带下来的，直接展示，别在客户端重算。索引源为空时不要空白页——给一句"到桌面端 Plugins → Discover 添加索引源"，并允许本页 `setIndexes`。

### P0-4 主机设置编辑器

**现状**：`core` 里 `settings/describe|update|replace|mutate`、`credentials/describe|set|unset`、`llm/listProviders|listConfigurableProviders|discoverModels` 全都写好了，**app 层零调用点**——也就是说手机上能看会话、能装插件，却改不了主机设置。这是移动端"Harness 管理"承诺里最大的洞。

**桌面有的设置页**：General、Models（+引导对话框）、Account、Agent loop（并行工具调用上限）、Shell（命令超时、每流输出上限）、Subagent（递归深度、并行容量、可选模型）、Web search（key/endpoint/每次预算）、Session-log（随请求上传开关）、Plugin inventory（只读 Cordis Loader 清单）。

**移动端落点**：新 `ui/screens/settings/HostSettingsScreen.kt`，从 `settings/describe` 返回的 schema **驱动表单**（不要硬编码字段——主机版本一升就会漂）：`FeatureField` / `FeatureAction` / `FeatureProgress`（`HarnessFeatures.kt` 已有）正好是这套控件；秘密字段走 `credentials/set|unset`；模型 provider 走 `llm/listProviders` + `discoverModels`，保存用 `settings/mutate`。

**坑**：`describe` 的字段类型/枚举/秘密标记形态需要实测确认（拿到真机 describe 结果再定 DTO）；`settings/openSettingsDocument` 的语义是"打开编辑器文档"而非"运行时设置"，别混用。

### P1-5 密钥遮罩

纯客户端、无主机依赖。在 Composer 预览和已发消息气泡的文本渲染处加一层 mask：识别 key/token 形态（`sk-...`、`ghp_...`、长 hex/base64），保留前 8 字符其余画 `*`，点一下可临时揭示该值。只改绘制——原文、复制内容、送给模型的文本都不变。桌面实现在 `dsh-client-ui-secret-mask`，规则可直接对照移植。

### P1-6 工作流运行节点

桌面把 durable workflow run 渲染成独立会话节点：展开看阶段，再展开看成员；running/failed/cancelled/interrupted 默认展开，completed 默认折叠；只显示身份与状态（脚本/输出/错误/用量/拓扑不在这个面）。移动端目前只有 `ChatLabels.kt:55` 的 `workflowStatusLabel` 状态标签。

落地：`core/.../core/session/EventFold.kt` 里把 `workflow/*` 事件聚成节点（和现有 job/subagent 节点同一套 fold 模式），新 `ui/screens/main/WorkflowNode.kt` 做两级 `DisclosureRow`。**前置动作**：先在 `mock-harness` 里补一组真实 workflow 事件 fixture，否则 DTO 只能靠猜。

### P1-7 用量/成本

投影层已经齐了：`tokenUsage`（`SessionStore.kt:533`）、`contextPressure`/`contextBreakdown`（`ContextMeter.kt`）、`sessionStats`（`DetailsPanel.kt` 在用，字段 `turns/steps/llmMs/toolMs/ttftMs/ttftSteps/decodeMs/decodeTokens`）。缺的只是"把这些摆好看"和"金额"。

建议：DetailsPanel 里加一张会话统计卡（回合数、步数、模型时间、工具时间、TTFT、解码速度）。金额不做默认展示——桌面 `dsh-cost-meter` 的数是靠计费历史/provider API 回填的，移动端没有等价数据源；要做就做成用户可填单价的估算值，并明确标"估算"。

### P1-8 分享 / Open In

桌面是"用记住的应用打开工作区目录 / 在默认应用里打开文件"。移动端已有反方向能力：`session/openWorkspacePath`（让**桌面端**打开）。要补的是 Android 侧出口：

- 文件预览页（`WorkspacePanels.kt`）加「分享」「用其他应用打开」：先 `workspaceFiles/readBytes` 落到 `cacheDir`，再用 `FileProvider` + `ACTION_VIEW`（MIME 按扩展名）/ `ACTION_SEND`。
- 长按文件加「在桌面端打开」，直接调已有的 `session/openWorkspacePath`。

坑：FileProvider authority 要并进现有 `provider` 配置，别新增第二个；大文件先下再分享，别在内存里拼。

### P2-9 原始日志检查器

`TrajectoryTab` 已经是事件账本，加一个「原始 JSON」开关，把每条事件的 `data` 用 `JsonDisclosure` 摊开即可。桌面的 session-inspector 还有 group/node 表和 delta 行高亮，移动端屏幕小，先不做。

### P2-10 逐文件 diff 审查

桌面 deliverables 的逐文件对比页，hunk 是从工具调用携带的 patch 文本解析出来的（`workspace-files` 服务**没有** diff 方法，`workspace/changes` 事件只带 `{turn}` 文件名——`ChangedFiles.kt:57` 已经写明了这点）。所以移动端也能做：在 `ToolCards` 里对 `apply_patch` / 统一 diff 两种参数格式做解析，渲染 unified 视图（窄屏不做 split）。注意兼容 `*** Begin Patch` 与标准 `---/+++` 两种头。

### P2-11 会话分组/排序

抽屉现在是"按 workspace 分组 + 每组预览 5 条"（CHANGELOG 已记录）。补一个排序/分组切换：按最近活动、按未读/运行中、按归档。低成本。

### P2-12 MCP 只读状态

移动端没有 MCP 命名空间，`dsh-mcp-client` 的能力也走主机侧。真要做得先有 Remote 契约，现在只能读 settings 里的 MCP 配置做只读展示，价值低，可跳过。

---

## 三、已对齐（不需要动）

移动端已经覆盖、且实现深度够用的桌面能力：

审批接管、提问/计划审阅、GoalBar、Job 列表与流式输出、子代理目录与续聊、定时任务（Automation*）、归档会话、轨迹账本、改动文件卡、回合导航（`turnOutline`）、过程折叠、长按菜单、`/` 命令、`@` 文件/会话引用、技能（skills）、Agent 预设、模型选择、权限预设、图片附件、消息 Like/Dislike 反馈、上下文压力表、会话统计、终端（WebView xterm）、工作区文件浏览与预览（文本/图片/PDF/HTML/SVG）、目录选择、插件安装/启停/新建、11 语言 + RTL、Markdown 表格/任务列表/代码高亮、`dsh-rewind` 回退。

## 四、不适用 / 不建议照搬

| 桌面功能 | 为什么不做 |
| --- | --- |
| `dsh-experimental-inspector`（Chrome DevTools 查 Cordis 运行时） | 开发工具，手机上没屏幕也没用 |
| `dsh-client-ui-shortcuts`（快捷键参考/录制） | 移动端无物理键盘 |
| `dsh-client-ui-layout`（三栏 AppFrame + 拖拽手柄） | 手机是单栏 + 抽屉 + 底部弹层，已有等价导航 |
| `dsh-client-ui-entry-customization`（左栏/右栏插件浏览 + Creator 草稿） | 桌面布局专属；移动端"新建插件"已由 `PluginManagerScreen.stageFeatureDraft` 覆盖 |
| `dsh-client-ui-sidebar-browser`（右栏 HTTP 浏览器） | 移动端连接是 ws mux，没有通用 HTTP 转发；要做需主机新增代理方法，收益不成比例 |
| `dsh-experimental-auto-review`（工具调用前模型审查） | 主机插件、默认关闭；移动端要做只是 settings 里一个开关，等 P0-4 设置页做好后顺带 |
| `dsh-experimental-client-ui-cordis`（插件卡片 run/stop） | 移动端插件启停已有（`setBundleEnabled`/`setPluginEnabled`） |
| `dsh-client-ui-settings-account`（DeepSeek 登录 + 计费） | `account/startSignIn` 需要 `callbackOrigin` 深链回调，移动端要自建回调页；收益低。只读余额可留到以后 |
| worktree / ralph / office / ptc | 都是主机侧工具，移动端通过工具卡渲染即可，无需专属 UI |

## 五、移动端反超桌面端的地方

移植时别把这几样弄丢——它们是移动端独有或做得更好的：

- 一步连接（局域网链接 / QR 扫码 / `adb reverse` / Termux 本机 / HTTPS 反代），桌面只有本机。
- 通知保活：回合结束、Goal 完成、提问待答都能推到系统通知栏。
- 长按即操作（消息/工具/文件/队列/发送）——比桌面 hover 菜单更省手指。
- 回合导航条 + 过程折叠：长会话在手机上比桌面更好读。
- 归档会话独立列表、定时任务的移动端管理面。
- 11 语言 + RTL 完整度。

## 六、建议落地顺序

**第一批（做完就能天天用）**：P0-1 语音输入 → P0-3 插件市场 Discover → P0-2 Agent Teams 面板。**已完成，见 [第一批落地记录](#七第一批落地记录)。**

理由：语音是手机最自然的输入；市场 Discover 让"装插件"不再需要回到桌面；Teams 面板补上"我在手机上却看不见队友在干什么"的断层。

**第二批（补齐管理面）**：P0-4 主机设置编辑器（先做 describe 驱动的通用表单，再补 models/credentials 两个专页）→ P1-5 密钥遮罩 → P1-8 分享/Open In。

**第三批（锦上添花）**：P1-6 工作流节点（先补 fixture）→ P2-10 diff 审查 → P1-7 统计卡 → P2-9 原始日志 → P2-11 分组排序。

**每批的验收口径**（沿用项目现有测试习惯）：

1. `:core:test :mock-harness:test :app:testDebugUnitTest` 全绿；
2. 新 Remote 方法在 `mock-harness` 有假实现 + 契约测试；
3. 主机缺该能力时（`capability-unavailable`）入口隐藏、不报错；
4. `docs/PROTOCOL.md` 对应章节补上新命名空间（`speech`、`pluginMarket`、`agentTeam` 投影）；
5. 真机（局域网 + Relay 各一遍）走通主路径。

---

## 七、第一批落地记录

2026-10-30 落地。三项都按上面的验收口径交付：契约测试在 `app/src/test/java/com/qgx1992/dshmobile/connection/DesktopParityContractsEndToEndTest.kt`，假主机在 `mock-harness/.../MarketScenario.kt`。

| 项 | 新增文件 | 改动文件 |
| --- | --- | --- |
| P0-1 语音 | `core/.../core/audio/WavWriter.kt`（+ 单测）、`app/.../ui/media/VoiceRecorder.kt`、`app/.../ui/components/VoiceInput.kt` | `Composer.kt`（`voice` 参数）、`ChatScreen.kt`（`rememberVoiceInput` + 追加转写）、`SessionStore.kt`（`speechCatalog` / `transcribeSpeech`）、`AndroidManifest.xml`（`RECORD_AUDIO`） |
| P0-2 Teams | `app/.../ui/screens/main/AgentTeamSheet.kt`、`TeamPresentation.kt` | `ProjectionDtos.kt`（`AgentTeamView` 一族）、`SessionStore.kt`（`agentTeam` 投影）、`ChatTopBar.kt`（team chip）、`ChatScreen.kt`（`ChatSheet.Team`） |
| P0-3 Discover | `core/.../wire/dto/PluginMarketDtos.kt`、`app/.../ui/screens/main/PluginMarketScreen.kt`、`MarketPresentation.kt` | `Harness021Api.kt`（三个 market 方法）、`PluginManagerScreen.kt`（双标签）、`FeatureAvailability.kt`（`SPEECH` / `PLUGIN_MARKET`） |

### 三个没预料到的坑

1. **`strictObject` 拒 null。** `pluginMarket/search` 的 `query` 是 strict schema，`sort`/`offset`/`limit` 必填，其余键**不能出现**（哪怕是 `null`）。所以 `MarketQuery.toJson()` 是手写的「只写已设字段」，不是 `encodeDefaults = true`。
2. **`configure` 的参数叫 `patch` 不叫 `request`。** 网关按参数名匹配 `args`，`speech/transcribe` 包 `{request}`、`speech/configure` 包 `{patch}`、`speech/cancelPreparation` 则是裸 `providerId`——三个方法三种形状，测试里三个都覆盖了。
3. **`JsonElement.jsonPrimitive` 在目标是对象时抛异常。** 写 mock 的模糊搜索时踩到：`localizedText` 既可能是字符串也可能是语言 map，先 `as? JsonPrimitive` 再取值，否则搜索命中标题就 500。

### 留给第二批的接口

- `speech/follow`（provider 就绪的流式快照）没有接。当前实现是「连上时取一次 catalog」，主机后面装了识别器要重连才看得见。真要接，`RemoteStreamMux` 已经支持 stream RPC。
- `speech/prepare` / `cancelPreparation` 只在 API 层存在，UI 没暴露——本地 Whisper 首次要下载几百 MB，手机上给个「预热」按钮是合理的，但那属于设置页（P0-4）。
- `agentTeam` 的 `failure` 字段画了但没法重试：重试用的是 Lead 自己的工具，不在本客户端。
