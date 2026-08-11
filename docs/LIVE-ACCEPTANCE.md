# Minecraft 与反重力实机验收

本清单用于 `Dragons_ZH_1.20.1-Codex` 的最终实机验收。测试只访问 `127.0.0.1`，不读取或输出 API Key，不上传存档、目录、日志或截图。需要视觉证据时只截取 Minecraft 窗口。

## 0. 只读预检

进入测试世界并看到 NPC 后运行：

```powershell
npm run smoke:live-preflight
```

预检不会发聊天、移动 NPC、分配任务或修改世界。它验证：真实 Forge NPC 身份、16 项能力、生命/饥饿/饱和/自然回血、空气/护甲、背包与装备记录、姿态、活动与暂停队列、家/复活点，以及反重力是否绑定同一条会话。输出只含布尔值和记录数量。
- 2026-08-09 当前 JAR 只读预检结果：`ok=true,localOnly=true,destructiveActions=false`，伴侣为 `backend=forge-1.20.1,embodiment=in-world-npc`，暴露 16 项能力、20 条背包记录、空任务队列且家园解析成功；反重力状态为 `available=true,connected=true,conversationBound=true,exactConversationBound=true`。预检未发送聊天、未读取会话正文，也未触发外部 AI 请求。

## 1. 状态与进食

- 右键 NPC，确认背包 UI 同时显示生命、饱食/饱和、模式、姿态、当前任务和暂停原因。
- 降低饱食度到 10 以下并提供普通食物，确认出现持物与进食动画，吃满后停止。
- 降低生命且保持足够饱食，确认遵守世界自然回血规则；饱食不足时状态明确显示不能回血。
- 放入更好的护甲、盾牌和不死图腾，确认自动换装；低血量优先图腾，健康时优先可用盾牌。

## 2. 跟随、飞行与伤害

- 生存地面跟随、创造飞行跟随、玩家落地后的持续跟随分别验证。
- 拉开远距离：有作弊权限时验证召回；无作弊权限时验证持续寻路而不静默传送。
- 玩家直接攻击和箭矢都不能伤害自己的 NPC，环境和敌对生物仍能造成伤害。
- 乘船、跨维度和倒地恢复后，姿态与未完成任务仍保持或恢复。
- 2026-08-09 当前 JAR 跟随韧性实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`ok=true`、`localOnly=true`、`reversible=true`、`cleanup=restored`。地面跟随最终距离 `3.765` 格且双方真实落地；创造飞行距离 `3.070` 格、高差 `2.164` 格且 NPC 使用飞行无重力状态；玩家落地后 NPC 恢复重力并在 `2.230` 格内真实落地；有权限远距召回最终距离 `1` 格。主人近战和箭矢伤害接受计数均为 0，NPC 生命保持 `20 -> 20`；环境伤害接受计数为 1，生命真实降至 `19.31`。清理恢复维度、模式、位置、姿态、生命、重力、飞行能力、背包、落地状态和跟随状态共 10 项，最终为“跟随待命”。

## 3. 采集、交付与任务优先级

- 发送“砍 16 个原木给我”，确认整树采集、远程继续搜索、返回玩家并物理丢出 16 个。
- 在采集中让怪物攻击玩家，确认采集显示暂停、NPC 优先护主，危险解除后从原进度继续。
- 护主结束后再发送“跟着我”，确认立即恢复跟随，不残留战斗目标。
- 在远距离与不可达资源条件下验证重试上限、明确失败原因和后续任务继续运行。
- 2026-08-09 当前 JAR 自然树采集实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：任务 `0ae1c6ca-2ad8-41a7-a69e-3719906f1f18` 真实破坏并取得 10 个 `#minecraft:logs`；受保护原木 4、边界保护 2 和人工结构原木 4 全部保持，远距直接破坏、视线违规和客户端/服务器同步违规均为 0，最大合法触及距离为 `4.466` 格。流程返回 `ok=true`、`localOnly=true`、`reversible=true`。
- 发送“去远征挖 12 个铁矿给我”，确认先补齐合适镐子、持续搜索到目标数量、护主后恢复，并最终返回交付。可用 `node scripts/live-action-smoke.mjs --apply --task=expedition-log --wait-seconds=300` 做最小远征动作验证。
- 关闭作弊并以生存模式进入自然维度后，可运行 `npm run smoke:live-no-cheat-expedition -- --apply --wait-seconds=300` 做严格的无作弊远征闭环。脚本只通过 typed bridge/live-fixture API 建立可回滚场景，然后分配一个 `life.expedition-and-deliver` 宏来取得夹具指定的 4 个资源；任务参数不得包含 `movement: "walk"`，脚本记录首次分配返回的任务 ID，并只等待该同一任务 ID。
- 该项只有在 `inspect` 的 `snapshot.liveFixtureAck.status` 同时证明全程生存且没有作弊/创造权限、NPC 总步行距离至少 55 格、任一 tick 位移不超过 4 格、真实破坏恰好 4 个、物理交付恰好 4 个、交付后距玩家不超过 3.2 格、物品守恒且任务 ID 未漂移时才通过。成功、失败或中途异常都会在 `finally` 执行 typed `cleanup` 并要求玩家、NPC、复活点、方块和测试物品全部恢复。
- 作弊 fixture、模拟器、在任务中硬编码 `movement: "walk"`、聊天回复或任务成功文案都不能替代上述世界状态证据。此清单只描述待执行验收；没有保存真实 smoke 输出时不得记录为“已通过”。
- 2026-08-09 无作弊远征实机结果：在 Minecraft 界面进入世界 `Codex-NoCheat-Acceptance` 后，Forge 夹具全程观测 `cheatsObserved=0`、`creativeObserved=0`。AI NPC 使用同一任务 `89a91844-c889-4cca-99a6-9cd0ba7956d2` 自主完成 `life.expedition-and-deliver`：最大离开距离 `83.394` 格、单 tick 最大位移 `0.257` 格，真实破坏并物理交付 `4` 个原木，交付后距玩家 `2.790` 格；玩家持有 `4`、NPC 与世界残留均为 `0`，任务 ID 未漂移且观测/同步错误均为 `0`。清理确认玩家、NPC、复活点、方块和测试物品五项全部恢复，流程返回 `ok=true`、`localOnly=true`、`reversible=true`。

