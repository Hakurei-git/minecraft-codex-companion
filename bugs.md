# 缺陷修复记录

> 可直接打开的正式记录，路径：`minecraft-codex-companion/bugs.md`。
> 原始验收证据仍保存在 `.runtime/full-acceptance-20260825-140819/`，下方原始记录未删除。

## 当前结论（2026-08-26 23:38 v0.1.14 候选构建后）

- `0.1.12` 安装版 EXE 已分别冷启动 `Dragons_ZH_1.20.1` 与 `1.21.1-NeoForge` 原始源实例；实际进程版本/游戏目录匹配选中实例，未创建或启动新的 `-Codex` 副本。
- 10:40 的双版本实测发现 BUG-009、BUG-019、BUG-025～BUG-027；这些缺陷现已完成代码修复和离线自动回归，但尚未用 `0.1.14` 做双版本实机复测，因此不能标为实机通过。
- 最新自动回归：Forge 与 NeoForge 完整 clean build 通过；Forge 隔离 JUnit `465/465`；控制层 `311` 项通过、`1` 项按设计跳过；Dashboard `15/15`、worker `4/4`、启动器 `41/41`、协议 `46/46`、脚本 `183/183`；全工作区类型检查和 `git diff --check` 通过。
- BUG-028 已定位为桌面测试工具的窗口/消息自动化可能触发 GLFW 重新捕获光标。启动器不再置顶、置底、最小化、还原或释放/限制光标；自动截图、后台 T 聊天、重生、进出世界和退出游戏均改为明确的人工步骤，只保留 GET 遥测和只读 Win32 审计。当前运行实例的严格后台审计 `91/91` 采样通过，但仍需由用户用 `0.1.14` 新 EXE 人工冷启动两种源实例并确认鼠标可自由离开，才能标为完整实机通过。
- 临时聊天设置已恢复为 `freeChatEnabled=true`、`target=active-provider`、`actionMode=smart`、`tokenBudget=512`、`persona.mode=inherit`。未调用 Codex、OpenAI 或反重力在线提供方。
- 已基于当前工作区生成新 `0.1.14` 安装包候选与 AgentKit，并完成完整性、隐私和本机 ClamAV 门禁；该包仍须通过不使用窗口自动化的双版本实机回归后才能标为最终验收通过。

最新 `0.1.14` 安装包候选构建证据：

- 文件：`build/single-exe/MinecraftCodexCompanion-Setup.exe`
- SHA-256：`38fa353d1b1057b1322bb9d1119d539edf9864364383a9bc4ef4273f0ec14050`
- Package ID：`6251c572fc1bb9f9b355ce838781863cb926e2fb4882e3137f68c2ea139cc548`
- 大小：`195994624` bytes；内含 `5888` 个载荷文件、`562080453` bytes 未压缩载荷
- Forge JAR SHA-256：`e20dee92cea1c97eb4230d0576bbc495cb3a62b07a7b9f30c5b6637c38d84570`
- NeoForge JAR SHA-256：`09cc419e93d319344a2d3fe1068adb8e3be09277949ac5889135d0856f297c89`
- 完整性：已验证
- 隐私门禁：仅本机、已通过
- 防病毒扫描：ClamAV 1.5.4，`clean`、感染文件 0；本机静态病毒库报告超过 7 天，需保留该时效警告
- Authenticode：未签名（构建机没有代码签名证书）
- AgentKit：`build/agent-kit/MinecraftCodexCompanion-AgentKit-v0.1.14.zip`，SHA-256 `c51b14478a084ff2ac4e4719c7ff518b7a843c01fade1c15c99530ff856ae847`，9 个预期文件，隐私校验通过
- 安装器验证：正式 EXE 的 `--self-test --quiet` 退出码为 `0`；离线安装器套件通过 WinForms 初始化、确定性构建、校验解包、启动、幂等安装、原子更新和未知文件保留测试

## 修复状态表

