---
name: play-minecraft
description: Control independent Minecraft companion players through the Minecraft Codex Companion MCP server. Use for free-chat inbox replies, in-game chat, observation, following, travel, gathering, crafting, smelting, farming, storage, combat, dragon care, blueprint or image-based building, declarative learned routines, controller handoff, and multi-bot coordination in Forge, NeoForge, or vanilla worlds.
---

# Play Minecraft

Operate as an independent companion player. Plan and converse at human time scales; leave movement, combat reactions, navigation, and survival ticks to the local backend.

## Core Workflow

1. Call `mc_list_companions` and choose a connected companion with the required capability.
2. For high-level survival requests, prefer the local Agent v2 route: call `mc_submit_goal`, inspect `mc_get_plan`, then call `mc_advance_goal`. This creates a recoverable WorkGraph with local knowledge and facility memory.
3. For immediate control, safety, or one-step work, call `mc_acquire_control` with a stable owner name. Never force takeover unless the user explicitly requests it.
4. Call `mc_observe` before assigning direct work and again whenever the world may have changed materially.
5. Call `mc_assign_task` only for one typed low-level task, or when `mc_advance_goal` has queued the next task for you. Keep direct tasks small enough to monitor and recover.
6. Poll `mc_get_task` only when the caller explicitly needs a manual status check. Long tasks keep running locally and send terminal progress back to Minecraft.
7. Use `mc_chat` for concise progress and questions in the player's current language; default to Simplified Chinese when the language is unclear.
8. Call `mc_release_control` when handing the companion to another controller.

## Local Agent v2 Workflow

Use Agent v2 for requests that require planning, missing-material resolution, facilities, storage, mining, building, farming, ranching, or multi-step recovery. Examples: `给我做一把钻石镐`, `建造田地`, `去找些食物`, `我要64个火把`, `箱子里有铁`, `停止目标回来`, `一起骑龙`, `继续上次失败的建造`.

Recommended sequence:

1. `mc_submit_goal` with a short `title`, the full player `objective`, `requestedBy`, `source`, `mode`, constraints, and optional `deliverTo`. Do not include API keys, local file paths, screenshots, logs, or provider prompts.
2. Inspect the returned plan. If it contains `await_plan` blocked, ask a concise clarifying question or fall back to a safe direct typed task only when the user's intent is unambiguous.
3. Call `mc_advance_goal` to run local-only nodes and queue the next real task through the single-writer executor.
4. Use `mc_get_goal` / `mc_get_plan` for status. Use `mc_pause_goal`, `mc_resume_goal`, or `mc_cancel_goal` when the player says pause, continue, stop, or replace the objective.
5. Use `mc_query_knowledge` for local gameplay facts and `mc_list_facilities` for remembered homes, workstations, farms, ranches, mines, storage, builds, redstone facilities, and dragon landing areas.

Do not bypass Agent v2 by decomposing a complex player request into unrelated one-off `mc_assign_task` calls. The WorkGraph is responsible for prerequisites, checkpoint recovery, facility reuse, and not restarting from scratch after failure.

Agent v2 currently covers local planning for diamond-pickaxe deep mining, torch preparation with nearby-coal priority, bed crafting and placement, crop farms with hoe/bucket/water prerequisites, farm maintenance, ranch establishment and operations, food/meat provisioning, storage organization and fetch/delivery, common crafting requests, iron equipment, blueprint builds, cobblestone generators, mob farms, tree farms, and Book of Dragons / Saints Dragons actions including shared riding.

When the companion app reports that Antigravity automatic triggering is ready, let the local Agent bridge wake the exact bound conversation. It reuses that conversation until the configured local size limit, then rotates once and persists the new binding. Do not start a second polling loop or create a conversation per Minecraft message.

Use `mc_list_chat_messages` with `waitSeconds: 30` only as the manual MCP fallback when automatic triggering is unavailable. Pass the returned `nextSequence` as the next `afterSequence`, then answer relevant messages with `mc_chat`. Keep that manual run alive because the fallback MCP transport itself is pull-based.

Read [references/tools.md](references/tools.md) when composing unfamiliar task payloads or coordinating multiple companions.

## Immediate Safety Rules

- Treat direct stop phrases as local commands. Call `mc_emergency_stop` immediately without asking a model to interpret them.
- Preview every build with `mc_preview_build`. Review bounds, block count, origin, and materials. Call `mc_confirm_build` only after user confirmation when the permission profile requires it.
- Never break containers or alter a large area unless the active permission profile explicitly permits it.
- Treat unknown modded entities as unknown or neutral, never hostile by default.
- Avoid PvP unless both the permission profile and the user's current request allow it.
- Maintain one writer lease per companion. Coordinate workers through separate companion IDs.
- Store learned routines as declarative task macros. Never generate or execute arbitrary learned code.

## Survival And Combat

Observe health, food, air, equipment, terrain, nearby entities, and escape routes before combat or exploration. Prefer recovery, lighting, shelter, food, and retreat over continuing an unsafe task. Use local guard/combat behavior for reaction speed and Codex for target policy and longer plans.

Treat `去找些食物`, requests to find food, and requests to prepare provisions as real actions. Assign `provision-food` with `count: 8` and `source: auto` unless the player specifies a count or explicitly asks for forage-only or hunting. Default to `destination: backpack`; `给我找些食物` or a trailing `给我` means `destination: player`, while a request to put the result in a home chest means `destination: home-storage`. Do not replace it with generic gathering or delivery, and do not merely acknowledge it in chat. The local task forages over an expanding range, safely hunts only eligible adult livestock when allowed, cooks raw food through real furnace interactions, and then performs the requested physical delivery or storage.

For a request to build a pen and bring back pigs, cows, or sheep, use the built-in `life.establish-ranch` macro. Direct follow-up management may use `ranch` with `action: breed` or `action: cull`. Do not generate animals, move protected livestock, teleport while leading an animal, or alter unrelated player pens.

## Dragons And Modded Entities

Observe first. Identify the entity, ownership, health, disposition, and supported bridge capability before feeding, healing, taming, mounting, or issuing follow/stay behavior. If identity or mod semantics are uncertain, describe what is visible and ask instead of guessing an interaction.

## Building

Normalize schematics, Litematica files, JSON plans, pixel art, or reference images into a preview. Confirm the origin and orientation, calculate materials, and check for protected or occupied blocks. Split large builds into recoverable phases and monitor each phase as a normal task.

## Conversation

Use the language of the player's current message for short Minecraft chat replies, and default to Simplified Chinese only when the language is unclear. State what is happening, what completed, or the one decision needed from the player. Do not narrate implementation details, prompts, tools, or internal reasoning in game.

The player may press `T` and send an ordinary message when free chat is enabled. The control service filters ordinary messages by `playerName` and routes them either to the active Codex/Claude provider or to the Antigravity MCP inbox. Directed `@codex`, `codex:`, and `codex ` messages remain available independently; they are handled by the active Codex/Claude provider rather than the Antigravity inbox. Exact stop phrases such as `停`, `急停`, and `stop` take priority and must never be treated as casual conversation.

Respect the persona in the returned chat settings:

- `persona.mode = "inherit"` is the default. Preserve the current provider or Antigravity agent persona; do not invent or replace it merely because Minecraft MCP is connected.
- `persona.mode = "custom"` supplies a Minecraft-specific overlay through `displayName`, `personality`, `speakingStyle`, and `memoryNotes`. Apply non-empty fields on top of the current base persona, never as a reset of that persona.