## 4. 完整生活能力

- 在 `T` 聊天中精确发送“去找些食物”，确认只创建一个 `kind="provision-food"` 任务（默认 `count=8`、`source="auto"`），而不是普通采集交付或只有口头回复；任务需先复用 NPC 背包和家中箱子食物，再扩大范围寻找食物，最终在背包中保留目标口粮。
- 分别发送“给我找些食物”和“找些食物放到家里箱子”，确认前者使用 `destination="player"` 返回并物理丢出目标数量，后者使用 `destination="home-storage"` 存入家/复活点附近容器；两者都不得先从目标玩家或箱子取出现有食物来伪造新增结果，保存退出后仍保持原去向与已转移计数。
- 2026-08-09 食物去向双链路实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：后台 `T` 消息“给我找些食物”创建唯一任务 `d5c0e528-6025-4e18-8023-4d40c1ae3f55`，规格为 `provision-food,count=8,source=auto,destination=player`，守恒计数从 `player=0,npc=8,world=0` 变为 `player=8,npc=0,world=0`；“找些食物放到家里箱子”创建任务 `b496313f-0889-4de6-958e-a9b4753c80c4`，规格为 `destination=home-storage`，计数从 `home=0,npc=8,containers=0` 变为 `home=8,npc=0,containers=1`。两项均 `status=succeeded`、`localOnly=true`、`reversible=true`，清理后恢复“跟随待命”。
- 可运行 `node scripts/live-food-delivery-smoke.mjs --apply --wait-seconds=120` 验证“给我找些食物”的完整 `T` 聊天链路；增加 `--destination=home-storage` 则验证“找些食物放到家里箱子”。脚本暂存并恢复 NPC 原有食物、饱食状态、位置和朝向，测试面包使用独立 NBT 标签，结束时只从 NPC、玩家、家附近容器和附近物品实体中移除这些测试面包；临时箱为空时恢复为空气。2026-08-07 家园实机结果为 `home=8,npc=0,containers=1`，任务使用 `kind="provision-food",count=8,source="auto",destination="home-storage"`，清理后 NPC 恢复“跟随待命”。
- 分别验证采集成熟作物与安全猎食：普通成熟作物收获后重新种植；猎食只选成年、未命名、未驯服且未拴住的猪牛羊，自动模式在附近为同类保留至少两只成年繁殖种群，并避开玩家家附近牲畜。
- 获得可烹饪生食时，确认查找或按真实配方制作熔炉、准备安全燃料并等待熟食；保存退出、护主插队或控制服务重启后，从原烹饪阶段恢复，不重复装料、不遗失已有计数，也不在烹饪期间抢先吃掉生肉。
- 可运行 `npm run smoke:live-food-survival-restart -- --apply --wait-seconds=300` 严格验证后台 `T` 消息“给我找4份食物”。夹具在家园保护范围外建立可回滚场地，生成 6 头可猎杀成年牛以及各 1 头幼崽、命名牛和拴绳牛；后三者误伤任意一头即失败。验收必须观察 NPC 自己的真实攻击、击杀和生牛肉掉落，空熔炉的装料、燃料、点燃、熟牛肉出炉与取出，烹饪期间护主插队后恢复同一任务，以及原版保存退出、重进同一世界后沿用同一任务 ID。最终玩家必须实际取得 4 份熟牛肉，结果固定声明 `actor="ai-npc"`、`playerGameplayAssistanceUsed=false`、`usedMinecraftTChat=true`；聊天确认、任务成功文案或 fixture 直接给予物品均不能替代这些世界证据。脚本不检查 Minecraft 日志，结束时只清理专用标签实体、物品和精确记录方块，恢复玩家复活点及 NPC 完整状态，并默认正常关闭 Minecraft。
- 发送“建个围栏养两只牛”或“把猪牛羊牵过来养”，确认执行 `life.establish-ranch`：先建围栏，再用真实拴绳逐只步行牵回、开关围栏门并释放到围栏内；不得为了牵引传送 NPC 导致断绳。随后验证按对应饲料繁殖，以及保留目标种群后的安全淘汰。
- 可运行 `node scripts/live-ranch-smoke.mjs --apply --wait-seconds=300` 验证完整 `T` 聊天链路：脚本在 Minecraft 中真实发送“建个围栏养两只牛”，确认创建标准 `life.establish-ranch` 宏，并保留“NPC 建造围栏 -> NPC 牵回牲畜”两个步骤。回环地址上的一次性验收开关只准备可回滚隔离平台、NPC 生存物资与 `CodexAcceptanceRanchAnimal` 标签牲畜，仅向 ranch 步骤注入标签；不会预建围栏、移动玩家或进入普通提示词和生产任务参数。验收要求准备态为 `built=0,blocks=0,placements=0,gate=missing`，完成态必须由与 NPC 同步的 Forge `FakePlayer` 在 32 个固定坐标各产生一次唯一放置，并保持 `built=1,blocks=32,placements=32,gate=closed`。临时平台、部分或完整围栏、生物与物资在 `finally` 中清理，并恢复 NPC 完整实体 NBT。
- 2026-08-09 AI NPC 畜牧复验（Forge JAR SHA-256 `3A93B64CFCA14358F8168C780F8B01339106DD89BC693A9044945446C7E6342C`）：玩家侧只通过后台 `T` 发送一次“建个围栏养两只牛”，未替 NPC 寻路、牵引、开门、喂食或攻击。NPC创建任务 `bbb58728-16f5-4744-be61-b95900c4927c`，逐只步行牵回两头成年牛并在每次进出时真实开关围栏门；任务 `bea9ed31-6755-4c53-8fbe-c7b33c65c66e` 由 NPC 给两头成年牛各喂食一次并产下幼崽；任务 `9f351cbe-450c-4f55-828c-947d04c923ca` 只淘汰一头额外成年牛。最终 `adults=2,babies=1,inside=3,outside=0,gate=closed`，清理为 `ranch-fixture:cleanup restored`，NPC 返回 `idle` 且没有活动或暂停任务。此次修复把可逆场地搜索从 44 格扩至 128 格并按外圈周长增加候选密度；Forge 完整回归为 `284/284`、0 失败。
- 2026-08-09 严格 NPC 建造与畜牧实机结果（Forge JAR SHA-256 `7F4AE3C9B5FBCC2D62A0C8DC13AC1ABC09915B80D9EC35A49600E2DFBFD37C17`）：结果明确返回 `actor="ai-npc"`、`playerGameplayAssistanceUsed=false`、`usedMinecraftTChat=true`。玩家只通过后台 `T` 发送一次“建个围栏养两只牛”；准备态为 `built=0,blocks=0,placements=0,gate=missing`。NPC 用任务 `dd29347f-e16b-4688-ab33-7051de3c2171` 先亲手完成 9x9 围栏的 31 个围栏与 1 个朝南关闭围栏门，世界和唯一 `FakePlayer` 放置证据均为 `32/32`，再逐只步行牵回两头牛并关门。任务 `938df520-6d63-4c63-83ee-f99f4b93570b` 喂食两头成年牛并产下幼崽；任务 `a649c14e-669c-476d-b4ab-fae314fec9da` 只淘汰一头额外成年牛。最终 `adults=2,babies=1,inside=3,outside=0,built=1,blocks=32,placements=32,gate=closed`，清理为 `ranch-fixture:cleanup restored`。Forge 全量回归为 `287/287`、0 失败，Minecraft 随后正常保存退出且未强制终止。
- 真实配方制作原木 -> 木板 -> 木棍 -> 工具，熔炉烧炼、种植/收获、钓鱼、睡觉分别验证。
- 2026-08-09 铁镐递归依赖链实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：任务 `a3b6355d-6d2c-4401-94b6-859f23d66d58` 从仅有 3 个粗铁、无原木/木板/木棍/圆石/工作台/熔炉的状态开始，真实破坏 4 个原木和 8 个石块，完成木制前置工具，放置工作台与熔炉、加入原料和燃料并点燃，取得铁锭后制作 1 把铁镐并物理交付玩家。最终 `playerPick=1,npcPick=0,worldPick=0`，制作/交付、checkpoint 持久化、同任务 ID、依赖深度和两次往返均为 1 或预期计数，错误为 0；流程返回 `localOnly=true`、`reversible=true`。
- 可运行 `npm run smoke:live-bed-sleep -- --apply --wait-seconds=300` 验证专用床与睡眠闭环：夹具从 NPC 背包移除木材、羊毛、剪刀、工作台和床，只保留 2 个铁锭；NPC 必须在真实世界中砍取受隔离的自然树、制作木板/工作台/剪刀、剪白羊取得羊毛、执行内置 `life.craft-and-place-bed` 并在临时玩家复活点附近放置完整床。随后脚本切到临时晴朗夜晚，确认 NPC 真正进入 sleeping 状态，再切到白天确认同一睡眠任务成功且 NPC 离床。`finally` 会取消未结束任务，并恢复玩家/NPC 完整实体状态、时间、天气、复活点及全部临时方块、生物和物品；跨维度、非自然维度、创造物资、骑乘、进食或非空闲状态会拒绝开始。
- 2026-08-09 床与睡眠后台 `T` 实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`triggeredViaTChat=true`，床任务 `a7122a3d-ec79-47e2-80a1-2f0b10a0e6f7` 从仅有 2 个铁锭开始，真实破坏 2 个原木、制作木板/工作台/剪刀、剪 2 只羊并在临时家旁 1 格处放置完整床；睡眠任务 `8e3fe0c7-8d0c-42f4-9864-1403ea6b5d6a` 在夜间使 NPC 真实进入 sleeping，切回白天后离床并成功结束。玩家、NPC、时间、天气、复活点、方块和实体七项清理全部为 1，错误为 0。
- 指定吃腐肉/西瓜，确认消耗对应物品且有进食动作；饱食满时不继续吃。
- 2026-08-09 玩家式状态与生活实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：状态夹具确认 NPC 真实进食 2 次、饱食达到 20、自然回血后生命为 13，并自动换上钻石头盔、钻石胸甲与盾牌；钓鱼任务 `b355f706-62c6-4bcc-b037-85d2aed12f71` 产生归属于 NPC 的真实浮漂，收杆后浮漂消失、获得 1 份战利品且鱼竿损耗 1；农作任务 `6cce7096-aa1b-4cf1-b3ba-041edf7bb14c` 破坏 2 株成熟作物并补种 2 株幼苗，空场景按预期以 `FARM_TARGET_NOT_FOUND` 失败而不伪报成功；护主插队期间任务 `816bb2b8-0adf-469f-8e27-52370bb3d39c` 的进度冻结在 `83/1000`，危险解除后同一任务恢复并最终真实破坏/取得 12 个原木。整套返回 `ok=true`、`localOnly=true`、`reversible=true`，各夹具清理均确认恢复。
- 2026-08-09 AI NPC 玩家式生活复验（Forge JAR SHA-256 `1644611C35F5B8630B6C64710CA4936EAA82C8FAF30669AAF5D9E0AF02FDFAAF`）：结果明确返回 `actor="ai-npc"`、`playerGameplayAssistanceUsed=false`。NPC 从饱食 `8/20` 开始时实时观测到 `automaticEating=true`，完成后恰好进食 2 次、饱食 `20/20`、熟牛肉由 4 变为 2，且 `managedEating=0,usingItem=0`，证明低于一半会自动进食并在吃饱后停止；自然回血至 13，并自动选择钻石头盔、钻石胸甲和盾牌。NPC 钓鱼任务 `2d4266ca-572f-420e-a186-7032a4fa3731` 生成归属于自己的真实浮漂，收杆取得 1 份战利品并损耗鱼竿 1；农作任务 `b745e8f9-8d67-4b9a-9d31-b21df69bd467` 真实收割 2 株并补种 2 株；护主插队冻结并恢复同一采集任务 `22619a7c-9520-4d7c-94c1-51315178a763`，最终破坏并取得 12 个原木。整套 `ok=true`、本地可逆，Minecraft 随后正常保存退出。
- 2026-08-09 AI NPC 指定进食严格实机复验（Forge JAR SHA-256 `6A4C18F9348615F87685852158011776DD3DE6AB2DDC27C5F6B04964408F6BFC`）：整套明确返回 `actor="ai-npc"`、`playerGameplayAssistanceUsed=false`、`usedMinecraftTChat=true`，玩家没有替 NPC 操作。`T` 输入“把3个腐肉吃掉”后 NPC的真实物品使用事件为 `eaten=3,starts=3,finishes=3`，腐肉 `3 -> 0` 而干扰西瓜保持 2；输入“把2片西瓜吃掉”后为 `2/2/2`，西瓜 `2 -> 0` 而干扰腐肉保持 3；满饱食输入“把西瓜吃掉”后消耗、开始和完成事件均为 0。三组 `violations=0` 且结束时 `managedEating=0,usingItem=0`。同轮还确认低于一半饱食会自动吃到 20、自然回血至 13、自动换装，NPC 自己完成钓鱼、收割补种，并在护主插队后恢复同一个采集任务取得 12 个原木；腐肉产生的临时饥饿效果会在可逆清理前清空，再恢复原 NPC 效果与持久状态。Forge 严格回归为 `292/292`，Minecraft 随后正常保存退出。
- 指定丢物品、存入家中箱子、从家中取物和整理仓库，核对物品 ID、显示名和数量无乱码。
- 仓库满时确认用真实木板制作并放置工作台/箱子；保留食物、好装备和稀有物品，将低级多余装备归档。
- 可运行 `node scripts/live-storage-smoke.mjs --apply --wait-seconds=180`，通过后台 `T` 聊天依次验证三条真实链路：从两个家园箱跨箱取出 8 个圆石并交付玩家、只把背包多余材料分类入库而保留食物、满箱后使用 NPC 背包中的箱子物品真实放置并继续整理。脚本临时切换并最终恢复玩家原复活点，暂存并恢复 NPC 原背包与位置；所有测试物品带独立 NBT 标记，隔离家园与临时箱在 `finally` 清理，不访问玩家真实仓库。可用 `--scenario=retrieve|organize|expand` 单独运行固定场景。
- 2026-08-09 家园仓库三场景实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`retrieve` 由任务 `2158c451-8c11-435e-985b-03ced2fee32b` 跨两个隔离箱取出并物理交付 8 个圆石，守恒计数从 `home=8,npc=0,player=0` 变为 `home=0,npc=0,player=8`；`organize` 由任务 `d2935b6b-3213-46c7-9c95-93888239db14` 把 NPC 的 4 个多余物品归档，并原样保留 4 份食物；`expand` 在原仓库 `homeFiller=1728` 满载时由任务 `f595859c-a9e5-47bc-9b8f-c0cf5c54e6ad` 放置新容器（`expanded=1`）并归档 4 个物品，NPC 最终残留为 0。三项均为真实后台 `T` 聊天、`status=succeeded`、`localOnly=true`、`reversible=true`。
- 可运行 `node scripts/live-storage-restart-smoke.mjs --apply --wait-seconds=300` 验证保存退出恢复：夹具把 96 个带标记圆石分散到 96 个隔离临时箱，在 Forge 快照确认 `retrieve` 已活动后由后台脚本打开暂停菜单并执行原版 Save and Quit，再由后台 HMCL 启动同一世界。脚本捕获开始时的 `worldId` 与维度，重进世界列表时通过后台 `PostMessage` 精确筛选该世界并在桥接后再次逐字校验，不使用 `-AnyWorld`；错误世界或维度会在加载旧坐标及移除夹具方块前失败并保留恢复 marker。原任务 ID 必须在断开期间保持非终态，重连后从存档检查点继续并完成 96 个交付；最后恢复复活点和 NPC 完整实体 NBT（背包、装备、属性、生命、饱食、姿态、状态及位置），移除全部临时箱，并返回严格的 `storage-fixture:cleanup restored` ACK。该项只有记录任务 ID 连续性、`statusDuringRestart`、初始/最终守恒计数和 cleanup ACK 的实机输出后才算通过；离线持久化测试不能替代。
- 2026-08-09 跨重启实机结果：任务 `185116ec-9c6a-494a-b6f2-bafc4dbcd373` 在保存退出前已由 Forge 接受（`sequence=232`、`progress=1/96`），Minecraft 断开期间仍为 `statusDuringRestart=running`，重进原世界和原维度后由同一任务 ID 完成。初始守恒计数为 `home=96,npc=0,player=0,world=0,containers=96`，最终为 `home=0,npc=0,player=96,world=0,containers=0`；清理返回 `storage-fixture:cleanup restored`，且流程声明 `localOnly=true`、`reversible=true`、`normalSaveAndQuit=true`。