| 缺陷 | 当前状态 | 本次修复摘要 |
| --- | --- | --- |
| BUG-001 | 代码已修复；自动化通过；待实机回归 | 反重力标题不匹配改为明确的 4xx/可公开错误，不再折叠为可重试 500。 |
| BUG-002 | 实机回归通过（2026-08-26） | EXE 精确冷启动两个选定源实例，进程 `--version`/`--gameDir` 匹配，未新增或启动 `-Codex` 副本。 |
| BUG-003 | 代码已修复；自动化通过；待实机回归 | NeoForge `remote-player` 不支持的 NPC 控制返回明确 422，不再虚报 202。 |
| BUG-004 | 代码已修复；自动化通过；待实机回归 | 全局急停改为隔离各实例失败，断开的历史实例不再让在线实例急停返回 500。 |
| BUG-005 | 代码已修复；自动化通过；待实机回归 | 桥连接加入重连代次管理，退出世界再进入可建立新连接。 |
| BUG-006 | 代码已修复；自动化通过；待实机回归 | 召回前确保/重载 NPC，并选择有支撑、无碰撞的安全召回位置。 |
| BUG-007 | 代码已修复；自动化通过；待实机回归 | 明确“吃掉腐肉”等指令固定走本地 `eat`，不再被 AI 改写为备粮。 |
| BUG-008 | 代码已修复；自动化通过；待实机回归 | 父任务取消会同步取消本地补粮子任务和暂停队列。 |
| BUG-009 | 代码已修复；自动化通过；待新构建实机回归 | 到达已验证自然树后优先重试同簇剩余树干，最多 3 轮；仍不可达则明确失败，不再进入 64 区远程循环。 |
| BUG-010 | 代码已修复；自动化通过；待实机回归 | 自然“找些食物”固定为验收约定的 8 份，并在智能模式保留该确定性合同。 |
| BUG-011 | 实机回归通过（会话门禁） | Forge 与 NeoForge 两轮前后固定 conversationId 始终不变，`busy=false`、`queuedTurns=0`，后续 T 聊天可继续进入同一会话。 |
| BUG-012 | 代码已修复；自动化通过；待实机回归 | 存储夹具按真实高度图寻找干燥、有支撑、箱盖可开的独立箱位。 |
| BUG-013 | 代码已修复；自动化通过；待实机回归 | 以世界中真实床脚/床头配对确认放置，不再仅凭背包缺床判失败。 |
| BUG-014 | 代码已修复；自动化通过；待实机回归 | 玩家死亡时原地暂停活动任务，重生后恢复，不再无限保持伪 `running`。 |
| BUG-015 | 代码已修复；自动化通过；待实机回归 | 去重指纹排除自动漂移锚点，同义建造提示不会重复创建任务。 |
| BUG-016 | 代码已修复；自动化通过；待实机回归 | 聊天显示 ACK 超时不再回写矛盾失败，迟到 ACK 作为遥测处理。 |
| BUG-017 | 代码已修复；自动化通过；待实机回归 | 深挖夹具矿点加入专属深板岩密封层，避免天然洞穴导致检查点前提前完成。 |
| BUG-018 | 代码已修复；自动化通过；待实机回归 | 夹具按 Y=-54 的实际策略要求使用 0 梯子，提示与验收条件均动态计算。 |
| BUG-019 | 代码已修复；自动化通过；待新构建实机回归 | 兼容 provider 的 `parameters`、`task`、`action`、`input` 别名；精确相对步行在严格校验前转换为绝对 `move`。 |
| BUG-020 | 代码已修复；自动化通过；待实机回归 | Saints 降落调用公开 `markLandedNow()`，仅在 `flying=false/onGround=true` 后提交成功。 |
| BUG-021 | 代码已修复；自动化通过；待实机回归 | Book 治疗把饱食补到 85 的缓冲值，并将自然再生确认窗口延长至 160 tick。 |
| BUG-022 | 代码已修复；自动化通过；待实机回归 | 龙照料夹具抑制自治进食/战斗，安全放置龙和 NPC，保护角色，并可强制清理恢复。 |
| BUG-023 | 实机回归通过（2026-08-26） | Forge 与 NeoForge 的真实死亡画面均识别为 `death`，后台 `RespawnIfDead` 成功恢复 gameplay，未使用物理输入。 |
| BUG-024 | 代码已修复；自动化通过；待实机回归 | 移动任务绑定源维度；跨维度后暂停/重新绑定，不再复用原维度坐标。 |
| BUG-025 | 代码已修复；自动化通过；待新构建实机回归 | 常备口粮目标按配置固定，不再随饥饿上移；备粮任务达到目标时先真实进食并复核剩余库存。 |
| BUG-026 | 代码已修复；自动化通过；待新构建实机回归 | 玩家回 NPC 仅保留为受限验收夹具；正式 NPC 倒地恢复后召回存活玩家，离线或死亡时持久等待。 |
| BUG-027 | 代码已修复；自动化通过；待新构建实机回归 | 5 格相对步行规范化为水平绝对目标，修正 explore 半径，加入 0.5 格到达阈值、Baritone 停止及树叶/碰撞脱困。 |
| BUG-028 | 代码已修复；自动化与只读实机审计通过；待新 EXE 人工冷启动确认 | 移除所有 Minecraft 窗口、键盘、鼠标和截图自动化；启动器不再改变 Minecraft/HMCL 窗口状态；新增只读置顶、前台和光标限制审计。 |

