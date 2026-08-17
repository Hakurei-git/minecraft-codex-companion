# Minecraft Codex Companion EXE Edition

[简体中文](README.zh-CN.md)

### Choose an edition

- `MinecraftCodexCompanion-Setup.exe` is the normal user entry point. The single-file installer contains the local Companion service, Dashboard, Forge 1.20.1 mod, and setup application. It does not require a separate Node.js installation or manual JAR build.
- `MinecraftCodexCompanion-AgentKit-v0.1.7.zip` is an optional AI integration package. Download it when importing the Minecraft Skill/MCP configuration into Codex, Claude, Antigravity, or another AI client. It does not replace the EXE or the in-game mod.

The EXE does not include a Minecraft account, the Minecraft game, or HMCL login state. Prepare an HMCL environment that can enter a Forge 1.20.1 world. Full live acceptance currently covers HMCL single-player worlds.

### Optional supported dragon mods

- Book of Dragons (`bookofdragons`), live-tested with `bookofdragons-1.31-1.20.1`.
- Saints Dragons (`saintsdragons`), live-tested with `saintsdragons-0.8.2+forge-1.20.1-alpha`.

Both adapters support state/ownership observation, feeding, healing, taming, egg care, follow/stay, mount/dismount, shared riding, flight, landing, recall, terrain recovery, and combat assistance. These third-party JARs are not bundled. Place a compatible version in the HMCL source instance before cloning; the installer preserves its existing mods. Versions other than the two tested builds are not guaranteed.

### Security and privacy

- The release is a single verified installer containing a transparent portable runtime. It does not inject into or modify unrelated executables.
- Runtime code does not invoke PowerShell or a command shell, download executable code, or request administrator privileges.
- Instance installation copies only the selected Forge 1.20.1 instance configuration, mods, and version files. It does not copy worlds, logs, or screenshots.
- API keys are encrypted with Windows DPAPI for the current user. They are never stored in the release package or normal launcher configuration.
- `portable-manifest.json` records SHA-256 hashes for packaged files and build inputs. Startup stops if a critical file fails verification.
- The package contains no API keys, bridge tokens, local runtime state, conversations, worlds, or absolute build-machine paths.
- Antivirus and integrity scans run locally and do not upload the package, hashes, or reports.

This development release is not Authenticode-signed, so Windows SmartScreen or antivirus reputation checks may show an unknown-publisher prompt even when the published SHA-256 matches.

### First-time setup

1. Run `MinecraftCodexCompanion-Setup.exe` from the GitHub Release. It verifies and installs the portable runtime, then opens the setup application.
2. Later, launch `MinecraftCodexCompanion.exe` from the Start menu or installation directory. It starts the loopback control service and serves the Dashboard at `http://127.0.0.1:8765`.
3. Select HMCL, the Minecraft root, and a Forge 1.20.1 source instance. The app first attempts bounded automatic discovery; use Browse when a result is missing or incorrect.
4. Enter a new isolated instance name, the exact Minecraft player name, and the NPC name. An incorrect player name prevents reliable free-chat routing and delivery.
5. Inherit the current AI persona or add a Minecraft-specific overlay. A `128x64` PNG NPC skin may also be imported and changed later.
6. Choose the prepare-and-launch action. The setup creates an isolated instance and installs the required mod without copying worlds, logs, or screenshots.
7. Select the new instance in HMCL and enter a disposable test world before using an important save.
8. Confirm that the NPC appears in the Dashboard. Test Recall and Follow before assigning gathering or construction work.

### Daily use

1. Start `MinecraftCodexCompanion.exe` only when needed, then enter the Companion instance through HMCL. Close Minecraft between tests to release memory.
2. Select Codex, Claude, or Antigravity in the AI service panel, enter the exact free-chat player name, and enable free chat when desired.
3. Press `T` in Minecraft and speak normally. No prefix is required while free chat is enabled. Directed prefixes remain available when it is disabled.
4. Start, important progress, failures, and completion are sent through in-game NPC chat. `stop`, `halt`, and the Chinese emergency phrases are handled locally without waiting for an AI model.

