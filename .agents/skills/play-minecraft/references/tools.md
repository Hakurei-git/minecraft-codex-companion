# Minecraft Companion Tool Reference

## Tools

| Tool | Purpose |
| --- | --- |
| `mc_list_companions` | Discover companions, capabilities, status, and leases. |
| `mc_list_chat_messages` | Read ordinary player chat routed to the Antigravity MCP inbox and return the next polling cursor. |
| `mc_submit_ai_decision` | Submit exactly one bound Smart-AI decision for an Antigravity/Minecraft interaction. |
| `mc_submit_goal` | Create a durable high-level Agent v2 goal and initial WorkGraph. |
| `mc_get_goal` | Read a persisted high-level Agent goal, status, progress, and recovery message. |
| `mc_get_plan` | Read the persisted Agent v2 WorkGraph for one goal. |
| `mc_advance_goal` | Advance a WorkGraph until local nodes complete or the next real Minecraft task is queued. |
| `mc_pause_goal` | Pause one Agent goal without deleting its checkpoint. |
| `mc_resume_goal` | Resume a paused Agent goal from its persisted checkpoint. |
| `mc_cancel_goal` | Cancel one Agent goal and keep its journal evidence. |
| `mc_query_knowledge` | Search the packaged local gameplay knowledge index; no web browsing or file upload. |
| `mc_list_facilities` | List remembered homes, storage, workstations, farms, ranches, mines, builds, redstone facilities, and dragon landing areas. |
| `mc_register_facility` | Register a reusable in-world facility after observation or construction. |
| `mc_list_skills` | List built-in and learned declarative routines. |
| `mc_save_skill` | Save a validated task-template routine without executable code. |
| `mc_delete_skill` | Delete a learned routine; built-ins remain protected. |
| `mc_list_build_plans` | List local audited build templates and confirmed previews. |
| `mc_observe` | Read world, position, health, inventory, and nearby entities. |
| `mc_chat` | Send at most 256 characters through one companion. |
| `mc_control_companion` | Summon, recall, follow, or park the visible in-world companion NPC. |
| `mc_assign_task` | Queue a typed task after acquiring control. |
| `mc_get_task` | Poll status, progress, result, or recovery information. |
| `mc_cancel_task` | Cancel one queued or running task. |
| `mc_preview_build` | Create a non-executing normalized build preview and material list. |
| `mc_import_build` | Import JSON, `.schem`, `.litematic`, or PNG data into an unconfirmed build preview. |
| `mc_confirm_build` | Confirm a reviewed plan; it does not start construction. |
| `mc_acquire_control` | Obtain the single-writer lease for one companion. |
| `mc_release_control` | Release the lease for handoff. |
| `mc_emergency_stop` | Cancel all work and optionally disconnect companions. |

## Agent v2 Goals, Knowledge, And Facilities

Use Agent v2 when the player asks for a result rather than a single primitive action. It is the default route for multi-step Minecraft survival work because it plans prerequisites, records facilities, resumes failed checkpoints, and keeps world-changing actions inside the existing task executor.

Typical flow:

1. `mc_submit_goal` with the companion id and a `spec` containing:
   - `title`: short human-readable summary.
   - `objective`: the player's full request, such as `给我做一把钻石镐并交给我`.
   - `requestedBy`: exact Minecraft player name.
   - `source`: usually `t-chat`, `mcp`, or `dashboard`.
   - `mode`: `stable` for deterministic local chains or `smart` when a Smart AI decision asked for Agent planning.
   - Optional `deliverTo`, `constraints`, `taskHints`, and small non-sensitive `metadata`.
2. Inspect the returned WorkGraph. If it is ready, call `mc_advance_goal`.
3. Repeat `mc_advance_goal` or read `mc_get_goal` / `mc_get_plan` when the player asks for progress. Do not restart the goal from scratch after a failed node; the journal checkpoint is the recovery source.
4. Use `mc_pause_goal`, `mc_resume_goal`, or `mc_cancel_goal` for player commands such as pause, continue, stop target, or replace objective.

Planner coverage includes:

- Diamond-pickaxe deep mining with food, one stack of spare wood, iron pickaxe, torches, ladders/safe access, nearby or remembered mine reuse, and low-value stone cleanup.
- Torches with nearby-coal-vein priority before storage, charcoal fallback, and crafting.
- Beds, common tools, weapons, armor, shields, shears, buckets, worktables, furnaces, fishing rods, and chests.
- Crop-farm construction with hoe, bucket, water, seeds, facility registration, and later plant/harvest/cycle reuse.
- Ranch construction and operations with lure food or leads, sheep/cow/pig support, shearing, breeding, culling, and facility reuse.
- Food/meat provisioning, automatic food reserve policy, storage deposit, exact-count player delivery, and cooked-food preference.
- Home storage organization, storage fetch/delivery when the player says “箱子里有”, and protection of tools, weapons, armor, food, and active-task materials.
- Blueprint buildings, cobblestone generators, mob farms, tree farms, build checkpoint recovery, obstacle handling policy, and facility memory.
- Book of Dragons / Saints Dragons observation, riding, shared ride (`share-ride`, player front and NPC rear), landing, recall, dismount, `fly-to`, and combat assist.