## 2026-08-26 v0.1.12 双版本实机复测

| 用例 | 结果 | 核心证据 |
| --- | --- | --- |
| RT-01：EXE → Forge 源实例冷启动 | 通过 | 安装版 buildId `d98f36db0b8050a37bb42b95d6dc8599487427c3ab19765988327fff47181cf1`；实际 gameDir 为 `versions/Dragons_ZH_1.20.1`；桥 `forge-1.20.1 / 0.2.3`；没有新增 `-Codex`。 |
| RT-02：Forge 自动生存与死亡 | 失败 | NPC 有 6 份猪肉时仍不吃，随后有 11 份安全食物仍继续远程备粮；生命降至 3，玩家在 NPC 远离期间死亡两次。 |
| RT-03：Forge + Codex + 无作弊 + T 聊天 | 失败 | T 往返通过；约 130 秒后回写 `unrecognized_keys`，未识别 `parameters`；无 goal、无任务、0/4 原木。 |
| RT-04：Forge + 固定反重力 + 无作弊 + T 聊天 | 部分通过、终态失败 | 同一固定会话成功接令并创建 `life.expedition-and-deliver`；NPC 生存步行最大 87.289 格、最大单步 0.257 格、无作弊/无创造；只砍 2/4，31 次远程搜索后超时取消，未交付。 |
| RT-05：死亡恢复 | 部分通过 | 旧构建的双版本死亡识别与重生通过；后续 Forge 测试夹具已把玩家放到 NPC 约 2.74 格处。正式 NPC 倒地恢复召回仅完成代码与自动化，待新构建实测。 |
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
- 状态：v0.1.12 实机稳定复现；代码已修复、自动化通过，待新构建实机回归。
- 复现：Forge 无作弊夹具建立 4 个橡木原木和钻石斧，通过真实 T 聊天发送 `去远征砍4个橡木原木给我`，目标为固定反重力会话。
- 实际：任务 ID `c835fe97-3df9-4dcf-b321-8bba1c7a9b9b` 稳定，NPC 首先真实砍下 2 个原木；夹具终态为 `logBreaks=2`、`npcItems=2`、`remainingFixtureLogs=2`、`skippedTargets=2`、`excursions=31`。任务运行超过 648 秒仍为 25%，没有 delivery，最终按验收超时取消。
- 安全性：`cheatsObserved=0`、`creativeObserved=0`、最大步长 0.257 格，证明失败发生在真实步行采集链，不是传送或作弊替代。
- 疑似原因：树簇队列中的后两个目标被标为 skipped 后没有在同簇重新扫描；宏又允许最多 64 次远程恢复，导致明知夹具仍有同类原木时长时间绕行。
- 修复后：保留首次自然树扫描得到的完整树干集合；每次破坏后重新排队剩余树干，路径跳过会先进行最多 3 轮同簇近距离重试，耗尽后以 `TREE_CLUSTER_NOT_REACHABLE` 有界失败。策略测试及 Forge `465/465` 通过。

### BUG-019 实机回归补充：Codex 动作输出仍存在两种合同漂移

- 严重级别：P1 / 高
- 状态：v0.1.12 在 Forge 和 NeoForge 各稳定复现一次；代码已修复、自动化通过，待新构建实机回归。
- Forge 实际：请求 `264385ed-e9a2-4ffd-982f-2d1e5d176940` 约 130 秒后失败，顶层 `parameters` 被 strict parser 判为 `unrecognized_keys`；没有新增 goal 或任务。
- NeoForge 实际：`向前走5格，不要传送` 约两分钟后回写 `Invalid input: expected object, received undefined`，错误路径为 `spec.target`；玩家位置完全不变。
- 影响：Codex T 聊天往返和错误回写本身均正常，但任何自然语言动作仍无法进入执行器；自动化所覆盖的顶层 `task` → `spec` 规范化没有覆盖这两种真实输出。
- 修复后：统一规范化 `parameters`、`task`、`action`、`input`；`向前走5格，不要传送` 等精确相对请求强制转换为带绝对目标、`arrivalTolerance=0.5` 和 `arrivalMetric=horizontal` 的 `move`。控制层与协议测试通过。

### BUG-025：自动备粮任务会阻止已有食物的自动进食，并形成越饿目标越高的循环

