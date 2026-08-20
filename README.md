# Minecraft Codex Companion

<p align="center">
  <img src="assets/branding/app-icon.png" alt="Minecraft Codex Companion application icon" width="180">
</p>

<p align="center">
  <strong>A local-first Minecraft Forge 1.20.1 AI NPC companion for Codex, Claude-compatible APIs, and Antigravity MCP.</strong>
</p>

<p align="center">
  <a href="https://github.com/Hakurei-git/minecraft-codex-companion/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Hakurei-git/minecraft-codex-companion?display_name=tag&amp;sort=semver"></a>
  <a href="https://github.com/Hakurei-git/minecraft-codex-companion/releases"><img alt="GitHub release downloads" src="https://img.shields.io/github/downloads/Hakurei-git/minecraft-codex-companion/total"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/Hakurei-git/minecraft-codex-companion"></a>
  <img alt="Minecraft Forge 1.20.1" src="https://img.shields.io/badge/Minecraft%20Forge-1.20.1-4f7f35">
  <img alt="Local Model Context Protocol server" src="https://img.shields.io/badge/MCP-local--first-6f42c1">
</p>

<p align="center">
  <a href="https://github.com/Hakurei-git/minecraft-codex-companion/releases/latest"><strong>Download</strong></a>
  · <a href="#two-minute-setup">Two-minute setup</a>
  · <a href="#capabilities">Capabilities</a>
  · <a href="#in-game-chat">T-chat</a>
  · <a href="README.zh-CN.md">简体中文</a>
</p>

Minecraft Codex Companion is a local AI companion system for Minecraft. The Forge 1.20.1 mod creates an independent, visible NPC in a single-player world. Codex, Claude-compatible APIs, and Antigravity MCP can share that actor to observe the world, chat, and run validated game-side tasks.

Movement, gathering, crafting, smelting, storage, combat, dragon care, and construction are performed by the game-side executor. AI is used for conversation and optional high-level planning; it does not operate the game through screen-coordinate macros.

## Why use it?

| Goal | What the companion does |
| --- | --- |
| Talk naturally | Replies through Minecraft `T` chat with optional free chat and configurable personas |
| Play instead of pretending | Moves, gathers, crafts, builds, fights, farms, stores items, and physically delivers results |
| Finish multi-step work | Resolves missing tools, workstations, materials, food, inventory space, and safe return paths |
| Survive interruptions | Pauses for combat or protection, resumes work, and restores supported tasks after restarts |
| Choose the AI cost | Runs recognized action chains locally, or enables Smart AI for complex language at additional token cost |
| Reuse your tools | Connects Codex, Claude-compatible providers, or a bound Antigravity conversation through local MCP |

## Download

GitHub Releases provide two editions:

- **[Windows Setup EXE](https://github.com/Hakurei-git/minecraft-codex-companion/releases/latest)**: the complete edition for normal Windows users. It installs the local runtime and Forge bridge without requiring Node.js or a manual mod build.
- **[AgentKit ZIP](https://github.com/Hakurei-git/minecraft-codex-companion/releases/tag/v0.1.9)**: a small Skill and MCP import package for supported AI clients. It contains instructions and a loopback MCP example, not the game runtime. The EXE-installed control service and Minecraft bridge must still be running on the same PC.

The installer does not embed or migrate accounts, API keys, Antigravity conversations, Minecraft worlds, or machine-specific paths.

## Two-minute setup

1. Download the Windows Setup EXE from the latest release and verify the published SHA-256.
2. Select the detected HMCL launcher and Forge 1.20.1 source instance, then choose your player name, NPC name, persona, and optional 128×64 skin.
3. Create the isolated companion instance, launch it from HMCL, and enter a new disposable single-player world for the first check.
4. Open the local Dashboard, select Codex, a Claude-compatible provider, or Antigravity MCP, and configure free chat and optional Smart AI. Press `T` in Minecraft to start talking or assigning work.

## Compatibility and automatic discovery

On first launch, the portable app checks a bounded set of user-level locations for:

- `HMCL*.exe` or `HMCL*.jar` at the top level of `Desktop`, `Downloads`, or `OneDrive\Desktop`;
- a `.minecraft` directory with `versions`, next to HMCL or under `%APPDATA%`;
- the standard Antigravity `.gemini\antigravity\mcp_config.json` path in the current user profile.

The app does not recursively scan drives or read account files. Incorrect or missing results can be replaced with the Browse controls. `MC_HMCL_PATH`, `MC_MINECRAFT_ROOT`, and `MC_ANTIGRAVITY_CONFIG_PATH` are optional explicit overrides.

Full live acceptance currently covers **HMCL with a Forge 1.20.1 single-player world**. HMCL Microsoft-account login and the official Minecraft Launcher have not completed live acceptance, so this release does not claim support for those flows.

## Tested mod integrations

The Forge bridge contains explicit adapters for these optional third-party dragon mods:

| Mod | Mod ID | Live-tested Minecraft 1.20.1 build | Integrated behavior |
| --- | --- | --- | --- |
| Book of Dragons | `bookofdragons` | `bookofdragons-1.31-1.20.1` | Observe ownership/state, feed, heal, tame, egg care, follow/stay, mount/dismount, shared riding, flight, landing, recall, terrain recovery, and combat assistance |
| Saints Dragons | `saintsdragons` | `saintsdragons-0.8.2+forge-1.20.1-alpha` | Observe ownership/state, feed, heal, tame, egg care, follow/stay, mount/dismount, shared riding, flight, landing, recall, terrain recovery, and combat assistance |

These third-party mod JARs are **not bundled** in either release asset. The EXE preserves compatible mods already present in the selected HMCL source instance when it creates the isolated clone. The versions above are the live-tested compatibility targets; other releases may change their internal entity APIs and are not claimed as verified.

## Language support

- The Minecraft mod ships `zh_cn` and `en_us` language files and follows the selected Minecraft language for its localized messages.
- AI free chat can converse in Chinese or English depending on the configured model, provider, and persona.
- The machine-facing AgentKit Skill is written in English and its user guide is bilingual.
- The Dashboard, portable setup UI, and deterministic local T-chat action phrases are currently Chinese-first. Complex English action requests should use Smart AI. The current release is **not** advertised as a fully localized bilingual UI.

## AI entry points

| Entry | Purpose | Configuration |
| --- | --- | --- |
| Codex | Reply to Minecraft chat and invoke validated Minecraft tools | Use the local Codex login or add a Codex-compatible API profile |
| Claude | Reply to chat and invoke validated tools | Add an Anthropic Messages API-compatible Base URL, model ID, and API key |
| Antigravity | Control Minecraft through the local MCP server and reuse an existing bound conversation | Merge the generated MCP entry and bind the exact conversation title |

Custom Codex endpoints and Claude-compatible endpoints use different protocols. A service that only supports OpenAI Chat Completions cannot be used through the Claude entry. API keys are stored only in the local state directory and protected with Windows DPAPI; the Dashboard never returns their plaintext values in normal responses.

## Smart AI and deterministic local mode

`Task understanding` can be changed at any time:

- `Smart AI enabled`: free-form and compound goals are converted into one structured request within a configurable token budget. The local executor still validates actors, permissions, arguments, recipes, safety rules, and delivery targets.
- `Smart AI disabled`: known action phrases use deterministic local parsers and consume no planning tokens. Gathering, delivery, crafting, building menus, follow, recall, stop, and the deep-mining diamond chain remain available. Unrecognized complex requests fail clearly instead of pretending to execute.

Free chat is a separate switch. Disabling Smart AI does not disable ordinary AI conversation when free chat remains enabled.

**Token cost:** Smart AI adds a model-planning call for a complex or otherwise unrecognized action request. The selected provider may bill both input tokens (the player request plus a minimized world/task snapshot) and output tokens (the structured decision). The configured output budget limits the requested response size but does not make the call free and may not cap provider-side input billing. Explicit multi-agent mode can use separate adviser calls plus a coordinator call, so it normally costs more than single-agent planning. Free chat also consumes model tokens independently whenever an AI provider answers. With Smart AI disabled, recognized deterministic actions use no planning-model tokens, but enabled free chat may still consume tokens.

## Basic setup from source

Requirements: Node.js 24+, PowerShell 5.1+, Java 17 for Forge 1.20.1, and Java 21 for the optional NeoForge 1.21.1 source build.

```powershell
npm install
npm run build
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-companion.ps1 -SkipBuild -OpenDashboard
```

The control service listens on loopback by default:

- Dashboard: `http://127.0.0.1:8765`
- MCP: `http://127.0.0.1:8765/mcp`
- Game bridge: `ws://127.0.0.1:8765/bridge`

## HMCL isolated instance

Do not test by modifying a normal modpack instance or an important world. The installer creates a separate Forge 1.20.1 clone and does not copy `saves`, `logs`, or `screenshots`. Start the cloned instance and create a disposable test world first.

The Forge NPC has independent health, hunger, equipment, and inventory. Right-click to open its inventory; sneak-right-click toggles follow and stay. Dashboard and MCP controls also provide summon, recall, follow, and stay actions.

## In-game chat

When free chat is disabled, directed prefixes remain available:

```text
@codex <request>
@claude <request>
@multi-agent <request>
@antigravity <request>
```

When free chat is enabled, the configured player may press `T` and speak normally. Exact `stop`, `halt`, or emergency-stop messages bypass AI and cancel tasks locally. Chinese deterministic phrases cover the broadest set of direct actions in this release; English free-form actions should use Smart AI.

If Antigravity stops replying after a network or provider-location error, type `恢复反重力` or `重连反重力` in Minecraft `T` chat. This local command clears the stale conversation state and retry backoff without invoking the model. Location failures use a visible 30-second backoff; messages during it receive a status reply, and the next message after expiry automatically probes the provider again.

The Antigravity bridge binds the exact configured conversation title and persists that conversation ID across app updates and restarts. It does not rotate on a locally estimated turn or character count by default; it creates a numbered successor only after Antigravity explicitly reports that the real context capacity is exhausted. Optional local limits are available through `MC_ANTIGRAVITY_MAX_TURNS` and `MC_ANTIGRAVITY_MAX_PROMPT_CHARACTERS`.

### Remembering a player-built home

The bed/respawn point is the stable home anchor. The companion keeps two related records:

- **House bounds** describe the physical indoor area. A bounded roofed-space scan is attempted first; if it cannot identify a closed room, the safe fallback is the full 24-block home circle.
- **Home circle** is a 24-block radius around the normalized bed foot. Chests, crafting tables, furnaces, and other home services are searched there. Crop farms and livestock pens remain separate facility records even when they overlap that circle.

For an irregular or player-built house, record the boundary from Minecraft `T` chat without using an AI provider:

1. Stand at one outside corner and send `记录房屋第一个角`.
2. Walk diagonally to the opposite outside corner and send `记录房屋第二个角`.
3. If the bed or roof changed, send `重新识别我的房屋范围` to refresh the automatic scan.

The two-corner command stores a conservative rectangular boundary, uses the current bed anchor, and is persisted in the local facility journal. A later snapshot will not overwrite a manual boundary unless the bed moves outside the old 24-block home circle or the player explicitly rescans. No world save, API key, conversation content, or external file is uploaded for this operation.

### Home-compound building placement

Automatic construction measures the shortest horizontal gap from the **complete blueprint bounds** to the remembered **house bounds**. It does not measure from the NPC, bed, or blueprint origin:

- residential buildings: 8-24 blocks outside the house;
- production facilities such as crop farms, ranches, animal pens, tree farms, and watchtowers: 16-40 blocks;
- industrial facilities such as mob farms and cobblestone generators: 40-64 blocks.

Every candidate keeps at least 12 blocks of clearance from remembered facilities. The Forge executor rechecks terrain and protected blocks before and during construction, may perform only bounded light preparation, and fails without placing blocks when no safe site exists. It never silently expands the search to a remote 96-160-block site. Existing distant farms and ranches are preserved as secondary outposts; ordinary requests use a new home-primary facility, while an explicit request for the old or remote facility may reuse it. Player-specified coordinates and confirmed plan origins remain authoritative.

## Capabilities

- Observe position, health, hunger, equipment, inventory, blocks, nearby entities, task state, and bounded item transaction history.
- Follow, guard, move, explore, gather whole trees or ore clusters, craft, smelt, farm, fish, sleep, eat, drop items, and store or retrieve items.
- Resolve recipe prerequisites recursively, including lower-tier tools, crafting tables, furnaces, fuel, safe raw-material gathering, return, and physical delivery.
- When home storage is full, recursively obtain materials, craft and physically place a crafting table and chest, then resume the same storage task. Placement uses player-equivalent Forge events rather than direct world edits.
- Persist active tasks, paused work, storage operations, deep-mining checkpoints, and recoverable construction failure points across bridge, control-service, and Minecraft restarts.
- Import staged JSON, Sponge `.schem`, Litematica `.litematic`, and PNG build plans after preview and confirmation.
- Support the audited `bookofdragons` and `saintsdragons` integrations for care, following, riding, shared seating, terrain recovery, landing, recall, and combat assistance.

All operations remain subject to Minecraft permissions, protection events, world safety rules, reachability, and available resources. The executor reports a failure rather than generating items from nothing.

## Security and verification

```powershell
npm test
npm run typecheck
npm run release:single-exe
npm run release:agent-kit
```

Release builds verify package integrity and perform local privacy scans. Published artifacts must not contain API keys, Base URL profiles, bridge tokens, local state, account files, conversations, Minecraft worlds, logs, screenshots, or absolute build-machine paths. The development EXE is currently not Authenticode-signed; verify its published SHA-256 before running it.

## Community and contributing

- Ask setup and usage questions in [GitHub Discussions](https://github.com/Hakurei-git/minecraft-codex-companion/discussions).
- Report reproducible bugs with the structured [issue form](https://github.com/Hakurei-git/minecraft-codex-companion/issues/new/choose).
- Read [CONTRIBUTING.md](CONTRIBUTING.md) before sending code, documentation, translations, blueprints, or compatibility fixes.
- Report vulnerabilities through [SECURITY.md](SECURITY.md), not a public issue.
