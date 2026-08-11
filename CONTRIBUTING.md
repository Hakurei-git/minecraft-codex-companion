# Contributing to Minecraft Codex Companion

[简体中文](CONTRIBUTING.zh-CN.md)

Thank you for helping improve the Minecraft AI NPC companion, Forge bridge, local MCP service, AgentKit, documentation, translations, or compatibility adapters.

## Before opening an issue

1. Search existing issues and discussions.
2. Reproduce the problem with the latest release in an isolated HMCL instance and a disposable world when possible.
3. Remove API keys, Base URLs, account data, local absolute paths, conversation text, logs, screenshots, and world files that are not required to explain the problem.
4. Use Discussions for setup questions and an issue form for reproducible bugs or scoped feature requests.

## Development setup

The main source workflow requires Node.js 24+, PowerShell 5.1+, Java 17 for Forge 1.20.1, and Java 21 only for the optional NeoForge 1.21.1 build.

```powershell
npm install
npm run build
npm run typecheck
```

Create a focused branch, keep changes small, and describe the user-visible behavior rather than only the implementation.

## Testing expectations

- Documentation-only changes: verify links, Markdown rendering, English/Chinese navigation, and privacy wording.
- TypeScript changes: run the affected workspace tests plus `npm run typecheck`.
- Installer changes: run `npm run test:single-exe`; release candidates must also pass the package integrity and local antivirus gates.
- Forge behavior changes: run the Forge tests and document any live disposable-world acceptance performed.
- Task-chain changes: add a deterministic regression test for parsing, prerequisites, interruption, persistence, or delivery as appropriate.

## Privacy and dependency rules

- Never commit an API key, token, cookie, account file, private Base URL profile, machine-specific absolute path, conversation, runtime state, log, screenshot, or Minecraft world.
- Do not add third-party mod JARs without explicit redistribution permission and a recorded source, license, and SHA-256. Book of Dragons and Saints Dragons are integrations, not bundled dependencies.
- External Skills, MCP metadata, blueprints, skins, and libraries must have a clear source and compatible license.
- Do not weaken loopback-only defaults, redaction, path boundaries, permission checks, or task validation to make a test pass.

## Pull requests

A pull request should include:

- the problem and intended behavior;
- the affected Minecraft version, loader, AI entry point, or task chain;
- tests run and their results;
- documentation updates for user-visible changes;
- confirmation that no sensitive or unrelated local files were added.

Report vulnerabilities according to [SECURITY.md](SECURITY.md), not in a public issue or pull request.
