# 缺陷修复记录

> 可直接打开的正式记录，路径：`minecraft-codex-companion/bugs.md`。
> 原始验收证据仍保存在 `.runtime/full-acceptance-20260825-140819/`，下方原始记录未删除。

## 当前结论（2026-08-28 v0.1.14 最终安装版 Forge 实测）

- 本轮最终实机范围只包含 `Dragons_ZH_1.20.1`；`1.21.1-NeoForge` 按用户决定明确排除，不计为通过或失败。
- 最终安装包为 `build/single-exe/MinecraftCodexCompanion-Setup.exe`，大小 `196124160` bytes，SHA-256 `8db54c259907bbe62bcd8c2bdb28fc47eefaada90a556a0f634240598c5c255a`，Package ID `b159f2ec04a8695671931aea88580ae6d5c1197210bc5af6a3987eea125c5cbf`；含 `5888` 个载荷文件、`562146571` bytes 未压缩载荷。
- 最终 Forge JAR SHA-256 为 `62b15a6964ac131cec5f2aefad694f2f8ed0c9b926f6e675f8dac8808fc33ce2`；最终 NeoForge JAR SHA-256 为 `4762ed6bf941a4e6ef54f931ad12a1462bdeb2df757ce84d13e47a2f1c29ac41`。完整性与隐私门禁通过；ClamAV 1.5.4 扫描为 `clean`、感染文件 0，但本机静态病毒库报告超过 7 天；安装包未签名。
- 最终自动门禁通过：发布门禁 `19/19`、Forge 隔离测试 `474/474`、控制层 `311` 项通过且 `1` 项按设计跳过、Dashboard `15/15`、worker `4/4`、启动器 `41/41`、协议 `46/46`、脚本 `188/188`，类型检查、生产构建和便携包验证均通过。
- 无额外环境变量的最终安装版冷启动精确使用所选源实例：`instanceMode=direct-source`、`exactSelectionVerified=true`，实际 JVM 为 `Dragons_ZH_1.20.1` 且命令行、版本和游戏目录均不含 `-Codex`。JDK 17 短临时目录隔离、ACL 限制和窗口 v6 焦点策略均生效，`3831/3831` 次全过程审计安全。
- 最终安装版正式卸载召回通过：同一 NPC UUID 在距玩家 `16548.441` 格的卸载位置被正式召回，`874ms` 后距玩家 `7.373` 格；最终 `idle`、暂停数 0、队列为空。该链没有把玩家传送到 NPC，也没有使用测试专用键鼠或窗口操作。
- 最终安装版 Minecraft T 聊天通过：真实客户端 `ChatScreen.handleChatInput` 提交 `原地等待喵`，请求 `succeeded`，游戏内回写 `好，我在这里等你。`；桥遥测增量为 `1/1/1`，NPC 保持 `stay/idle`。Codex 固定线程及反重力 `conversationId` 前后哈希均未变化，45 秒外连采样为 0，没有调用 provider，也没有向任何 `base_url` 发送日志、路径、配置、截图、聊天、会话或其他敏感数据。
- T 聊天截图已人工核对，提问与回复均清楚可见；主审计 `4369/4369`、T 聊天专项审计 `822/822`、关闭聊天后审计 `273/273`、原生保存退出审计 `410/410` 均安全，stderr 均为空。Minecraft、控制服务、安装版启动器及 `127.0.0.1:8776` 监听已全部关闭。
- 玩家真实死亡后，测试专用客户端原生重生夹具已把 `death` 恢复为 `gameplay`；随后受限 `view-npc:npc` 夹具把玩家恢复到 NPC 旁 `3.033` 格。两步均未使用窗口或键鼠输入，且不属于正式游戏行为。
- 正式 NPC 倒地恢复通过：NPC 在距玩家 `13.342` 格处进入 `downed`，约 10 秒后恢复为 10 点生命并回到玩家旁 `0.997` 格；玩家没有被正式恢复流程传送。NPC 与玩家最终同为 `Y=111`，安全放置检查通过，未再出现 NPC 陷地数据表现。
- BUG-029 已在无作弊世界通过实机回归：阻塞目标在 `2159ms` 内明确失败为 `NAVIGATION_TARGET_UNREACHABLE`；主动取消后的下一条五格 move 在 `1003ms` 内成功，终点经 3 个后续 snapshot 稳定在水平容差 `0.380` 格内，正式移动阶段无传送或命令。
- 独立作弊测试副本 `Codex-Dragon-Acceptance-20260828` 完成龙照料矩阵：Book 与 Saints 的 feed、heal、tame、egg 共 `8/8` 成功；Book 的 9 个动作阶段全部成功，Saints 完整套件本轮连续 `4/4` 成功。所有夹具最终完整回滚，NPC idle、队列为空。
- BUG-006 跨维度残留记录冷重启修复通过：修复版使用同一 NPC UUID 在玩家当前主世界安全恢复，记录同步更新，12/12 后续样本稳定。BUG-024 跨维度闭环也通过：下界 12/12 样本均 paused、水平漂移 `0`，返回主世界后同一任务恢复 `running/active`，最终清理完整。
- BUG-001、004、005、007–010、012–019、020–026、029、030 均已有 Forge 实机通过证据；BUG-002、011 也保持既有实机通过。BUG-003 与 BUG-027 属于本轮明确排除的 NeoForge 范围；BUG-028 在最终候选的两次独立安装版冷启动及后续流程均未复现，但因历史 RC6 曾间歇触发，仍保留残余风险。因此本轮可以提交最终 Forge 候选，但不把未测的 NeoForge 范围或 BUG-028 历史风险误标为完整稳定 Release。

### 最终 Forge 接续实测明细

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| F7-F01：最终 EXE、JAR 与安全门禁 | 通过 | EXE `8db54c…c255a`、Package ID `b159f2…5cbf`；Forge/NeoForge JAR 分别为 `62b15a…ce2`、`4762ed…c41`；完整性、隐私、ClamAV 均通过。 |
| F7-F02：最终安装版精确冷启动源实例 | 通过 | `direct-source`、`exactSelectionVerified=true`；实际 JVM 无 `-Codex`；短 Temp、ACL、v6 焦点策略生效，审计 `3831/3831` 安全。 |
| F7-F03：正式卸载 NPC 召回 | 通过 | UUID 不变，距离 `16548.441 -> 7.373`，耗时 `874ms`；最终 idle、暂停数 0、队列为空。 |
| F7-F04：Minecraft T 聊天本地回写 | 通过 | `原地等待喵 -> 好，我在这里等你。`，请求 succeeded、遥测增量 `1/1/1`；固定线程与会话 ID 不漂移，外连采样 0。 |
| F7-F05：T 聊天截图、关闭与保存退出 | 通过 | 游戏内提问/回复可见；关闭聊天和保存退出均由 Minecraft 客户端线程执行，审计分别 `273/273`、`410/410` 安全。 |
| F7-F06：玩家测试专用原生重生 | 通过 | `death -> gameplay`；无窗口、键鼠或光标写操作，玩家回 NPC 仅为显式测试夹具。 |
| F7-F07：正式 NPC 倒地恢复 | 通过 | `13.342` 格处倒地，恢复后生命 `10`、距玩家 `0.997` 格；正式流程未传送玩家。 |
| F7-F08：安全路径五格无作弊步行 | 通过 | `(261.5,111,-8.5)` 到稳定终点 `(266.432,111,-8.5)`，距目标 `0.068` 格；survival、无传送/命令。 |
| F7-F09：阻塞路径与后继导航恢复 | 通过 | 阻塞目标 `2159ms` 明确失败；取消后的下一条五格 move `1003ms` 成功并经 3 个后续 snapshot 稳定确认。 |
| F7-F10：NPC 不陷地 | 通过（服务端数据） | 正式恢复后 NPC/玩家同为 `Y=111`，安全站位与碰撞检查通过；固定镜头没有拍到 NPC 本体。 |
| F7-F11：龙照料、动作与回滚 | 通过 | Book/Saints 照料 `8/8`；Book 完整动作套件通过，Saints 连续 `4/4` 完整套件通过；cleanup 无残留。 |
| F7-F12：跨维度残留 NPC 记录冷重启恢复 | 通过 | 下界残留记录在修复版冷重启后以同一 UUID 恢复到玩家主世界附近，记录同步更新；12/12 稳定样本通过。 |
| F7-F13：跨维度 move 暂停与返回恢复 | 通过 | 下界 12/12 样本 paused、水平漂移 `0`；返回主世界后同一任务恢复 `running/active`，最终取消、idle、清理完整。 |
| F7-N01：`1.21.1-NeoForge` | 未测试（范围排除） | 用户明确决定本轮只测 `1.20.1`。 |

## 上一阶段结论（2026-08-28 v0.1.14 RC6 Forge 单版本实测）

- 本轮范围按用户决定收敛为 `Dragons_ZH_1.20.1`；`1.21.1-NeoForge` 明确记为**未测试（本轮范围排除）**，不计为通过或失败。
- 新安装包 `build/single-exe/MinecraftCodexCompanion-Setup.exe` 已完成自检和静默安装，两个退出码均为 `0`。EXE SHA-256 为 `1ea379df309906c6effe46aefe74192bd9a2146c987e9f00e58e6369d5cf4aab`，Package ID 为 `4292725eab1253d1b6b626d3403b3424db104f440c3962a096cdb52635b1e640`，载荷文件数 `5889`、未压缩载荷 `562232222` bytes，Authenticode 未签名。
- 自动门禁已通过：发布门禁 `19/19`、启动器 `41/41`、Forge 隔离测试 `470/470`。窗口代理 SHA-256 为 `00e7daa67c0f7727dd9d6b02abb547c1bd93a01c3b14a6b92ce02e081ca05a17`，并已通过 synthetic、LWJGL `3.3.1`、LWJGL `3.3.3` 的 `java -Xverify:all`。
- EXE 四次都精确冷启动所选源实例 `Dragons_ZH_1.20.1`，`instanceMode=direct-source`、`exactSelectionVerified=true`；实际 JVM 命令包含源版本且不含 `-Codex`。历史 `Dragons_ZH_1.20.1-Codex` 基线仍为 `764` 个文件、`483204540` bytes，本轮没有创建或启动副本。
- RC5 首次实机发现的 `GLFW.glfwShowWindow(J)V` `VerifyError` 已修复；RC6 四次真实启动均无 `VerifyError`。状态证据为 v3 策略 `glfw-create-show-noactivate-and-explicit-focus-v3`，`WS_EX_NOACTIVATE` 只在原生显示期间临时使用，显示后恢复，`minecraftWindowPersistentMutation=false`。
- **BUG-028 仍为间歇性失败。** 四次冷启动只读审计中，第 1、3、4 次观察到 Minecraft 曾成为前台，仅第 2 次没有；第 2 次独立世界进入审计也没有。五份审计均未观察到置顶或光标限制，且测试器没有使用键盘、鼠标、窗口层级或光标写操作。Attempt 4 进入 gameplay 后 Minecraft 正处于前台，严格后台截图按设计拒绝执行，因此不能只凭代理内部校验判通过。
- 固定测试世界 `Codex-NoCheat-Retest-20260826` 的前三次客户端线程打开请求在 Netty selector 阶段因 `Unable to establish loopback connection` / `Invalid argument: connect` 崩溃。诊断发现用户 Temp 中已有 `261` 个 `socket_*` 条目；同一 Java 17 把 `TEMP/TMP` 和 `jdk.net.unixdomain.tmpdir` 临时指向全新短目录后，最小 `Selector.open()` 通过。该环境前置不需要改系统变量或产品代码。
- Attempt 4 使用仅对 Companion 子进程生效的干净 Temp 后成功进入固定世界，Forge `codex-forge / 唯 / Crow` 已连接，snapshot 为 `clientUiState=gameplay`、`gameMode=survival`、`materialMode=survival`。随后 BUG-028 再次触发前台化；为遵守“不把游戏放到最前端”的硬约束，本轮立即通过回环急停并由 Minecraft 客户端线程原生保存退出。因此清理夹具、无作弊 5 格步行、NPC 倒地回到玩家附近、龙/NPC 不陷地、玩家原生死亡重生、Minecraft T 聊天与本地自然语言回写均记为**受 BUG-028 安全门禁中止，未执行**。
- 只读截图 `.runtime/v014-rc6-forge-live-20260828-015123/screenshots/01-attempt2-main-menu-background.png` 只含 Minecraft 客户区，`854x480`、`ForegroundUnchanged=true`、`CursorClipUnchanged=true`，未使用 `PrintWindow`、剪贴板或输入。
- 测试结束已正常关闭 Companion、控制服务和 HMCL；Forge 临时配置、`options.txt`、聊天、启动器及 HMCL 设置共 7 个文件均按 RC5 测试前备份恢复并通过 SHA-256 对比。世界存档和已安装的新桥接 JAR未回滚。
- 本轮未调用 Codex、OpenAI 或反重力在线提供方。当前构建可作为带已知问题的测试候选提交和上传，但不能标记为完整实机验收通过或正式稳定 Release。