- 严重级别：P1 / 高
- 状态：v0.1.12 实机稳定复现；代码已修复、自动化通过，待新构建实机回归。
- 前置：Forge survival，默认 `npcFoodReserveCount=8`，恢复世界中存在自动备粮任务。
- 实际：NPC 饱食 9 时备粮目标为 14，已持有 6 份猪肉仍不吃；随后饱食降至 5，已持有 11 份安全食物，目标动态升至 16，任务仍继续远征。NPC 一度生命 3、距离玩家约 300 格；无人看护的玩家被蜘蛛杀死两次。
- 交叉验证：仅为隔离后续 provider 测试，临时把 reserve 设为 0 并重启；急停旧任务后 NPC 立即真实消耗食物，饱食恢复 20、生命开始恢复。临时字段已在本轮结束前删除并恢复默认。
- 疑似原因：备粮任务持有调度器期间 managed eating 被抑制；同时目标库存包含随当前饥饿增加的进食缓冲，导致饥饿下降时完成线不断后移。
- 修复后：常备口粮任务创建时冻结配置目标，旧动态目标只迁移一次；`provision-food` 非烹饪阶段允许自动进食，达到目标后先吃到安全状态并再次核对库存。新增口粮审计夹具与策略测试，待实机观察真实消耗和任务终态。

### BUG-026：测试死亡恢复与正式 NPC 倒地恢复的语义边界需要拆分

- 严重级别：P1 / 高（用户明确纠正的产品规则）
- 状态：需求边界已纠正；代码与自动化已完成，待新构建实测。
- 测试专用预期：验收过程中玩家死亡时，`RespawnIfDead` 先通过后台窗口消息完成重生；仅对 Forge `in-world-npc` 再调用受限的 live fixture，把玩家放到 NPC 附近安全位置以继续测试。该步骤必须明确记为测试夹具传送，不计入无作弊步行结果，不使用物理鼠标，也不得暴露为正式产品接口。NeoForge `remote-player` 没有独立 NPC，因此不执行这一步。
- 正式游戏预期：NPC 受到致命伤进入倒地状态并恢复后，应安全回到仍在线且存活的玩家身边，再按调度规则恢复或暂停原任务；若玩家暂时死亡或离线，则保留待召回状态，等玩家可用后完成。
- 修复后当前实现：测试脚本只在明确启用 live fixture 时衔接受限 `view-npc:npc`，已有一次本地 API 证据把玩家恢复到 NPC 约 2.74 格处；正式 `CodexNpcEntity` 每次倒地恢复后都进入持久召回状态，玩家在线存活时安全召回并恢复任务，玩家死亡或离线时等待。正式链仍待实机复测。
- 产品边界：不新增公开的“玩家传送到 NPC”控制动作；`view-npc` 继续只存在于启用 live fixture 的本机验收环境。

### BUG-027：NeoForge 反重力步行距离被放大并卡入树叶，最终导航超时

- 严重级别：P1 / 高
- 状态：v0.1.12 实机稳定复现；代码已修复、自动化通过，待新构建实机回归。
- 前置：NeoForge 1.21.1 原始源实例、`allowCommands=0`、survival、固定反重力会话。
- 复现：真实 T 聊天发送 `向前走5格，不要传送`。
- 实际：反重力建立 `kind=explore,radius=8,direction=any`，不是精确 5 格 move。玩家从 `(-5.536,75,4.700)` 移到约 `(2.17,78,9.54)`，三维位移约 9.8 格；采样最大单步 0.107 格，确认没有传送。随后角色卡在树叶内部，进度固定 0.678、剩余距离 3 格，5 分钟后以 `NAVIGATION_TIMEOUT` 失败。
- 疑似原因：规划层把精确短距离移动退化为最小半径 8 的 explore；NeoForge remote-player 的本地 `driveToward` 缺少可靠的树叶/高差脱困，且最小化后台运行路径没有 Forge NPC 侧同等的单人世界保活兜底。
- 修复后：精确相对请求不再接受 provider 的 `explore` 退化；移动以 X/Z 水平距离判定，普通高差不会造成假超时；本地驱动检测停滞、水平碰撞和树叶后跳跃并交替侧移，完成时释放按键并停止 Baritone。Forge 与 NeoForge 完整构建通过。

### BUG-028：后台测试仍可能让 Minecraft 捕获鼠标

