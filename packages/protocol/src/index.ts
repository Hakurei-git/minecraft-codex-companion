import { z } from "zod";

export const PROTOCOL_VERSION = 1 as const;

export const backendKindSchema = z.enum([
  "simulator",
  "mineflayer",
  "forge-1.20.1",
  "neoforge-1.21.1",
]);
export type BackendKind = z.infer<typeof backendKindSchema>;

export const capabilitySchema = z.enum([
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
  "fish",
  "sleep",
  "build",
  "commands",
  "dragon-care",
  "multi-bot",
]);
export type Capability = z.infer<typeof capabilitySchema>;

export const vec3Schema = z.object({
  x: z.number().finite(),
  y: z.number().finite(),
  z: z.number().finite(),
});
export type Vec3 = z.infer<typeof vec3Schema>;

export const inventoryItemSchema = z.object({
  id: z.string().min(1),
  displayName: z.string().min(1),
  count: z.number().int().nonnegative(),
  slot: z.number().int().nonnegative(),
  slotType: z.enum(["backpack", "main_hand", "off_hand", "head", "chest", "legs", "feet"]).optional(),
  damage: z.number().int().nonnegative().optional(),
  maxDamage: z.number().int().nonnegative().optional(),
  remainingDurability: z.number().int().nonnegative().optional(),
  customName: z.boolean().optional(),
  foil: z.boolean().optional(),
  enchantable: z.boolean().optional(),
  tags: z.array(z.string().min(1)).max(64).optional(),
  enchantments: z.array(z.object({
    id: z.string().min(1),
    level: z.number().int().positive(),
    curse: z.boolean(),
  })).optional(),
  nbtSummary: z.string().max(2048).optional(),
});
export type InventoryItem = z.infer<typeof inventoryItemSchema>;

export const itemTransactionSchema = z.object({
  sequence: z.number().int().positive(),
  gameTime: z.number().int().nonnegative(),
  taskId: z.string().max(160).optional(),
  action: z.string().min(1).max(160),
  itemId: z.string().min(1).max(160),
  delta: z.number().int().refine((value) => value !== 0, "delta must not be zero"),
  balanceAfter: z.number().int().nonnegative(),
});
export type ItemTransaction = z.infer<typeof itemTransactionSchema>;

export const mobEffectSnapshotSchema = z.object({
  id: z.string().min(1),
  amplifier: z.number().int().nonnegative(),
  duration: z.number().int(),
  ambient: z.boolean(),
});
export type MobEffectSnapshot = z.infer<typeof mobEffectSnapshotSchema>;

export const homeStateSchema = z.object({
  dimension: z.string().min(1),
  position: vec3Schema,
  temporary: z.boolean(),
});
export type HomeState = z.infer<typeof homeStateSchema>;

export const miningStateSchema = z.object({
  phase: z.enum(["preflight", "seek-cave", "waiting-entry", "descending", "branching", "vein", "returning"]),
  itemId: z.string().min(1),
  targetY: z.number().int(),
  staircaseStep: z.number().int().nonnegative(),
  branchIndex: z.number().int().nonnegative(),
  branchProgress: z.number().int().nonnegative(),
  regionIndex: z.number().int().nonnegative(),
  brokenBlocks: z.number().int().nonnegative(),
  placedTorches: z.number().int().nonnegative(),
  entrance: vec3Schema.optional(),
  lastSafeStand: vec3Schema.optional(),
});
export type MiningState = z.infer<typeof miningStateSchema>;

export const dragonStateSchema = z.object({
  modId: z.enum(["bookofdragons", "saintsdragons"]),
  entityId: z.string().uuid(),
  name: z.string().min(1),
  mounted: z.boolean(),
  playerMounted: z.boolean().optional(),
  coRiding: z.boolean().optional(),
  autopilot: z.boolean().optional(),
  playerInputLocked: z.boolean().optional(),
  controlMode: z.enum(["npc-autopilot", "player", "dragon-ai"]).optional(),
  ownedByPlayer: z.boolean(),
  flying: z.boolean(),
  health: z.number().min(0).optional(),
  maxHealth: z.number().positive().optional(),
  saddled: z.boolean().optional(),
  seatLocked: z.boolean().optional(),
  playerRideReady: z.boolean().optional(),
  sharedRideEnabled: z.boolean().optional(),
});
export type DragonState = z.infer<typeof dragonStateSchema>;

export const npcTaskQueueEntrySchema = z.object({
  id: z.string().min(1),
  kind: z.string().min(1),
  phase: z.enum(["active", "paused"]),
  priority: z.number().int().min(0).max(1000),
  progress: z.number().min(0).max(1),
  pauseReason: z.string().max(500).optional(),
});
export type NpcTaskQueueEntry = z.infer<typeof npcTaskQueueEntrySchema>;

export const nearbyEntitySchema = z.object({
  id: z.string().min(1),
  type: z.string().min(1),
  name: z.string().min(1),
  position: vec3Schema,
  distance: z.number().nonnegative(),
  health: z.number().nonnegative().nullable().default(null),
  disposition: z.enum(["owner", "ally", "neutral", "hostile", "unknown"]),
});
export type NearbyEntity = z.infer<typeof nearbyEntitySchema>;