### RC6 Forge 实测明细

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| RC6-F01：EXE 自检与静默安装 | 通过 | 自检、安装退出码均为 `0`；Package ID `4292725eab1253d1b6b626d3403b3424db104f440c3962a096cdb52635b1e640`。 |
| RC6-F02：精确冷启动源实例 | 通过 | 四次均为 `direct-source`、`exactSelectionVerified=true`，实际 JVM包含 `Dragons_ZH_1.20.1`，无 `-Codex` 路径。 |
| RC6-F03：不创建或启动 `-Codex` | 通过 | 实际进程不含副本路径；历史副本基线未变化。 |
| RC6-F04：v3 窗口代理 JVM 校验 | 通过 | 四次真实 LWJGL 启动均无 `VerifyError`；临时 `WS_EX_NOACTIVATE` 显示后恢复，无持久窗口样式修改。 |
| RC6-F05：冷启动全过程不抢前台 | **失败（间歇，BUG-028）** | 第 1/3/4 次审计记录 `MinecraftForegroundObserved=true`，第 2 次为 `false`；四次均未置顶或限制光标。 |
| RC6-F06：世界进入阶段不抢前台 | 通过（单次） | 第 2 次独立世界进入审计为 `Safe=true`，无前台化、置顶、光标限制或输入。 |
| RC6-F07：后台 Minecraft 客户区截图 | 通过 | `01-attempt2-main-menu-background.png` 为 `854x480`，只读 `GetDC + BitBlt`，前台和光标边界未变化。 |
| RC6-F08：进入固定无作弊测试世界 | 通过（环境隔离后） | 前三次受用户 Temp 中 Java socket 环境阻塞；Attempt 4 使用进程级干净 Temp 后进入 gameplay，Forge `codex-forge` 成功连接。 |
| RC6-F09：NPC、龙、步行、重生、T 聊天回写 | 未执行（BUG-028 安全中止） | 世界已连接，但 Minecraft 成为前台；按用户硬约束立即急停并原生保存退出，未继续长时间功能测试。 |
| RC6-F10：测试后恢复 | 通过 | 7 个配置文件与测试前备份哈希一致；Minecraft、HMCL、`8776` 均已清空。 |
| RC6-N01：`1.21.1-NeoForge` | 未测试（范围排除） | 用户明确决定本轮只测 `1.20.1`。 |

## 上一轮基线结论（2026-08-27 v0.1.14 双版本实机回归后）

- 当前 `0.1.14` 安装包候选已分别冷启动 `Dragons_ZH_1.20.1` 与 `1.21.1-NeoForge` 原始源实例；实际进程 `--version`/`--gameDir` 与所选实例一致，均未使用 `-Codex` 路径。历史 `Dragons_ZH_1.20.1-Codex` 仍只有一个，创建时间 `2026-07-31T11:55:49.1256206Z`、修改时间 `2026-08-25T11:50:19.1373874Z`、文件数 `764` 均未变化。
- Forge 已完成源世界、原生重生、正式 NPC 倒地恢复、无作弊远征和真实 Minecraft T 聊天本地回写；T 聊天遥测为 `incoming=3`、`delivered=3`、`roundTrip=3`，固定本地请求 `原地等待喵` 成功回写 `好，我在这里等你。`。
- NeoForge 已完成源世界、窗口化冷启动、原生重生和真实 Minecraft T 聊天本地回写；T 聊天遥测为 `1/1/1`，固定本地请求 `停下喵` 成功回写 `已立即停止所有任务。`。但 5 格无作弊步行出现新的 BUG-027 表现：任务虚报 `succeeded`，服务端稳定位置实际只前进约 `1.56` 格、距目标仍约 `3.394` 格。
- 因 BUG-027 仍是 P1 实机失败，当前候选只可作为已构建、已安装和已测试的修复候选，不能标为最终验收通过，也暂不创建正式标签或 Release。
- BUG-028 的双版本实机专项已通过：Forge 的 `77/77`、`228/228`、`274/274` 以及 NeoForge 的 `365/365`、`818/818`、`273/273` 连续只读审计均未观察到置顶、Minecraft 前台化、后台光标限制或任何窗口/键鼠/光标写操作。截图始终只包含 Minecraft 客户区。首次 NeoForge 启动沿用了 HMCL Home 的既有全屏预设，用户关闭后已把 HMCL 全局与预设统一改为 `fullscreen=false`、`854×480`；第二次冷启动实测为 854×480 窗口。
- 后台进世界、T 聊天、重生和保存退出均由本机受限 JVM 测试代理或回环固定夹具完成；没有调用窗口消息、输入模拟或光标 API。测试结束时先断开桥，再在 Minecraft 客户端线程调用原生停止流程保存退出，HMCL 随后自行退出。
- 最新自动回归：Forge 与 NeoForge 完整 clean build 通过；Forge 隔离 JUnit `468/468`；控制层 `311` 项通过、`1` 项按设计跳过；Dashboard `15/15`、worker `4/4`、启动器 `41/41`、协议 `46/46`、脚本 `188/188`；全工作区 build/typecheck 与 `git diff --check` 通过。
- 临时聊天设置为 `freeChatEnabled=true`、`target=active-provider`、`actionMode=smart`、`tokenBudget=512`、`persona.mode=inherit`；本轮只使用确定性的本地回复路径，未调用 Codex、OpenAI 或反重力在线提供方。
- 已基于当前工作区重新生成 `0.1.14` 安装包候选与 AgentKit，并完成完整性、正式载荷安装、隐私和本机 ClamAV 门禁。测试过程中误传 `--result-file` 时，正式安装器按设计拒绝离线测试专用参数并弹出错误；改用正式 `--quiet --install-only` 后安装成功，不是载荷损坏。

最新 `0.1.14` 安装包候选构建证据：

- 文件：`build/single-exe/MinecraftCodexCompanion-Setup.exe`
- SHA-256：`27555e08c4d36d0beea1724479d585387cc6c0f26fc9ea18c65ff876582b5e39`
- Package ID：`fa1e411975020304276ed891cba94a6c4e16db945c63261f340eeb8afe60da2f`
- 大小：`195999232` bytes；内含 `5888` 个载荷文件、`562086194` bytes 未压缩载荷
- Forge JAR SHA-256：`952309635da91e4fab2686eefe6741da7636751596f5fa4b3abca079429c6d16`
- NeoForge JAR SHA-256：`205be691bcd5efb4b8127162d5ef8e53ee358d6618af26613a1e84bae8618828`
- 完整性：已验证
- 隐私门禁：仅本机、已通过
- 防病毒扫描：ClamAV 1.5.4，`clean`、感染文件 0；本机静态病毒库报告超过 7 天，需保留该时效警告
- Authenticode：未签名（构建机没有代码签名证书）
- AgentKit：`build/agent-kit/MinecraftCodexCompanion-AgentKit-v0.1.14.zip`，SHA-256 `c51b14478a084ff2ac4e4719c7ff518b7a843c01fade1c15c99530ff856ae847`，9 个预期文件，隐私校验通过
- 安装器验证：正式 EXE 的 `--self-test --quiet` 退出码为 `0`；离线安装器套件通过 WinForms 初始化、确定性构建、校验解包、启动、幂等安装、原子更新和未知文件保留测试；正式 `195 MB` 候选也在短 Temp 根下完成 `--quiet --install-only`，安装目录 Package ID 与报告一致，两个 JAR 哈希一致且均含 `ClientRecoveryLiveFixture.class`

### 2026-08-27 v0.1.14 Forge 完整实测

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| V14-F01：新版 EXE 读取并更新源实例 | 通过 | 正式安装目录 Package ID 为 `fa1e411975020304276ed891cba94a6c4e16db945c63261f340eeb8afe60da2f`；配置为 `instanceMode=direct-source`、`sourceVersion=targetVersion=Dragons_ZH_1.20.1`；实例内 Forge JAR SHA-256 与载荷一致，均为 `952309635da91e4fab2686eefe6741da7636751596f5fa4b3abca079429c6d16`。 |
| V14-F02：EXE → Forge 源实例精确冷启动 | 通过 | 启动结果为 `exactInstance=Dragons_ZH_1.20.1`、`instanceMode=direct-source`、`exactSelectionVerified=true`；实际进程同时包含 `--version Dragons_ZH_1.20.1` 与源实例 `--gameDir`，没有 `-Codex` 路径。 |
| V14-F03：源世界与桥接 | 通过 | 由 Minecraft 客户端线程打开既有测试世界，Forge 桥以 `forge-1.20.1` 连接；没有使用窗口消息、键盘或鼠标输入。 |
| V14-F04：原生死亡重生 | 通过 | 真实死亡后调用固定 `client-recovery:respawn`，`clientUiState` 从 `death` 恢复为 `gameplay`、生命恢复 `20/20`；截图为 `v014-candidate-forge-death-before-native-respawn-20260827.png`、`v014-candidate-forge-gameplay-after-safe-respawn-20260827.png`。 |
| V14-F05：无作弊远征 | 通过 | 官方无作弊远征链在 survival 下完成；没有传送、创造模式或命令替代，移动和采集由正式任务链执行。 |
| V14-F06：正式 NPC 倒地恢复 | 通过 | NPC 倒地后按正式产品链恢复并回到玩家附近，未使用“玩家传送到 NPC”测试夹具；截图为 `v014-candidate-forge-npc-downed-20260827.png`、`v014-candidate-forge-npc-recovered-near-player-20260827.png`。 |
| V14-F07：Minecraft T 聊天与自然语言回写 | 通过 | 通过真实 `ChatScreen.handleChatInput` 提交固定本地短语 `原地等待喵`，请求 `succeeded`，回复 `好，我在这里等你。`；桥遥测 `3/3/3`。聊天历史截图为 `v014-candidate-forge-native-tchat-open-history-20260827.png`。 |
| V14-F08：不抢焦点与只读截图 | 通过 | 多段严格后台审计 `77/77`、`228/228`、`274/274` 全部安全；客户区截图为 854×480，`ForegroundUnchanged=true`、`CursorClipUnchanged=true`，未使用剪贴板或 `PrintWindow`。 |

### 2026-08-27 v0.1.14 NeoForge 完整实测

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| V14-N01：EXE → NeoForge 源实例精确冷启动 | 通过 | 实际进程为 `--version 1.21.1-NeoForge`、`--gameDir C:\\Users\\Administrator.WWW\\AppData\\Roaming\\.minecraft`，`UsesCodexClone=false`；源实例 NeoForge JAR SHA-256 与载荷一致，为 `205be691bcd5efb4b8127162d5ef8e53ee358d6618af26613a1e84bae8618828`。 |
| V14-N02：保持窗口化 | 通过（修正既有 HMCL 设置后） | 首次启动沿用 HMCL Home 的既有 `FULLSCREEN/1920` 全局预设；用户关闭后将 HMCL 全局及预设统一为 `fullscreen=false`、`854×480`。第二次冷启动客户区实测正好为 854×480，截图 `v014-candidate-neoforge-windowed-main-menu-20260827.png`。 |
| V14-N03：源世界与桥接 | 通过 | 客户端线程打开既有世界 `新的世界`，连接身份为 `codex-neoforge / neoforge-1.21.1 / 0.1.0`；源世界截图为 `v014-candidate-neoforge-source-world-gameplay-20260827.png`。 |
| V14-N04：5 格无作弊步行 | **失败（BUG-027）** | 从 `(2.170,78,9.544)` 指向 `(7.105,78,8.739)` 的精确水平 move 被报告 `succeeded/已到达目标位置`，但服务端稳定终点为 `(3.711,78,8.831)`：只前进约 `1.56` 格，仍差约 `3.394` 格。没有传送、创造或命令；截图 `v014-candidate-neoforge-move-false-success-20260827.png`。 |
| V14-N05：原生死亡重生 | 通过 | 真实死亡后新 JAR 的固定重生夹具把 `death` 恢复为 `gameplay`，不使用窗口、键鼠或光标 API；最终地表满血截图 `v014-candidate-neoforge-final-native-respawn-windowed-20260827.png`。 |
| V14-N06：Minecraft T 聊天与自然语言回写 | 通过 | 通过真实 `ChatScreen.handleChatInput` 提交固定本地短语 `停下喵`，请求状态 `stopped`，回复 `已立即停止所有任务。`，桥遥测 `1/1/1`。 |
| V14-N07：不抢焦点与只读截图 | 通过 | 世界加载、移动和重生/T 聊天后的严格后台审计分别为 `365/365`、`818/818`、`273/273`，均未见置顶、前台化、光标限制或桌面写操作；所有证据图只含 Minecraft 客户区。 |
| V14-N08：保存退出 | 通过 | 先通过回环急停安全断开桥，再由本机临时 JVM agent 在 Minecraft 客户端线程调用原生停止流程；游戏保存后自行退出，HMCL 随之退出，没有窗口消息或输入模拟。临时 agent 位于忽略目录，不提交、不打包。 |

## 修复状态表

