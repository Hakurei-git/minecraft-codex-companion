# Minecraft Codex Companion

[简体中文](README.zh-CN.md)

Minecraft Codex Companion is a local AI companion system for Minecraft. The Forge 1.20.1 mod creates an independent, visible NPC in a single-player world. Codex, Claude-compatible APIs, and Antigravity MCP can share that actor to observe the world, chat, and run validated game-side tasks.

Movement, gathering, crafting, smelting, storage, combat, dragon care, and construction are performed by the game-side executor. AI is used for conversation and optional high-level planning; it does not operate the game through screen-coordinate macros.

### Downloads

GitHub Releases provide two editions:

- `MinecraftCodexCompanion-Setup.exe`: the complete single-EXE edition for normal Windows users. It installs the local runtime and Forge bridge without requiring Node.js or a manual mod build.
- `MinecraftCodexCompanion-AgentKit-v0.1.0.zip`: a small Skill and MCP import package for supported AI clients. It contains instructions and a loopback MCP example, not the game runtime. The EXE-installed control service and Minecraft bridge must still be running on the same PC.

The installer does not embed or migrate accounts, API keys, Antigravity conversations, Minecraft worlds, or machine-specific paths.

### Compatibility and automatic discovery

On first launch, the portable app checks a bounded set of user-level locations for:

- `HMCL*.exe` or `HMCL*.jar` at the top level of `Desktop`, `Downloads`, or `OneDrive\Desktop`;
- a `.minecraft` directory with `versions`, next to HMCL or under `%APPDATA%`;
- the standard Antigravity `.gemini\antigravity\mcp_config.json` path in the current user profile.

The app does not recursively scan drives or read account files. Incorrect or missing results can be replaced with the Browse controls. `MC_HMCL_PATH`, `MC_MINECRAFT_ROOT`, and `MC_ANTIGRAVITY_CONFIG_PATH` are optional explicit overrides.

Full live acceptance currently covers **HMCL with a Forge 1.20.1 single-player world**. HMCL Microsoft-account login and the official Minecraft Launcher have not completed live acceptance, so this release does not claim support for those flows.

### Tested mod integrations

The Forge bridge contains explicit adapters for these optional third-party dragon mods:

| Mod | Mod ID | Live-tested Minecraft 1.20.1 build | Integrated behavior |
| --- | --- | --- | --- |
| Book of Dragons | `bookofdragons` | `bookofdragons-1.31-1.20.1` | Observe ownership/state, feed, heal, tame, egg care, follow/stay, mount/dismount, shared riding, flight, landing, recall, terrain recovery, and combat assistance |
| Saints Dragons | `saintsdragons` | `saintsdragons-0.8.2+forge-1.20.1-alpha` | Observe ownership/state, feed, heal, tame, egg care, follow/stay, mount/dismount, shared riding, flight, landing, recall, terrain recovery, and combat assistance |

These third-party mod JARs are **not bundled** in either release asset. The EXE preserves compatible mods already present in the selected HMCL source instance when it creates the isolated clone. The versions above are the live-tested compatibility targets; other releases may change their internal entity APIs and are not claimed as verified.

### Language support

- The Minecraft mod ships `zh_cn` and `en_us` language files and follows the selected Minecraft language for its localized messages.
- AI free chat can converse in Chinese or English depending on the configured model, provider, and persona.
- The machine-facing AgentKit Skill is written in English and its user guide is bilingual.
- The Dashboard, portable setup UI, and deterministic local T-chat action phrases are currently Chinese-first. Complex English action requests should use Smart AI. The current release is **not** advertised as a fully localized bilingual UI.

### AI entry points

| Entry | Purpose | Configuration |
| --- | --- | --- |
| Codex | Reply to Minecraft chat and invoke validated Minecraft tools | Use the local Codex login or add a Codex-compatible API profile |
| Claude | Reply to chat and invoke validated tools | Add an Anthropic Messages API-compatible Base URL, model ID, and API key |
| Antigravity | Control Minecraft through the local MCP server and reuse an existing bound conversation | Merge the generated MCP entry and bind the exact conversation title |