export const liveFixtureAckSchema = z.object({
  sequence: z.number().int().nonnegative(),
  suite: z.string().regex(/^[a-z][a-z0-9-]{0,63}$/),
  mode: z.string().regex(/^[a-z][a-z0-9-]{0,63}$/),
  // Normal entity display text remains capped at 120 characters. Registry
  // matrix fixtures use this loopback-only acknowledgement field for a
  // bounded list of complete resource locations.
  status: z.string().max(2_048),
});
export type LiveFixtureAck = z.infer<typeof liveFixtureAckSchema>;

export const worldSnapshotSchema = z.object({
  sequence: z.number().int().nonnegative(),
  capturedAt: z.string().datetime(),
  worldId: z.string().min(1),
  dimension: z.string().min(1),
  position: vec3Schema,
  ownerPosition: vec3Schema.optional(),
  ownerDistance: z.number().finite().nonnegative().optional(),
  yaw: z.number().finite(),
  pitch: z.number().finite(),
  health: z.number().min(0),
  maxHealth: z.number().positive(),
  food: z.number().min(0),
  maxFood: z.number().positive().optional(),
  saturation: z.number().min(0).optional(),
  exhaustion: z.number().min(0).optional(),
  materialMode: z.enum(["survival", "creative"]).optional(),
  naturalRegenerationEnabled: z.boolean().optional(),
  canNaturalRegenerate: z.boolean().optional(),
  automaticEating: z.boolean().optional(),
  managedEating: z.boolean().optional(),
  usingItem: z.boolean().optional(),
  air: z.number().min(0),
  maxAir: z.number().positive().optional(),
  armor: z.number().min(0).optional(),
  absorption: z.number().min(0).optional(),
  gameMode: z.enum(["survival", "creative", "adventure", "spectator"]),
  timeOfDay: z.number().int().nonnegative(),
  weather: z.enum(["clear", "rain", "thunder"]),
  inventory: z.array(inventoryItemSchema),
  recentItemTransactions: z.array(itemTransactionSchema).max(64).optional(),
  effects: z.array(mobEffectSnapshotSchema).optional(),
  nearbyEntities: z.array(nearbyEntitySchema),
  status: z.string(),
  clientUiState: z.enum(["gameplay", "chat", "pause", "death", "other"]).optional(),
  npcEntityUuid: z.string().uuid().optional(),
  npcDowned: z.boolean().optional(),
  stance: z.enum(["follow", "stay", "guard", "work"]).optional(),
  activeTaskId: z.string().optional(),
  activeTaskKind: z.string().optional(),
  activeTaskProgress: z.number().min(0).max(1).optional(),
  pausedTaskCount: z.number().int().nonnegative().optional(),
  activeTaskPriority: z.number().int().min(0).max(1000).optional(),
  taskSchedulerLifecycle: z.enum(["idle", "running", "downed"]).optional(),
  taskQueue: z.array(npcTaskQueueEntrySchema).max(128).optional(),
  miningState: miningStateSchema.optional(),
  liveFixtureAck: liveFixtureAckSchema.optional(),
  homeState: homeStateSchema.optional(),
  dragonState: dragonStateSchema.optional(),
});
export type WorldSnapshot = z.infer<typeof worldSnapshotSchema>;

export const permissionProfileSchema = z.object({
  mode: z.enum(["survival", "recovery", "convenience"]),
  allowCommands: z.boolean(),
  allowPvp: z.boolean(),
  allowBreakingContainers: z.boolean(),
  requireBuildConfirmation: z.boolean(),
});
export type PermissionProfile = z.infer<typeof permissionProfileSchema>;

export const aiProviderKindSchema = z.enum([
  "codex-cli",
  "codex-api",
  "claude-api",
  "antigravity-mcp",
]);
export type AiProviderKind = z.infer<typeof aiProviderKindSchema>;

export const aiProviderStateSchema = z.enum([
  "ready",
  "unconfigured",
  "error",
  "external",
]);
export type AiProviderState = z.infer<typeof aiProviderStateSchema>;

const aiProviderNameSchema = z.string().trim().min(1).max(80);
const httpUrlSchema = z.string().url().refine(
  (value) => value.startsWith("http://") || value.startsWith("https://"),
  "Only HTTP(S) URLs are supported",
);

export const aiProviderDraftSchema = z.discriminatedUnion("kind", [
  z.object({
    kind: z.literal("codex-api"),
    name: aiProviderNameSchema,
    baseUrl: httpUrlSchema,
    model: z.string().trim().min(1).max(200),
    apiKey: z.string().trim().max(4096).optional(),
  }),
  z.object({
    kind: z.literal("claude-api"),
    name: aiProviderNameSchema,
    baseUrl: httpUrlSchema,
    model: z.string().trim().min(1).max(200),
    apiKey: z.string().trim().max(4096).optional(),
  }),
  z.object({
    kind: z.literal("antigravity-mcp"),
    name: aiProviderNameSchema,
    mcpUrl: httpUrlSchema,
  }),
]);
export type AiProviderDraft = z.infer<typeof aiProviderDraftSchema>;