- 严重级别：P1 / 高（直接影响用户继续使用桌面）。
- 状态：代码已修复；专项自动化和当前运行实例只读审计通过；待 `0.1.14` 新 EXE 双版本人工冷启动确认。
- 实际：测试工具没有移动物理鼠标，但 Minecraft 窗口重新获得 GLFW 光标捕获后，用户鼠标无法离开游戏。此前的光标坐标/前台窗口检查仍可同时通过，因此 RT-10 的观测口径不足。
- 影响：后台世界选择、聊天、重生、截图、暂停或窗口状态切换即使不调用物理输入，也可能妨碍用户操作 Codex 和其他应用。
- 根因：旧桌面测试链仍调用 `PostMessage`、`PrintWindow`、`ShowWindowAsync`、`SetWindowPos`、`ClipCursor` 和 `ReleaseCapture`。这些调用即使不移动物理光标，也可能改变 Minecraft 的 UI/焦点生命周期并触发 GLFW 重新限制光标。
- 修复：从桌面启动器移除窗口层级、最小化和光标修改；截图、T 聊天、重生、自动进出世界及 Save and Quit 脚本全部 fail closed 为 `MANUAL_UI_REQUIRED`；新增 `audit-minecraft-desktop-safety.ps1`，只读检查 `WS_EX_TOPMOST`、前台窗口与 `GetClipCursor`；新增仅使用 GET 的人工 T 聊天观察器。
- 自动证据：桌面安全专项 `28/28` 通过并纳入完整脚本回归 `183/183`；发布门禁禁止启动器重新引入窗口/光标写操作。
- 当前实机证据：对 PID `50580`、HWND `10096296` 严格后台采样 `91` 次，`WindowTopmostObserved=false`、`MinecraftForegroundObserved=false`、`BackgroundCursorConfinementObserved=false`，且键鼠、窗口和光标写操作均为 0。
- 剩余边界：该采样来自修复前已运行的 Minecraft 进程，只能证明新观察器自身无副作用；必须由用户手动关闭旧游戏、运行 `0.1.14` EXE、进入 Forge 与 NeoForge 源实例并确认鼠标可自由离开，才可把 RT-10 标为完整实机通过。

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
- 状态：稳定复现，未修复（本轮只测试、不改代码）
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
- 状态：首轮稳定记录到失败，紧接着的独立同套件复测通过；未修复（本轮只测试、不改代码）
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

## BUG-021：Book of Dragons 治疗夹具选中偏爱食物，但真实互动不被确认

- 严重级别：P1 / 高
- 关联用例：S26
- 状态：首轮稳定复现；诊断复测再次通过 feed，但在建立第二次 heal 夹具前被 BUG-022 的空闲竞态阻断；未修复（本轮只测试、不改代码）
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

## BUG-022：Dragon care 空闲竞态留下卡地夹具龙/NPC，并导致玩家死亡与清理自阻塞

- 严重级别：P1 / 高
- 关联用例：S26
- 状态：诊断复测稳定触发一次；未修复（本轮只测试、不改代码）
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

## BUG-023：真实死亡画面被控制接口报告为 gameplay，后台重生辅助流程会跳过重生

- 严重级别：P1 / 高
- 关联用例：S26、S27
- 状态：本轮复现一次；未修复（本轮只测试、不改代码）
- 前置条件：Minecraft 客户端处于死亡界面，本地 Companion 控制服务仍在线。
- 复现步骤：在 Crow 被残留致命纳德射杀后，同时使用被动 `PrintWindow` 留图并读取 Companion snapshot；随后运行标准后台辅助脚本的 `-NormalizeOnly -RespawnIfDead` 路径。
- 预期：`clientUiState=death`，辅助脚本发送仅限目标窗口的后台重生消息，并等待恢复到 gameplay。
- 实际：截图清楚显示“你死了！”和“Crow 被致命纳德射杀”，但 snapshot/辅助脚本读取到 `InitialUiState=gameplay`；辅助流程因此返回 `Respawned=false`，没有执行重生。稍后再次被动截图时玩家已在出生点存活，因此没有额外发送重生点击。
- 影响：自动化或无人值守恢复无法可靠识别死亡状态；依赖 `RespawnIfDead` 的聊天验收可能在死亡界面误判为可继续，或跳过必要恢复。
- 原始证据：`raw-results/S26-dragon-care.json`
- 截图证据：`screenshots/S26-book-dragon-underground-residual.png` 为死亡界面；`screenshots/S26-player-respawned-at-spawn.png` 为后续出生点存活状态。
- 疑似原因：服务端 snapshot 的 `clientUiState` 没有同步客户端死亡 Screen，或死亡事件与 UI telemetry 使用了不同状态源/更新周期；辅助脚本完全信任该字段而没有额外视觉/窗口状态兜底。
- 原因置信度：高（状态不一致已直接交叉取证）；具体更新链路为中等。

## BUG-024：跨维度召回后把原维度 move 坐标直接当作新维度坐标继续执行

- 严重级别：P1 / 高
- 关联用例：S27
- 状态：本轮稳定复现一次；未修复（本轮只测试、不改代码）
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