Custom Codex endpoints and Claude-compatible endpoints use different protocols. A service that only supports OpenAI Chat Completions cannot be used through the Claude entry. API keys are stored only in the local state directory and protected with Windows DPAPI; the Dashboard never returns their plaintext values in normal responses.

### Smart AI and deterministic local mode

`Task understanding` can be changed at any time:

- `Smart AI enabled`: free-form and compound goals are converted into one structured request within a configurable token budget. The local executor still validates actors, permissions, arguments, recipes, safety rules, and delivery targets.
- `Smart AI disabled`: known action phrases use deterministic local parsers and consume no planning tokens. Gathering, delivery, crafting, building menus, follow, recall, stop, and the deep-mining diamond chain remain available. Unrecognized complex requests fail clearly instead of pretending to execute.

Free chat is a separate switch. Disabling Smart AI does not disable ordinary AI conversation when free chat remains enabled.

**Token cost:** Smart AI adds a model-planning call for a complex or otherwise unrecognized action request. The selected provider may bill both input tokens (the player request plus a minimized world/task snapshot) and output tokens (the structured decision). The configured output budget limits the requested response size but does not make the call free and may not cap provider-side input billing. Explicit multi-agent mode can use separate adviser calls plus a coordinator call, so it normally costs more than single-agent planning. Free chat also consumes model tokens independently whenever an AI provider answers. With Smart AI disabled, recognized deterministic actions use no planning-model tokens, but enabled free chat may still consume tokens.

### Basic setup from source

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

### HMCL isolated instance

Do not test by modifying a normal modpack instance or an important world. The installer creates a separate Forge 1.20.1 clone and does not copy `saves`, `logs`, or `screenshots`. Start the cloned instance and create a disposable test world first.

The Forge NPC has independent health, hunger, equipment, and inventory. Right-click to open its inventory; sneak-right-click toggles follow and stay. Dashboard and MCP controls also provide summon, recall, follow, and stay actions.

### In-game chat

When free chat is disabled, directed prefixes remain available:

```text
@codex <request>
@claude <request>
@multi-agent <request>
@antigravity <request>
```

When free chat is enabled, the configured player may press `T` and speak normally. Exact `stop`, `halt`, or emergency-stop messages bypass AI and cancel tasks locally. Chinese deterministic phrases cover the broadest set of direct actions in this release; English free-form actions should use Smart AI.

### Capabilities

- Observe position, health, hunger, equipment, inventory, blocks, nearby entities, task state, and bounded item transaction history.
- Follow, guard, move, explore, gather whole trees or ore clusters, craft, smelt, farm, fish, sleep, eat, drop items, and store or retrieve items.
- Resolve recipe prerequisites recursively, including lower-tier tools, crafting tables, furnaces, fuel, safe raw-material gathering, return, and physical delivery.
- Persist active tasks, paused work, storage operations, deep-mining checkpoints, and recoverable construction failure points across bridge, control-service, and Minecraft restarts.
- Import staged JSON, Sponge `.schem`, Litematica `.litematic`, and PNG build plans after preview and confirmation.
- Support the audited `bookofdragons` and `saintsdragons` integrations for care, following, riding, shared seating, terrain recovery, landing, recall, and combat assistance.

All operations remain subject to Minecraft permissions, protection events, world safety rules, reachability, and available resources. The executor reports a failure rather than generating items from nothing.

### Security and verification

```powershell
npm test
npm run typecheck
npm run release:single-exe
npm run release:agent-kit
```

Release builds verify package integrity and perform local privacy scans. Published artifacts must not contain API keys, Base URL profiles, bridge tokens, local state, account files, conversations, Minecraft worlds, logs, screenshots, or absolute build-machine paths. The development EXE is currently not Authenticode-signed; verify its published SHA-256 before running it.