| 缺陷 | 当前状态 | 本次修复摘要 |
| --- | --- | --- |
| BUG-001 | Forge 实机回归通过（2026-08-28） | 错误反重力标题返回 `409 / ANTIGRAVITY_CONVERSATION_TITLE_MISMATCH`、`retryable=false`，在解析在线端点前拒绝；Forge 保持连接且未调用在线模型。 |
| BUG-002 | v0.1.14 双版本基线通过；RC6 Forge 再次通过（2026-08-28） | RC6 四次精确冷启动 `Dragons_ZH_1.20.1` 源实例，均为 `direct-source`、`exactSelectionVerified=true`，未新增或启动 `-Codex` 副本。 |
| BUG-003 | 代码与自动化通过；本轮未实测（NeoForge 范围排除） | NeoForge `remote-player` 不支持的 NPC 控制应返回明确 422，不再虚报 202；不能用 Forge 实测替代该后端验证。 |
| BUG-004 | Forge 实机回归通过（2026-08-28） | 存在断开的历史实例时，全局急停仍返回 202；在线 Forge 进入 idle、队列为空，历史实例保持断开。 |
| BUG-005 | Forge 实机回归通过（2026-08-28） | 同一 Minecraft JVM 退出世界后桥断开，重进后建立新连接；序列和时间继续增长、NPC UUID 不变，稳定样本均 gameplay/idle。 |
| BUG-006 | Forge 实机回归通过（2026-08-28） | 同维度仍按旧记录加载；跨维度残留记录直接以同一 UUID 在玩家当前维度安全恢复。冷重启后记录、实体与 12/12 快照一致。 |
| BUG-007 | Forge 实机回归通过（2026-08-28） | 真实 T 聊天“吃掉腐肉”建立本地 `eat` 任务并成功消费目标食物，没有改写为备粮或调用在线模型；最终 cleanup 完整。 |
| BUG-008 | Forge 实机回归通过（2026-08-28） | 父采集任务取消后，本地补粮子任务、暂停队列和 active 状态同步清空；最终 idle 且夹具完整恢复。 |
| BUG-009 | Forge 无作弊实机回归通过（2026-08-28） | 正式生存链真实砍伐并交付 `4/4` 原木，剩余夹具原木、排队目标和跳过目标均为 0；无作弊、创造或传送。 |
| BUG-010 | Forge 实机回归通过（2026-08-28） | 真实 T 聊天“给我找些食物”稳定创建 `provision-food count=8`，智能模式仍保留确定性合同；未调用在线模型。 |
| BUG-011 | 实机回归通过（会话门禁） | Forge 与 NeoForge 两轮前后固定 conversationId 始终不变，`busy=false`、`queuedTurns=0`，后续 T 聊天可继续进入同一会话。 |
| BUG-012 | Forge 实机回归通过（2026-08-28） | 真实 T 聊天从两个可用容器中取出并交付 8 个圆石；实体箱内 `8 -> 0`、玩家 `0 -> 8`，任务 succeeded 且 cleanup 完整。 |
| BUG-013 | Forge 产品链实机回归通过（2026-08-28） | 床宏成功、真实床脚/床头配对可见，sleep 成功并观察到入睡/起床；调度器 idle、队列为空。严格夹具 NBT 对比只识别到易变玩家字段，不否定产品断言。 |
| BUG-014 | Forge 实机回归通过（2026-08-28） | 同一 gather 任务在玩家死亡时进入 paused 并稳定零漂移，原生重生后同一任务 ID 恢复 active；最终取消、idle 和保护恢复完整。 |
| BUG-015 | Forge 实机回归通过（2026-08-28） | 两条同义建造提示只生成 1 个 goal 和 1 个 build task，第二次复用同一 goal；取消前无建造事务，背包前后完全一致。 |
| BUG-016 | Forge 实机回归通过（2026-08-28） | 客户端线程阻塞约 15 秒制造迟到 ACK；请求仍 succeeded，迟到 ACK 只增加遥测，未生成任何矛盾聊天或失败回写。 |
| BUG-017 | Forge 冷重启实机回归通过（2026-08-28） | 深挖任务在 `preflight` 检查点原生保存退出，重启后继续并最终制作/交付钻石镐；inspect `ok=1`，cleanup 后 idle、队列为空。 |
| BUG-018 | Forge 实机回归通过（2026-08-28） | Y=-54 深挖夹具按实际策略使用 `l=0`，检查与验收均通过，不再把 0 梯子误报为“32 梯子已备齐”。 |
| BUG-019 | Forge 实机回归通过（2026-08-28） | `parameters/task/action/input` 四种别名均接受；“向前走5格，不要传送”规范化为精确水平 move，最终距目标 `0.453` 格并经 3 个快照稳定，无传送或 provider 调用。 |
| BUG-020 | Forge 实机回归通过（2026-08-28） | Saints 本轮连续 4 套完整动作矩阵均安全落地；叠加前一轮独立复测，修复后已有 5 次连续成功。 |
| BUG-021 | Forge 实机回归通过（2026-08-28） | Book heal 消耗 2 份食物、生命增加 `1.320`、饱食增加 `20.400`，同一目标确认成功。 |
| BUG-022 | Forge 实机回归通过（2026-08-28） | Book/Saints 照料共 8 项成功，完整 cleanup 后无残留夹具，NPC idle、任务队列为空，玩家保持 gameplay。 |
| BUG-023 | Forge 再次实机回归通过（2026-08-28） | 当前 Forge 真实死亡画面报告 `death`，客户端原生重生恢复 gameplay；全程无窗口、键鼠或光标写操作。 |
| BUG-024 | Forge 实机回归通过（2026-08-28） | 移动任务绑定源维度；下界 12/12 paused 且漂移 `0`，返回主世界后同一任务恢复 `running/active`，清理完整。 |
| BUG-025 | Forge 实机回归通过（2026-08-28） | 饥饿从 16 降到 5 时常备目标持续固定为 8；managed eating 启动，分批交付后完成 3 次真实进食，最终饱食 20、保留 8 份安全食物并精确恢复夹具。 |
| BUG-026 | Forge 正式链再次实机通过（2026-08-28） | 玩家回 NPC 仅保留为受限验收夹具；最新 Forge 正式倒地恢复从 `13.342` 格外回到玩家旁 `0.997` 格，玩家未被正式流程传送。 |
| BUG-027 | **上一候选 NeoForge 实机失败；本轮未实测（范围排除）** | 服务端权威位置与连续 40 tick 到达确认的代码和自动化通过；Forge 精确五格 move 已通过，但不能替代 NeoForge `remote-player` 实测。 |
| BUG-028 | 最终安装版连续实测通过；保留历史间歇风险 | 两次独立最终安装版冷启动及后续聊天、关闭和退出审计均未前台化、置顶或限制光标；历史 RC6 四次冷启动曾有三次前台化，仍需更多跨机器重复后再永久关闭。 |
| BUG-029 | Forge 实机回归通过（2026-08-28） | 阻塞目标快速明确失败，取消后导航/MoveControl 完整复位，下一条五格 move 成功并经稳定 snapshot 确认。 |
| BUG-030 | 最终安装版实机回归通过（2026-08-28） | HMCL 与后继 JVM 使用长度安全、ACL 受限的独立短 Temp；最终源世界冷启动成功，避免 JDK 17 Unix socket 临时路径过长导致 Netty selector 崩溃。 |

## 2026-08-26 v0.1.12 双版本实机复测

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| RT-01：EXE → Forge 源实例冷启动 | 通过 | 安装版 buildId `d98f36db0b8050a37bb42b95d6dc8599487427c3ab19765988327fff47181cf1`；实际 gameDir 为 `versions/Dragons_ZH_1.20.1`；桥 `forge-1.20.1 / 0.2.3`；没有新增 `-Codex`。 |
| RT-02：Forge 自动生存与死亡 | 失败 | NPC 有 6 份猪肉时仍不吃，随后有 11 份安全食物仍继续远程备粮；生命降至 3，玩家在 NPC 远离期间死亡两次。 |
| RT-03：Forge + Codex + 无作弊 + T 聊天 | 失败 | T 往返通过；约 130 秒后回写 `unrecognized_keys`，未识别 `parameters`；无 goal、无任务、0/4 原木。 |
| RT-04：Forge + 固定反重力 + 无作弊 + T 聊天 | 部分通过、终态失败 | 同一固定会话成功接令并创建 `life.expedition-and-deliver`；NPC 生存步行最大 87.289 格、最大单步 0.257 格、无作弊/无创造；只砍 2/4，31 次远程搜索后超时取消，未交付。 |
| RT-05：死亡恢复 | 通过（Forge 正式 NPC 链） | 玩家死亡期间 NPC 倒地恢复后在 41.592 格外持久等待；玩家手动重生后 NPC 安全回到 5.283 格内，`npcDowned=false`、暂停任务数 0，调度器恢复 `running` 并继续原 `provision-food` 任务。旧构建的 Forge/NeoForge 死亡识别也已通过。 |
| RT-06：EXE → NeoForge 源实例冷启动 | 通过 | 实际 `--version=1.21.1-NeoForge`、gameDir 为 `.minecraft`，桥 `neoforge-1.21.1 / 0.1.0`；进入原世界 `新的世界`；没有新增 `-Codex`。 |
| RT-07：NeoForge 无作弊世界与重生 | 通过 | `level.dat` 为 `allowCommands=0`、`GameType=0`、`VersionName=1.21.1`；真实死亡识别为 `death`，后台重生恢复 20/20。 |
| RT-08：NeoForge + Codex + 无作弊 + T 聊天 | 失败 | T 往返通过；回写 `Invalid input: expected object, received undefined`，路径为 `spec.target`；玩家位置不变。 |
| RT-09：NeoForge + 固定反重力 + 无作弊 + T 聊天 | 部分通过、终态失败 | 同一固定会话创建 `explore radius=8`；从原点移动约 9.8 格，采样最大单步 0.107 格，确认非传送；随后卡进树叶并以 `NAVIGATION_TIMEOUT` 失败。 |
| RT-10：不抢占用户鼠标 | 代码修复与当前实例只读审计通过；待新 EXE 人工确认 | 已删除会触发 GLFW 重新捕获的窗口、消息、截图和输入自动化；严格后台审计 `91/91` 采样未见置顶、前台或后台光标限制。仍需 `0.1.14` 双版本冷启动人工确认。见 BUG-028。 |

本轮截图（均为本机可直接打开的绝对证据，位于仓库 `.runtime`）：

- Forge Codex 失败：`.runtime/live-retest-20260826-085958-forge-codex-tchat-failed.png`
- Forge 反重力接令与执行：`.runtime/live-retest-20260826-085958-forge-antigravity-tchat-running.png`
- Forge 无作弊远征中途：`.runtime/live-retest-20260826-085958-forge-antigravity-nocheat-mid.png`
- Forge 玩家死亡：`.runtime/live-retest-20260826-085958-forge-codex-player-death.png`
- NeoForge 源实例加载：`.runtime/live-retest-20260826-085958-neoforge-source-main-menu.png`
- NeoForge 死亡与后台重生：`.runtime/live-retest-20260826-085958-neoforge-player-death.png`、`.runtime/live-retest-20260826-085958-neoforge-player-respawned.png`
- NeoForge Codex 失败：`.runtime/live-retest-20260826-085958-neoforge-codex-tchat.png`
- NeoForge 反重力步行卡入树叶：`.runtime/live-retest-20260826-085958-neoforge-antigravity-tchat-running.png`

### BUG-009 实机回归补充：树簇仍会只采一半后进入远程搜索循环

- 严重级别：P1 / 高
- 状态：v0.1.12 原问题稳定复现；代码与自动化已修复，2026-08-28 Forge 无作弊实机回归通过。NeoForge 的“提前成功、服务端回退”属于独立 BUG-027，本轮范围排除。
- 复现：Forge 无作弊夹具建立 4 个橡木原木和钻石斧，通过真实 T 聊天发送 `去远征砍4个橡木原木给我`，目标为固定反重力会话。
- 实际：任务 ID `c835fe97-3df9-4dcf-b321-8bba1c7a9b9b` 稳定，NPC 首先真实砍下 2 个原木；夹具终态为 `logBreaks=2`、`npcItems=2`、`remainingFixtureLogs=2`、`skippedTargets=2`、`excursions=31`。任务运行超过 648 秒仍为 25%，没有 delivery，最终按验收超时取消。
- 安全性：`cheatsObserved=0`、`creativeObserved=0`、最大步长 0.257 格，证明失败发生在真实步行采集链，不是传送或作弊替代。
- 疑似原因：树簇队列中的后两个目标被标为 skipped 后没有在同簇重新扫描；宏又允许最多 64 次远程恢复，导致明知夹具仍有同类原木时长时间绕行。
- 修复后：保留首次自然树扫描得到的完整树干集合；每次破坏后重新排队剩余树干，路径跳过会先进行最多 3 轮同簇近距离重试，耗尽后以 `TREE_CLUSTER_NOT_REACHABLE` 有界失败。最终 Forge 隔离测试 `474/474` 通过。
- 修复复测：正式无作弊生存链成功砍伐并交付 `4/4` 个原木；`logBreaks=4`、`deliveryItems=4`、玩家获得 4、NPC/世界残留 0，`remainingFixtureLogs=0`、`queuedTargets=0`、`skippedTargets=0`。最大单步 `0.257` 格，`cheatsObserved=0`、`creativeObserved=0`。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/bug009-v18e-no-cheat-live.json`。

### BUG-019 实机回归补充：Codex 动作输出仍存在两种合同漂移

- 严重级别：P1 / 高
- 状态：v0.1.12 在 Forge 和 NeoForge 各稳定复现一次；代码与自动化已修复，2026-08-28 Forge 实机回归通过；NeoForge 后端仍由 BUG-027 单独跟踪。
- Forge 实际：请求 `264385ed-e9a2-4ffd-982f-2d1e5d176940` 约 130 秒后失败，顶层 `parameters` 被 strict parser 判为 `unrecognized_keys`；没有新增 goal 或任务。
- NeoForge 实际：`向前走5格，不要传送` 约两分钟后回写 `Invalid input: expected object, received undefined`，错误路径为 `spec.target`；玩家位置完全不变。
- 影响：Codex T 聊天往返和错误回写本身均正常，但任何自然语言动作仍无法进入执行器；自动化所覆盖的顶层 `task` → `spec` 规范化没有覆盖这两种真实输出。
- 修复后：统一规范化 `parameters`、`task`、`action`、`input`；`向前走5格，不要传送` 等精确相对请求强制转换为带绝对目标、`arrivalTolerance=0.5` 和 `arrivalMetric=horizontal` 的 `move`。控制层与协议测试通过。
- 修复复测：四种别名合同均被接受；精确五格请求从服务端起点执行为水平 move，任务 succeeded，最终距目标 `0.4529` 格并经 3 个终态后快照稳定。全程 survival、无传送，外连请求 0，未读取或调用 provider `base_url`。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/bug019-v18h-local-contract-flat-fixture.json`。