## 5. 建筑与家园

- 在生存模式执行基础住宅、农田和仓库 Skill，确认先采集/制作材料，再回到接令位置建造。
- 清空常用建材后运行 `node scripts/live-action-smoke.mjs --apply --task=build-material-chain --wait-seconds=900`，确认同一个任务依次完成圆石直采、原木到木板、沙到玻璃、煤/木棍到火把、玻璃到玻璃板，并在每次补料后返回原索引继续放置。
- 把其中一种材料放入家附近箱子再重复验证，确认优先取箱内材料；补料时触发护主或保存退出，确认恢复后不重复消耗、不重置路线，建筑进度与已放方块数一致。
- 采集期间把 NPC 拉到远处，确认建筑原点不漂移；控制服务重启后仍使用同一原点。
- 创造模式执行同一 Skill，确认跳过材料准备。
- 导入 JSON、`.schem`、`.litematic` 或 PNG 时，确认先预览和确认；危险方块/NBT 被拒绝。
- 2026-08-11 材料建造链实机结果（Forge JAR SHA-256 `658ADE110F04D9C7E866991DA99D44BE7C358A8A3E82F714A77D8E18AB8984B1`）：同一任务真实采集 3 个原木、9 个圆石、7 个沙和 2 个煤，只放置并复用 1 个工作台与 1 个熔炉，完成木镐、木板、玻璃、火把和玻璃板动作链，最终 5/5 目标方块匹配。任务 ID 未漂移，最大离开 16.915 格并返回至 6.610 格，远程破坏、视线、同步、未知世界编辑与非夹具破坏均为 0；流程 `ok=true`、`localOnly=true`、`reversible=true`，结构化证据见 [`LIVE-BUILD-MATERIAL-CHAIN-ACCEPTANCE-20260811.json`](LIVE-BUILD-MATERIAL-CHAIN-ACCEPTANCE-20260811.json)。

