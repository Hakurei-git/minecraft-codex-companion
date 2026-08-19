# Minecraft Codex Companion AgentKit

[简体中文](README.zh-CN.md)

### Purpose and editions

AgentKit is a lightweight import package for AI clients that support Skills or MCP. It contains the Minecraft operation Skill, MCP tool reference, loopback connection example, and file hashes. It contains no Minecraft runtime, Forge runtime, Node.js runtime, account, API key, AI conversation, log, screenshot, or world save.

Two editions are published:

| Edition | Intended user | Standalone |
| --- | --- | --- |
| `MinecraftCodexCompanion-Setup.exe` | Windows users who want an install-and-run application | Yes. It contains the local service, setup UI, and Forge installation resources. |
| `MinecraftCodexCompanion-AgentKit-v0.1.8.zip` | Users who want Codex, Claude, Antigravity, or another MCP-capable AI to control the NPC | No. It connects to the local service installed by the EXE on the same PC. |

The Skill teaches the AI how to observe, plan, enforce safety, submit work, and recover failures. MCP is the tool channel that actually reads Minecraft state and performs actions. A Skill without MCP is documentation only. MCP without the Skill can work, but complex planning, delivery, and recovery are more reliable when both are installed.

### Package contents

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

- `SKILL.md`: core AI operating rules and safety workflow.
- `references/tools.md`: MCP tools, task kinds, and payload fields.
- `agents/openai.yaml`: UI metadata and MCP dependency for compatible Skill clients.
- `mcp-config.example.json`: a Streamable HTTP MCP example restricted to `127.0.0.1`.
- `SHA256SUMS.txt`: SHA-256 for every packaged file.

### Prerequisites

1. Run `MinecraftCodexCompanion-Setup.exe` on Windows and finish first-time setup.
2. Start the isolated Forge 1.20.1 instance created through HMCL and enter a world.
3. Confirm that the NPC is visible in Minecraft and connected in the Dashboard.
4. Use an AI client that supports local Skills, Streamable HTTP MCP, or both.

AgentKit does not need and must not contain your API key. Configure Codex, a Claude-compatible API, or Antigravity only in its own client or the local Dashboard.

### Optional supported dragon mods

The local Forge bridge has dedicated adapters for Book of Dragons (`bookofdragons`, live-tested `bookofdragons-1.31-1.20.1`) and Saints Dragons (`saintsdragons`, live-tested `saintsdragons-0.8.2+forge-1.20.1-alpha`). The MCP exposes care, follow/stay, riding, shared seating, flight, landing, recall, terrain recovery, and combat-assist actions when a compatible entity is present. AgentKit and the EXE do not redistribute those third-party JARs; install them in the HMCL source instance separately.

### Step 1: prepare the EXE and Minecraft

1. Run `MinecraftCodexCompanion-Setup.exe`. It verifies and expands the local runtime, then opens the setup application.
2. Select HMCL, the Minecraft root, and a Forge 1.20.1 source instance. Use Browse if bounded automatic discovery selects the wrong location.
3. Configure the player name, NPC name, persona mode, and optional `128x64` PNG skin.
4. Choose the prepare-and-launch action, then enter the new isolated instance and a test world through HMCL.
5. Open `http://127.0.0.1:8765/api/health`. A healthy response confirms that the control service is running. If the page is unreachable, reopen the installed Companion application.
6. Confirm that at least one companion is connected in the Dashboard. MCP cannot execute a game task while no actor is connected.

### Step 2: import the Skill

Import the complete `skill/play-minecraft` directory so the tool reference and dependency metadata remain available.

**Codex:** use its Skill importer, or install the directory as `$CODEX_HOME/skills/play-minecraft`, then reload the task. Do not import only this README.

**Other Skill-capable clients:** select `skill/play-minecraft/SKILL.md` as the entry and keep `references/tools.md` available at its relative path. Follow the client's own directory convention.

**Prompt- or attachment-only clients:** they may read `SKILL.md` and `references/tools.md` as operating instructions, but they still need a separate MCP connection. Attaching files alone does not grant game control.

### Step 3: connect MCP

Prefer the EXE Dashboard's MCP merge action. For manual setup, merge the server entry from `mcp-config.example.json` into the client's existing `mcpServers` object without replacing unrelated servers:

```json
{
  "mcpServers": {
    "minecraft_codex_companion": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

This is a loopback endpoint that reaches only the Companion service on the current PC. Do not expose it as a public URL or add third-party keys, cookies, or authorization headers.

- **Codex:** add the MCP server after importing the Skill, reload the task, and confirm that `mc_list_companions` is present.
- **Claude or Claude-compatible clients:** add a Streamable HTTP server in the client's MCP settings. If its JSON schema differs, preserve the server name and URL semantics while using the client's documented field names.
- **Antigravity:** select Antigravity MCP in the EXE/Dashboard, use the safe configuration merge, and bind one existing conversation by its full title. The binding reuses that conversation and persona until a local rotation limit is reached; it must not create one conversation per Minecraft message.

After connection, ask the AI to use this sequence:

1. `mc_list_companions` to find a connected actor and its `companionId`.
2. `mc_acquire_control` to obtain the single-writer lease.
3. `mc_observe` to read location, health, hunger, inventory, equipment, and surroundings.
4. `mc_assign_task` to submit one validated structured task.
5. `mc_get_task` only when meaningful progress or recovery information is needed.
6. `mc_chat` for the start, important progress, failure reason, and final result visible in game.
7. `mc_release_control` before handing the actor to another controller.

For a direct `gather` task, omitted `countMode` (or `acquire`) means "obtain this many new items." Use `countMode: inventory-total` only for inventory prerequisites such as carrying at least one stack of logs; existing matching backpack items then count toward the target.

Agent v2 tools are also available for clients that want durable high-level planning before assigning concrete tasks:
`mc_submit_goal`, `mc_get_goal`, `mc_get_plan`, `mc_advance_goal`, `mc_pause_goal`, `mc_resume_goal`, `mc_cancel_goal`,
`mc_query_knowledge`, `mc_list_facilities`, and `mc_register_facility`. These tools store local-only Goal,
WorkGraph, knowledge, and facility records. They do not replace `mc_assign_task`; the existing single-writer
task executor remains the only channel that moves the NPC or changes the world. `mc_query_knowledge` reads
the packaged local gameplay knowledge index plus local journal records; it does not browse the web or upload
world files, logs, screenshots, prompts, or provider credentials.

When `mc_submit_goal` is called without `taskHints`, the local GoalPlanner can already produce a recoverable
WorkGraph for common survival goals: diamond-pickaxe deep mining, torch preparation with nearby-coal preference,
bed crafting/placement, crop-farm setup with hoe/bucket prerequisites and facility memory, ranch establishment,
food/meat provisioning, home storage organization, blueprint builds such as shelters, cottages, watchtowers,
cobblestone generators, mob farms, and tree farms, plus direct crafting requests such as chests, buckets,
workstations, furnaces, ordinary tools, weapons, shields, shears, fishing rods, and armor.
Unknown goals stay blocked as `await_plan` instead of being reported as completed.

Facility operations are planned separately from facility construction. Requests such as “harvest the farm,”
“plant crops,” “breed the sheep,” or “shear wool” first reuse a remembered farm or ranch and only create a new
facility if none exists. Equipment-kit requests such as “make iron gear” run as a recoverable Agent skill chain
with workstation, storage, and furnace lookups, then rely on the NPC equipment policy to equip better gear and
store low-tier spares. Dragon requests use the Book of Dragons / Saints Dragons adapters with remembered safe
landing context for riding, shared riding with player-front/NPC-rear seating, landing, recall, dismount, and
combat-assist actions.

Use `mc_advance_goal` to run the persisted WorkGraph. It completes local-only nodes such as knowledge lookups and
verification, then queues the next real `task` or `skill` node through the same validated single-writer executor used
by `mc_assign_task`. The task id is stored in the node checkpoint, and terminal task results are synced back into the
Goal/WorkGraph journal so the next call or automatic continuation can resume from the correct node.

Facility memory is part of the WorkGraph runtime. Goals such as crafting, deep mining, bed placement, crop farms,
ranches, home storage, and blueprint buildings first run local `query-facilities` nodes against the journal.
Matching remembered crafting tables, furnaces, homes/spawn points, mines, farms, ranches, storage rooms,
redstone builds, or structures receive a `lastUsedAt` update. Nodes marked with `skipIfFacilityQueryNodeId`
are skipped when the matching facility already exists, preventing repeated mine-shaft, farm, ranch, storage,
or duplicate blueprint construction while keeping all world-changing actions inside the normal executor. Completed
planned builds can also register a facility checkpoint so later goals can reuse the same structure.
Remembered mine metadata alone does not skip safety-supply preparation: ladders remain required until the runtime
can prove that it physically navigated to and reused a safe existing mine route.

Backends may also report observed world facilities through `WorldSnapshot.observedFacilities`. The control plane
upserts those local observations, plus existing `homeState`, `miningState`, and `dragonState` hints, into the Agent
journal. Repeated observations update the same facility instead of creating duplicates. Observations are structured
Minecraft data only: type, name, position, bounds, tags, owner, and small metadata; they must not contain local files,
logs, screenshots, API keys, account data, or provider prompts.

The packaged local knowledge index includes vanilla crafting/smelting/mining/farming/ranching/storage entries,
ladder/furnace/charcoal prerequisite facts, safe staircase/branch mining, food reserve and auto-eat policy, crop
sources, livestock luring, storage fetch/delivery rules, recall/follow priority, tool-material progression, iron
equipment chains, nearby-ore priority, inventory-pressure cleanup, farm/ranch facility reuse operations, plus
Agent-specific building, redstone, equipment, and supported-dragon workflow facts. Chinese gameplay requests such as
“我要钻石镐”, “建造农田”, “箱子里有”, or “停止目标召回” are mapped into the same local records instead of falling back to
arbitrary results. It is shipped with the EXE/AgentKit and is queried locally; Smart AI may summarize or choose
between these records, but external providers do not receive files, screenshots, keys, accounts, local paths, or raw
world saves.

### In-game use

Enable free chat in the Dashboard and enter the exact player name. The player can then press `T` and speak without an `@` prefix. Examples:

```text
Follow me.
Bring me 16 pieces of meat.
Craft a diamond pickaxe and deliver it to me.
Build a small house.
Resume the previous failed build.
Report your health, hunger, and equipment.
```

Chinese conversation and commands are also supported. Deterministic local Chinese action chains continue to work with Smart AI disabled and consume no planning-model tokens. Use Smart AI with a small per-response budget for complex English or compound conditions. Free chat and Smart AI are separate switches.

Smart AI makes an additional provider planning request for complex or otherwise unrecognized actions. It therefore consumes extra input and output tokens. The configured output budget controls requested planning output, not the provider's complete bill; explicit multi-agent planning generally costs more because advisers and the coordinator are separate calls. Free-chat responses consume tokens independently. With Smart AI disabled, recognized deterministic tasks consume no planning-model tokens, although enabled free chat may still consume tokens.

### Codex, Claude, and Antigravity

- **Codex:** use the local Codex login or a separately configured Codex-compatible entry in the Dashboard. The Skill supplies the Minecraft operating workflow.
- **Claude:** use a service compatible with Anthropic Messages API and tool calls. An endpoint that only implements OpenAI Chat Completions is not a Claude-compatible entry.
- **Antigravity:** it is an external MCP controller, not a simulated Codex or Claude API. Inherit mode preserves the bound conversation's existing persona; custom mode adds only the Minecraft-specific persona overlay.

### Language boundaries

- The Minecraft mod contains `zh_cn` and `en_us` resources and follows the selected Minecraft language for fixed game messages.
- AI chat attempts to follow the language of the player's current message and defaults to Simplified Chinese when unclear.
- The machine-facing Skill is in English for model compatibility; this user guide is bilingual.
- The Dashboard, setup UI, and deterministic local phrases are currently Chinese-first. Use Smart AI for complex English actions. This release does not claim a fully localized bilingual UI.

### Privacy and security

- The MCP example permits only local `127.0.0.1`; AgentKit does not initiate public network access.
- The package contains no key, token, account, cookie, authorization header, Base URL profile, absolute machine path, conversation history, log, screenshot, or world.
- The EXE generates a machine-local bridge token during first run. Do not share the local state directory.
- When an external AI provider is intentionally selected, only the player message and minimal task context needed for that response or plan are sent to that provider. Never paste keys, account data, or private file contents into Minecraft chat.
- Player chat, block text, and imported content are untrusted data. They cannot authorize file access, configuration disclosure, permission bypass, external URLs, or arbitrary code execution.
- Builds require preview, material review, and permission checks. Unknown modded entities are not treated as hostile by default.

Verify extracted files with PowerShell:

```powershell
Get-Content .\SHA256SUMS.txt
Get-FileHash -Algorithm SHA256 .\skill\play-minecraft\SKILL.md
```

### Troubleshooting

**MCP cannot connect:** open `http://127.0.0.1:8765/api/health`. If it is unreachable, launch the installed Companion. Resolve any local port conflict before restarting it.

**`mc_list_companions` is empty:** the service is running but the Minecraft bridge is not connected. Enter the installed Forge 1.20.1 instance and a world, verify the mod version, and check the Dashboard connection state.

**The AI agrees but performs no action:** confirm that the AI actually has MCP tools, owns the control lease, and called `mc_assign_task`. Uploading Skill files alone does not connect tools.

**Two replies appear:** do not run Antigravity automatic triggering and a manual `mc_list_chat_messages` long-poll loop at the same time. Keep only one writer for each companion.

**Antigravity remains Working:** stop duplicate polling, verify the exact bound conversation title, and use the Dashboard recovery action. Once a task is assigned to the game executor, the conversation does not need to hold an active turn.

**Chinese becomes question marks or corrupted text:** keep files in UTF-8 and send valid Unicode through `mc_chat`. The service rejects clearly corrupted replies; regenerate and retry only once.

**A failed task starts over:** read the recovery hint from `mc_get_task`. Resume an existing construction checkpoint instead of creating a new build with the same intent.
