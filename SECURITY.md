# Security policy

## Data boundary

Minecraft Codex Companion is designed around a local control boundary:

- the dashboard, bridge, and HTTP MCP listen on loopback by default;
- API keys are stored in the target user's local state and protected with Windows DPAPI;
- worlds, screenshots, logs, launcher accounts, bridge tokens, and local AI configuration are excluded from source and release payloads;
- configured remote AI providers receive redacted text only, never local files or images;
- Antigravity integration merges only the Minecraft MCP entry into the selected local configuration and creates a backup first.

The release build fails when it detects local paths, credentials, state files, Minecraft worlds, or unsafe links in the payload. The published installer is additionally checked with an offline ClamAV signature database. Scan reports never upload files or hashes.

## Reporting

Please use GitHub's private vulnerability reporting feature when it is available. Do not include real API keys, account data, Minecraft worlds, or private logs in a report. A minimal reproduction with synthetic data is preferred.

## Compatibility status

The verified path is HMCL with a Forge 1.20.1 single-player instance. Microsoft-account login through HMCL and the official Minecraft Launcher have not completed live acceptance yet; see the README before testing those configurations.