## 6. 双龙模组

- 分别用 `bookofdragons` 与 `saintsdragons` 验证观察、喂养、治疗、驯服、跟随、停留和龙蛋照料。
- 验证上龙跟随、玩家飞行导航、远距离召回、骑乘协战、降落和下龙。
- 运行 `npm run smoke:live-dragon -- --apply --wait-seconds=360`，依次对两个模组的固定标签测试龙验证：真实 `follow/stay` 指令值、NPC 上龙/下龙、玩家主座与 NPC 后座同骑、空中同骑时 NPC 生命和坠落距离、骑乘 `fly-to`、安全 `land`、远距离 `recall`、骑乘 `assist-combat`，以及存在完整树叶障碍时抵达障碍另一侧。脚本在每个任务终态后读取 `dragon-fixture:inspect` 世界状态，不把聊天或任务消息当作成功。
- 地形场景只会在预先确认全为空气的区域放置带固定坐标清单的持久树叶；清理时只移除这些坐标上仍为测试树叶的方块。每个模组结果必须显式包含 `cleanup.entities` 与 `cleanup.blocks`，并恢复玩家/NPC 起点及 NPC 原先记住的龙；中途失败也在 `finally` 中执行同一回滚。
- 上述 suite 聚焦骑乘、移动、协战和地形脱困。喂养、治疗、驯服与龙蛋照料继续使用既有 dragon-care 实机项单独验收，不能用本 suite 的通过结果替代。
- 2026-08-09 双龙骑乘动作套件实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`bookofdragons` 致命纳德与 `saintsdragons` 殷雷龙均完成 observe、follow、stay、NPC mount/dismount、玩家主座与 NPC 后座 `coRiding=1,firstPlayer=1`、带持久叶墙的 terrain-flight、1 秒稳定性采样、airborne-fall-safety、land、recall 与 assist-combat。两条避障路线分别面对 162 和 330 个真实障碍方块并在目标 3.042/3.489 格内稳定抵达；同骑飞行期间 `npcHealth=20000,npcFall=0,dragonFall=0`，落地后 `onGround=1`，协战目标真实被击败。两个模组最终 cleanup 均为 `entities=0,blocks=0`，流程 `ok=true`、`localOnly=true`。
- 2026-08-09 dragon-care 当前 JAR 实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`bookofdragons` 与 `saintsdragons` 的喂养、治疗、驯服、龙蛋照料共 8 个真实任务全部 `succeeded`。Book 龙喂养消耗 1 份并增加 `11.2` 饱食，治疗消耗 1 份并增加 `1.29` 生命/`10` 饱食，驯服产生真实所有权变化，龙蛋进度增加 `20`；Saints 龙喂养消耗 1 份并增加 `10` 饱食/`4` 快乐，治疗消耗 1 份并增加 `29` 生命/`20` 饱食/`8` 快乐，驯服产生真实所有权变化，龙蛋进度增加 `1`。每项均证明目标仍存在、同一目标 ID 未漂移；整套输出为 `localOnly=true`、`reversible=true`，不以聊天回复代替动作成功。
- 不支持乘客控制的具体龙种应明确报告兼容限制，不伪造成功。

