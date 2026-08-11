# Minecraft Companion Tool Reference

## Tools

| Tool | Purpose |
| --- | --- |
| `mc_list_companions` | Discover companions, capabilities, status, and leases. |
| `mc_list_chat_messages` | Read ordinary player chat routed to the Antigravity MCP inbox and return the next polling cursor. |
| `mc_list_skills` | List built-in and learned declarative routines. |
| `mc_save_skill` | Save a validated task-template routine without executable code. |
| `mc_delete_skill` | Delete a learned routine; built-ins remain protected. |
| `mc_observe` | Read world, position, health, inventory, and nearby entities. |
| `mc_chat` | Send at most 256 characters through one companion. |
| `mc_assign_task` | Queue a typed task after acquiring control. |
| `mc_get_task` | Poll status, progress, result, or recovery information. |
| `mc_cancel_task` | Cancel one queued or running task. |
| `mc_preview_build` | Create a non-executing normalized build preview and material list. |
| `mc_import_build` | Import JSON, `.schem`, `.litematic`, or PNG data into an unconfirmed build preview. |
| `mc_confirm_build` | Confirm a reviewed plan; it does not start construction. |
| `mc_acquire_control` | Obtain the single-writer lease for one companion. |
| `mc_release_control` | Release the lease for handoff. |
| `mc_emergency_stop` | Cancel all work and optionally disconnect companions. |

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
- `gather`, `craft`, `smelt`: namespaced `itemId` and positive `count`.
- `provision-food`: optional `count` (default `8`), `source` (`auto`, `forage`, or `hunt`), `destination` (`backpack`, `player`, or `home-storage`), and a player reference for physical delivery. It searches safely over an expanding range, cooks supported raw food, then keeps, throws, or stores the requested count.
- `ranch`: `action` (`establish`, `breed`, or `cull`), optional `animalType` (`any`, `minecraft:pig`, `minecraft:cow`, or `minecraft:sheep`), `count`, and `radius`. Use `life.establish-ranch` when a new pen must be built before animals are led home.
- `farm`: namespaced `cropId`, `action` (`plant`, `harvest`, or `cycle`), optional `radius`.
- `store`: optional `itemId` and `count`.
- `explore`: `radius` and optional cardinal `direction`.
- `combat`: `targetType` and optional `maxDistance`. Never use `unknown` as a hostile target policy.
- `dragon`: `action` (`observe`, `feed`, `heal`, `tame`, `follow`, `stay`, `mount`, `dismount`, or `care-for-egg`) and optional `targetId`.
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
