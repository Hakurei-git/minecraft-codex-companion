import type {
  ActionSpec,
  FacilityType,
  GoalRecord,
  KnowledgeTopic,
  TaskSpec,
  WorkGraphEdge,
  WorkGraphStatus,
  WorkNode,
} from "@mc/protocol";
import { parseDeterministicChatAction } from "./chat-action-intent.js";

export interface GoalPlannerResult {
  readonly nodes: WorkNode[];
  readonly edges: WorkGraphEdge[];
  readonly status: WorkGraphStatus;
  readonly message: string;
}

const GENERIC_LOG_SELECTOR = "#minecraft:logs";
const DEFAULT_DELIVERY_PHRASE = /(?:给我|交给我|拿给我|带给我|送给我|丢给我|deliver|bring|give|to me)/iu;
const MEAT_PHRASE = /(?:肉|牛肉|猪肉|羊肉|meat|beef|pork|mutton)/iu;
const FOOD_PHRASE = /(?:食物|吃的|口粮|food|rations?)/iu;
const STORAGE_GOAL_PHRASE = /(?:仓库|储物|整理|分类|归类|存放|放回|storage|organize|sort)/iu;
const COMPANION_RECALL_BEFORE_WORK_PHRASE = /(?:回到我身边|到我身边来|(?:你|npc|NPC|伙伴|队友)\s*(?:快\s*)?回来|先[^。.!！]{0,80}回来|recall\s+(?:you|the\s+)?(?:npc|companion)|(?:come|return)\s+back(?:\s+to\s+me)?)/iu;

type PlannedNodeDraft = {
  id: string;
  label: string;
  action: ActionSpec;
  checkpoint?: Record<string, unknown>;
};

type BuildFacilityRoute = {
  skillId: string;
  label: string;
  facilityType: FacilityType;
  tags: string[];
};

function normalizeGoalText(goal: GoalRecord): string {
  return [
    goal.spec.title,
    goal.spec.objective,
    ...goal.spec.constraints,
    Object.values(goal.spec.metadata)
      .filter((value): value is string | number | boolean => (
        typeof value === "string" || typeof value === "number" || typeof value === "boolean"
      ))
      .map(String)
      .slice(0, 16)
      .join(" "),
  ].join(" ").normalize("NFKC");
}

function requestedBy(goal: GoalRecord): string {
  return goal.spec.deliverTo?.trim() || goal.spec.requestedBy.trim() || "player";
}

function note(goal: GoalRecord, suffix?: string): string {
  const base = goal.spec.objective || goal.spec.title;
  const full = suffix ? `${base} | ${suffix}` : base;
  return full.slice(0, 500);
}

function taskAction(spec: TaskSpec): ActionSpec {
  return { kind: "task", spec };
}

function skillAction(
  skillId: string,
  args: Record<string, unknown> = {},
  options: Pick<Extract<ActionSpec, { kind: "skill" }>, "materialMode" | "materialPreference"> = {},
): ActionSpec {
  return {
    kind: "skill",
    skillId,
    arguments: args,
    ...(options.materialMode ? { materialMode: options.materialMode } : {}),
    ...(options.materialPreference ? { materialPreference: options.materialPreference } : {}),
  };
}

function queryKnowledgeAction(query: string, topics: KnowledgeTopic[] = []): ActionSpec {
  return { kind: "query-knowledge", query: query.slice(0, 240), topics };
}

function queryFacilitiesAction(type: FacilityType, tags: string[] = [], owner?: string): ActionSpec {
  return {
    kind: "query-facilities",
    type,
    tags,
    limit: 8,
    ...(owner ? { owner } : {}),
  };
}

function queryExistingWorkstationDraft(): PlannedNodeDraft {
  return {
    id: "query_existing_workstation",
    label: "Look up remembered crafting workstations before crafting",
    action: queryFacilitiesAction("workstation", ["crafting_table"]),
    checkpoint: {
      facilityType: "workstation",
      reusePurpose: "crafting",
    },
  };
}

function queryExistingFurnaceDraft(): PlannedNodeDraft {
  return {
    id: "query_existing_furnace",
    label: "Look up remembered furnaces before smelting prerequisites",
    action: queryFacilitiesAction("workstation", ["furnace"]),
    checkpoint: {
      facilityType: "workstation",
      reusePurpose: "smelting",
    },
  };
}

function queryExistingStorageDraft(): PlannedNodeDraft {
  return {
    id: "query_existing_storage",
    label: "Look up remembered home storage before consuming or crafting materials",
    action: queryFacilitiesAction("storage", ["home"]),
    checkpoint: {
      facilityType: "storage",
      reusePurpose: "material-supply",
    },
  };
}

function node(
  id: string,
  label: string,
  action: ActionSpec,
  dependsOn: readonly string[],
  checkpoint: Record<string, unknown> = {},
): WorkNode {
  return {
    id,
    label,
    action,
    dependsOn: [...dependsOn],
    status: "pending",
    attempts: 0,
    progress: 0,
    checkpoint,
  };
}

function chainFromDrafts(firstDependency: string, drafts: readonly PlannedNodeDraft[]): {
  nodes: WorkNode[];
  edges: WorkGraphEdge[];
} {
  const nodes: WorkNode[] = [];
  const edges: WorkGraphEdge[] = [];
  let previous = firstDependency;
  for (const draft of drafts) {
    nodes.push(node(draft.id, draft.label, draft.action, [previous], draft.checkpoint ?? {}));
    edges.push({ from: previous, to: draft.id });
    previous = draft.id;
  }
  return { nodes, edges };
}

function extractCount(text: string, fallback: number, max: number): number {
  const matches = [...text.matchAll(/(?<count>\d{1,4})\s*(?:个|份|块|片|根|组|把|x|×)?/giu)];
  const raw = matches.at(-1)?.groups?.count;
  const parsed = raw ? Number(raw) : fallback;
  return Math.max(1, Math.min(max, Number.isFinite(parsed) ? parsed : fallback));
}

function wantsDelivery(goal: GoalRecord, text: string): boolean {
  return Boolean(goal.spec.deliverTo) || DEFAULT_DELIVERY_PHRASE.test(text);
}