export const aiProviderProfileSchema = z.object({
  id: z.string().min(1),
  name: aiProviderNameSchema,
  kind: aiProviderKindSchema,
  active: z.boolean(),
  builtIn: z.boolean(),
  executable: z.boolean(),
  hasApiKey: z.boolean(),
  baseUrl: httpUrlSchema.nullable(),
  model: z.string().nullable(),
  mcpUrl: httpUrlSchema.nullable(),
  state: aiProviderStateSchema,
  stateMessage: z.string(),
  lastTestedAt: z.string().datetime().nullable(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});
export type AiProviderProfile = z.infer<typeof aiProviderProfileSchema>;

export const freeChatTargetSchema = z.enum([
  "active-provider",
  "multi-agent",
  "antigravity-mcp",
]);
export type FreeChatTarget = z.infer<typeof freeChatTargetSchema>;

/**
 * The public configuration is intentionally binary. `hybrid` is accepted only
 * by the draft parser below so older launcher/settings files migrate to the
 * smart planner without silently disabling their AI behavior.
 */
export const chatActionModeSchema = z.enum(["stable", "smart"]);
export type ChatActionMode = z.infer<typeof chatActionModeSchema>;

export const DEFAULT_CHAT_ACTION_MODE: ChatActionMode = "stable";
export const DEFAULT_CHAT_TOKEN_BUDGET = 512;
export const MIN_CHAT_TOKEN_BUDGET = 128;
export const MAX_CHAT_TOKEN_BUDGET = 4_096;

const chatActionModeDraftSchema = z.preprocess(
  (value) => value === "hybrid" ? "smart" : value,
  chatActionModeSchema,
);

export const personaModeSchema = z.enum(["inherit", "custom"]);
export type PersonaMode = z.infer<typeof personaModeSchema>;

export const companionPersonaSettingsSchema = z.object({
  mode: personaModeSchema,
  displayName: z.string().trim().max(64),
  personality: z.string().trim().max(1_200),
  speakingStyle: z.string().trim().max(600),
  memoryNotes: z.string().trim().max(2_000),
});
export type CompanionPersonaSettings = z.infer<typeof companionPersonaSettingsSchema>;

export const DEFAULT_COMPANION_PERSONA_SETTINGS: CompanionPersonaSettings = {
  mode: "inherit",
  displayName: "",
  personality: "",
  speakingStyle: "",
  memoryNotes: "",
};

export const chatSettingsDraftSchema = z.object({
  freeChatEnabled: z.boolean(),
  playerName: z.string().trim().min(1).max(64),
  companionName: z.string().trim().min(1).max(64).optional(),
  target: freeChatTargetSchema,
  actionMode: chatActionModeDraftSchema.default(DEFAULT_CHAT_ACTION_MODE),
  tokenBudget: z.number().int().min(MIN_CHAT_TOKEN_BUDGET).max(MAX_CHAT_TOKEN_BUDGET)
    .default(DEFAULT_CHAT_TOKEN_BUDGET),
  persona: companionPersonaSettingsSchema.default(DEFAULT_COMPANION_PERSONA_SETTINGS),
});
type NormalizedChatSettingsDraft = z.infer<typeof chatSettingsDraftSchema>;
export type ChatSettingsDraft = Omit<NormalizedChatSettingsDraft, "actionMode" | "tokenBudget"> & {
  actionMode?: ChatActionMode;
  tokenBudget?: number;
};

export const chatSettingsSchema = z.object({
  freeChatEnabled: z.boolean(),
  playerName: z.string().trim().min(1).max(64),
  companionName: z.string().trim().min(1).max(64),
  target: freeChatTargetSchema,
    actionMode: chatActionModeSchema.default(DEFAULT_CHAT_ACTION_MODE),
  tokenBudget: z.number().int().min(MIN_CHAT_TOKEN_BUDGET).max(MAX_CHAT_TOKEN_BUDGET)
    .default(DEFAULT_CHAT_TOKEN_BUDGET),
  persona: companionPersonaSettingsSchema,
  updatedAt: z.string().datetime(),
});
export type ChatSettings = z.infer<typeof chatSettingsSchema>;

const playerReferenceSchema = z.string().trim().min(1).max(64);
const resourceLocationSchema = z.string().trim().min(1).max(256).regex(
  /^[a-z0-9_.-]+:[a-z0-9/._-]+$/u,
  "Expected a namespaced Minecraft resource identifier",
);
const resourceSelectorSchema = z.string().trim().min(1).max(257).refine(
  (value) => resourceLocationSchema.safeParse(value.startsWith("#") ? value.slice(1) : value).success,
  "Expected a Minecraft resource identifier or #tag",
);

export const buildMaterialPreferenceSchema = z.object({
  source: z.enum(["auto", "inventory", "home", "nearby"]).default("auto"),
  preferredBlockId: resourceLocationSchema.optional(),
  allowMixed: z.boolean().default(false),
});
export type BuildMaterialPreference = z.infer<typeof buildMaterialPreferenceSchema>;

const taskBase = {
  requestedBy: z.string().trim().min(1).max(64).default("user"),
  note: z.string().max(500).optional(),
  priority: z.number().int().min(0).max(1000).optional(),
};

export const taskSpecSchema = z.discriminatedUnion("kind", [
  z.object({ ...taskBase, kind: z.literal("follow"), player: playerReferenceSchema, distance: z.number().min(1).max(16).default(3) }),
  z.object({ ...taskBase, kind: z.literal("guard"), player: playerReferenceSchema, radius: z.number().min(2).max(64).default(12) }),
  z.object({ ...taskBase, kind: z.literal("move"), target: vec3Schema }),
  z.object({
    ...taskBase,
    kind: z.literal("gather"),
    itemId: resourceSelectorSchema,
    count: z.number().int().min(1).max(4096),
    movement: z.enum(["auto", "walk"]).optional(),
  }),
  z.object({
    ...taskBase,
    kind: z.literal("craft"),
    itemId: resourceLocationSchema,
    count: z.number().int().min(1).max(256),
    deliverTo: playerReferenceSchema.optional(),
    placeAtHome: z.boolean().optional(),
  }),
  z.object({ ...taskBase, kind: z.literal("smelt"), itemId: resourceLocationSchema, count: z.number().int().min(1).max(256) }),
  z.object({ ...taskBase, kind: z.literal("farm"), cropId: resourceLocationSchema, action: z.enum(["plant", "harvest", "cycle"]), radius: z.number().min(1).max(64).default(12) }),
  z.object({ ...taskBase, kind: z.literal("store"), itemId: resourceSelectorSchema.optional(), count: z.number().int().min(1).max(4096).optional() }),
  z.object({ ...taskBase, kind: z.literal("retrieve"), itemId: resourceSelectorSchema, count: z.number().int().min(1).max(4096) }),
  z.object({ ...taskBase, kind: z.literal("organize-storage"), radius: z.number().int().min(8).max(64).default(24) }),
  z.object({ ...taskBase, kind: z.literal("deliver"), itemId: resourceSelectorSchema, count: z.number().int().min(1).max(4096), player: playerReferenceSchema }),
  z.object({ ...taskBase, kind: z.literal("eat"), itemId: resourceSelectorSchema.optional(), count: z.number().int().min(1).max(64).default(1) }),
  z.object({
    ...taskBase,
    kind: z.literal("provision-food"),
    count: z.number().int().min(1).max(64).default(8),
    source: z.enum(["auto", "forage", "hunt"]).default("auto"),
    foodCategory: z.enum(["any", "meat", "plant"]).default("any"),
    destination: z.enum(["backpack", "player", "home-storage"]).default("backpack"),
    player: playerReferenceSchema.optional(),
  }),
  z.object({
    ...taskBase,
    kind: z.literal("ranch"),
    action: z.enum(["establish", "breed", "cull"]).default("establish"),
    animalType: z.enum(["any", "minecraft:pig", "minecraft:cow", "minecraft:sheep"]).default("any"),
    count: z.number().int().min(2).max(24).default(2),
    radius: z.number().int().min(16).max(512).default(128),
    fixtureTag: z.string().regex(/^[A-Za-z][A-Za-z0-9_]{0,47}$/).optional(),
  }),
  z.object({ ...taskBase, kind: z.literal("drop"), itemId: resourceSelectorSchema, count: z.number().int().min(1).max(4096), player: playerReferenceSchema.optional() }),
  z.object({ ...taskBase, kind: z.literal("fish"), count: z.number().int().min(1).max(64).default(1), radius: z.number().int().min(4).max(64).default(24) }),
  z.object({ ...taskBase, kind: z.literal("sleep"), radius: z.number().int().min(4).max(64).default(32) }),
  z.object({ ...taskBase, kind: z.literal("explore"), radius: z.number().min(8).max(2048), direction: z.enum(["north", "south", "east", "west", "any"]).default("any") }),
  z.object({ ...taskBase, kind: z.literal("combat"), targetType: z.union([z.literal("hostile"), resourceLocationSchema]), maxDistance: z.number().min(2).max(64).default(24) }),
  z.object({
    ...taskBase,
    kind: z.literal("dragon"),
    action: z.enum(["observe", "feed", "heal", "tame", "follow", "stay", "mount", "dismount", "care-for-egg", "recall", "assist-combat", "land", "fly-to"]),
    targetId: z.string().trim().max(128).optional(),
    target: vec3Schema.optional(),
  }),
  z.object({
    ...taskBase,
    kind: z.literal("build"),
    planId: z.string().trim().min(1).max(128),
    placement: z.enum(["plan-origin", "companion"]).optional(),
    offset: vec3Schema.optional(),
    placementAnchor: vec3Schema.optional(),
    materialPreference: buildMaterialPreferenceSchema.optional(),
  }),
  z.object({
    ...taskBase,
    kind: z.literal("macro"),
    skillId: z.string().trim().min(1).max(128),
    arguments: z.record(z.string().max(64), z.unknown()).default({}),
    placementAnchor: vec3Schema.optional(),
    materialMode: z.enum(["survival", "creative"]).optional(),
    materialPreference: buildMaterialPreferenceSchema.optional(),
  }),
]);
export type TaskSpec = z.infer<typeof taskSpecSchema>;

export const skillParameterSchema = z.object({
  name: z.string().regex(/^[A-Za-z][A-Za-z0-9_]{0,47}$/),
  description: z.string().max(200).default(""),
  type: z.enum(["string", "number", "integer", "boolean"]),
  required: z.boolean().default(true),
  defaultValue: z.unknown().optional(),
  enumValues: z.array(z.union([z.string(), z.number(), z.boolean()])).max(64).optional(),
  minimum: z.number().finite().optional(),
  maximum: z.number().finite().optional(),
});
export type SkillParameter = z.infer<typeof skillParameterSchema>;

export const declarativeSkillStepSchema = z.object({
  label: z.string().trim().min(1).max(120),
  whenMaterialMode: z.enum(["always", "survival", "creative"]).optional(),
  task: z.record(z.string(), z.unknown()).refine(
    (value) => typeof value.kind === "string" && value.kind !== "macro",
    "Skill steps must contain a non-macro task kind",
  ),
});
export type DeclarativeSkillStep = z.infer<typeof declarativeSkillStepSchema>;

/** MCP tools a declarative skill may request. Keep this list Minecraft-only. */
export const skillToolPermissionSchema = z.enum([
  "mc_list_companions",
  "mc_observe",
  "mc_chat",
  "mc_control_companion",
  "mc_assign_task",
  "mc_get_task",
  "mc_cancel_task",
  "mc_preview_build",
]);
export type SkillToolPermission = z.infer<typeof skillToolPermissionSchema>;

export const skillSourceSchema = z.object({
  kind: z.enum(["built-in", "learned", "external"]).default("learned"),
  author: z.string().trim().min(1).max(120).optional(),
  license: z.string().trim().min(1).max(80).optional(),
  url: z.string().url().max(2_048).optional(),
}).superRefine((source, context) => {
  if (source.kind === "external" && (!source.author || !source.license || !source.url)) {
    context.addIssue({
      code: "custom",
      message: "External skills require author, license, and source URL",
    });
  }
});
export type SkillSource = z.infer<typeof skillSourceSchema>;

export const skillPermissionManifestSchema = z.object({
  tools: z.array(skillToolPermissionSchema).max(16).default(["mc_assign_task"]),
  network: z.enum(["none", "local-only", "allowlist"]).default("none"),
  allowedHosts: z.array(z.string().trim().min(1).max(253)).max(16).default([]),
  fileAccess: z.literal("none").default("none"),
  systemCommands: z.literal(false).default(false),
}).superRefine((permissions, context) => {
  if (permissions.network !== "allowlist" && permissions.allowedHosts.length > 0) {
    context.addIssue({ code: "custom", message: "allowedHosts requires allowlist network mode" });
  }
});
export type SkillPermissionManifest = z.infer<typeof skillPermissionManifestSchema>;

export const skillManifestSchema = z.object({
  version: z.string().trim().regex(/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/).default("1.0.0"),
  source: skillSourceSchema.default({ kind: "learned" }),
  permissions: skillPermissionManifestSchema.default({
    tools: ["mc_assign_task"],
    network: "none",
    allowedHosts: [],
    fileAccess: "none",
    systemCommands: false,
  }),
});
export type SkillManifest = z.infer<typeof skillManifestSchema>;

export const skillSecurityReviewSchema = z.object({
  status: z.enum(["trusted", "pending", "approved", "rejected"]),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  reviewedAt: z.string().datetime().nullable(),
  findings: z.array(z.string().max(240)).max(64),
});
export type SkillSecurityReview = z.infer<typeof skillSecurityReviewSchema>;

export const declarativeSkillDraftSchema = z.object({
  id: z.string().regex(/^[a-z0-9][a-z0-9._-]{1,79}$/),
  name: z.string().trim().min(1).max(120),
  description: z.string().trim().min(1).max(500),
  parameters: z.array(skillParameterSchema).max(32).default([]),
  steps: z.array(declarativeSkillStepSchema).min(1).max(64),
  manifest: skillManifestSchema.optional(),
});
export type DeclarativeSkillDraft = z.infer<typeof declarativeSkillDraftSchema>;

export const declarativeSkillSchema = declarativeSkillDraftSchema.extend({
  manifest: skillManifestSchema,
  security: skillSecurityReviewSchema,
  builtIn: z.boolean(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});
export type DeclarativeSkill = z.infer<typeof declarativeSkillSchema>;

export const taskStatusSchema = z.enum([
  "queued",
  "running",
  "paused",
  "succeeded",
  "failed",
  "cancelled",
]);
export type TaskStatus = z.infer<typeof taskStatusSchema>;

/**
 * Optional, source-backed progress facts for a task.
 *
 * `progress` on a macro is the progress of the whole macro. These fields keep
 * the current child step separate so consumers never multiply the parent
 * percentage by an item target and present that estimate as an observed count.
 * Count fields are deliberately optional: producers must omit facts they did
 * not directly observe.
 */
export const taskProgressDetailsSchema = z.object({
  currentStepIndex: z.number().int().nonnegative().optional(),
  currentStepKind: z.enum([
    "follow",
    "guard",
    "move",
    "gather",
    "craft",
    "smelt",
    "farm",
    "store",
    "retrieve",
    "organize-storage",
    "deliver",
    "eat",
    "provision-food",
    "ranch",
    "drop",
    "fish",
    "sleep",
    "explore",
    "combat",
    "dragon",
    "build",
  ]).optional(),
  stepProgress: z.number().min(0).max(1).optional(),
  completedCount: z.number().int().nonnegative().optional(),
  targetCount: z.number().int().positive().optional(),
  retainedCount: z.number().int().nonnegative().optional(),
});
export type TaskProgressDetails = z.infer<typeof taskProgressDetailsSchema>;

export const taskRecordSchema = z.object({
  id: z.string().uuid(),
  companionId: z.string().min(1),
  spec: taskSpecSchema,
  status: taskStatusSchema,
  progress: z.number().min(0).max(1),
  message: z.string(),
  createdAt: z.string().datetime(),
  startedAt: z.string().datetime().nullable(),
  finishedAt: z.string().datetime().nullable(),
  error: z.object({
    code: z.string(),
    message: z.string(),
    retryable: z.boolean(),
    suggestedRecovery: z.string().optional(),
  }).nullable(),
}).extend(taskProgressDetailsSchema.shape);
export type TaskRecord = z.infer<typeof taskRecordSchema>;

export const companionSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  backend: backendKindSchema,
  gameVersion: z.string().min(1),
  loader: z.string().min(1),
  bridgeVersion: z.string().min(1).max(64).optional(),
  connected: z.boolean(),
  capabilities: z.array(capabilitySchema),
  leaseOwner: z.string().nullable(),
  activeTaskId: z.string().uuid().nullable(),
  snapshot: worldSnapshotSchema,
  embodiment: z.enum(["simulation", "remote-player", "in-world-npc"]).optional(),
  ownerName: z.string().min(1).nullable().optional(),
  entityUuid: z.string().uuid().nullable().optional(),
});
export type Companion = z.infer<typeof companionSchema>;

