const CAPABILITIES = [
  "chat",
  "observe",
  "move",
  "follow",
  "combat",
  "gather",
  "craft",
  "smelt",
  "farm",
  "storage",
  "build",
  "dragon-care",
  "multi-bot",
];

function gameMode(bot) {
  const value = String(bot.game?.gameMode ?? bot.player?.gamemode ?? "survival").toLowerCase();
  if (["survival", "creative", "adventure", "spectator"].includes(value)) return value;
  const numeric = Number(value);
  return numeric === 1 ? "creative" : numeric === 2 ? "adventure" : numeric === 3 ? "spectator" : "survival";
}

function disposition(entity, ownerName) {
  const name = String(entity.username ?? entity.name ?? "");
  if (ownerName && name.toLowerCase() === ownerName.toLowerCase()) return "owner";
  if (entity.type === "player") return "ally";
  const kind = String(entity.kind ?? entity.mobType ?? "").toLowerCase();
  if (kind.includes("hostile") || kind.includes("monster")) return "hostile";
  if (entity.type === "mob") return "neutral";
  return "unknown";
}

function entityName(entity) {
  return String(entity.username ?? entity.displayName?.toString?.() ?? entity.name ?? entity.type ?? "unknown");
}

export function createSnapshot(bot, config, status, sequence) {
  const position = bot.entity?.position ?? { x: 0, y: 0, z: 0 };
  const nearbyEntities = Object.values(bot.entities ?? {})
    .filter((entity) => entity && entity !== bot.entity && entity.position && entity.position.distanceTo(position) <= config.observeRadius)
    .sort((left, right) => left.position.distanceTo(position) - right.position.distanceTo(position))
    .slice(0, 64)
    .map((entity) => ({
      id: String(entity.uuid ?? entity.id),
      type: String(entity.name ?? entity.type ?? "unknown"),
      name: entityName(entity),
      position: { x: entity.position.x, y: entity.position.y, z: entity.position.z },
      distance: entity.position.distanceTo(position),
      health: typeof entity.health === "number" && entity.health >= 0 ? entity.health : null,
      disposition: disposition(entity, config.ownerName),
    }));
  const inventory = (bot.inventory?.slots ?? [])
    .map((item, slot) => item ? {
      id: item.name?.includes(":") ? item.name : `minecraft:${item.name}`,
      displayName: String(item.displayName ?? item.name),
      count: Math.max(0, Number(item.count ?? 0)),
      slot,
    } : null)
    .filter(Boolean);

  return {
    sequence,
    capturedAt: new Date().toISOString(),
    worldId: `${config.server.host}:${config.server.port}`,
    dimension: String(bot.game?.dimension ?? "minecraft:overworld"),
    position: { x: position.x, y: position.y, z: position.z },
    yaw: Number(bot.entity?.yaw ?? 0),
    pitch: Number(bot.entity?.pitch ?? 0),
    health: Math.max(0, Number(bot.health ?? 0)),
    maxHealth: Math.max(1, Number(bot.entity?.attributes?.["minecraft:generic.max_health"]?.value ?? 20)),
    food: Math.max(0, Number(bot.food ?? 0)),
    air: Math.max(0, Number(bot.oxygenLevel ?? 20)),
    gameMode: gameMode(bot),
    timeOfDay: Math.max(0, Math.floor(Number(bot.time?.timeOfDay ?? 0))),
    weather: Number(bot.thunderState ?? 0) > 0 ? "thunder" : bot.isRaining ? "rain" : "clear",
    inventory,
    nearbyEntities,
    status,
  };
}

export function createCompanion(bot, botConfig, workerConfig, snapshot) {
  return {
    id: botConfig.id,
    name: bot.username ?? botConfig.username,
    backend: "mineflayer",
    gameVersion: String(bot.version ?? workerConfig.server.version),
    loader: "vanilla",
    capabilities: CAPABILITIES,
    snapshot,
  };
}