function wantsCompanionRecallBeforeWork(text: string): boolean {
  return COMPANION_RECALL_BEFORE_WORK_PHRASE.test(text);
}

function craftTask(
  goal: GoalRecord,
  itemId: string,
  count = 1,
  options: { deliver?: boolean; placeAtHome?: boolean; nodeNote?: string } = {},
): TaskSpec {
  const deliverTo = options.deliver ? requestedBy(goal) : undefined;
  return {
    kind: "craft",
    itemId,
    count,
    requestedBy: requestedBy(goal),
    note: note(goal, options.nodeNote),
    ...(deliverTo ? { deliverTo } : {}),
    ...(options.placeAtHome ? { placeAtHome: true } : {}),
  };
}

function gatherTask(
  goal: GoalRecord,
  itemId: string,
  count: number,
  options: {
    countMode?: Extract<TaskSpec, { kind: "gather" }>["countMode"];
    nodeNote?: string;
  } = {},
): TaskSpec {
  return {
    kind: "gather",
    itemId,
    count,
    requestedBy: requestedBy(goal),
    note: note(goal, options.nodeNote),
    ...(options.countMode ? { countMode: options.countMode } : {}),
  };
}

function macroTask(goal: GoalRecord, skillId: string, args: Record<string, unknown> = {}, nodeNote?: string): TaskSpec {
  return {
    kind: "macro",
    skillId,
    arguments: args,
    requestedBy: requestedBy(goal),
    note: note(goal, nodeNote),
  };
}

function provisionFoodTask(
  goal: GoalRecord,
  count: number,
  options: {
    source?: Extract<TaskSpec, { kind: "provision-food" }>["source"];
    foodCategory?: Extract<TaskSpec, { kind: "provision-food" }>["foodCategory"];
    destination?: Extract<TaskSpec, { kind: "provision-food" }>["destination"];
  } = {},
): TaskSpec {
  const destination = options.destination ?? "backpack";
  return {
    kind: "provision-food",
    count,
    source: options.source ?? "auto",
    foodCategory: options.foodCategory ?? "any",
    destination,
    requestedBy: requestedBy(goal),
    note: note(goal, "Maintain food reserve before or during the requested goal."),
    ...(destination === "player" ? { player: requestedBy(goal) } : {}),
  };
}

function diamondPickaxePlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const deliver = wantsDelivery(goal, text) || /(?:diamond pickaxe|钻石镐)/iu.test(text);
  return [
    queryExistingWorkstationDraft(),
    queryExistingFurnaceDraft(),
    {
      id: "prepare_food_reserve",
      label: "Prepare food reserve before deep mining",
      action: taskAction(provisionFoodTask(goal, 16)),
      checkpoint: { minimumFoodReserve: 16, reason: "deep-mining" },
    },
    {
      id: "craft_iron_pickaxe",
      label: "Craft or obtain an iron pickaxe for diamond ore",
      action: taskAction(craftTask(goal, "minecraft:iron_pickaxe", 1, { nodeNote: "Search storage first; mine and smelt iron if needed." })),
      checkpoint: {
        prerequisiteFor: "minecraft:diamond",
        preferredWorkstationQueryNodeId: "query_existing_workstation",
        preferredFurnaceQueryNodeId: "query_existing_furnace",
      },
    },
    {
      id: "craft_torches",
      label: "Craft torches for safe descent and branch mining",
      action: taskAction(craftTask(goal, "minecraft:torch", 64, { nodeNote: "Prefer nearby coal; make charcoal from spare logs if coal is unavailable." })),
      checkpoint: { placeWhileMining: true, targetCount: 64 },
    },
    {
      id: "query_existing_diamond_mine",
      label: "Look up remembered diamond mines before opening a new route",
      action: queryFacilitiesAction("mine", ["diamond"]),
      checkpoint: {
        facilityType: "mine",
        reusePurpose: "diamond-mining",
      },
    },
    {
      id: "craft_ladders",
      label: "Craft ladders for safe vertical access",
      action: taskAction(craftTask(goal, "minecraft:ladder", 64, { nodeNote: "Use spare wood to prepare a reversible mine shaft route." })),
      checkpoint: {
        safeAccess: "ladder-shaft",
        targetCount: 64,
        rememberedMineQueryNodeId: "query_existing_diamond_mine",
        requireVerifiedPhysicalReuseBeforeSkipping: true,
      },
    },
    {
      id: "prepare_spare_wood",
      label: "Replenish one full stack of spare wood after crafting mining supplies",
      action: taskAction(gatherTask(goal, GENERIC_LOG_SELECTOR, 64, {
        countMode: "inventory-total",
        nodeNote: "After crafting tools, torches, and ladders, keep one full stack of logs before descending.",
      })),
      checkpoint: { keepInBackpack: true, targetCount: 64, afterSupplyCrafting: true },
    },
    {
      id: "mine_diamonds",
      label: "Deep-mine until enough diamonds are collected",
      action: taskAction(gatherTask(goal, "minecraft:diamond", 3, {
        countMode: "inventory-total",
        nodeNote: "Descend toward diamond layers, light the route, and discard low-value stone only when more inventory slots are required.",
      })),
      checkpoint: {
        targetY: -58,
        discardLowValueStoneWhenInventoryFull: true,
        requiredTool: "minecraft:iron_pickaxe",
      },
    },
    {
      id: "craft_diamond_pickaxe",
      label: deliver ? "Craft the diamond pickaxe and deliver it" : "Craft the diamond pickaxe",
      action: taskAction(craftTask(goal, "minecraft:diamond_pickaxe", 1, {
        deliver,
        ...(deliver ? { nodeNote: "Craft at a reachable crafting table, then return and hand it to the requesting player." } : {}),
      })),
      checkpoint: { output: "minecraft:diamond_pickaxe", deliver, preferredWorkstationQueryNodeId: "query_existing_workstation" },
    },
  ];
}

function torchPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const count = extractCount(text, 64, 256);
  const coalCount = Math.max(1, Math.ceil(count / 4));
  return [
    queryExistingWorkstationDraft(),
    {
      id: "find_coal_for_torches",
      label: "Find or mine coal for torches",
      action: taskAction(gatherTask(goal, "minecraft:coal", coalCount, {
        countMode: "inventory-total",
        nodeNote: "Search reachable nearby coal ore before starting a distant expedition.",
      })),
      checkpoint: { preferNearbyResource: true, requiredFor: "minecraft:torch", count: coalCount },
    },
    {
      id: "prepare_sticks_for_torches",
      label: "Prepare sticks from logs if needed",
      action: taskAction(gatherTask(goal, GENERIC_LOG_SELECTOR, Math.max(8, Math.ceil(count / 8)), {
        countMode: "inventory-total",
        nodeNote: "Use existing logs/planks first; gather logs only if sticks are missing.",
      })),
      checkpoint: { mayShortCircuitIfInventoryHasSticks: true },
    },
    {
      id: "craft_torches",
      label: `Craft ${count} torches`,
      action: taskAction(craftTask(goal, "minecraft:torch", count, {
        deliver: wantsDelivery(goal, text),
        nodeNote: "Use coal or charcoal recipe according to available local resources.",
      })),
      checkpoint: { output: "minecraft:torch", count, preferredWorkstationQueryNodeId: "query_existing_workstation" },
    },
  ];
}

function cropIdFromText(text: string): string {
  return /(?:胡萝卜|carrot)/iu.test(text)
    ? "minecraft:carrots"
    : /(?:土豆|马铃薯|potato)/iu.test(text)
      ? "minecraft:potatoes"
      : /(?:甜菜|beetroot)/iu.test(text)
        ? "minecraft:beetroots"
        : "minecraft:wheat";
}

function farmActionFromText(text: string): Extract<TaskSpec, { kind: "farm" }>["action"] {
  if (/(?:收割|harvest)/iu.test(text)) return "harvest";
  if (/(?:播种|种植|种\b|plant)/iu.test(text)) return "plant";
  return "cycle";
}

function isFarmMaintenanceGoal(text: string): boolean {
  const hasFarmAction = /(?:收割|播种|种植|照料|打理|护理|harvest|plant|tend|cycle)/iu.test(text);
  if (!hasFarmAction) return false;
  const hasBuildIntent = /(?:建造|搭建|新建|建一个|造一个|建立|build|create|setup|set up)/iu.test(text);
  if (hasBuildIntent && /(?:以后|后续|之后|将来|未来|later|future).*?(?:收割|播种|种植|照料|打理|harvest|plant|tend|cycle)/iu.test(text)) {
    return false;
  }
  return true;
}

function farmPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const cropId = cropIdFromText(text);
  return [
    {
      id: "query_existing_farm",
      label: "Look up remembered crop farms before building a new one",
      action: queryFacilitiesAction("farm", ["crop"]),
      checkpoint: {
        facilityType: "farm",
        reusePurpose: "crop-farm",
      },
    },
    {
      id: "find_or_craft_hoe",
      label: "Find or craft a hoe before preparing farmland",
      action: taskAction(craftTask(goal, "minecraft:stone_hoe", 1, { nodeNote: "Search nearby/home storage first; craft a hoe only if none is usable." })),
      checkpoint: { preferExistingTool: true, toolRole: "hoe", skipIfFacilityQueryNodeId: "query_existing_farm" },
    },
    {
      id: "find_or_craft_bucket",
      label: "Find or craft a bucket for irrigation water",
      action: taskAction(craftTask(goal, "minecraft:bucket", 1, { nodeNote: "Search nearby/home storage for a bucket or iron; mine and smelt iron only if needed." })),
      checkpoint: { preferExistingItem: true, itemRole: "water_bucket", skipIfFacilityQueryNodeId: "query_existing_farm" },
    },
    {
      id: "build_crop_farm",
      label: "Build the crop farm blueprint with irrigation",
      action: skillAction("build.crop-farm", { cropId, radius: 12 }),
      checkpoint: {
        facilityType: "farm",
        shouldReuseExistingFacility: true,
        shouldRegisterFacilityAfterBuild: true,
        skipIfFacilityQueryNodeId: "query_existing_farm",
      },
    },
    {
      id: "verify_farm_memory",
      label: "Verify the farm is recorded for later planting and harvesting",
      action: {
        kind: "verify",
        evidenceKind: "fixture",
        expectation: "The crop farm position is stored as a farm facility and reused for future farm commands.",
      },
      checkpoint: { facilityType: "farm", memoryRequired: true },
    },
  ];
}

function farmMaintenancePlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const cropId = cropIdFromText(text);
  const action = farmActionFromText(text);
  return [
    ...farmPlan(goal, text),
    {
      id: "operate_crop_farm",
      label: action === "harvest"
        ? "Harvest the remembered or newly built crop farm"
        : action === "plant"
          ? "Plant crops in the remembered or newly built crop farm"
          : "Cycle the remembered or newly built crop farm",
      action: taskAction({
        kind: "farm",
        cropId,
        action,
        radius: 24,
        requestedBy: requestedBy(goal),
        note: note(goal, "Reuse the recorded farm position when available; build the farm first only if no remembered crop farm exists."),
      }),
      checkpoint: {
        facilityType: "farm",
        preferredFacilityQueryNodeId: "query_existing_farm",
        useRecordedFacility: true,
        buildIfMissing: true,
        cropId,
        farmAction: action,
      },
    },
  ];
}

function bedPlan(goal: GoalRecord): PlannedNodeDraft[] {
  return [
    queryExistingWorkstationDraft(),
    {
      id: "query_existing_home",
      label: "Look up remembered home/spawn facilities before placing a bed",
      action: queryFacilitiesAction("home", ["spawn"]),
      checkpoint: {
        facilityType: "home",
        reusePurpose: "bed-placement",
      },
    },
    {
      id: "craft_and_place_bed",
      label: "Craft and place a bed near the home/spawn area",
      action: skillAction("life.craft-and-place-bed"),
      checkpoint: {
        searchBeyondNearbyArea: true,
        missingWoolStrategy: "find sheep; make shears when iron is available; fall back according to safety rules",
        placeAtHome: true,
        preferredWorkstationQueryNodeId: "query_existing_workstation",
        preferredHomeQueryNodeId: "query_existing_home",
      },
    },
  ];
}

function ranchPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const count = extractCount(text, /(?:猪牛羊|livestock|animals)/iu.test(text) ? 6 : 2, 24);
  const animalType = /(?:猪|pig)/iu.test(text)
    ? "minecraft:pig"
    : /(?:羊|sheep)/iu.test(text)
      ? "minecraft:sheep"
      : /(?:牛|cow)/iu.test(text)
        ? "minecraft:cow"
        : "any";
  return [
    {
      id: "query_existing_ranch",
      label: "Look up remembered ranches before building a new pen",
      action: queryFacilitiesAction("ranch", ["livestock"]),
      checkpoint: {
        facilityType: "ranch",
        reusePurpose: "livestock",
      },
    },
    {
      id: "build_ranch_pen",
      label: "Build and immediately remember the livestock pen",
      action: skillAction("build.animal-pen"),
      checkpoint: {
        facilityType: "ranch",
        shouldRegisterFacilityAfterBuild: true,
        skipIfFacilityQueryNodeId: "query_existing_ranch",
        facilityName: "Remembered livestock pen",
        facilityTags: ["livestock", "animal-pen"],
      },
    },
    {
      id: "establish_ranch",
      label: "Bring livestock to the remembered or newly built pen",
      action: taskAction({
        kind: "ranch",
        action: "establish",
        animalType,
        count,
        radius: 128,
        requestedBy: requestedBy(goal),
        note: note(goal, "Reuse the recorded pen anchor; do not rebuild a completed pen."),
      }),
      checkpoint: {
        facilityType: "ranch",
        preferredFacilityQueryNodeId: "query_existing_ranch",
        preferredFacilityBuildNodeId: "build_ranch_pen",
        useRecordedFacility: true,
        animalType,
        count,
      },
    },
    {
      id: "verify_ranch_memory",
      label: "Verify the ranch is recorded for later breeding, shearing, culling, and food requests",
      action: {
        kind: "verify",
        evidenceKind: "fixture",
        expectation: "The livestock pen is stored as a ranch facility and reused instead of rebuilding a new pen.",
      },
      checkpoint: { facilityType: "ranch", memoryRequired: true },
    },
  ];
}

function isRanchOperationGoal(text: string): boolean {
  return /(?:繁殖|喂养|剪羊毛|剪毛|屠宰|宰杀|杀|收获羊毛|breed|shear|cull)/iu.test(text);
}

function ranchOperationPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const base = ranchPlan(goal, text);
  if (/(?:剪羊毛|剪毛|收获羊毛|shear)/iu.test(text)) {
    return [
      ...base,
      {
        id: "craft_or_find_shears",
        label: "Find or craft shears before collecting wool from the ranch",
        action: taskAction(craftTask(goal, "minecraft:shears", 1, { nodeNote: "Search storage first; craft shears only when none are usable." })),
        checkpoint: {
          preferExistingTool: true,
          toolRole: "shears",
          preferredStorageQueryNodeId: "query_existing_ranch",
        },
      },
      {
        id: "collect_ranch_wool",
        label: "Collect wool from the remembered or newly established sheep ranch",
        action: taskAction(gatherTask(goal, "#minecraft:wool", extractCount(text, 3, 64), {
          nodeNote: "Reuse the recorded sheep ranch; expand search only if the ranch has no shearable sheep.",
        })),
        checkpoint: {
          facilityType: "ranch",
          preferredFacilityQueryNodeId: "query_existing_ranch",
          useRecordedFacility: true,
          resourceStrategy: "shear-sheep-before-hunting",
        },
      },
    ];
  }
  const action: Extract<TaskSpec, { kind: "ranch" }>["action"] = /(?:屠宰|宰杀|杀|cull)/iu.test(text) ? "cull" : "breed";
  return [
    ...base,
    {
      id: `operate_ranch_${action}`,
      label: action === "cull"
        ? "Cull livestock from the remembered or newly established ranch"
        : "Breed livestock in the remembered or newly established ranch",
      action: taskAction({
        kind: "ranch",
        action,
        animalType: /(?:羊|sheep)/iu.test(text)
          ? "minecraft:sheep"
          : /(?:猪|pig)/iu.test(text)
            ? "minecraft:pig"
            : /(?:牛|cow)/iu.test(text)
              ? "minecraft:cow"
              : "any",
        count: extractCount(text, 2, 24),
        radius: 128,
        requestedBy: requestedBy(goal),
        note: note(goal, "Reuse the recorded ranch position when available; establish a ranch first only if none exists."),
      }),
      checkpoint: {
        facilityType: "ranch",
        preferredFacilityQueryNodeId: "query_existing_ranch",
        useRecordedFacility: true,
        buildIfMissing: true,
        ranchAction: action,
      },
    },
  ];
}

function foodPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const count = extractCount(text, 16, 64);
  const wantsMeat = MEAT_PHRASE.test(text);
  const destination = /(?:箱子|仓库|storage|chest)/iu.test(text)
    ? "home-storage" as const
    : wantsDelivery(goal, text)
      ? "player" as const
      : "backpack" as const;
  return [{
    id: "provision_food",
    label: wantsMeat ? "Hunt or prepare meat food" : "Find or prepare food",
    action: taskAction(provisionFoodTask(goal, count, {
      source: wantsMeat ? "hunt" : "auto",
      foodCategory: wantsMeat ? "meat" : "any",
      destination,
    })),
    checkpoint: {
      maintainBackpackFoodReserve: destination === "backpack",
      destination,
      count,
    },
  }];
}