## 7. 反重力单会话

- 响应端选择“反重力 MCP”，在游戏中不带 `@` 发送闲聊，确认原绑定对话被唤醒且游戏只收到一条回复。
- 发送采集交付命令，确认反重力先在游戏内确认、任务异步继续、终态主动发回游戏，不持续停在 `Working…`。
- 确认人格沿用现有反重力会话；所有玩家可见台词与简短心理活动都通过 `mc_chat` 出现在游戏中。
- 制造一次假忙/超时后执行恢复，确认仍复用同一会话，不创建测试对话，也不要求在游戏外批准权限。
- 最终钻石镐链路运行 `npm run smoke:live-final-diamond`。脚本必须从 Minecraft `T` 发送“给我制作一把钻石镐并交给我”，并只接受同一已绑定反重力会话提交的一个 `craft minecraft:diamond_pickaxe` 玩家交付任务。成功证据必须同时包含智能决策事件、开始回复、终态回复、深矿准备、下挖/分支、火把、3 个钻石、至少丢弃 2 组且 128 个石头、真实制作、玩家取得钻石镐及严格清理；任何口头承诺、模拟器或直接 HTTP 建任务均不能替代。

## 8. 玩家自然提示词、建造纠错与任务去重

以下是玩家已实际使用、产品必须直接理解的表达，不要求玩家记忆 MCP 名称、JSON 或固定口令：