Examples:

```text
Follow me.
Bring me 16 pieces of meat.
Craft a diamond pickaxe and deliver it to me.
Build a small house.
Resume the previous failed build.
Report your health, hunger, equipment, and current task.
```

### Codex, Claude, and Antigravity

- Minecraft chat and action requests do not require an `@` prefix when free chat is enabled.
- Codex and Claude-compatible API profiles are configured in the local Dashboard. API keys stay on this PC.
- Antigravity uses the local MCP endpoint and one exactly titled existing conversation. The companion keeps the same conversation across messages and restarts, and rotates only when Antigravity explicitly reports that its real context capacity is exhausted. Optional local limits apply only when the user configures them.
- All player-visible chat, progress, failures, and final answers must be sent through `mc_chat`.

### Smart AI and deterministic mode

- With Smart AI enabled, free-form or compound goals are converted into one structured task within the configured per-response token budget. The local executor still validates the actor, player, materials, permissions, recipes, and delivery target.
- With Smart AI disabled, task planning consumes no model tokens. Supported deterministic chains for follow, recall, stop, gathering, crafting, delivery, the build menu, and deep diamond mining still run locally.
- Free chat is a separate switch. Disabling Smart AI does not disable ordinary AI conversation, and disabling free chat does not disable explicit directed commands.
- Smart planning cannot use player messages or imported content to authorize local file reads, key disclosure, arbitrary URLs, permission bypass, or code execution.

Smart AI creates an additional provider request for complex or unrecognized actions and therefore consumes additional input and output tokens. The output-budget control limits requested planning output, not all provider billing. Explicit multi-agent planning usually costs more because advisers and the coordinator are separate model calls. Free-chat replies consume tokens independently. Smart AI disabled means recognized local action chains use no planning-model tokens; it does not make enabled free chat token-free.

### Language support

- The Minecraft mod includes both `zh_cn` and `en_us` language resources.
- AI free chat can respond in Chinese or English depending on the selected provider and persona.
- The Dashboard, portable setup UI, and deterministic local action phrases are currently Chinese-first. Complex English requests should use Smart AI. This release does not claim a fully localized bilingual UI.
- Documentation included with both the EXE and AgentKit is bilingual.

### Troubleshooting

**The Dashboard is unreachable:** start the installed `MinecraftCodexCompanion.exe`, then check `http://127.0.0.1:8765/api/health`. Resolve a local port conflict before restarting Companion.

**The service is healthy but no NPC is listed:** enter the installed Forge 1.20.1 instance and a world, verify the mod and bridge state, then use Recall.

**Minecraft chat receives no reply:** verify free chat, the exact player name, and the selected provider. Antigravity must also be running and bound to the exact intended conversation title.

**The AI agrees but no task runs:** check whether a task was actually created. Enable Smart AI for complex language. An external AgentKit controller must also have MCP tools and the companion control lease.

**A build restarts instead of resuming:** inspect the failure and recovery hint, clear the blocker or provide materials, then resume the saved checkpoint instead of creating a new build.

**Chinese text becomes question marks:** ensure the external AI sends valid Unicode through `mc_chat`. Clearly corrupted text is rejected locally.

**Minecraft uses too much memory:** run the game only during testing or play. Closing the game and HMCL process does not delete Companion settings.

### Moving to another PC

Run the original installer on the new PC and select that machine's launcher and Minecraft paths again. Do not copy `%LOCALAPPDATA%\MinecraftCodexCompanion`; it contains machine-local bridge credentials, encrypted settings, and logs.

### Local release verification

```powershell
npm run release:single-exe
npm run release:agent-kit
```

Build and security reports are written beneath the ignored `build` directory. Signing credentials and private keys are never stored in source or release artifacts.