function storagePlan(goal: GoalRecord): PlannedNodeDraft[] {
  return [
    {
      id: "query_existing_storage",
      label: "Look up remembered home storage before building a new storage room",
      action: queryFacilitiesAction("storage", ["home"]),
      checkpoint: {
        facilityType: "storage",
        reusePurpose: "home-storage",
      },
    },
    {
      id: "build_or_find_storage",
      label: "Build or reuse home storage",
      action: skillAction("build.storage-room"),
      checkpoint: {
        facilityType: "storage",
        shouldReuseExistingFacility: true,
        shouldRegisterFacilityAfterBuild: true,
        skipIfFacilityQueryNodeId: "query_existing_storage",
      },
    },
    {
      id: "organize_storage",
      label: "Organize surplus items while keeping active supplies",
      action: taskAction({
        kind: "organize-storage",
        radius: 24,
        requestedBy: requestedBy(goal),
        note: note(goal, "Keep weapons, armor, food, tools, and active-task materials in the backpack."),
      }),
      checkpoint: {
        keepCategories: ["food", "tools", "weapons", "armor", "active-task-materials"],
        storeSurplusCategories: ["low-value-stone", "spare-blocks", "low-tier-equipment"],
      },
    },
  ];
}

function metadataString(goal: GoalRecord, key: string): string | undefined {
  const value = goal.spec.metadata[key];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

function buildFacilityRoute(skillId: string): BuildFacilityRoute {
  const suffix = skillId.replace(/^build\./u, "");
  switch (skillId) {
    case "build.crop-farm":
      return { skillId, label: "crop farm", facilityType: "farm", tags: ["crop", suffix] };
    case "build.storage-room":
      return { skillId, label: "storage room", facilityType: "storage", tags: ["home", suffix] };
    case "build.animal-pen":
      return { skillId, label: "animal pen", facilityType: "ranch", tags: ["livestock", suffix] };
    case "build.cobblestone-generator":
      return { skillId, label: "cobblestone generator", facilityType: "redstone", tags: ["cobblestone-generator", suffix] };
    case "build.mob-farm":
      return { skillId, label: "mob farm", facilityType: "redstone", tags: ["mob-farm", suffix] };
    case "build.tree-farm":
      return { skillId, label: "tree farm", facilityType: "farm", tags: ["tree-farm", suffix] };
    case "build.watchtower":
      return { skillId, label: "watchtower", facilityType: "build", tags: ["watchtower", suffix] };
    case "build.stone-cottage":
      return { skillId, label: "stone cottage", facilityType: "build", tags: ["stone-cottage", suffix] };
    default:
      return { skillId, label: suffix || "structure", facilityType: "build", tags: [suffix || "structure"] };
  }
}

function plannedBuildFacilityPlan(goal: GoalRecord): PlannedNodeDraft[] {
  const metadataSkillId = metadataString(goal, "proposedSkillId") ?? metadataString(goal, "deterministicSkillId");
  if (metadataSkillId?.startsWith("build.")) return plannedBuildFacilityDrafts(metadataSkillId);
  const action = parseDeterministicChatAction(goal.spec.objective || goal.spec.title, requestedBy(goal));
  if (!action || action.operation !== "task") return [];
  if (action.spec.kind !== "macro" || !action.spec.skillId.startsWith("build.")) return [];
  return plannedBuildFacilityDrafts(action.spec.skillId, action.spec.materialPreference);
}

function plannedBuildFacilityDrafts(
  skillId: string,
  materialPreference?: Extract<TaskSpec, { kind: "macro" }>["materialPreference"],
): PlannedNodeDraft[] {
  const route = buildFacilityRoute(skillId);
  const queryId = `query_existing_${route.facilityType}_${route.tags[0]?.replace(/[^a-z0-9_-]+/giu, "_") || "structure"}`;
  return [
    {
      id: queryId,
      label: `Look up remembered ${route.label} facilities before building`,
      action: queryFacilitiesAction(route.facilityType, route.tags.slice(0, 2)),
      checkpoint: {
        facilityType: route.facilityType,
        facilityTags: route.tags,
        reusePurpose: route.label,
      },
    },
    {
      id: "build_requested_structure",
      label: `Build requested ${route.label} blueprint`,
      action: skillAction(route.skillId, {}, {
        ...(materialPreference ? { materialPreference } : {}),
      }),
      checkpoint: {
        facilityType: route.facilityType,
        facilityTags: route.tags,
        shouldReuseExistingFacility: true,
        shouldRegisterFacilityAfterBuild: true,
        skipIfFacilityQueryNodeId: queryId,
        skillId: route.skillId,
      },
    },
    {
      id: "verify_build_memory",
      label: `Verify the ${route.label} is recorded for later reuse`,
      action: {
        kind: "verify",
        evidenceKind: "fixture",
        expectation: `The ${route.label} position is stored as a ${route.facilityType} facility and reused instead of rebuilding.`,
      },
      checkpoint: {
        facilityType: route.facilityType,
        facilityTags: route.tags,
        memoryRequired: true,
      },
    },
  ];
}

function fallbackFromDeterministicChat(goal: GoalRecord): PlannedNodeDraft[] {
  const action = parseDeterministicChatAction(goal.spec.objective || goal.spec.title, requestedBy(goal));
  if (!action || action.operation !== "task") return [];
  return [{
    id: "deterministic_chat_task",
    label: `${action.spec.kind} task from deterministic chat parser`,
    action: taskAction(action.spec),
    checkpoint: { source: "deterministic-chat-parser", reply: action.reply },
  }];
}

type DirectCraftRoute = {
  pattern: RegExp;
  itemId: string;
  label: string;
  materialTier?: "wood" | "stone" | "iron" | "gold" | "diamond" | "utility";
  defaultCount?: number;
};

const DIRECT_CRAFT_ROUTES: readonly DirectCraftRoute[] = [
  { pattern: /(?:钻石镐|diamond pickaxe)/iu, itemId: "minecraft:diamond_pickaxe", label: "diamond pickaxe", materialTier: "diamond" },
  { pattern: /(?:铁镐|iron pickaxe)/iu, itemId: "minecraft:iron_pickaxe", label: "iron pickaxe", materialTier: "iron" },
  { pattern: /(?:石镐|stone pickaxe)/iu, itemId: "minecraft:stone_pickaxe", label: "stone pickaxe", materialTier: "stone" },
  { pattern: /(?:木镐|wooden pickaxe)/iu, itemId: "minecraft:wooden_pickaxe", label: "wooden pickaxe", materialTier: "wood" },
  { pattern: /(?:金镐|golden pickaxe)/iu, itemId: "minecraft:golden_pickaxe", label: "golden pickaxe", materialTier: "gold" },
  { pattern: /(?:钻石斧|diamond axe)/iu, itemId: "minecraft:diamond_axe", label: "diamond axe", materialTier: "diamond" },
  { pattern: /(?:铁斧|iron axe)/iu, itemId: "minecraft:iron_axe", label: "iron axe", materialTier: "iron" },
  { pattern: /(?:石斧|stone axe)/iu, itemId: "minecraft:stone_axe", label: "stone axe", materialTier: "stone" },
  { pattern: /(?:木斧|wooden axe)/iu, itemId: "minecraft:wooden_axe", label: "wooden axe", materialTier: "wood" },
  { pattern: /(?:金斧|golden axe)/iu, itemId: "minecraft:golden_axe", label: "golden axe", materialTier: "gold" },
  { pattern: /(?:钻石铲|diamond shovel)/iu, itemId: "minecraft:diamond_shovel", label: "diamond shovel", materialTier: "diamond" },
  { pattern: /(?:铁铲|iron shovel)/iu, itemId: "minecraft:iron_shovel", label: "iron shovel", materialTier: "iron" },
  { pattern: /(?:石铲|stone shovel)/iu, itemId: "minecraft:stone_shovel", label: "stone shovel", materialTier: "stone" },
  { pattern: /(?:木铲|wooden shovel)/iu, itemId: "minecraft:wooden_shovel", label: "wooden shovel", materialTier: "wood" },
  { pattern: /(?:金铲|golden shovel)/iu, itemId: "minecraft:golden_shovel", label: "golden shovel", materialTier: "gold" },
  { pattern: /(?:钻石锄|diamond hoe)/iu, itemId: "minecraft:diamond_hoe", label: "diamond hoe", materialTier: "diamond" },
  { pattern: /(?:铁锄|iron hoe)/iu, itemId: "minecraft:iron_hoe", label: "iron hoe", materialTier: "iron" },
  { pattern: /(?:石锄|stone hoe)/iu, itemId: "minecraft:stone_hoe", label: "stone hoe", materialTier: "stone" },
  { pattern: /(?:木锄|wooden hoe)/iu, itemId: "minecraft:wooden_hoe", label: "wooden hoe", materialTier: "wood" },
  { pattern: /(?:金锄|golden hoe)/iu, itemId: "minecraft:golden_hoe", label: "golden hoe", materialTier: "gold" },
  { pattern: /(?:钻石剑|diamond sword)/iu, itemId: "minecraft:diamond_sword", label: "diamond sword", materialTier: "diamond" },
  { pattern: /(?:铁剑|iron sword)/iu, itemId: "minecraft:iron_sword", label: "iron sword", materialTier: "iron" },
  { pattern: /(?:石剑|stone sword)/iu, itemId: "minecraft:stone_sword", label: "stone sword", materialTier: "stone" },
  { pattern: /(?:木剑|wooden sword)/iu, itemId: "minecraft:wooden_sword", label: "wooden sword", materialTier: "wood" },
  { pattern: /(?:金剑|golden sword)/iu, itemId: "minecraft:golden_sword", label: "golden sword", materialTier: "gold" },
  { pattern: /(?:铁头盔|iron helmet)/iu, itemId: "minecraft:iron_helmet", label: "iron helmet", materialTier: "iron" },
  { pattern: /(?:铁胸甲|iron chestplate)/iu, itemId: "minecraft:iron_chestplate", label: "iron chestplate", materialTier: "iron" },
  { pattern: /(?:铁护腿|iron leggings)/iu, itemId: "minecraft:iron_leggings", label: "iron leggings", materialTier: "iron" },
  { pattern: /(?:铁靴|iron boots)/iu, itemId: "minecraft:iron_boots", label: "iron boots", materialTier: "iron" },
  { pattern: /(?:钻石头盔|diamond helmet)/iu, itemId: "minecraft:diamond_helmet", label: "diamond helmet", materialTier: "diamond" },
  { pattern: /(?:钻石胸甲|diamond chestplate)/iu, itemId: "minecraft:diamond_chestplate", label: "diamond chestplate", materialTier: "diamond" },
  { pattern: /(?:钻石护腿|diamond leggings)/iu, itemId: "minecraft:diamond_leggings", label: "diamond leggings", materialTier: "diamond" },
  { pattern: /(?:钻石靴|diamond boots)/iu, itemId: "minecraft:diamond_boots", label: "diamond boots", materialTier: "diamond" },
  { pattern: /(?:盾牌|shield)/iu, itemId: "minecraft:shield", label: "shield", materialTier: "iron" },
  { pattern: /(?:剪刀|shears?)/iu, itemId: "minecraft:shears", label: "shears", materialTier: "iron" },
  { pattern: /(?:铁桶|水桶|桶|bucket)/iu, itemId: "minecraft:bucket", label: "bucket", materialTier: "iron" },
  { pattern: /(?:打火石|flint and steel)/iu, itemId: "minecraft:flint_and_steel", label: "flint and steel", materialTier: "iron" },
  { pattern: /(?:弓|bow)/iu, itemId: "minecraft:bow", label: "bow", materialTier: "utility" },
  { pattern: /(?:箭|arrow)/iu, itemId: "minecraft:arrow", label: "arrows", materialTier: "utility", defaultCount: 16 },
  { pattern: /(?:钓鱼竿|鱼竿|fishing rod)/iu, itemId: "minecraft:fishing_rod", label: "fishing rod", materialTier: "utility" },
  { pattern: /(?:熔炉|furnace)/iu, itemId: "minecraft:furnace", label: "furnace", materialTier: "stone" },
  { pattern: /(?:工作台|crafting table)/iu, itemId: "minecraft:crafting_table", label: "crafting table", materialTier: "wood" },
  { pattern: /(?:箱子|chest)/iu, itemId: "minecraft:chest", label: "chest", materialTier: "wood" },
  { pattern: /(?:火把|torch|torches)/iu, itemId: "minecraft:torch", label: "torches", materialTier: "utility", defaultCount: 64 },
  { pattern: /(?:锄头|锄\b|hoe)/iu, itemId: "minecraft:stone_hoe", label: "stone hoe", materialTier: "stone" },
  { pattern: /(?:镐子|镐\b|pickaxe)/iu, itemId: "minecraft:stone_pickaxe", label: "stone pickaxe", materialTier: "stone" },
  { pattern: /(?:斧子|斧\b|axe)/iu, itemId: "minecraft:stone_axe", label: "stone axe", materialTier: "stone" },
  { pattern: /(?:铲子|铲\b|shovel)/iu, itemId: "minecraft:stone_shovel", label: "stone shovel", materialTier: "stone" },
  { pattern: /(?:剑|武器|sword|weapon)/iu, itemId: "minecraft:stone_sword", label: "stone sword", materialTier: "stone" },
];

function materialCheckpoint(route: DirectCraftRoute): Record<string, unknown> {
  switch (route.materialTier) {
    case "diamond":
      return {
        searchStorageFirst: true,
        missingMaterialChain: ["prepare iron pickaxe", "deep mine diamonds", "return to workstation"],
        requiredToolForOre: "minecraft:iron_pickaxe",
      };
    case "iron":
    case "gold":
      return {
        searchStorageFirst: true,
        preferredFurnaceQueryNodeId: "query_existing_furnace",
        missingMaterialChain: ["search storage", "mine ore", "smelt ingots", "craft requested item"],
      };
    case "stone":
      return {
        searchStorageFirst: true,
        missingMaterialChain: ["search storage", "gather stone-tier materials", "craft requested item"],
      };
    case "wood":
      return {
        searchStorageFirst: true,
        missingMaterialChain: ["search storage", "gather logs", "craft planks/sticks if needed", "craft requested item"],
      };
    case "utility":
    default:
      return {
        searchStorageFirst: true,
        missingMaterialChain: ["search storage", "gather prerequisite resources", "craft requested item"],
      };
  }
}

function directCraftPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const route = DIRECT_CRAFT_ROUTES.find((candidate) => candidate.pattern.test(text));
  if (!route) return [];
  const count = extractCount(text, route.defaultCount ?? 1, 256);
  const drafts: PlannedNodeDraft[] = [
    queryExistingWorkstationDraft(),
    queryExistingStorageDraft(),
  ];
  if (route.materialTier === "iron" || route.materialTier === "gold") drafts.push(queryExistingFurnaceDraft());
  if (route.materialTier === "diamond" && route.itemId !== "minecraft:diamond_pickaxe") {
    drafts.push({
      id: "query_existing_diamond_mine",
      label: "Look up remembered diamond mines before crafting diamond gear",
      action: queryFacilitiesAction("mine", ["diamond"]),
      checkpoint: {
        facilityType: "mine",
        reusePurpose: "diamond-materials",
      },
    });
  }
  drafts.push({
    id: "craft_requested_item",
    label: `Craft ${route.label}`,
    action: taskAction(craftTask(goal, route.itemId, count, {
      deliver: wantsDelivery(goal, text),
      nodeNote: "Use existing nearby workstations and storage first; gather, mine, or smelt prerequisites only when missing.",
    })),
    checkpoint: {
      output: route.itemId,
      count,
      preferredWorkstationQueryNodeId: "query_existing_workstation",
      preferredStorageQueryNodeId: "query_existing_storage",
      ...materialCheckpoint(route),
    },
  });
  return drafts;
}

function kitPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const proposed = metadataString(goal, "proposedSkillId");
  const skillId = proposed === "craft.iron-equipment" || /(?:铁质装备|铁装备|铁甲|防具|护甲|盔甲|装备|iron equipment|iron armor)/iu.test(text)
    ? "craft.iron-equipment"
    : proposed === "craft.starter-tools" || /(?:基础工具|新手工具|工具套装|starter tools)/iu.test(text)
      ? "craft.starter-tools"
      : proposed === "craft.building-materials" || /(?:建筑材料|building materials)/iu.test(text)
        ? "craft.building-materials"
        : null;
  if (!skillId) return [];
  const drafts: PlannedNodeDraft[] = [queryExistingWorkstationDraft(), queryExistingStorageDraft()];
  if (skillId === "craft.iron-equipment") drafts.push(queryExistingFurnaceDraft());
  if (skillId === "craft.building-materials") {
    drafts.push({
      id: "prepare_building_wood",
      label: "Prepare generic logs for building material crafting",
      action: taskAction(gatherTask(goal, GENERIC_LOG_SELECTOR, 64, {
        countMode: "inventory-total",
        nodeNote: "Use existing planks/logs first; gather one stack only when building stock is missing.",
      })),
      checkpoint: { targetCount: 64, materialRole: "building-material-stock" },
    });
  }
  return [
    ...drafts,
    {
      id: "craft_requested_kit",
      label: `Run ${skillId} as a recoverable equipment/material work chain`,
      action: skillAction(skillId, skillId === "craft.iron-equipment" ? { ironInput: "minecraft:raw_iron", ironCount: 32 } : {}),
      checkpoint: {
        skillId,
        preferredWorkstationQueryNodeId: "query_existing_workstation",
        preferredStorageQueryNodeId: "query_existing_storage",
        ...(skillId === "craft.iron-equipment" ? {
          preferredFurnaceQueryNodeId: "query_existing_furnace",
          autoEquipBetterArmorAndWeapons: true,
          storeLowTierSpareEquipment: true,
        } : {}),
      },
    },
    ...(skillId === "craft.iron-equipment"
      ? [{
          id: "verify_auto_equipment_policy",
          label: "Verify better gear can be auto-equipped and spare low-tier gear can be stored",
          action: {
            kind: "verify" as const,
            evidenceKind: "fixture" as const,
            expectation: "The NPC keeps food/tools, equips better iron gear, and stores lower-tier spare gear when storage is available.",
          },
          checkpoint: { equipmentPolicy: "auto-equip-best", memoryRequired: false },
        }]
      : []),
  ];
}