export const companionActionSchema = z.enum(["summon", "recall", "follow", "stay"]);
export type CompanionAction = z.infer<typeof companionActionSchema>;

const aiDecisionBase = {
  reply: z.string().trim().min(1).max(220),
  summary: z.string().trim().max(400).default(""),
};

export const aiTaskDecisionTypeSchema = z.enum([
  "chat",
  "clarify",
  "inspect",
  "control",
  "task",
  "skill",
  "retry-build",
]);

/** A model may propose one typed local action, never an arbitrary tool loop. */
export const aiTaskDecisionSchema = z.discriminatedUnion("type", [
  z.object({ ...aiDecisionBase, type: z.literal("chat") }).strict(),
  z.object({ ...aiDecisionBase, type: z.literal("clarify") }).strict(),
  z.object({
    ...aiDecisionBase,
    type: z.literal("inspect"),
    scope: z.enum(["activity", "vitals", "inventory", "full"]),
  }).strict(),
  z.object({ ...aiDecisionBase, type: z.literal("control"), action: companionActionSchema }).strict(),
  z.object({
    ...aiDecisionBase,
    type: z.literal("task"),
    spec: taskSpecSchema,
    replaceConflictingDelivery: z.boolean().optional(),
  }).strict(),
  z.object({
    ...aiDecisionBase,
    type: z.literal("skill"),
    skillId: z.string().trim().min(1).max(128),
    arguments: z.record(z.string().max(64), z.unknown()).default({}),
    materialMode: z.enum(["survival", "creative"]).optional(),
    materialPreference: buildMaterialPreferenceSchema.optional(),
  }).strict(),
  z.object({ ...aiDecisionBase, type: z.literal("retry-build") }).strict(),
]);
export type AiTaskDecision = z.infer<typeof aiTaskDecisionSchema>;