### BUG-025：自动备粮任务会阻止已有食物的自动进食，并形成越饿目标越高的循环

- 严重级别：P1 / 高
- 状态：v0.1.12 实机稳定复现；代码、自动化与 2026-08-28 Forge 实机回归均通过。
- 前置：Forge survival，默认 `npcFoodReserveCount=8`，恢复世界中存在自动备粮任务。
- 实际：NPC 饱食 9 时备粮目标为 14，已持有 6 份猪肉仍不吃；随后饱食降至 5，已持有 11 份安全食物，目标动态升至 16，任务仍继续远征。NPC 一度生命 3、距离玩家约 300 格；无人看护的玩家被蜘蛛杀死两次。
- 交叉验证：仅为隔离后续 provider 测试，临时把 reserve 设为 0 并重启；急停旧任务后 NPC 立即真实消耗食物，饱食恢复 20、生命开始恢复。临时字段已在本轮结束前删除并恢复默认。
- 疑似原因：备粮任务持有调度器期间 managed eating 被抑制；同时目标库存包含随当前饥饿增加的进食缓冲，导致饥饿下降时完成线不断后移。
- 修复后：常备口粮任务创建时冻结配置目标，旧动态目标只迁移一次；`provision-food` 非烹饪阶段允许自动进食，达到目标后先吃到安全状态并再次核对库存。
- 修复复测：饱食从 16 降到 5 时任务目标仍固定为 8，managed eating 启动；先交付 3 份仍不够安全，再交付 6 份后完成 3 次真实进食，最终饱食 20、保留 8 份安全食物、调度器 idle。cleanup 后 NPC 快照精确恢复，未调用 provider 或上传敏感数据。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/bug025-v18g-low-hunger-fixed-target.json`、`bug025-v18g-batched-delivery.json`、`bug025-v18g-final-cleanup.json`。

### BUG-026：测试死亡恢复与正式 NPC 倒地恢复的语义边界需要拆分

- 严重级别：P1 / 高（用户明确纠正的产品规则）
- 状态：需求边界已纠正；代码与自动化已完成；2026-08-28 最终 Forge 正式 NPC 链再次实机回归通过。2026-08-27 Forge/NeoForge 原生重生基线仍保留。
- 测试专用预期：验收过程中玩家死亡后，可以由用户手动重生，也可以在显式设置 `MC_ENABLE_LIVE_FIXTURES=1` 的回环控制服务上调用固定 `client-recovery:respawn`；后者只调用 Minecraft 客户端线程的原生 `LocalPlayer.respawn()`，不得发送窗口消息或键鼠输入。若后续显式启用 Forge `in-world-npc` 的受限 live fixture，把玩家放到 NPC 附近只用于恢复测试现场；该步骤必须明确记为测试夹具传送，不计入无作弊步行结果，不得暴露为正式产品接口。NeoForge `remote-player` 没有独立 NPC，因此不执行这一步。
- 正式游戏预期：NPC 受到致命伤进入倒地状态并恢复后，应安全回到仍在线且存活的玩家身边，再按调度规则恢复或暂停原任务；若玩家暂时死亡或离线，则保留待召回状态，等玩家可用后完成。
- 修复后当前实现：测试脚本只在明确启用 live fixture 时衔接受限 `view-npc:npc`，已有一次本地 API 证据把玩家恢复到 NPC 约 2.74 格处；正式 `CodexNpcEntity` 每次倒地恢复后都进入持久召回状态，玩家在线存活时安全召回并恢复任务，玩家死亡或离线时等待。
- 实机证据：玩家死亡期间，NPC 已恢复但保持“等待玩家重生后返回身边”，与玩家距离 `41.5917` 格；玩家手动重生后 `clientUiState=gameplay`，NPC 返回到 `5.2829943` 格，`npcDowned=false`、`pausedTaskCount=0`，调度器由 `downed` 恢复为 `running`，原 `provision-food` 任务继续执行。
- 桌面安全证据：早期正式 NPC 链同步只读审计 27 个采样全部通过；新构建双版本重生还分别包含 Forge `228/228`、`274/274` 与 NeoForge `273/273` 的严格后台审计。所有阶段均未观察到置顶、Minecraft 前台化、后台光标限制或键鼠/窗口/光标写操作。
- 原生重生补测：旧运行 JAR 阶段曾用忽略目录 `.runtime/respawn-agent/` 验证同一客户端原生调用，该历史诊断不计作新 JAR 通过。安装当前候选后，Forge 和 NeoForge 均已通过正式 `scripts/respawn-minecraft-for-test.mjs` → `client-recovery:respawn` 路由实测：真实 `death` 恢复为 `gameplay`、生命 `20/20`，脚本报告 `mouseOrKeyboardInputUsed=false`、`windowMutationUsed=false`、`cursorMutationUsed=false`。证据图包括 `v014-candidate-forge-death-before-native-respawn-20260827.png`、`v014-candidate-forge-gameplay-after-safe-respawn-20260827.png`、`v014-candidate-neoforge-final-native-respawn-windowed-20260827.png`。
- 最终 Forge 补测：正式卸载召回以同一 NPC UUID 在 `874ms` 内把距离从 `16548.441` 降到 `7.373`，最终 idle、暂停数 0、队列为空；另一轮正式倒地恢复从 `13.342` 格外回到玩家旁 `0.997` 格。两条正式链均未传送玩家。
- 产品边界：不新增公开的“玩家传送到 NPC”控制动作；`view-npc` 继续只存在于启用 live fixture 的本机验收环境。

### BUG-027：NeoForge 反重力步行距离被放大并卡入树叶，最终导航超时

- 严重级别：P1 / 高
- 状态：上一候选 NeoForge 实机稳定复现；新候选代码与自动化已修复。本轮按用户决定排除 `1.21.1-NeoForge`，仍待该后端有效实机回归。
- 前置：NeoForge 1.21.1 原始源实例、`allowCommands=0`、survival、固定反重力会话。
- 复现：真实 T 聊天发送 `向前走5格，不要传送`。
- 实际：反重力建立 `kind=explore,radius=8,direction=any`，不是精确 5 格 move。玩家从 `(-5.536,75,4.700)` 移到约 `(2.17,78,9.54)`，三维位移约 9.8 格；采样最大单步 0.107 格，确认没有传送。随后角色卡在树叶内部，进度固定 0.678、剩余距离 3 格，5 分钟后以 `NAVIGATION_TIMEOUT` 失败。
- 疑似原因：规划层把精确短距离移动退化为最小半径 8 的 explore；NeoForge remote-player 的本地 `driveToward` 缺少可靠的树叶/高差脱困，且最小化后台运行路径没有 Forge NPC 侧同等的单人世界保活兜底。
- v0.1.14 新构建实测：直接提交精确水平 move，从 `(2.169968,78,9.543853)` 到 `(7.104814,78,8.739307)`，`arrivalTolerance=0.5`、`arrivalMetric=horizontal`。任务 `07e4c84a-9ee0-4d0f-8df7-af7daebb3751` 返回 `succeeded/已到达目标位置`，但权威 snapshot 稳定在 `(3.711463,78,8.831246)`：只前进约 `1.56` 格，距目标仍约 `3.394` 格。全程 survival，未观察到传送、创造或命令。
- 新疑似原因：NeoForge 客户端本地预测位置可能短暂进入 0.5 格阈值，`TaskEngine.tickMove` 单次命中后立即提交成功并停止驱动；随后服务器位置校正把玩家拉回，但任务已不可逆地终态。Baritone 目标还把小数坐标截为整数 `(7,78,8)`，会放大客户端/服务端到达合同差异。该判断是基于任务时序和最终权威 snapshot 的推断，置信度中高。
- 自动化缺口：现有测试验证目标规范化、水平距离和单次到达阈值，却没有要求服务端连续多个 snapshot 确认到达，也没有覆盖“客户端预测到达后服务器校正”的回退。后续修复应加入连续稳定确认或服务端 ACK，再提交成功。
- 新候选修复：到达判定改为服务端权威位置，并要求连续 40 tick 满足到达条件；多人模式不再用单次客户端预测位置提交成功，Baritone 目标改为 `BlockPos.containing`。最终 Forge 隔离测试 `474/474` 通过，Forge 对应精确移动合同也已实机通过；但这些证据不能替代 NeoForge `remote-player` 实测，故本条不标记通过。
- 截图证据：`.runtime/manual-safe-screenshots/v014-candidate-neoforge-move-false-success-20260827.png`。

### BUG-029：Forge 遇到阻塞目标后任务不终止，并使后继移动零位移

- 严重级别：P1 / 高。
- 状态：代码、自动化与 2026-08-28 `Dragons_ZH_1.20.1` 无作弊实机回归均通过。
- 对照通过：测试夹具先把 NPC 放到完整碰撞和地面支撑校验通过的起点 `(261.5,111,-8.5)`，正式 move 目标为 `(266.5,111,-8.5)`。NPC 最终稳定在 `(266.432,111,-8.5)`，水平剩余 `0.0676` 格，调度器回到 `idle`；全程 survival，正式移动阶段未使用传送、命令或键鼠输入。该结果说明最新 Forge 最后一段 `MoveControl` 在安全路径上有效。
- 首次失败：从 `(277.524,111,-11.259)` 朝右侧建筑方向移动 5 格，NPC 只走到 `(279.832,111,-11.497)` 后停止；60 秒内任务持续 `running/移动中`，水平剩余 `2.702` 格，最终只能由验收脚本取消。
- 后继失败：不改变 NPC 位置，再向另一个方向提交 5 格 move；连续 60 秒、294 次采样均为零位移，任务仍保持 `running/移动中，距离 5 格`，再次只能取消。取消后调度器表面恢复 `idle`、队列为空，但新任务不能重新取得有效导航。
- 影响：普通地形或目标点阻塞会把一次可解释的不可达问题放大为持久导航失效；玩家看到任务长期假运行，后续自然语言移动也无法恢复，容易误以为只能靠传送脱困。
- 疑似原因：寻路进入无进展状态时没有在有界窗口内重规划或以明确的 `NAVIGATION_*` 错误终止；取消路径虽清理任务记录，但没有完全重置底层 navigation / move control 状态。该判断来自任务时序和安全起点对照，仍需修复阶段用服务端导航状态进一步确认。
- 建议修复验收：阻塞目标必须在有界时间内成功绕行或明确失败；取消后立即从安全起点提交第二条 move 必须可执行；成功只能在稳定服务端位置进入 `arrivalTolerance` 后对外终态。测试器需要在任务终态后等待至少两个新 snapshot，避免把桥接快照延迟误判为虚报成功。
- 证据：`.runtime/v014-move-fix-forge-live-20260828-050000/results/five-block-move-final-approach.json`、`five-block-move-open-direction-zplus.json`、`five-block-move-safe-staged-post-terminal-stability.json`；截图为同目录 `screenshots/04-five-block-move-blocked.png`、`09-second-five-block-move-zero-displacement.png`、`10-safe-path-five-block-arrival.png`。
- 修复复测：新路径 reach range 收紧为 0，停滞时有界重规划并在 3 次不可达后失败；所有 move 终止路径同时复位 navigation、MoveControl 和水平速度。实机阻塞目标 `2159ms` 返回 `NAVIGATION_TARGET_UNREACHABLE`，取消后的下一条五格 move `1003ms` 成功，终点水平剩余 `0.380` 格并经 3 个后续 snapshot 保持稳定。证据：`.runtime/v014-bug029-fix-forge-live-20260828-063500/results/bug029-live-acceptance.json`。

### BUG-028：后台测试仍可能让 Minecraft 捕获鼠标或抢占前台

- 严重级别：P1 / 高（直接影响用户继续使用桌面）。
- 状态：最终安装版两次独立冷启动及后续完整流程均通过；因 RC6 和一个中间短 Temp 候选仍有间歇前台化证据，缺陷保留残余风险，不永久关闭。
- 实际：测试工具没有移动物理鼠标，但 Minecraft 窗口重新获得 GLFW 光标捕获后，用户鼠标无法离开游戏。此前的光标坐标/前台窗口检查仍可同时通过，因此 RT-10 的观测口径不足。
- 影响：后台世界选择、聊天、重生、截图、暂停或窗口状态切换即使不调用物理输入，也可能妨碍用户操作 Codex 和其他应用。
- 根因：旧桌面测试链仍调用 `PostMessage`、`PrintWindow`、`ShowWindowAsync`、`SetWindowPos`、`ClipCursor` 和 `ReleaseCapture`。这些调用即使不移动物理光标，也可能改变 Minecraft 的 UI/焦点生命周期并触发 GLFW 重新限制光标。
- 新冷启动子问题：即使测试链不再调用上述 API，Minecraft 原生窗口创建/显示阶段仍可能主动取得前台。v3 代理除 `GLFW_FOCUSED=false`、`GLFW_FOCUS_ON_SHOW=false` 外，还拦截 `glfwShowWindow` 与显式 `glfwFocusWindow`；显示时临时加 `WS_EX_NOACTIVATE`，调用后立即恢复。RC6 状态连续返回 `focusPolicyVerified=true`、`windowNoActivateStyleRestoredAfterShow=true`、`minecraftWindowPersistentMutation=false`，但真实只读审计仍有 3/4 冷启动记录到前台化，说明内部插桩成功并不等于桌面结果稳定。具体剩余调用点或 Windows 激活时序仍需修复阶段继续定位。
- 修复：从桌面启动器移除窗口层级、最小化和光标修改；T 聊天、自动进出世界及 Save and Quit 脚本继续 fail closed 为 `MANUAL_UI_REQUIRED`；重生改为回环、显式启用、固定枚举的客户端原生夹具；截图只使用目标 Minecraft 客户区的 `GetDC + BitBlt`，不调用 `PrintWindow`、`PostMessage`、窗口层级或输入 API，并在保存前后验证前台窗口和 `GetClipCursor` 结果未变化；新增只读桌面安全审计和仅使用 GET 的人工 T 聊天观察器。
- 自动证据：RC6 发布门禁 `19/19`、启动器 `41/41`、Forge 隔离测试 `470/470`；代理还通过 synthetic、LWJGL `3.3.1` 和 `3.3.3` 的 `java -Xverify:all`。发布门禁继续禁止启动器重新引入窗口、输入或光标写操作。
- v0.1.14 双版本实机证据：Forge 冷启动/T 聊天/世界内流程的严格后台审计为 `77/77`、`228/228`、`274/274`；NeoForge 世界加载/移动/重生与 T 聊天为 `365/365`、`818/818`、`273/273`。全部结果均为 `WindowTopmostObserved=false`、`MinecraftForegroundObserved=false`、`BackgroundCursorConfinementObserved=false`，键鼠、窗口和光标写操作均为 0。用户在测试期间多次正常移动游戏画面和桌面鼠标，没有再次出现鼠标无法离开游戏。
- 新候选反证：`.runtime/v014-rc3-live-20260827-205741/neoforge-window-creation-audit.json` 覆盖 `5374` 个采样，记录 Minecraft 前台化一次；同目录后续稳定期 `273/273` 与世界打开阶段 `1089/1089` 均安全，且全程 `CursorConfinementObserved=false`、`TopmostObserved=false`。这说明上一轮审计从窗口已稳定后开始，漏掉了创建窗口瞬间。
- RC6 Forge 证据：`.runtime/v014-rc6-forge-live-20260828-015123/results/window-creation-audit.json`、`attempt3-window-audit.json`、`attempt4-window-audit.json` 均记录 `MinecraftForegroundObserved=true`；`attempt2-window-creation-audit.json` 和 `attempt2-world-entry-window-audit.json` 为 `Safe=true`。五份结果均为 `CursorConfinementObserved=false`、`TopmostObserved=false`、`MouseOrKeyboardInputUsed=false`，因此当前结论是“前台化间歇失败，但未复现锁鼠标或置顶”。
- 最终修复候选：HMCL 改为通过 `CreateProcessW + SW_SHOWNOACTIVATE` 启动；窗口代理 v4 在窗口创建后持续保留 `WS_EX_NOACTIVATE`，直到启动器确认 Forge 运行时标题稳定 8 秒，再由启动器移除临时样式并执行 2 秒释放后审计。整个过程验证窗口所属 PID、前台进程和样式状态，最终 `minecraftWindowPersistentMutation=false`，不通过抢回焦点来掩盖失败。
- 中间候选反证：`.runtime/hmcl-short-temp-live-20260828-203151/window-audit.json` 的 `3829` 个采样仍记录一次前台化，尽管内部 v6 策略字段均为 true。该证据保留，说明内部插桩成功仍不能单独证明桌面安全，也是本条不永久关闭的原因。
- 最终安装版证据：后续重新构建的最终 EXE 两次独立冷启动均安全。第一次 `.runtime/final-installed-cold-start-20260828-215612/window-audit.json` 为 `3831/3831`；第二次 `.runtime/final-installed-tchat-20260828-221623/window-audit.json` 为 `4369/4369`，其 T 聊天专项、关闭聊天后和保存退出审计又分别为 `822/822`、`273/273`、`410/410`。所有结果均未观察到前台化、置顶或光标限制，也没有键鼠、窗口或光标写操作。
- 只读截图证据：Forge 与 NeoForge 的历史主菜单、世界、死亡、重生、NPC 恢复、T 聊天和失败现场均只截 Minecraft 客户区；每次返回 `ClientAreaOnly=true`、`ReadOnlyCapture=true`、`ForegroundUnchanged=true`、`CursorClipUnchanged=true`。RC6 新证据为 `.runtime/v014-rc6-forge-live-20260828-015123/screenshots/01-attempt2-main-menu-background.png`，精确 `854x480`，同样未使用 `PrintWindow`、剪贴板或输入。
- 窗口化补充：NeoForge 首次启动出现全屏并非启动器置顶或抢焦点，而是 HMCL Home 既有全局设置 `fullscreen=true,width=1920` 及 `FULLSCREEN/1920` 预设。用户关闭首次启动后，已把两处改为 `fullscreen=false,width=854,height=480`，并与 `.minecraft/options.txt` 的 `fullscreen:false` 交叉验证；第二次 EXE 冷启动客户区实测为 854×480。产品仍尊重用户的 HMCL 设置，本轮只为满足明确的“不要全屏”验收要求修正本机配置。
- 保存退出证据：测试结束先断开桥，再由忽略目录中的本机 JVM agent 在 Minecraft 客户端线程执行原生停止流程；游戏保存退出、HMCL 自行结束，不调用 `WM_CLOSE`、`PostMessage`、窗口层级或输入 API。临时 agent 不提交、不打包。

### BUG-030：JDK 17 Unix socket 临时路径过长导致进入世界时 Netty selector 崩溃

- 严重级别：P1 / 高。
- 状态：代码、自动化和 2026-08-28 最终安装版 Forge 实机回归均通过。
- 复现：RC6 的前三次世界打开请求均在客户端线程触发 `java.io.IOException: Unable to establish loopback connection`，根异常为 `java.net.SocketException: Invalid argument: connect`；Minecraft 随后生成崩溃报告并退出，无法进入固定无作弊世界。
- 环境证据：用户 Temp 中当时已有 `261` 个 `socket_*` 条目。Java 17 的最小 `Selector.open()` 在原 Temp 下失败；仅把该测试进程的 `TEMP`、`TMP`、`java.io.tmpdir` 和 `jdk.net.unixdomain.tmpdir` 指向全新短目录后立即通过。没有修改系统或用户级环境变量。
- 根因：Windows 上 JDK 17 的 selector 初始化会建立 Unix-domain 临时 socket；过长或被大量残留 socket 污染的临时路径会让底层 connect 返回 `EINVAL`，最终表现为 Netty loopback selector 初始化失败。该问题发生在进入世界时创建集成服务端线程，并非世界存档或桥接 JAR 损坏。
- 修复：桌面启动器为每次 HMCL 及其后继 Java 进程创建长度不超过 40 个字符的独立短 Temp，ACL 仅授权当前用户、SYSTEM 和 Administrators；子进程环境同时设置 `TEMP/TMP`，`JAVA_TOOL_OPTIONS` 同时固定 `java.io.tmpdir` 与 `jdk.net.unixdomain.tmpdir`。隔离只作用于本次启动树，不污染全局环境，也不复用用户原 Temp 的 `socket_*`。
- 自动证据：启动器与发布门禁覆盖短路径上限、ACL 限制、进程级环境继承、JVM 属性注入和失败即拒绝启动；最终发布门禁 `19/19`、启动器 `41/41`、Forge 隔离测试 `474/474` 均通过。
- 最终实机证据：无额外调用方环境变量的最终安装版直接冷启动 `Dragons_ZH_1.20.1` 并成功进入源世界；启动状态为 `isolatedRuntimeTempApplied=true`、`isolatedRuntimeTempScope=hmcl-and-descendant-java-processes`、`runtimeTempPathLengthSafe=true`、`runtimeTempAccessRestricted=true`。随后另一轮最终安装版又成功进入同一世界并完成 T 聊天，全程没有 selector、Unix socket 或 loopback 崩溃。
- 安全边界：短 Temp 路径只保存在本机启动状态中，不写入安装载荷、不提交、不上传；本轮没有向任何 `base_url` 发送该路径、崩溃日志或其他本机环境数据。

## 原始验收记录（2026-08-25，修复前）

## BUG-001：错误反重力会话标题只返回泛化 HTTP 500

- 严重级别：P2 / 中等
- 关联用例：FORGE-09
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：服务已配置固定标题 `Execute Minecraft Smart NPC Task`，且该固定会话在线。
- 复现步骤：向 `POST /api/antigravity/bind` 提交不同标题 `Codex Acceptance Missing Title 20260825`。
- 预期：返回 4xx 客户端错误，并明确说明只能绑定配置中的完整标题；原固定会话保持不变。
- 实际：返回 HTTP 500、`INTERNAL_ERROR`、`控制服务发生内部错误`、`retryable: true`。原固定会话确实保持不变。
- 影响：用户无法从错误响应判断标题不匹配，且会被错误引导为可重试的内部故障；不会造成会话漂移或数据丢失。
- 原始证据：`raw-results/FORGE-09-antigravity-fault-recovery.json`
- 截图证据：`screenshots/FORGE-09-before-offline.png`、`screenshots/FORGE-09-antigravity-recovered.png`
- 清理结果：反重力已恢复在线，固定 conversationId 仍为 `8e21f5f1-31ee-4618-8af4-4d9aaf2ae286`；Minecraft 与 Companion 未中断。
- 疑似原因：`bindConversationByTitle` 对标题不匹配抛出普通 `Error`，随后被全局错误处理器折叠为泛化 `INTERNAL_ERROR`，而不是映射为可公开的 `ControlError`。
- 原因置信度：高。

## BUG-003：NeoForge remote-player 控制接口返回 202 但实际无动作

- 严重级别：P1 / 高
- 关联用例：NEO-03
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：`1.21.1-NeoForge` 的 `codex-neoforge` 已连接，embodiment 为 `remote-player`，世界为 `新的世界`。
- 复现步骤：依次调用 stay、follow、recall 确定性控制，并在每次调用前后读取快照。
- 预期：控制被真实执行；若该 embodiment 不支持，则返回明确的 4xx / `NPC_CONTROL_UNSUPPORTED`。
- 实际：三次请求均返回 HTTP 202、sequence 均递增，但状态始终为“已急停”，角色位置完全不变。
- 影响：调用方收到虚假的成功信号，无法判断远程玩家没有执行控制；跟随、停留和召回在 NeoForge 轨道不可用。
- 原始证据：`raw-results/NEO-03-preflight-controls.json`
- 截图证据：`screenshots/NEO-03-controls-after.png`
- 疑似原因：NeoForge `LocalPlayerActor` 没有覆盖 `CompanionActor.control`，继承的默认实现为空操作；控制服务仍按成功请求返回 202。
- 原因置信度：高。

## BUG-004：存在已断开的历史实例时全局急停返回 HTTP 500

- 严重级别：P1 / 高
- 关联用例：NEO-03
- 状态：稳定复现 2 次，未修复（本轮只测试、不改代码）
- 前置条件：Forge 实例已正常退出但仍保留在运行时列表，NeoForge 实例在线。
- 复现步骤：调用 `POST /api/emergency-stop`，正文指定 `disconnect: false`。
- 预期：所有在线实例安全停止；已断开的历史实例不应使整个急停请求失败，接口应返回 202 或逐实例结果。
- 实际：接口返回 HTTP 500、`INTERNAL_ERROR`。NeoForge 最终仍保持连接且状态为“已急停”、任务数为 0，但调用方无法确认急停是否成功。
- 影响：最关键的安全控制在多实例切换后会向调用方报告失败，且仅给出泛化内部错误；自动化无法可靠判断安全状态。
- 原始证据：`raw-results/NEO-03-preflight-controls.json`
- 截图证据：`screenshots/NEO-03-controls-after.png`
- 疑似原因：`ControlService.emergencyStop` 对所有记忆中的 runtime 执行 `Promise.all`；已断开 Forge 后端的 stop 异常导致整个请求被拒绝，未过滤离线实例或汇总局部结果。
- 原因置信度：高。

## BUG-002：Minecraft 运行中仍可覆盖官方源实例桥接 JAR

- 严重级别：P1 / 高
- 关联用例：FORGE-10
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：`Dragons_ZH_1.20.1` 正在运行并通过 Forge 0.2.3 连接；将已备份的旧桥接 JAR（SHA-256 `68961776...`）作为待更新版本放入该源实例。
- 复现步骤：在新版客户端“运行”页点击“安装 / 更新桥接”。
- 预期：检测到该实例正在运行，拒绝替换，并提示先保存退出 Minecraft。
- 实际：客户端活动记录显示“完成：安装或更新 Codex 实例”，磁盘 JAR 从 `68961776...` 被覆盖为 `C7CA24BC...`；Minecraft PID 16712 同时仍在运行且桥连接未断。
- 影响：运行中 JVM 所加载的模组版本与磁盘版本产生不一致；若复制或后续操作中断，可能影响下一次启动，也违反运行中更新锁的安全预期。
- 原始证据：`raw-results/FORGE-10-update-lock.json`
- 截图证据：`screenshots/FORGE-10-update-while-running.png`、`screenshots/FORGE-10-minecraft-still-running.png`
- 清理结果：已从逐文件备份恢复测试前桥接与配置；桥接 SHA-256、配置 SHA-256 和两者时间戳均与测试前完全一致，当前 Minecraft 连接正常。
- 疑似原因：`/api/install` 在调用 `installOrUpdate` / `installDirectSource` 前没有检测正在运行的目标 Minecraft 实例或已连接的同实例桥；复制逻辑会直接移动并原子替换 JAR。
- 原因置信度：高。

## BUG-005：同一 Minecraft 客户端退出世界后重入不恢复桥连接

- 严重级别：P1 / 高
- 关联用例：S03
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：Forge 1.20.1 客户端已连接 `Codex-Full-Acceptance-20260825`，随后正常保存退出到主菜单。
- 复现步骤：不退出 Minecraft 进程，直接从世界列表再次进入同一世界。
- 预期：世界加载完成后桥自动恢复，快照序号继续增长。
- 实际：Minecraft 正常进入世界且服务端成功启动，但 `/api/health` 显示 `connectedCompanions: 0`，快照停在退出前；完整关闭并重新启动 Minecraft 后才恢复连接。
- 影响：用户在常见的“退出世界再重进”流程后会失去聊天、控制和任务能力，界面容易继续展示旧快照。
- 原始证据：`raw-results/S03-player-life.json`
- 截图证据：`screenshots/S03-reentry-timeout-diagnostic.png`
- 疑似原因：Forge 客户端桥的世界生命周期关闭了 WebSocket/actor，但后续 integrated-server 启动没有重新创建连接或重新注册 NPC actor。
- 原因置信度：高。

## BUG-006：急停后的远距离 NPC 会随区块卸载而失联，召回返回 500

- 严重级别：P1 / 高
- 关联用例：S03
- 状态：Forge 修复版实机回归通过（2026-08-28）
- 前置条件：本地自动备粮任务已把 NPC 带到距玩家约 255–357 格外，随后执行全局急停。
- 复现步骤：等待远处 NPC 所在区块卸载，再调用 live fixture cleanup 或 `recall`。
- 预期：NPC 应留在可管理状态；至少 recall 应能重新加载或安全重建唯一 NPC，并返回明确结果。
- 实际：服务仍标记 companion 为 connected，但快照序号永久冻结；fixture 日志报 `Player life fixture requires an in-world NPC`，recall 返回 HTTP 500。把玩家传送到 NPC 最后位置加载区块后才恢复；日志随后还出现 `UUID of added entity already exists`。
- 影响：急停可能把 NPC 永久留在远区块，后续控制、测试和自然语言任务全部失效；恢复过程有重复 UUID/实体一致性风险。
- 原始证据：`raw-results/S03-player-life.json`
- 截图证据：`screenshots/S03-frozen-diagnostic.png`、`screenshots/S03-recovery-command-diagnostic.png`
- 清理结果：在沙盒中临时传送玩家加载 NPC 区块，成功 recall 后用实体类型选择器把 NPC 与玩家一起恢复到 `(115.5,70,99.5)`；最终 survival、idle、距离 0。
- 疑似原因：急停释放了任务区块票，但运行时仍只持有远处 NPC 的内存引用/旧快照；`NpcManager.find` 与 recall 只扫描已加载实体，且恢复/重载没有正确消解同 UUID 的持久实体。
- 原因置信度：高。
- 修复复测：冷重启前玩家持久化记录指向 `minecraft:the_nether` 的未加载区块 `(2000,2001)`；修复版不再为跨维度残留记录强制加载旧区块，而是保留 UUID/备份状态，在玩家当前主世界安全恢复。实机恢复 UUID 为 `99a257ba-99b9-4f26-ac36-ee7bf2521e38`，初始距玩家 `4.957` 格，随后记录维度更新为 `minecraft:overworld`、记录区块实体已加载；12/12 个稳定样本同 UUID、同维度、idle 且无活动任务。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/bug006-fixed-restart-acceptance.json`、`bug006-fixed-npc-record-diagnostic.json` 与 `screenshots/14-bug006-fixed-restored-overworld.png`。