function dragonPlan(goal: GoalRecord, text: string): PlannedNodeDraft[] {
  const proposed = metadataString(goal, "proposedSkillId") ?? metadataString(goal, "deterministicSkillId");
  const drafts: PlannedNodeDraft[] = [{
    id: "query_dragon_landing_area",
    label: "Look up remembered safe dragon landing areas before riding or dismounting",
    action: queryFacilitiesAction("dragon-landing", ["dragon"]),
    checkpoint: {
      facilityType: "dragon-landing",
      reusePurpose: "dragon-movement-safety",
    },
  }];
  if (proposed === "dragon.shared-ride" || /(?:同骑|共骑|一起骑|一同骑|共享座位|双人骑乘|同乘|share.?ride|co.?ride)/iu.test(text)) {
    drafts.push({
      id: "share_ride_dragon",
      label: "Mount player and NPC together on a compatible owned dragon",
      action: skillAction("dragon.shared-ride", { targetId: "" }),
      checkpoint: {
        supportedMods: ["bookofdragons", "saintsdragons"],
        preferredLandingQueryNodeId: "query_dragon_landing_area",
        playerSeat: "front",
        companionSeat: "rear",
        avoidDismountUntilLanded: true,
      },
    });
    return drafts;
  }
  if (proposed === "dragon.mount-and-follow" || /(?:骑龙|上龙|mount.*dragon|dragon.*follow)/iu.test(text)) {
    drafts.push({
      id: "mount_and_follow_dragon",
      label: "Mount a compatible owned dragon and follow the player",
      action: skillAction("dragon.mount-and-follow", { targetId: "" }),
      checkpoint: {
        supportedMods: ["bookofdragons", "saintsdragons"],
        preferredLandingQueryNodeId: "query_dragon_landing_area",
        avoidDismountUntilLanded: true,
      },
    });
    return drafts;
  }
  const action: Extract<TaskSpec, { kind: "dragon" }>["action"] = /(?:协战|帮我打|assist|combat)/iu.test(text)
    ? "assist-combat"
    : /(?:召回|叫回|recall)/iu.test(text)
      ? "recall"
      : /(?:降落|落地|land)/iu.test(text)
        ? "land"
        : /(?:下来|下龙|dismount)/iu.test(text)
          ? "dismount"
          : "observe";
  drafts.push({
    id: `dragon_${action.replace(/[^a-z0-9]+/giu, "_")}`,
    label: `Run dragon action: ${action}`,
    action: taskAction({
      kind: "dragon",
      action,
      requestedBy: requestedBy(goal),
      note: note(goal, "Use compatible Book of Dragons or Saints Dragons adapters; prefer safe landing before dismounting."),
    }),
    checkpoint: {
      supportedMods: ["bookofdragons", "saintsdragons"],
      preferredLandingQueryNodeId: "query_dragon_landing_area",
      avoidDismountUntilLanded: action === "dismount",
    },
  });
  return drafts;
}