export const aiTaskDecisionResultSchema = z.object({
  ok: z.literal(true),
  interactionId: z.string().trim().min(1).max(128),
  decisionType: aiTaskDecisionTypeSchema,
  taskId: z.string().uuid().optional(),
  reply: z.string().trim().min(1).max(220),
});
export type AiTaskDecisionResult = z.infer<typeof aiTaskDecisionResultSchema>;

const buildPropertiesSchema = z.record(
  z.string().trim().min(1).max(64),
  z.string().max(256),
).refine((properties) => Object.keys(properties).length <= 32, "A block may have at most 32 properties");

export const buildBlockSchema = z.object({
  position: vec3Schema,
  blockId: resourceLocationSchema,
  properties: buildPropertiesSchema.default({}),
});
export type BuildBlock = z.infer<typeof buildBlockSchema>;

export const buildSourceSchema = z.enum([
  "json",
  "schem",
  "litematic",
  "pixel-art",
  "reference-image",
  "demo",
]);
export type BuildSource = z.infer<typeof buildSourceSchema>;

export const buildPlanDraftSchema = z.object({
  name: z.string().min(1).max(120),
  source: buildSourceSchema,
  origin: vec3Schema,
  blocks: z.array(buildBlockSchema).min(1).max(250_000),
});
export type BuildPlanDraft = z.infer<typeof buildPlanDraftSchema>;