- “建造房子吧”
- “你来建造”
- “不要把木头给我，你来在附近建造小屋”
- “怎么还没有开始建造房子”
- “根据图纸造一个屋子”
- “老是把木头给我呀”

验收要求：

- 明确的房屋、小屋、住宅或施工意图必须解析成 `kind="macro"`、`skillId="build.basic-shelter"`；不得改写成 `life.gather-and-deliver`、`life.expedition-and-deliver`、独立 `deliver` 或 `drop`。
- 生存建造可以先采集、制作和烧炼，但中间材料必须保留在 NPC 背包或任务仓储中，并在准备完成后自动进入真实 `build` 步骤；不得把建材交给玩家后声称正在施工。
- 创造物资模式必须跳过生存材料准备并直接进入建造，用于快速确认放置动作、抬手动画和蓝图进度。
- 玩家明确纠正“不要给我，拿去建造”时，应取消或替换同一控制者此前误建的采集交付任务，并创建一个建造任务；不能要求玩家手工输入任务 UUID。
- “怎么还没开始”“继续建造”等追问应查询、说明或恢复当前建造任务，不得每次重复加入一个新宏。相同结构、相同锚点和同一控制者的短时间重复指令必须具备幂等/去重语义。
- `mc_chat` 只算玩家可见确认，不算动作成功。确认消息必须来自真实 `mc_assign_task` 返回值并包含任务 ID；最终成功必须由任务终态和世界中的已放置方块共同证明。
- 接令时锁定 NPC 附近的建筑锚点；远程采集、护主插队、玩家催问、控制服务重启或恢复任务都不得把建筑原点移动到资源点。
- 对上述每条自然提示词分别实测，确认任务队列中最多只有一个目标建造宏，旧的错误交付任务不会继续把材料丢给玩家。
- 2026-08-09 建造调色板与失败续建实机结果（Forge JAR SHA-256 `28622DBCAD877870BB0496A8127D2A8E765D8F2FEE586E2C72619C968F8BCAD2`）：`build-palette` 的 `mixed` 计划 `a95add11-c13a-4ba4-89df-3661218fa662` 与 `chain` 计划 `108ae7c8-8974-408a-bcd5-754463cc1061` 均在世界中得到 `expected=6,matching=6,wrong=0`；`build-resume` 计划 `bce077e1-96bb-4733-bec2-e45df9900024` 的任务 `40ba2c68-cbff-4991-9895-f84db8b9b0a1` 在索引 `3/6` 因 `BLOCK_BREAK_DENIED` 产生可恢复 checkpoint，障碍释放后沿用同一任务 ID 与索引继续，最终 `matching=6,wrong=0` 且真实执行 1 次解除后的破坏，没有另建任务或重置蓝图。