`mc_query_knowledge` is local-only. It searches packaged records plus local journal observations and must not browse public websites, read arbitrary files, or upload screenshots, logs, API keys, account data, local paths, prompts, or world saves. `mc_list_facilities` and `mc_register_facility` also accept only structured Minecraft data such as type, name, dimension, position, tags, owner, and small metadata.

## Antigravity Free-Chat Inbox

The installed companion app normally uses its local Agent bridge to wake the exact bound Antigravity conversation automatically. It reuses the stored conversation, serializes messages, rotates only after the configured local round or prompt-size limit, and persists the new binding. Do not combine automatic triggering with a second long-poll loop, and do not create one conversation per message.

The polling contract below is the manual MCP fallback when the app reports that automatic triggering is unavailable.

`mc_list_chat_messages` accepts `afterSequence` (a non-negative integer, default `0`), `limit` (from `1` to `100`, default `50`), and `waitSeconds` (from `0` to `30`, default `0`). For a live companion loop, use `waitSeconds: 30` so the call waits for new chat without rapid empty polling. It returns:

- `settings`: the current free-chat routing, selected `playerName`, target, and persona configuration.
- `messages`: new messages whose sequence is greater than `afterSequence`.
- `nextSequence`: the cursor to pass as the next `afterSequence`.

Only ordinary messages that match the configured player and the `antigravity-mcp` target enter this inbox. Directed `@codex` messages are handled by the active Codex/Claude provider, while exact stop phrases are handled locally before inbox delivery. After reading a message, select a connected companion and call `mc_chat` to reply.

Keep the manual Antigravity agent or companion run alive while long-polling. The fallback MCP transport does not push notifications and cannot wake a closed session by itself. Avoid replaying old messages by retaining `nextSequence` for the lifetime of the polling run and passing it as the next `afterSequence`.

When `settings.persona.mode` is `inherit`, keep the Antigravity agent's existing persona. When it is `custom`, treat non-empty `displayName`, `personality`, `speakingStyle`, and `memoryNotes` values as a Minecraft-specific overlay on that existing persona, not as a replacement.

## Task Kinds

- `follow`: `player`, optional `distance`.
- `guard`: `player`, optional `radius`.
- `move`: absolute `target` with `x`, `y`, and `z`.
- `gather`: a namespaced/tagged `itemId`, positive `count`, optional `movement`, and optional `countMode`. Omit `countMode` or use `acquire` when the NPC must obtain that many new items; use `inventory-total` only for a prerequisite such as "carry at least 64 logs", where matching items already in the backpack count toward the target.
- `craft`, `smelt`: namespaced `itemId` and positive `count`.
- `provision-food`: optional `count` (default `8`), `source` (`auto`, `forage`, or `hunt`), `destination` (`backpack`, `player`, or `home-storage`), and a player reference for physical delivery. It searches safely over an expanding range, cooks supported raw food, then keeps, throws, or stores the requested count.
- `ranch`: `action` (`establish`, `breed`, or `cull`), optional `animalType` (`any`, `minecraft:pig`, `minecraft:cow`, or `minecraft:sheep`), `count`, and `radius`. Use `life.establish-ranch` when a new pen must be built before animals are led home.
- `farm`: namespaced `cropId`, `action` (`plant`, `harvest`, or `cycle`), optional `radius`.
- `store`: optional `itemId` and `count`.
- `explore`: `radius` and optional cardinal `direction`.
- `combat`: `targetType` and optional `maxDistance`. Never use `unknown` as a hostile target policy.
- `dragon`: `action` (`observe`, `feed`, `heal`, `tame`, `follow`, `stay`, `mount`, `share-ride`, `dismount`, `care-for-egg`, `recall`, `assist-combat`, `land`, or `fly-to`) and optional `targetId`.
- `build`: confirmed `planId`.
- `macro`: declarative `skillId` and JSON `arguments`.

Every task accepts `requestedBy` and an optional short `note`.

## Declarative Skills

Use `mc_list_skills` before assigning a `macro`. A skill contains typed parameters and one to 64 sequential task templates. `${parameter}` placeholders preserve number and boolean types when they occupy an entire field. Learned skills may not contain nested macros or executable code, and every rendered step is checked against the normal task schema, companion capabilities, lease, and build-confirmation policy.

## Multi-Companion Coordination

Use one owner string for a coordinated operation and acquire each companion separately. Assign independent tasks that do not compete for the same blocks, container, target, or narrow path. The default operating limit is one modded client companion plus three vanilla Mineflayer workers.

## Build Payload

`mc_preview_build` accepts a `name`, `source`, absolute `origin`, and normalized `blocks`. Each block has a relative `position`, namespaced `blockId`, and optional string `properties`. Inspect the returned `size`, `requiredItems`, and `confirmed` fields before asking for confirmation.

`mc_import_build` accepts a `name`, `source`, absolute `origin`, and exactly one of `filePath` or `dataBase64`. Valid sources are `json`, `schem`, `litematic`, `pixel-art`, and `reference-image`. PNG imports may also set the `xy` or `xz` plane, maximum width and height, alpha threshold, and an optional block-to-hex-color palette. Importing only creates a preview: review its bounds and materials, then call `mc_confirm_build`, and finally assign a `build` task with the confirmed `planId`.