## BUG-007：明确“吃掉腐肉”被错误规划为外出备粮

- 严重级别：P1 / 高
- 关联用例：S03
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：NPC 空闲、survival、`eating-action` fixture 已放入 3 个腐肉和 2 片西瓜；聊天目标为当前固定 Antigravity 会话。
- 复现步骤：通过真实 Minecraft T 聊天发送 `把3个腐肉吃掉`。
- 预期：创建 `kind: eat`、`itemId: minecraft:rotten_flesh`、`count: 3` 的任务并执行真实进食动作。
- 实际：创建了 `kind: provision-food`、`source: hunt`、`foodCategory: meat`、`destination: backpack` 的 running 任务；备注中甚至保留了原始“吃掉3个腐肉”目标。S03 因找不到 eat 任务失败，错误任务在 runner 退出后仍保持 running。
- 影响：明确消费现有食物的指令会变成远距离打猎，既不满足用户目标，又可能触发 BUG-006 的 NPC 失联链路。
- 原始证据：`raw-results/S03-player-life.json`
- 截图证据：`screenshots/S03-player-life-fail.png`
- 清理结果：错误任务已通过公开 cancel 接口取消；服务与 Minecraft 快照均恢复 idle，NPC 与玩家同坐标。
- 疑似原因：自然语言规划器的生存前置策略把“eat”目标改写为 `provision-food`，没有优先尊重明确物品、数量与动作动词。
- 原因置信度：高。