## 9. 建筑木材族、库存复用与调色板替换

玩家已实际发现：基础住宅当前偏向固定采集和使用橡木，背包里已有的其他原木不会被正确用于施工。深色橡木只是一项示例，最终实现不得为某一种木材写特例，也不得把“木材”简单等同于 `minecraft:oak_log`；应通过通用材料族解析器支持所有具有安全完整配方链的木材。

验收要求：

- “用深色橡木建房”“拿这些深色橡树建”“用附近的木头建”等自然表达必须解析出建筑木材偏好，无需玩家输入物品 ID。
- 玩家明确指定木材时优先使用该木材族；未指定时按“背包现有库存 → 家中箱子库存 → 建筑地点附近可持续取得的木材”选择资源最充足的兼容木材族，而不是无条件寻找橡木。
- 选择一种木材族后必须进行一致的配方与蓝图调色板替换，例如 `dark_oak_log → dark_oak_planks → dark_oak_slab`；门、楼梯、栅栏、木桶等后续木制构件也应使用同族兼容方块。
- 默认保持房屋主体木材一致，不能因为背包里有多种原木就随机混搭；玩家明确要求混搭或装饰配色时才允许按可预览的规则组合。
- 木材替换只改变安全材料调色板，不改变蓝图几何、朝向、水浸等方块状态，也不能把原木直接当作模板要求的木板或半砖放置。
- `#minecraft:logs` 标签匹配必须同时解决库存计数、真实配方输出和最终模板方块映射；已有足量兼容原木时不得重复进入“再采 1 个原木”的循环。
- 覆盖橡木、云杉、白桦、丛林木、金合欢、深色橡木、红树、樱花、竹材、绯红菌木和诡异菌木，以及各自兼容的原木/木头、去皮变体、木板、半砖、楼梯、门、栅栏等构件；不能把下界菌柄错误当成普通 `logs` 而漏掉。
- 模组木材不得靠硬编码名称逐个适配；应从本机注册表、方块标签和真实制作配方中识别完整材料族。只有能够安全解析输入材料、配方输出和模板替换目标时才自动启用，缺少某种构件时应说明并选择兼容回退方案。
- 同一套通用调色板机制后续还应支持石头、深板岩、砖块等可互换建筑材料族；玩家指定“用我背包里的材料”“就地取材”或某个具体方块族时都必须遵循，而不是只处理木头。
- 任务状态与游戏聊天应明确报告本次采用的木材族、已有数量、仍缺数量及替换后的主要方块，最终以世界内真实建筑材料为准。
- 可运行 `npm run smoke:live-build-palette -- --apply --scenario=matrix --wait-seconds=300` 执行通用族矩阵。脚本先从本机 Forge 注册表与真实配方图读取只读目录，再严格验证 11 个原版木材族的基材、楼梯、半砖、栅栏、活板门与压力板，以及石头、圆石、石砖、圆石深板岩、深板岩砖和红砖的基材、楼梯与半砖；实际存在的模组木材只有在天然来源和六种安全构件完整时才运行，否则必须返回具体缺失角色并记为跳过。每个场景都保存 NPC 背包与玩家/NPC位置，在隔离平台真实制作、放置和逐块核对后恢复，目录索引仅接受 1–4 位数字，HTTP 仅允许 loopback。

只有以上各阶段都有真实世界证据时，才把完整目标标记为完成。离线单元测试、模拟器和代码存在本身不能替代这些阶段；最终完成状态与当前产物哈希以第 11 节为准。

## 10. 2026-08-09 发布门禁记录（历史）

- 完整工作区测试、TypeScript 类型检查与 Forge 1.20.1 `clean build` 均通过；Minecraft 后台启动、世界选择、`T` 聊天和严格 Save and Quit 全程不使用物理鼠标、键盘、剪贴板或截图。
- 本轮重新通过：自然树采集、无作弊远征与物理交付、跟随/创造飞行/落地/召回、玩家伤害免疫、家园仓库取物/分类/满箱扩容、食物双目的地、畜牧、调色板建造、精确失败点续建、两个龙模组共享骑乘/避障/协战和全部照料动作。
- `T` 输入“建造”会由本地确定性驱动返回 1–9 菜单；输入“取消”会清除该玩家与 NPC 对应的待选菜单，不创建任务或修改世界。
- 最新便携归档 SHA-256 为 `78CB49FA5DF7FC22635AF2ABE958962AA0796730E7CFA87554DF711CA7E08B8C`；归档内 Forge JAR SHA-256 为 `C501079D4151578E7E803963D518CA01C54F08AC7C81CDBBC06A0232297CC513`。
- 发布门禁从归档解压后验证原生客户端、路径选择器、人格/NPC 名称/皮肤、控制服务健康、MCP 回复规则与隔离 HMCL 安装；`PrivateSourceDataCopied=false`。
- ClamAV 1.5.4 使用本地静态病毒库同时扫描解压目录和 ZIP，两个目标均为 `clean`、感染文件为 0；完整性核对 5839 个文件，扫描脚本无网络调用且未上传文件或哈希。
- 发布载荷不包含当前机器身份路径、用户提到的自定义 Base URL 或疑似字面 API Key。第一方 EXE 尚未使用代码签名证书，安全结论来自可复现哈希、完整性门禁与本地双目标杀毒结果。
- 反重力离线时仍保留并显示原会话 ID 与完整标题，但明确保持 `connected=false`；正确程序重新上线后状态恢复为 `connected=true`、完整标题精确匹配用户配置的 `Execute Minecraft Smart NPC Task`。最终后台 `T` 实测复用同一会话，Minecraft 稳定窗口内只收到 1 条匹配回复，owner 为 `antigravity-autoplay`；未读取会话正文、未新建对话、未使用物理输入、剪贴板或截图。

