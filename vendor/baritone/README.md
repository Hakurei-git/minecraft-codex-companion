# Baritone runtime

This directory contains the official Baritone API Forge runtime used by the
isolated HMCL Forge 1.20.1 companion instance.

- Upstream release: https://github.com/cabaletta/baritone/releases/tag/v1.10.3
- Upstream asset: `baritone-api-forge-1.10.3.jar`
- Local filename: `baritone-api-forge-1.20.1-1.10.3.jar`
- Minecraft versions declared upstream: 1.20 and 1.20.1
- Published SHA-1: `c9e080d6590628b854926af2872d6e3ba6201fa0`
- Local SHA-256: `55a3a30f56c9714c867ed43e07d3dc8568e9f2ae25e0936ead69959e5b469a31`

The API build is intentional. The bridge reflects through the stable
`baritone.api.BaritoneAPI` surface; the standalone build obfuscates most of
that surface and must not be installed alongside this file.