## BUG-008：父采集任务取消后本地补粮子任务仍运行并阻塞清理

- 严重级别：P1 / 高
- 关联用例：S06
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：`resource-priority` 的 12 煤采集任务已完成约 2/3，深挖安全策略触发 `local:deep-mining-food:<parentId>`。
- 复现步骤：等待父任务因“深挖前补充安全口粮”进入 paused，随后由测试 runner 取消父任务并执行 fixture cleanup。
- 预期：取消父任务应级联终止其本地生存前置任务；fixture cleanup 应恢复原状态。
- 实际：父任务变为 cancelled，但本地 `provision-food` 子任务继续 running，NPC 离玩家 114–170 格寻找食物；cleanup 被 `Finish NPC tasks before changing the resource priority fixture` 拒绝。
- 影响：用户取消任务后 NPC 仍会继续远行；自动化与 UI 看到父任务已取消，却无法清理或开始下一项工作，并可能进一步触发远区块失联。
- 原始证据：`raw-results/S06-resource-priority.json`
- 截图证据：`screenshots/S06-resource-priority-fail.png`
- 清理结果：全局急停后在区块仍加载时立即 recall，再手动执行 fixture cleanup；最终 `rp:cleanup=restored`、survival、idle、空背包并恢复原坐标。
- 疑似原因：`local:deep-mining-food` 只存在 NPC 调度器中，不作为控制服务 task 子节点管理；父任务 cancel 没有向 NPC 调度器传播对子任务的级联取消。
- 原因置信度：高。

## BUG-009：无作弊步行远征反复换目标，超时前只采到 1/4 原木

- 严重级别：P1 / 高
- 关联用例：S07
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：世界 `allowCommands=0`，survival/materialMode 均为 survival，NPC 与玩家空闲；执行 `life.expedition-and-deliver` 采集并交付 4 个橡木原木。
- 复现步骤：在不指定 movement 覆盖、不使用任何 `/tp` 或作弊命令的前提下，让 AI 自主步行远征。
- 预期：NPC 步行到至少 55 格外的固定资源区，采集 4 个原木，步行返回并物理交付给玩家。
- 实际：无作弊与纯步行约束通过（最远 87.289 格、最大相邻采样位移 0.257 格），但 5 分钟内只采到 1 个原木；累计 13 次 excursion、3 个 skipped target，`targetSelected=0`，未进入 deliver，最终超时。
- 影响：AI 在严格生存/不传送模式下不会作弊，但会在资源目标选择与重新搜索之间循环，无法完成最基本的远征交付。
- 原始证据：`raw-results/S07-no-cheat-walking-expedition.json`
- 截图证据：`screenshots/S07-no-cheat-walk-fail.png`
- 清理结果：runner 自动取消任务并确认 `no-cheat-expedition:cleanup|r=1,1,1,1,1`；玩家、NPC、重生点、方块和物品全部恢复，最终 survival、idle、空背包。
- 疑似原因：树簇目标在到达后没有稳定锁定可破坏方块，目标队列将剩余 3 个资源标记为 skipped 并不断创建新的 excursion，而不是在同一已到达簇内继续选取。
- 原因置信度：高。

## BUG-010：自然“找些食物”请求被规划为 16 个而非验收约定的 8 个

- 严重级别：P2 / 中等
- 关联用例：S08
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：`food-delivery` player fixture 已准备，NPC 空闲，真实 Minecraft T 聊天走固定 Antigravity 会话。
- 复现步骤：发送 `给我找些食物`。
- 预期：创建 `provision-food`、`count: 8`、`source: auto`、`destination: player`。
- 实际：任务种类、来源和目的地均正确，但 `count` 为 16；runner 在执行前即判定任务契约不符。
- 影响：模糊数量的自然请求工作量翻倍，延长外出时间、提高生存风险，并可能触发远区块或补粮链路问题。
- 原始证据：`raw-results/S08-food-delivery-player.json`
- 截图证据：`screenshots/S08-food-player-fail.png`
- 清理结果：runner 自动取消错误任务并确认 `food-fixture:cleanup restored`；服务与快照任务均清空。
- 疑似原因：Antigravity/规划器对未指定数量的食物请求使用了默认 16，而验收与 fixture 的产品约定是默认 8。
- 原因置信度：高。

## BUG-011：在线固定反重力会话卡住后持续吞掉后续 T 聊天

- 严重级别：P1 / 高
- 关联用例：S09、S11、S13、S14、S15、S20
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：桥在线，反重力 `available=true`、`connected=true`，固定 conversationId 为 `8e21f5f1-31ee-4618-8af4-4d9aaf2ae286`；home-storage fixture 已准备。
- 复现步骤：先通过真实 Minecraft T 聊天发送 `找些食物放到家里箱子`，其超时后再发送 `给我做一把钓鱼竿`。
- 预期：每条消息都应在有界时间内回写成功或明确失败；第二条不应受第一条影响。
- 实际：第一条使 `incomingChatCount` 增加到 1，但观察 45 秒仍无回写；随后第二条把 incoming 增加到 2，`deliveredChatCount` 和 `roundTripCount` 仍保持 0，也没有创建制作任务。调用 recover 后，S13 的下一条消息仍被吞；完整退出并重新启动 Minecraft 后，S14 的新桥连接再次出现 `incoming=1`、`delivered=0`、`roundTrip=0`，没有创建食物任务。S15 随后在保持在线的同一新连接中发送牧场提示，计数再次呈 `incoming=1`、`delivered=0`、`roundTrip=0`，没有创建 `life.establish-ranch`。S20 在原始 smart/Antigravity 设置下发送 `建造房子吧`，incoming 从 1 增至 2，但 20 秒后仍没有回写、目标或任务。
- 影响：某个反重力 turn 卡住后，后续自然语言任务会连续无声丢失；状态接口仍误导性地显示 connected/ready。
- 原始证据：`raw-results/S09-food-delivery-home.json`、`raw-results/S11-explicit-crafting.json`、`raw-results/S13-storage-restart.json`、`raw-results/S14-food-survival-restart.json`、`raw-results/S15-ranch.json`、`raw-results/S20-natural-prompt-dedup.json`
- 截图证据：`screenshots/S09-food-home-fail.png`、`screenshots/S11-explicit-crafting-fail.png`、`screenshots/S13-storage-restart-fail.png`、`screenshots/S14-food-survival-restart-fail.png`、`screenshots/S15-ranch-fail.png`、`screenshots/S20-natural-prompt-dedup-fail.png`
- 清理结果：没有晚到任务；fixture 自动确认 `food-fixture:cleanup restored`。调用产品自带 `/api/antigravity/recover` 后明确返回“已解除反重力会话的假忙状态”，原 conversationId 保持不变；但 S13 的下一条 T 聊天再次无回写，说明假忙立即复发。证据见 `raw-results/BUG-011-antigravity-false-busy-recovery.json`、`raw-results/S13-storage-restart.json`。
- 疑似原因：反重力自动触发链路把会话 turn 串行化，但首个 pending turn 没有超时/取消机制；连接健康只反映 MCP 会话存在，不反映自动播放队列可继续消费。
- 原因置信度：高。

## BUG-012：存储 fixture 在真实生成地形无法放置测试箱子

- 严重级别：P2 / 中等
- 关联用例：S12
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：NPC/玩家空闲并处于新建 survival 世界的自然地形，cheats fixture 已启用。
- 复现步骤：执行 `live-storage-smoke.mjs --apply`，进入 `storage:setup-retrieve`。
- 预期：fixture 在可逆范围内选择或准备一个合法箱子位置，然后执行取出/存入完整循环。
- 实际：setup 立即抛出 `Storage fixture chest could not be placed`，主体存储任务未启动。
- 影响：存储完整循环无法在常见非平坦世界中验收；同样的刚性位置选择可能让实际自动建箱/存储逻辑在复杂地形失败。
- 原始证据：`raw-results/S12-storage-cycle.json`
- 截图证据：`screenshots/S12-storage-fail.png`
- 清理结果：没有任务、物品或设施残留；`storage-fixture:cleanup none`，NPC 保持 idle、空背包。
- 疑似原因：`placeEmptyChest` 只尝试固定/有限候选坐标，没有在周边搜索可替换方块且下方稳固的安全位置，也没有先构造可逆支撑面。
- 原因置信度：高。