export function planGoal(goal: GoalRecord, firstDependency = "knowledge_lookup"): GoalPlannerResult {
  const text = normalizeGoalText(goal);
  const lower = text.toLocaleLowerCase("en-US");
  const drafts = (() => {
    const proposedItemId = metadataString(goal, "proposedItemId");
    if (proposedItemId === "minecraft:diamond_pickaxe" || /(?:钻石镐|diamond pickaxe|diamond_pickaxe)/iu.test(text)) return diamondPickaxePlan(goal, text);
    if (proposedItemId === "minecraft:torch" || /(?:火把|torch|torches)/iu.test(text)) return torchPlan(goal, text);
    if (/(?:床|bed)/iu.test(text)) return bedPlan(goal);
    if (/(?:农田|农场|田地|作物|farm|crop field|farmland|crop)/iu.test(text)) {
      return isFarmMaintenanceGoal(text) ? farmMaintenancePlan(goal, text) : farmPlan(goal, text);
    }
    if (/(?:畜牧|牧场|围栏|猪牛羊|牲畜|剪羊毛|剪毛|繁殖|屠宰|ranch|livestock|animal pen|shear|breed|cull)/iu.test(text)) {
      return isRanchOperationGoal(text) ? ranchOperationPlan(goal, text) : ranchPlan(goal, text);
    }
    if (FOOD_PHRASE.test(text) || MEAT_PHRASE.test(text)) return foodPlan(goal, text);
    if (STORAGE_GOAL_PHRASE.test(text)) return storagePlan(goal);
    const kit = kitPlan(goal, text);
    if (kit.length > 0) return kit;
    const craft = directCraftPlan(goal, text);
    if (craft.length > 0 && /(?:制作|做|打造|合成|生产|craft|make|give|want|需要|想要|要)/iu.test(text)) {
      return craft;
    }
    const proposedSkillId = metadataString(goal, "proposedSkillId") ?? metadataString(goal, "deterministicSkillId");
    if (proposedSkillId?.startsWith("dragon.") || /(?:龙|dragon|bookofdragons|saintsdragons)/iu.test(text)) return dragonPlan(goal, text);
    const build = plannedBuildFacilityPlan(goal);
    if (build.length > 0) return build;
    return fallbackFromDeterministicChat(goal);
  })();

  if (drafts.length === 0) {
    const blocked = node(
      "await_plan",
      "Await local planner",
      {
        kind: "noop",
        note: "Goal was accepted, but the local planner does not yet recognize this objective.",
      },
      [firstDependency],
      { planner: "local", recognized: false, normalizedGoal: lower.slice(0, 200) },
    );
    blocked.status = "blocked";
    return {
      nodes: [blocked],
      edges: [{ from: firstDependency, to: blocked.id }],
      status: "draft",
      message: "Goal recorded; waiting for a supported local planner route",
    };
  }

  const routedDrafts = wantsCompanionRecallBeforeWork(text)
    ? [{
        id: "recall_companion",
        label: "Recall the companion before starting the requested work",
        action: { kind: "control", action: "recall" } as ActionSpec,
        checkpoint: {
          controlPriority: true,
          returnBeforeWork: true,
          resumePlannedWorkAfterRecall: true,
        },
      }, ...drafts]
    : drafts;
  const { nodes, edges } = chainFromDrafts(firstDependency, routedDrafts);
  return {
    nodes,
    edges,
    status: "ready",
    message: `Goal planned locally with ${nodes.length} actionable work node${nodes.length === 1 ? "" : "s"}`,
  };
}