export const buildImportSourceSchema = z.enum([
  "json",
  "schem",
  "litematic",
  "pixel-art",
  "reference-image",
]);
export type BuildImportSource = z.infer<typeof buildImportSourceSchema>;

export const buildImageOptionsSchema = z.object({
  plane: z.enum(["xy", "xz"]).default("xy"),
  maxWidth: z.number().int().min(1).max(512).default(128),
  maxHeight: z.number().int().min(1).max(512).default(128),
  alphaThreshold: z.number().int().min(0).max(255).default(16),
  palette: z.record(z.string().min(1), z.string().regex(/^#[0-9a-fA-F]{6}$/)).optional(),
});
export type BuildImageOptions = z.infer<typeof buildImageOptionsSchema>;

export const buildImportRequestSchema = z.object({
  name: z.string().trim().min(1).max(120),
  source: buildImportSourceSchema,
  origin: vec3Schema,
  fileName: z.string().trim().min(1).max(260).optional(),
  filePath: z.string().trim().min(1).max(4096).optional(),
  dataBase64: z.string().min(1).max(64 * 1024 * 1024).optional(),
  includeAir: z.boolean().default(false),
  image: buildImageOptionsSchema.default({
    plane: "xy",
    maxWidth: 128,
    maxHeight: 128,
    alphaThreshold: 16,
  }),
});
export type BuildImportRequest = z.infer<typeof buildImportRequestSchema>;

/** Provenance and the complete privilege inventory for a data-only build plan. */
export const buildContentManifestSchema = z.object({
  version: z.string().trim().regex(/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/),
  source: skillSourceSchema,
  permissions: z.object({
    network: z.literal("none"),
    fileAccess: z.literal("none"),
    systemCommands: z.literal(false),
    commandBlocks: z.literal(false),
    blockEntityNbt: z.literal(false),
  }),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
});
export type BuildContentManifest = z.infer<typeof buildContentManifestSchema>;

export const buildPlanSchema = z.object({
  id: z.string().uuid(),
  name: z.string().min(1),
  source: buildSourceSchema,
  origin: vec3Schema,
  size: vec3Schema,
  blocks: z.array(buildBlockSchema).max(250_000),
  requiredItems: z.record(z.string(), z.number().int().nonnegative()),
  confirmed: z.boolean(),
  builtIn: z.boolean().default(false),
  manifest: buildContentManifestSchema,
  createdAt: z.string().datetime(),
});
export type BuildPlan = z.infer<typeof buildPlanSchema>;

export const eventSchema = z.object({
  id: z.string().uuid(),
  type: z.enum(["system", "chat", "task", "connection", "warning"]),
  at: z.string().datetime(),
  companionId: z.string().nullable(),
  message: z.string(),
  data: z.record(z.string(), z.unknown()).optional(),
});
export type CompanionEvent = z.infer<typeof eventSchema>;

export const chatMessageSchema = z.object({
  sequence: z.number().int().positive(),
  at: z.string().datetime(),
  companionId: z.string().min(1),
  sender: z.string().min(1).max(64),
  message: z.string().min(1).max(256),
});
export type ChatMessage = z.infer<typeof chatMessageSchema>;

export const bridgeHelloSchema = z.object({
  type: z.literal("hello"),
  protocolVersion: z.literal(PROTOCOL_VERSION),
  token: z.string().min(16),
  companion: companionSchema.omit({ connected: true, leaseOwner: true, activeTaskId: true }),
});

export const bridgeMessageSchema = z.discriminatedUnion("type", [
  bridgeHelloSchema,
  z.object({ type: z.literal("snapshot"), companionId: z.string(), snapshot: worldSnapshotSchema }),
  z.object({
    type: z.literal("chat-delivered"),
    companionId: z.string(),
    deliveryId: z.string().uuid(),
  }),
  z.object({
    type: z.literal("chat"),
    companionId: z.string(),
    messageId: z.string().uuid().optional(),
    sender: z.string(),
    message: z.string(),
    at: z.string().datetime(),
  }),
  z.object({
    type: z.literal("task-progress"),
    companionId: z.string(),
    taskId: z.string().uuid(),
    progress: z.number().min(0).max(1),
    message: z.string(),
    phase: z.enum(["active", "paused"]).optional(),
  }).extend(taskProgressDetailsSchema.shape),
  z.object({
    type: z.literal("task-result"),
    companionId: z.string(),
    taskId: z.string().uuid(),
    ok: z.boolean(),
    message: z.string(),
    code: z.string().optional(),
  }).extend(taskProgressDetailsSchema.shape),
  z.object({ type: z.literal("heartbeat"), companionId: z.string(), at: z.string().datetime() }),
]);
export type BridgeMessage = z.infer<typeof bridgeMessageSchema>;

export const liveFixtureRequestSchema = z.discriminatedUnion("suite", [
  z.object({
    suite: z.literal("combat"),
    mode: z.enum(["spawn-husk", "hit-owner", "cleanup", "set-normal", "set-peaceful"]),
  }).strict(),
  z.object({
    suite: z.literal("damage"),
    mode: z.enum(["owner-melee", "owner-projectile", "environment", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("dragon"),
    mode: z.enum([
      "spawn-book",
      "spawn-saints",
      "move-book-far",
      "move-saints-far",
      "raise-book",
      "raise-saints",
      "set-book-wander",
      "set-saints-wander",
      "spawn-combat-target",
      "arm-combat-target",
      "prepare-book-feed",
      "inspect-book-needs",
      "inspect-book-tame",
      "drop-book-food",
      "co-ride-book",
      "co-ride-saints",
      "dismount-all",
      "inspect-book",
      "inspect-saints",
      "stage-obstacle-book",
      "stage-obstacle-saints",
      "clear-obstacle",
      "cleanup-combat",
      "cleanup",
      "set-creative",
      "set-survival",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("dragon-care"),
    mode: z.enum([
      "setup-book",
      "setup-saints",
      "stage-feed",
      "inspect-feed",
      "stage-heal",
      "inspect-heal",
      "stage-tame",
      "inspect-tame",
      "stage-egg",
      "inspect-egg",
      "cleanup",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("follow"),
    mode: z.enum([
      "setup",
      "move-ground",
      "inspect-ground",
      "take-off",
      "inspect-air",
      "land",
      "inspect-land",
      "far-recall",
      "inspect-recall",
      "cleanup",
      "reset-survival",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("life-skill"),
    mode: z.enum(["fishing", "sleep", "bed-chain", "bed-cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("farm-patch"),
    mode: z.enum(["create-3x3", "mature-existing-wheat"]),
  }).strict(),
  z.object({
    suite: z.literal("ranch"),
    mode: z.enum(["setup-establish", "arm-chat-establish", "supply-breed", "setup-cull", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("food-delivery"),
    mode: z.enum(["setup-player", "inspect-player", "setup-home", "inspect-home", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("food-survival"),
    mode: z.enum([
      "setup",
      "setup-16",
      "inspect",
      "arm-guard",
      "release-guard",
      "checkpoint",
      "verify-restart",
      "recover-cleanup",
      "cleanup",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("storage"),
    mode: z.enum([
      "setup-retrieve",
      "inspect-retrieve",
      "setup-organize",
      "inspect-organize",
      "setup-expand",
      "inspect-expand",
      "setup-craft-expand",
      "inspect-craft-expand",
      "setup-restart",
      "inspect-restart",
      "cleanup",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("no-cheat-expedition"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("build-palette"),
    mode: z.union([
      z.enum(["setup-mixed", "inspect-mixed", "setup-chain", "inspect-chain", "cleanup", "catalog"]),
      z.string().regex(/^(?:catalog|setup-family|inspect-family)-\d{1,4}$/u),
    ]),
  }).strict(),
  z.object({
    suite: z.literal("build-material-chain"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("build-resume"),
    mode: z.enum(["setup", "inspect-failed", "release", "inspect-complete", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("natural-tree"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("player-state"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("eating-action"),
    mode: z.enum(["setup-rotten", "setup-melon", "setup-full", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("fishing-action"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("farm-action"),
    mode: z.enum(["setup-work", "setup-empty", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("guard-resume"),
    mode: z.enum(["setup", "arm", "release", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("craft-chain"),
    mode: z.enum(["setup", "inspect", "checkpoint", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("resource-priority"),
    mode: z.enum([
      "setup", "setup-fishing", "setup-torches", "inspect", "inspect-craft", "cleanup",
    ]),
  }).strict(),
  z.object({
    suite: z.literal("bed-sleep"),
    mode: z.enum(["setup", "inspect", "prepare-night", "wake-day", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("deep-mining"),
    mode: z.enum(["setup", "inspect", "cleanup"]),
  }).strict(),
  z.object({
    suite: z.literal("save-and-quit"),
    mode: z.literal("arm"),
  }).strict(),
  z.object({
    suite: z.literal("view-npc"),
    mode: z.enum(["npc", "fishing", "sleep"]),
  }).strict(),
  z.object({
    suite: z.literal("drop-to-npc"),
    mode: z.literal("drop"),
    itemId: resourceLocationSchema,
    count: z.number().int().min(1).max(64),
  }).strict(),
  z.object({
    suite: z.literal("npc-state"),
    mode: z.literal("set"),
    food: z.number().int().min(0).max(20),
    saturation: z.number().min(0).max(20),
    health: z.number().min(1).max(20),
  }).strict(),
]).superRefine((fixture, context) => {
  if (fixture.suite === "npc-state" && fixture.saturation > fixture.food) {
    context.addIssue({
      code: "custom",
      path: ["saturation"],
      message: "NPC saturation cannot exceed its food level",
    });
  }
});
export type LiveFixtureRequest = z.infer<typeof liveFixtureRequestSchema>;
export type LiveFixtureBridgeCommand = { type: "live-fixture" } & LiveFixtureRequest;

export type BridgeCommand =
  | { type: "run-task"; task: TaskRecord; buildPlan?: BuildPlan }
  | { type: "cancel-task"; taskId: string; reason: string }
  | { type: "chat"; message: string; deliveryId?: string }
  | { type: "npc-control"; action: CompanionAction }
  | { type: "emergency-stop"; disconnect: boolean }
  | LiveFixtureBridgeCommand;

export function assertNever(value: never): never {
  throw new Error(`Unhandled value: ${JSON.stringify(value)}`);
}