## BUG-013：床已物理放置后被误判为背包缺床并失败

- 严重级别：P1 / 高
- 关联用例：S16
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：bed-sleep fixture 已建立可逆家园场景，NPC 处于 survival 且只有完成一张床所需的材料链。
- 复现步骤：直接执行 `life.craft-and-place-bed`，让 NPC 自主砍木、制作木板和剪刀、剪羊、放置工作台、制作并放置床。
- 预期：检测到家附近已经形成合法床头/床尾后，床宏任务成功；随后可执行夜间睡眠与离床验证。
- 实际：任务完成材料链并实际放置了一张床，fixture 明确记录 `bedPlacements=1`、`bedPair=1`、`sawBed=1`，但任务在 99% 以 `BED_ITEM_MISSING` 失败；此时 `bedItem=0` 是床已被放置消耗的正常结果，睡眠阶段未开始。
- 影响：严格生存模式下制作床会消耗唯一床物品，却因放置成功后的状态确认失败而把成功操作报告为失败，且阻断睡眠任务。
- 原始证据：`raw-results/S16-bed-sleep.json`
- 截图证据：`screenshots/S16-bed-sleep-fail.png`
- 清理结果：`bed-sleep:cleanup|r=1,1,1,1,1,1,1`；玩家、NPC、时间、天气、重生点、方块和实体全部恢复，NPC idle、空背包。
- 疑似原因：`tickBedPlacement` 只按预选 `foot + facing` 调用 `isBedPairAt`；物理交互已经放出有效床对，但实际朝向/落点与预选值不完全一致，于是代码把该床位加入 skipped 并重试。唯一床物品已在第一次放置中消耗，下一次循环便触发 `BED_ITEM_MISSING`。
- 原因置信度：高。

## BUG-014：玩家死亡后活动任务无限冻结为 running

- 严重级别：P1 / 高
- 关联用例：S17
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：NPC 正在执行任务，玩家在单人世界中死亡并停留于死亡界面，因此服务端暂时取不到在线 owner 实体。
- 复现步骤：S17 的 mixed 与 chain 建造已通过；chain 期间 Crow 被幻翼杀死。随后启动矩阵首项 `family-62`（oak planks），不人工点击重生并观察五分钟。
- 预期：任务应明确暂停并向控制服务暴露“等待玩家重生”，或在有界时间内以可重试错误结束；玩家重生后应可恢复。长矩阵 fixture 也应保护或自动恢复测试玩家。
- 实际：`family-62` 始终显示 running、进度 0，没有错误或暂停状态；五分钟后只能由 runner 取消。客户端一直是 `clientUiState=death`，快照中 owner 实体不存在。
- 影响：用户死亡、掉线或暂时跨出可解析状态时，任何活动 NPC 任务都可能永久占住调度器，UI 只显示运行中且无法解释为何没有进度。
- 原始证据：`raw-results/S17-build-palette.json`
- 截图证据：`screenshots/S17-build-palette-death-stall.png`
- 清理结果：runner 取消冻结任务并确认 `build-fixture:cleanup restored`；随后用后台 NormalizeOnly 重生路径恢复 gameplay，审计为 `ForegroundInteractionUsed=false`、`PhysicalMouseOrKeyboardInputUsed=false`，玩家恢复 20 点生命，NPC idle、空背包。
- 疑似原因：`NpcTaskEngine.tick()` 在 `owner == null` 时直接设置“等待主人上线”并 return，发生在 `active` 工作计时、暂停状态转换和任务超时之前；因此控制服务中的任务永远不会进入 paused/failed/terminal。
- 原因置信度：高。

## BUG-015：同义建造提示因自动锚点漂移绕过去重并创建多个房屋任务

- 严重级别：P1 / 高
- 关联用例：S20
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：stable 确定性动作模式、NPC idle、玩家通过真实 Minecraft T 聊天连续表达同一个基础住宅意图。
- 复现步骤：先发送 `建造房子吧`，任务 `7f1f81e9-…` 在锚点 `(212,64,120)` 开始运行；再发送同义的 `你来建造`，随后依次发送文档列出的纠正、催问、图纸和抱怨表达，并补发一次完全相同的 `建造房子吧`。
- 预期：同一玩家、同一结构、短时间内的同义或完全重复请求应查询/恢复同一个任务；任务队列中最多一个 `build.basic-shelter`，锚点锁定不漂移。
- 实际：第二条提示新建任务 `4be7dca3-…`，锚点漂移到 `(142,73,141)`；首任务继续 running，第二任务 paused。后续提示复用了第二任务，但共创建 6 个 Agent goal，其中催问走恢复分支而未新增 goal。最终同时存在 2 个房屋宏和 6 个 running goal。
- 影响：用户换一种说法或追问时可能在不同位置排队建多栋房子；任务/目标状态膨胀，材料和地形修改相互竞争。
- 原始证据：`raw-results/S20-natural-prompt-dedup.json`
- 截图证据：`screenshots/S20-natural-prompt-dedup-fail.png`
- 清理结果：6 个本项 goal 均已取消或随子任务取消落为 failed，2 个建造任务均 cancelled；调度器 idle、空队列，设置恢复为 smart + Antigravity + inherit。已采集的建材按取消语义保留在 NPC 背包并记录于原始证据。
- 疑似原因：`sameBuildRequest` 用完整已解析 spec 生成指纹，包含每次请求时重新计算的 `placementAnchor`、home/compound placement 与 `protectedBounds`；NPC 在首任务采集期间移动后，同义请求得到不同锚点，因而在语义去重之前就被判为不同建筑。
- 原因置信度：高。

## BUG-016：聊天回复已显示但 ACK 超时又回写“未出现”失败

- 严重级别：P1 / 高
- 关联用例：S20
- 状态：稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：Forge 单人服务器在真实建造/寻路负载下明显落后，stable 模式通过 T 聊天连续发送自然建造提示。
- 复现步骤：观察每条确定性建造回复及同一条请求的后续状态；同时检查 Minecraft `latest.log`、Codex request 状态和游戏内聊天截图。
- 预期：回复实际显示后请求应为 succeeded；若确认延迟，应容忍晚到 ACK 或在确认后撤销失败，不应再向玩家发送相反结论。
- 实际：5 个请求的自然确认文本实际显示在游戏聊天和日志中，随后同一会话又显示 `这次没有执行成功：Minecraft accepted the bridge command but did not confirm that the reply appeared in game chat`；请求记录为 failed。日志同时出现 integrated server 落后 `253864ms` 与 `501948ms`，可见回复约在提交后 8 秒到达。
- 影响：玩家已经看到任务 ID 和接令确认，却紧接着收到“没有执行成功”的错误提示；上层会把有效操作当失败并可能触发重复请求，加剧 BUG-015。
- 原始证据：`raw-results/S20-natural-prompt-dedup.json`
- 截图证据：`screenshots/S20-natural-prompt-dedup-fail.png`
- 清理结果：所有 S20 目标/任务已停止，聊天 UI 用后台 NormalizeOnly 恢复 gameplay；无物理鼠标、键盘或剪贴板操作。
- 疑似原因：桥命令 ACK 使用固定短超时；Integrated Server 主线程落后时，客户端最终显示回复但 ACK 晚于控制服务超时。请求一旦标为 failed 没有晚到 ACK 对账，失败处理又把矛盾提示发送回同一聊天。
- 原因置信度：高。

## BUG-017：同一深挖重启夹具可能在检查点前非确定性提前完成

- 严重级别：P1 / 高（阻断重启验收）
- 关联用例：S21
- 状态：本轮出现一次，紧接着的同世界诊断复测未复现；未修复（本轮只测试、不改代码）
- 前置条件：`deep-mining` 可逆夹具已 setup，NPC survival、空闲，控制服务提交制作并交付 1 把钻石镐的任务。
- 复现步骤：执行官方 `live-deep-mining-smoke.mjs --apply`，等待任务进入 `branching`、`staircaseStep>=4`、`branchProgress>=4`、`brokenBlocks>=20` 后执行正常退出/重启检查。
- 预期：任务在该检查点仍为 running，runner 正常保存退出 Minecraft，重进同一世界后用同一 task ID 恢复并完成。
- 实际：首轮任务 `cbaf8a46-…` 在 4.485 秒内直接 succeeded，runner 报 `Deep-mining task became succeeded before the restart checkpoint`，重启恢复阶段没有执行。紧接着同世界、同 companion、同输出目标的诊断任务 `8f7e12fa-…` 用 15.171 秒到达相同检查点、24.269 秒完成，说明夹具/执行路径不具备可重复的检查点窗口。
- 影响：官方重启 smoke 无法稳定覆盖持久检查点；一次“任务成功”反而使重启恢复验收失败，可能掩盖真实的恢复缺陷。
- 原始证据：`raw-results/S21-deep-mining.json`
- 截图证据：`screenshots/S21-deep-mining-fail.png` 仅记录严格不恢复窗口的截图尝试；Windows 对最小化窗口返回黑色客户区，不能作为玩法画面证据。
- 清理结果：首轮 runner 与诊断复测均确认夹具恢复；诊断复测为 `deep-mining:cleanup|r=1,1,1,1`。两轮均通过正常保存退出路径结束，未强制杀进程。
- 疑似原因：尚未唯一定位。夹具 `safeSite` 允许相邻空气，候选地下地形可能改变矿石的初始暴露/可达性；此外 runner 只接受一个运行中检查点，没有在任务提前终态时保存阶段轨迹。需要在修复阶段为夹具增加确定性的封闭几何与阶段审计后复测。
- 原因置信度：中低。

## BUG-018：深挖在零梯子时宣称“32 梯子已备齐”，并与夹具验收条件冲突

- 严重级别：P2 / 中等
- 关联用例：S21
- 状态：诊断复测稳定观察，未修复（本轮只测试、不改代码）
- 前置条件：深挖夹具入口位于 Y=-54，钻石目标层为 Y=-58，仅需下降 4 格。
- 复现步骤：执行钻石镐深挖任务，采样 preflight 库存和最终 `deep-mining:inspect`。
- 预期：状态文字应反映实际动态补给要求；fixture 的通过条件应与 `DeepMiningPolicy.requiredLadders` 一致。若验收要求 32 梯子，则背包必须真实持有并保留 32 个。
- 实际：`DeepMiningPolicy.requiredLadders(itemId,currentY)` 在 `currentY <= targetY+16` 时返回 0，所以任务没有制作梯子；但进入 seek-cave 时硬编码显示“深层采矿补给已备齐：32 梯子、32 火把和双镐”。最终检查为 `ok=0,l=0,t=32,p=2,d=1,b=1,s=4,r=9,x=1,k=34,o=3,g=1,v=1,q=1,w=7,j=448,n=1,e=0`，唯一不满足项是 `l=0`，而 fixture 固定要求至少 32。
- 影响：玩家收到与真实背包不符的安全补给提示；官方验收夹具与运行时策略互相矛盾，使一条实际完成下降、分支、采矿、制作和交付的任务仍必然失败。
- 原始证据：`raw-results/S21-deep-mining.json`
- 截图证据：`screenshots/S21-deep-mining-fail.png` 为黑色受限截图，不作为视觉断言依据。
- 清理结果：`deep-mining:cleanup|r=1,1,1,1`，恢复原玩家/NPC/方块/物品状态；正常保存退出，Git 干净。
- 疑似原因：补给完成提示硬编码 `32 梯子`，而实际预检使用按高度动态返回 0 或 32 的 `requiredLadders`；`DeepMiningLiveFixture.acceptanceComplete` 又无条件要求 `REQUIRED_LADDERS`，三处合同没有统一。
- 原因置信度：高。

## BUG-019：Codex 智能任务返回 `task` 包装，而执行器要求 `spec`

- 严重级别：P1 / 高
- 关联用例：S23
- 状态：本轮稳定复现，未修复（本轮只测试、不改代码）
- 前置条件：真实 Minecraft T 聊天、`actionMode=smart`、目标切换为 Codex，深挖可逆夹具已建立，Minecraft 保持最小化。
- 复现步骤：发送 `给我制作一把钻石镐并交给我`，等待智能规划请求结束。
- 预期：Codex 返回执行器可接受的 `{spec:{...}}` 智能任务结构，建立一个 Agent goal，并生成采矿、制作和交付子任务。
- 实际：请求 `cf30e635-…` 运行 129.677 秒后 failed；校验器同时报告 `spec` 为 undefined、顶层 `task` 是未识别键。没有新增 Agent goal，也没有新增 Minecraft 任务。
- 影响：Codex 智能动作模式即使成功生成任务内容，也会在进入规划器之前因包装结构不一致而整体失败，用户等待两分钟后仍无法执行自然语言动作。
- 原始证据：`raw-results/S23-codex-smart-diamond.json`
- 截图证据：`screenshots/S23-codex-smart-diamond-fail.png`；由本地 Companion 状态页无头截取，底部实时活动可见两条结构校验错误，不触碰前台窗口或鼠标。
- 清理结果：`deep-mining:cleanup|r=1,1,1,1`；调度器 idle，玩法状态正常，设置恢复为 smart + Antigravity + inherit，Minecraft 始终保持最小化。
- 疑似原因：Codex provider/适配层输出 `{task:{...}}`，而下游严格任务解析器只接受 `{spec:{...}}` 且拒绝额外键；两层使用了不同版本的动作输出合同。
- 原因置信度：高。

## BUG-020：Saints Dragons 偶发在距落点 0.4 格处保持飞行并超时

