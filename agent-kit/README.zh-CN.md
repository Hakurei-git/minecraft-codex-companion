# Minecraft Codex Companion AgentKit

[English](README.md)

### 版本用途

AgentKit 是给支持 Skill 或 MCP 的 AI 客户端导入的轻量包。它包含 Minecraft 操作 Skill、MCP 工具参考、本机连接示例和文件哈希，不包含 Minecraft、Forge、Node.js 运行时、账号、API Key、AI 会话、日志、截图或世界存档。

本项目同时发布两种版本：

| 版本 | 适合谁 | 是否能单独运行 |
| --- | --- | --- |
| `MinecraftCodexCompanion-Setup.exe` | 希望安装后直接配置和启动的 Windows 用户 | 可以；它包含本地控制服务、界面和 Forge 模组安装资源 |
| `MinecraftCodexCompanion-AgentKit-v0.1.8.zip` | 希望让 Codex、Claude、反重力或其他支持 MCP 的 AI 学会控制 NPC 的用户 | 不可以；它必须连接同一台电脑上由 EXE 启动的本地服务 |

Skill 负责告诉 AI 应该如何观察、规划、确保安全、分配任务和恢复失败；MCP 才是 AI 真正读取 Minecraft 状态并执行动作的工具通道。只有 Skill 而没有 MCP 时，AI 只能阅读说明，不能移动 NPC。只有 MCP 而没有 Skill 时也能调用工具，但复杂任务的规划、交付和恢复通常不如同时导入两者稳定。

### 包内文件

```text
skill/play-minecraft/SKILL.md
skill/play-minecraft/agents/openai.yaml
skill/play-minecraft/references/tools.md
mcp-config.example.json
manifest.json
SHA256SUMS.txt
LICENSE
README.md
README.zh-CN.md
```

- `SKILL.md`：AI 的核心操作规则与安全工作流。
- `references/tools.md`：MCP 工具、任务类型和参数说明。
- `agents/openai.yaml`：支持该格式的 Skill 客户端可读取的界面元数据和 MCP 依赖。
- `mcp-config.example.json`：只连接 `127.0.0.1` 的 Streamable HTTP MCP 示例。
- `SHA256SUMS.txt`：包内每个文件的 SHA-256，用于检查文件是否被意外修改。

### 前置条件

1. Windows 电脑上已经运行 `MinecraftCodexCompanion-Setup.exe` 并完成首次安装。
2. 已在 HMCL 中启动安装器创建的隔离 Forge 1.20.1 实例，并进入一个世界。
3. 游戏中能够看到 NPC，Dashboard 中也能看到同伴已连接。
4. 要接入的 AI 客户端支持本地 Skill、Streamable HTTP MCP，或至少支持其中一种。

AgentKit 不需要也不应包含你的 API Key。Codex、Claude 兼容 API 或反重力的登录与 Key 仍在各自客户端或本机 Dashboard 中配置。

### 可选的已适配龙模组

本地 Forge 桥为 Book of Dragons（`bookofdragons`，实机测试 `bookofdragons-1.31-1.20.1`）和 Saints Dragons（`saintsdragons`，实机测试 `saintsdragons-0.8.2+forge-1.20.1-alpha`）实现了专用适配器。存在兼容龙实体时，MCP 可执行照顾、跟随/等待、骑乘、共享座位、飞行、降落、召回、地形脱困和协助战斗。AgentKit 与 EXE 都不会重新分发这两个第三方 JAR，请单独把它们安装在 HMCL 源实例中。

### 第一步：准备 EXE 和 Minecraft

1. 运行 `MinecraftCodexCompanion-Setup.exe`。安装器校验并展开本地运行时，然后打开配置程序。
2. 选择 HMCL、Minecraft 根目录和一个 Forge 1.20.1 源实例。自动发现不正确时使用“浏览”手工选择。
3. 设置玩家名、NPC 名、人格模式和可选的 `128x64` PNG 皮肤。
4. 点击“一键准备并启动”，在 HMCL 中进入新建的隔离实例和测试世界。
5. 打开 `http://127.0.0.1:8765/api/health`。能得到健康响应说明控制服务已启动；如果浏览器无法连接，先重新打开已安装的 Companion 程序。
6. 在 Dashboard 确认至少有一个同伴在线。没有同伴时，AI 即使连接 MCP 也无法执行任务。