## 11. 2026-08-11 钻石镐发布与最终验收状态

- 最终 Forge JAR SHA-256 为 `7FE8AA4F1696EBD687C96E9C3CEE7E4FD707FFA46F7606E879A15CC9BF5E5414`。Forge 进程内测试为 `375/375`；控制层测试为 `186` 项通过、`1` 项 DPAPI 条件跳过；脚本验收为 `165/165`；TypeScript 类型检查与生产构建均通过。
- 关闭智能 AI 后的稳定模式实机验收已通过。Minecraft `T` 发送“给我做一把钻石镐”只创建一个本地确定性任务，`smartDecisionEvents=0`、`antigravityEvents=0`，无需外部模型。NPC 准备 33 个梯子、32 个火把和 2 把铁镐，执行向下挖掘与分支矿道，取得 3 个钻石，丢弃 9 组共 576 个多余石料，制作并交付钻石镐；开始/终态回复、设置恢复和世界清理均通过。脱敏证据见 [`FINAL-STABLE-DIAMOND-ACCEPTANCE-20260811.json`](FINAL-STABLE-DIAMOND-ACCEPTANCE-20260811.json)。
- 通用材料族矩阵已在同一最终 JAR 的真实世界中通过 17 组：橡木、云杉、白桦、丛林木、金合欢、深色橡木、红树、樱花、竹材、绯红菌木、诡异菌木，以及石头、圆石、石砖、圆石深板岩、深板岩砖和红砖。每组最终放置方块全部匹配，错误数为 0。本机注册表没有额外满足安全启用条件的模组木材族，因此没有伪造模组族成功。脱敏证据见 [`LIVE-BUILD-PALETTE-MATRIX-ACCEPTANCE-20260811.json`](LIVE-BUILD-PALETTE-MATRIX-ACCEPTANCE-20260811.json)。
- 最终便携 ZIP SHA-256 为 `E343082A43F7934501F949A7BCF47F1063561F1CEF4E3966EFB1B82AA23B42DE`，内含的 Forge JAR 与上述哈希一致。ClamAV 1.5.4 使用本地静态数据库同时扫描解压目录和 ZIP，两个目标均为 `clean`、感染文件为 0；完整性核对 5768 个文件。隐私报告确认未上传文件或哈希，载荷不包含 Key、桥令牌、存档、本地状态、自定义 Base URL 或构建机路径。
- 最终单 EXE `MinecraftCodexCompanion-Setup.exe` 的 SHA-256 为 `FD5AB48F4966A04E6DF471601D07AA6E0B47B51243F6C1D0EF5532C153088D37`。完整性检查通过，ClamAV 1.5.4 本地静态数据库扫描为 `clean`、感染文件为 0；隐私报告明确 `uploadedFiles=false`、`uploadedHashes=false`、`containsApiKeys=false`、`containsBaseUrlConfiguration=false`、`containsBridgeToken=false`、`containsLocalState=false`、`containsMinecraftWorlds=false` 与 `containsBuildMachinePaths=false`。第一方 EXE 没有本机代码签名证书，状态为 `NotSigned`。
- 最终反重力实机验收已通过。Minecraft `T` 精确发送“给我制作一把钻石镐并交给我”，复用同一条已绑定反重力会话且未创建新会话，只生成一个根任务。NPC 准备 33 个梯子、32 个火把和 2 把铁镐，真实执行向下挖掘与分支矿道，取得 3 个钻石；背包腾位时丢弃 9 组、共 576 个多余石料，随后制作并物理交付 1 把钻石镐。开始回复、任务终态回复与 `cleanup` 四项恢复均通过。脱敏结构化证据见 [`FINAL-DIAMOND-ACCEPTANCE-20260811.json`](FINAL-DIAMOND-ACCEPTANCE-20260811.json)。
- 最终 Codex 智能模式实机验收已通过。内置 `codex-cli` 作为活动提供方，通过 Minecraft `T` 创建且只创建一个 `craft minecraft:diamond_pickaxe` 玩家交付任务，`codexSmartDecisionEvents=1`、`antigravityEvents=0`。NPC 准备 33 个梯子、32 个火把和 2 把铁镐，执行向下挖掘与分支矿道，取得 3 个钻石，丢弃 9 组共 576 个多余石料，随后制作并物理交付 1 把钻石镐；开始/终态回复、聊天设置恢复与四项夹具清理全部通过。控制服务仅访问 loopback，未注入外部 Base URL，未使用物理输入、剪贴板或截图。脱敏证据见 [`FINAL-CODEX-DIAMOND-ACCEPTANCE-20260811.json`](FINAL-CODEX-DIAMOND-ACCEPTANCE-20260811.json)。