- 严重级别：P1 / 高
- 关联用例：S25
- 状态：代码与自动化已修复；2026-08-28 Forge 本轮连续 4 套完整矩阵通过，叠加前一轮独立复测后共 5 次连续成功。
- 前置条件：Saints Dragons 临时驯服龙，玩家与 NPC 共骑，龙已完成越障飞行并被夹具抬升，随后执行 `dragon/land`。
- 复现步骤：执行 `live-dragon-action-smoke.mjs --suite --apply --mods=saintsdragons`，观察 land 阶段直到终态。
- 预期：龙移动到安全落点，公开状态稳定为 `flying=0/onGround=1` 至少 5 tick，任务成功，并继续召回与协战阶段。
- 实际：首轮 land 进入垂直下降并到达距导航点 0.4 格（水平 0.0、高差 -0.4），但 90 秒内始终保持 `flying=1/onGround=0`，以 `DRAGON_LAND_TIMEOUT` 失败。自动驾驶生命周期已正常 end、未 invalidated，NPC/玩家仍共骑且无坠落伤害。独立复测换用新的同模组夹具龙和落点后完整通过。
- 影响：Saints Dragons 的真实落地动作会因小数高度/支撑面组合偶发失败，使玩家与 NPC 留在接近地面的共骑飞行状态，并阻断后续召回与协战计划。
- 原始证据：`raw-results/S25-dragon-action-suite.json`
- 截图证据：`screenshots/S25-saints-land-timeout.png` 显示失败的“龙降落超时”；`screenshots/S25-saints-retry-pass.png` 显示独立复测的落地、召回和协战均完成。
- 清理结果：首轮与复测均返回 `dragon-fixture:cleanup restored=1,entities=0,blocks=0`；调度器空闲。验收器仅调用回环 HTTP，无键鼠 API；复测安全监视器未观察到窗口恢复或聚焦事件。
- 疑似原因：距离小于 0.75 格后 `forceDragonGrounded` 需要 `supportedLandingPosition` 和下方真实碰撞同时成立；首轮的 0.4 格小数高度未形成可提交支撑，Saints 的公开 flying 状态又没有自行转为 grounded。目的地重选/停滞恢复仍未能在 90 秒总超时前找到可落位置，而不同落点复测成功。
- 原因置信度：中高。
- 修复复测：本轮每次 land 均完成上方接近、垂直下降和落地确认，公开状态稳定为 `flying=0/onGround=1`；随后 recall、assist-combat 和 cleanup 均成功。主结果为 `.runtime/v014-dragon-care-forge-live-20260828-065711/results/dragon-action-saints.json`，重复结果为同目录 `dragon-action-saints-repeat-1.json` 至 `-3.json`。

## BUG-021：Book of Dragons 治疗夹具选中偏爱食物，但真实互动不被确认

- 严重级别：P1 / 高
- 关联用例：S26
- 状态：代码与自动化已修复；2026-08-28 Forge 完整照料矩阵实机回归通过。
- 前置条件：dragon-care Book 夹具已建立；feed 子项已成功；heal 子项把同一条龙置为低生命并给 NPC 准备治疗食物。
- 复现步骤：执行 `live-dragon-care-smoke.mjs --apply --wait-seconds=180`，等待 Book 的 heal 子任务终态。
- 预期：NPC 使用真实可接受的龙食，物品数量减少，并观察到同一条龙生命值上升；heal 任务和 inspection 均通过。
- 实际：feed 先成功；heal 任务 `cdea6af2-…` 随后以 `DRAGON_INTERACTION_NOT_CONFIRMED` 失败。诊断为：物品 `minecraft:chicken`，`isFood=false`、`isFavoriteFood=true`、special/general 均 false，龙 `isTame=false` 但适配器判定属于主人，最终饱食 78；没有同时确认物品消耗和生命增加。
- 影响：Book of Dragons 的自然治疗命令可能挑中适配层认为“偏爱”但实体交互路径不真正接受的物品，导致龙受伤时无法由 NPC 治疗；串行验收也因此阻断驯服、龙蛋及整个 Saints care 矩阵。
- 原始证据：`raw-results/S26-dragon-care.json`
- 截图证据：`screenshots/S26-book-heal-fail.png`，任务队列顶部可见失败的治疗任务，前一喂养任务为完成。
- 清理结果：首轮任务队列为空、NPC idle、玩法状态正常，夹具确认 `dragon-care:cleanup|none`；诊断复测的最终恢复另见 BUG-022。本轮未生成物理键鼠输入。
- 疑似原因：`findDragonFoodSlot` 把 `isFavoriteFood=true` 当作足够的选择条件，但 Book 实体同时返回 `isFood=false` 且 `isTame=false`，代理互动没有形成验收要求的消费与生命恢复。饱食达到 78 后 `shouldRefillForHealing` 停止补喂，而 100 tick 确认窗口内也未触发生命再生，选择合同、驯服状态和确认窗口三者不一致。
- 原因置信度：中高。
- 修复复测：Book heal 任务成功，消耗 2 份食物、生命增加 `1.320`、饱食增加 `20.400`，同一龙 UUID、归属和目标存在性均通过。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/dragon-care-smoke.json`。

## BUG-022：Dragon care 空闲竞态留下卡地夹具龙/NPC，并导致玩家死亡与清理自阻塞

- 严重级别：P1 / 高
- 关联用例：S26
- 状态：代码与自动化已修复；2026-08-28 Forge 完整照料矩阵、强制清理与后续动作矩阵实机回归通过。
- 前置条件：Book of Dragons `dragon-care` 夹具已建立，feed 子任务成功，NPC 的食物/生命状态允许自动进食或自动协战启动。
- 复现步骤：再次执行 `live-dragon-care-smoke.mjs --apply --wait-seconds=180`；等待 feed 成功及 stage-heal/最终 cleanup；保持游戏现场不做测试器输入并观察残留实体。
- 预期：stage-heal 应在受控空闲点建立，或等待短暂自动进食结束；无论动作阶段是否失败，最终 cleanup 都必须恢复玩家、NPC、背包和实体，且不留下可攻击玩家的夹具龙。
- 实际：第二次 feed 任务 `5f85073e-…` 成功后，没有创建新的 heal 任务；runner 报“Dragon care action and restoration both failed”，cleanup 序列 172 返回 `npc-not-idle`。现场随后残留一条属于玩家、仍报告 `flying=true` 的 Book 致命纳德和临时 NPC 状态；用户目视发现龙与 NPC 均陷入地形。残留龙继而杀死 Crow，触发 `local:combat-assist`，使 cleanup 序列 173 再次以 `npc-not-idle` 失败；龙生命由 4.7812395 降至 0 后调度器才恢复 idle。
- 影响：验收失败会污染世界并伤害/杀死真实玩家；自动进食、自动协战和清理的互斥关系会形成恢复自阻塞，需要人工等待并再次调用清理。
- 原始证据：`raw-results/S26-dragon-care.json`
- 截图证据：`screenshots/S26-book-dragon-underground-residual.png` 保留“Crow 被致命纳德射杀”的死亡结果；`screenshots/S26-player-respawned-near-npc.png` 保留恢复后的玩家/NPC现场。龙与 NPC 卡地本身由用户实时目视报告，并由同一时段 API 中的残留龙 UUID、`flying=true` 和失败 cleanup 交叉确认。
- 恢复结果：玩家恢复后，通过无物理输入的后台 T 聊天传至 NPC 附近；cleanup 序列 174 最终返回 `dragon-care:cleanup|restored|dim=1|gm=1|ability=1|inv=1|npc=1`，调度器 idle、队列 0、`dragonState=null`、生存模式恢复。
- 疑似原因：`fixtureWhenIdle` 的空闲检查和服务端实际执行之间存在竞态；feed 后自动进食可在检查后启动，而 cleanup 的 `requireIdle` 又拒绝 managed eating。残留龙攻击玩家后，自动 `local:combat-assist` 使用同一调度器，再次让 cleanup 被自身安全任务挡住。最终恢复缺少“强制撤销夹具实体并回滚快照”的兜底通道。
- 原因置信度：中高。
- 修复复测：Book/Saints feed、heal、tame、egg 共 8 项全部成功；最终夹具恢复后 `taskSchedulerLifecycle=idle`、队列为空、玩家为 gameplay，随后 Book 及 Saints 动作矩阵可继续运行且每轮 cleanup 成功，没有残留龙、战斗任务或清理自阻塞。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/dragon-care-smoke.json`。

## BUG-023：真实死亡画面被控制接口报告为 gameplay，后台重生辅助流程会跳过重生

- 严重级别：P1 / 高
- 关联用例：S26、S27
- 状态：代码与自动化已修复；2026-08-28 当前 Forge 再次实机回归通过。
- 前置条件：Minecraft 客户端处于死亡界面，本地 Companion 控制服务仍在线。
- 复现步骤：在 Crow 被残留致命纳德射杀后，同时使用被动 `PrintWindow` 留图并读取 Companion snapshot；随后运行标准后台辅助脚本的 `-NormalizeOnly -RespawnIfDead` 路径。
- 预期：`clientUiState=death`，辅助脚本发送仅限目标窗口的后台重生消息，并等待恢复到 gameplay。
- 实际：截图清楚显示“你死了！”和“Crow 被致命纳德射杀”，但 snapshot/辅助脚本读取到 `InitialUiState=gameplay`；辅助流程因此返回 `Respawned=false`，没有执行重生。稍后再次被动截图时玩家已在出生点存活，因此没有额外发送重生点击。
- 影响：自动化或无人值守恢复无法可靠识别死亡状态；依赖 `RespawnIfDead` 的聊天验收可能在死亡界面误判为可继续，或跳过必要恢复。
- 原始证据：`raw-results/S26-dragon-care.json`
- 截图证据：`screenshots/S26-book-dragon-underground-residual.png` 为死亡界面；`screenshots/S26-player-respawned-at-spawn.png` 为后续出生点存活状态。
- 疑似原因：服务端 snapshot 的 `clientUiState` 没有同步客户端死亡 Screen，或死亡事件与 UI telemetry 使用了不同状态源/更新周期；辅助脚本完全信任该字段而没有额外视觉/窗口状态兜底。
- 原因置信度：高（状态不一致已直接交叉取证）；具体更新链路为中等。
- 修复复测：进入独立龙测试世界时玩家真实处于死亡画面，桥同步报告 `clientUiState=death`；测试专用客户端原生重生成功恢复 `gameplay`，随后 NPC 召回及龙矩阵均可继续执行。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/player-respawn.json` 与 `screenshots/03-player-dead-on-dragon-world-entry.png`。

## BUG-024：跨维度召回后把原维度 move 坐标直接当作新维度坐标继续执行

- 严重级别：P1 / 高
- 关联用例：S27
- 状态：Forge 修复版实机回归通过（2026-08-28）
- 前置条件：NPC 在主世界执行未完成的 `move` 任务，目标为仅含 XYZ 的主世界坐标；玩家跨入下界。
- 复现步骤：在主世界创建移动任务 `df0655b5-…`，目标 `(379.046,63,204.827)`；任务进入 `running/work` 后，将玩家传至下界临时安全台 `(32004.5,91,32004.5)`，观察同一 NPC UUID 和同一任务 ID。
- 预期：NPC 跨维度召回后保留任务和姿态，但应暂停原维度导航；返回主世界后才从原检查点继续，或任务目标必须显式携带并校验维度。
- 实际：UUID、任务 ID、`work` 姿态和 active 队列均正确保留，但状态立即变为“召回后继续 move 任务”，并在下界把主世界 XYZ 当作下界目标。页面显示距离目标 44,832 格，NPC 从临时安全台走到约 `(31996,63,31990)`，生命从 10 降至 2。返回主世界后同一任务才继续原目标，之后正常进入移动超时终态。
- 影响：玩家跨维度时，未完成的位置型任务会让 NPC 在错误维度长距离寻路、离开安全区并受到环境伤害；更危险环境中可能直接触发倒地或形成新的恢复循环。
- 原始证据：`raw-results/S27-boat-dimension-downed.json`
- 截图证据：`screenshots/S27-dimension-task-pass.png`，可见远端坐标、44,832 格距离、生命 2/20 和仍执行中的 move。
- 清理结果：玩家与 NPC 均返回主世界；任务终态后调度器 idle、队列 0、姿态 follow、玩家距离 1.15 格、生存模式；下界专用测试区已发送精确范围清空命令。
- 疑似原因：`TaskSpec.move.target` 和持久化 `ActiveWork.destination` 只有 `Vec3`，没有维度；`NpcManager.recall` 跨维度重建实体后无条件调用 `onRecalled()`，而该方法对任何 active work 都直接继续，`tickMove` 又只按当前 `npc.level()` 解释目标坐标。
- 原因置信度：高。
- 修复复测：在主世界创建 64 格 move 后，用服务端正确的 `ServerPlayer.teleportTo(ServerLevel,DDD,Set,FF)` 进入封闭下界测试室。同一 NPC UUID 和同一任务 ID 连续 12 个样本均保持 `paused`/队列 `phase=paused`，水平漂移 `0`、生命不变；返回主世界后任务恢复 `running`/队列 `phase=active`，随后取消并恢复 idle、队列 0，下界测试室已清理。证据：`.runtime/v014-dragon-care-forge-live-20260828-065711/results/bug024-cross-dimension-v6.json`、`screenshots/15-bug024-nether-paused-v6.png`、`screenshots/16-bug024-overworld-resumed-v6.png` 与 `screenshots/17-bug024-cleanup-idle-v6.png`。