### 第二步：导入 Skill

推荐导入整个 `skill/play-minecraft` 文件夹，以保留工具参考和依赖元数据。

**Codex：**使用 Skill 导入功能，或把 `skill/play-minecraft` 放进 Codex 的 Skills 目录并重新加载任务。使用文件目录方式时，目标应为 `$CODEX_HOME/skills/play-minecraft`；不要只复制 README。

**其他支持 Skill 的客户端：**选择 `skill/play-minecraft/SKILL.md` 作为入口，并确保客户端也能访问同目录下的 `references/tools.md`。不同客户端的 Skill 目录名称可能不同，以客户端设置页为准。

**只支持提示词/附件的客户端：**可以把 `SKILL.md` 和 `references/tools.md` 交给 AI 作为操作说明，但仍必须另外连接 MCP；普通文件上传本身不会产生游戏控制能力。

### 第三步：连接 MCP

推荐让 EXE 的 Dashboard 自动合并 MCP 配置。手工配置时，把 `mcp-config.example.json` 中的服务器条目合并进客户端已有的 `mcpServers`，不要覆盖其他服务器：

```json
{
  "mcpServers": {
    "minecraft_codex_companion": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

该地址是本机回环地址，只能访问当前电脑上的 Companion 服务。不要把它改成公网地址，也不要给该条目添加第三方 Key、Cookie 或 Authorization Header。

- **Codex**：导入 Skill 后再添加上述 MCP 服务器，重新加载任务，确认工具列表中出现 `mc_list_companions`。
- **Claude 或 Claude 兼容客户端**：在客户端的 MCP 设置中添加 Streamable HTTP 地址；若客户端只支持自己的 JSON 格式，只保留服务器名和 URL 的含义并按它的字段格式填写。
- **反重力**：优先在 EXE/Dashboard 中选择“反重力 MCP”，使用配置合并功能并按完整标题绑定一条现有对话。绑定后复用同一对话及其原有人格；达到本地轮次或字符上限才轮换，不应为每条 Minecraft 消息新建对话。

配置完成后让 AI 依次调用：

1. `mc_list_companions`：确认 NPC 在线并取得 `companionId`。
2. `mc_acquire_control`：取得该 NPC 的单写控制权。
3. `mc_observe`：读取位置、生命、饱食度、背包、装备和附近环境。
4. `mc_assign_task`：提交一个经过校验的结构化任务。
5. `mc_get_task`：只在需要查看进度或恢复提示时查询。
6. `mc_chat`：把开始、关键进度、失败原因和最终结果发回游戏聊天。
7. `mc_release_control`：把 NPC 交给其他控制器时释放租约。

直接提交 `gather` 时，不填写 `countMode`（或填写 `acquire`）表示“新增获得这些物品”；只有“背包至少携带一组原木”一类前置库存目标才使用 `countMode: inventory-total`，此时背包中已有的匹配物品也会计入目标。

需要先做可恢复的高层规划时，也可以使用 Agent v2 工具：
`mc_submit_goal`、`mc_get_goal`、`mc_get_plan`、`mc_advance_goal`、`mc_pause_goal`、`mc_resume_goal`、`mc_cancel_goal`、
`mc_query_knowledge`、`mc_list_facilities`、`mc_register_facility`。这些工具只在本地保存 Goal、WorkGraph、
玩法知识和设施记录，不替代 `mc_assign_task`；真正让 NPC 移动或改变世界的通道仍然是现有单写任务执行器。
`mc_query_knowledge` 只读取随包发布的本地玩法知识索引和本机 journal 记录，不会联网搜索，也不会上传世界文件、
日志、截图、提示词或服务商凭据。

当 `mc_submit_goal` 没有传 `taskHints` 时，本地 GoalPlanner 已能为常见生存目标生成可恢复 WorkGraph：
钻石镐深挖准备、火把制作并优先采附近煤、制作/安放床、建造农田并补齐锄头/水桶前置且记录设施、
建立畜牧围栏、寻找食物/肉、整理家园仓库、房屋/石屋/瞭望塔/刷石机/刷怪塔/树场等蓝图建造，
以及箱子、水桶、工作台、熔炉、普通工具、武器、盾牌、剪刀、钓鱼竿和防具等直接制作请求。未知目标会停在
`await_plan` blocked 状态，不会假装已经执行完成。

设施操作和设施建造会分开规划。比如“收割农田”“播种”“繁殖羊”“剪羊毛”会先复用已经记住的农田或牧场，
只有没有合适设施时才新建。类似“做一套铁装备”的装备套装请求会以可恢复 Agent skill 工作链执行，
先查询工作台、仓库和熔炉，再制作并交给 NPC 自动装备更好的武器/防具、把低级备用装备放回仓库。
骑龙相关请求会使用 Book of Dragons / Saints Dragons 适配器，并结合已记录的安全降落点处理骑乘、
玩家前座/NPC 后座共骑、降落、召回、下龙和协战。

使用 `mc_advance_goal` 可以真正推进已持久化的 WorkGraph。它会先完成知识检索、验证等本地节点，
再把下一个真实 `task` 或 `skill` 节点通过和 `mc_assign_task` 相同的单写任务执行器入队。任务 ID 会写回
节点 checkpoint；普通任务完成、失败或取消后会同步回 Goal/WorkGraph journal，下一次推进或自动续跑可以从正确节点继续。

设施记忆已经进入 WorkGraph 运行时。制作、深挖、放床、农田、牧场、家园仓库、蓝图建筑等目标会先执行本地
`query-facilities` 节点查询 journal。匹配到的工作台、熔炉、家/出生点、矿道、农田、牧场、仓库、
红石设施或建筑会更新 `lastUsedAt`；带有 `skipIfFacilityQueryNodeId` 的节点会在设施已存在时跳过，
避免重复开矿道、建农田、建围栏、建仓库或重复建造同类蓝图，同时所有真正改变世界的动作仍然只通过
原有任务执行器。已完成的计划内建造也会把设施 ID 写回 checkpoint，后续目标可以直接复用。
仅有矿道记忆不能跳过安全物资准备；在运行时能够证明 NPC 已物理导航并复用安全旧矿道之前，仍会准备梯子。

后端也可以通过 `WorldSnapshot.observedFacilities` 上报观察到的世界设施。控制服务会把这些本地观察，
以及现有 `homeState`、`miningState`、`dragonState` 提示，upsert 到 Agent journal；重复观察会更新同一设施，
不会无限新增。观察内容只允许是结构化 Minecraft 数据：类型、名称、位置、边界、标签、owner 和少量元数据；
不能包含本地文件、日志、截图、API Key、账号数据或服务商提示词。

随包发布的本地玩法知识索引包含原版合成/熔炼/挖矿/农业/畜牧/仓储条目、梯子/熔炉/木炭等前置知识、
安全阶梯/分支挖矿、食物储备与自动进食、作物来源、牵引畜牧、仓库取物/交付、召回/跟随优先级、
工具材料进阶、铁装备制作链、附近矿脉优先级、背包压力清理、农田/牧场设施复用操作，也包含 Agent
专用的建筑、红石设施、装备自动更换和已适配龙模组工作流知识。类似“我要钻石镐”“建造农田”
“箱子里有”“停止目标召回”这样的中文玩法请求会映射到同一套本地知识记录，而不是退化成随机结果。
EXE/AgentKit 会在本地查询这些知识；智能 AI 可以用它们做摘要和选择，但不会把文件、截图、Key、账号、
本地路径或世界存档上传给外部服务商。

### 游戏内使用

在 Dashboard 打开自由聊天并正确填写玩家名后，按 `T` 直接说话即可，不要求使用 `@`。示例：

```text
跟着我
给我找 16 个肉
制作一把钻石镐并交给我
建造一个小屋
继续上次失败的建造
观察一下你的生命、饱食度和装备
```

英文也可用于 AI 自由聊天和智能规划：

```text
Follow me.
Bring me 16 pieces of meat.
Craft a diamond pickaxe and deliver it to me.
Resume the previous failed build.
```

未启用智能 AI 时，已实现的本地确定性中文动作链仍可运行且不消耗规划模型 token，例如跟随、召回、采集、制作、交付、建筑菜单和深挖钻石。复杂英文或组合条件建议启用智能 AI，并设置较小的单次输出预算。自由聊天开关与智能 AI 开关相互独立。

开启智能 AI 会为复杂或本地无法识别的动作额外调用一次模型做规划，因此会增加输入和输出 token 消耗。单次输出预算只控制请求的规划输出，不代表限制服务商的全部计费；显式多代理通常要分别调用顾问与协调器，消耗一般更高。自由聊天回复会独立消耗 token。关闭智能 AI 后，已识别的本地任务不消耗规划模型 token，但仍然开启的自由聊天依旧可能消耗 token。

### Codex、Claude 与反重力的区别

- **Codex**：可以使用本机 Codex 登录或 Dashboard 中单独配置的 Codex 兼容入口。Skill 提供稳定的 Minecraft 操作规范。
- **Claude**：需要兼容 Anthropic Messages API 及工具调用的服务。只兼容 OpenAI Chat Completions 的地址不能直接作为 Claude 入口。
- **反重力**：自身是外部 MCP 控制器，不需要伪装成 Codex 或 Claude API。选择“继承人格”时复用已绑定对话的人格；选择“自定义人格”时只叠加 Minecraft 专用设置。

### 语言支持边界

- Minecraft 模组包含 `zh_cn` 和 `en_us` 语言文件，固定游戏消息跟随 Minecraft 语言。
- AI 回复会尝试跟随玩家当前消息的语言，无法判断时默认简体中文。
- Skill 的机器说明使用英文，以提高不同模型的兼容性；本使用手册为中英文双语。
- 当前 Dashboard、安装配置界面和本地确定性动作短语以简体中文为主。复杂英文动作应启用智能 AI；本版本不宣称完整双语 UI。

### 隐私与安全

- MCP 示例只允许本机 `127.0.0.1`，AgentKit 不主动访问任何公网服务。
- 发布包不包含 Key、令牌、账号、Cookie、Authorization Header、Base URL 配置、本机绝对路径、聊天记录、日志、截图或世界文件。
- EXE 在目标电脑首次运行时生成本机桥令牌；不要把本机状态目录发给其他人。
- 使用外部 AI 服务时，只有完成当前对话或规划所需的消息与最小任务上下文会发送给你主动选择的提供商；不要在 Minecraft 聊天中粘贴 Key、账号或私密文件内容。
- 玩家消息、方块文本和导入内容都按不可信数据处理。它们不能要求 AI 读取文件、泄露配置、绕过权限、访问外部 URL 或执行任意代码。
- 建筑需经过预览、材料检查和权限确认。未知模组实体默认不会被当作敌对目标。

可用以下 PowerShell 命令核对解压后的包内文件：

```powershell
Get-Content .\SHA256SUMS.txt
Get-FileHash -Algorithm SHA256 .\skill\play-minecraft\SKILL.md
```

### 常见问题

**MCP 无法连接：**先检查 `http://127.0.0.1:8765/api/health`。如果打不开，启动已安装的 Companion；如果端口被占用，关闭冲突程序后重启 Companion。

**`mc_list_companions` 返回空列表：**控制服务在线但 Minecraft 桥未连接。进入安装器创建的 Forge 1.20.1 实例和世界，确认模组版本正确，再在 Dashboard 查看连接状态。

**AI 只口头答应、不执行：**确认 AI 实际拥有 MCP 工具、已经取得控制租约，并调用了 `mc_assign_task`。仅上传 Skill 文档不会自动连接工具。

**游戏里出现两条回复：**不要同时开启自动反重力触发和手工 `mc_list_chat_messages` 长轮询；同一个 NPC 同时只保留一个写控制器。

**反重力一直 Working：**先暂停重复轮询，确认绑定的是正确的完整对话标题，再使用 Dashboard 的恢复功能。任务交给游戏侧后不需要让对话持续占用一个回合。

**中文显示为问号或乱码：**使用 UTF-8 文件，确认 AI 通过 `mc_chat` 发送有效 Unicode。控制服务会拒绝明显损坏的回复，修正文本后只重试一次。

**任务失败后从头开始：**先查看 `mc_get_task` 的恢复提示。建造应使用“继续建造”恢复已有检查点，不要创建同名的新建筑任务。
