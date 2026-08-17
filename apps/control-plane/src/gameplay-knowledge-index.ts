import {
  knowledgeRecordSchema,
  type KnowledgeRecord,
  type KnowledgeTopic,
} from "@mc/protocol";

const BUILTIN_GAMEPLAY_KNOWLEDGE_VERSION = "1.0.0";
const BUILTIN_GAMEPLAY_KNOWLEDGE_UPDATED_AT = "2026-08-16T00:00:00.000Z";

const SOURCE_PRIORITY: Record<KnowledgeRecord["source"], number> = {
  "world-state": 0,
  datapack: 1,
  "vanilla-registry": 2,
  "mod-adapter": 3,
  builtin: 4,
  skill: 5,
  "ai-suggestion": 6,
};

function builtinVanillaRecord(input: Omit<KnowledgeRecord, "version" | "gameVersion" | "source" | "confidence" | "updatedAt">): KnowledgeRecord {
  return knowledgeRecordSchema.parse({
    version: BUILTIN_GAMEPLAY_KNOWLEDGE_VERSION,
    gameVersion: "1.20.1",
    source: "builtin",
    confidence: "authoritative",
    updatedAt: BUILTIN_GAMEPLAY_KNOWLEDGE_UPDATED_AT,
    ...input,
  });
}

export const BUILTIN_GAMEPLAY_KNOWLEDGE: readonly KnowledgeRecord[] = Object.freeze([
  builtinVanillaRecord({
    id: "minecraft:crafting.diamond_pickaxe",
    topic: "crafting",
    inputs: ["minecraft:diamond", "minecraft:stick", "minecraft:crafting_table"],
    outputs: ["minecraft:diamond_pickaxe"],
    tags: ["tool", "pickaxe", "mining", "workstation"],
    summary: "Craft one diamond pickaxe from 3 diamonds and 2 sticks at a crafting table.",
    facts: {
      recipe: { "minecraft:diamond": 3, "minecraft:stick": 2 },
      workstation: "minecraft:crafting_table",
      prerequisiteToolChain: ["minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.iron_pickaxe",
    topic: "crafting",
    inputs: ["minecraft:iron_ingot", "minecraft:stick", "minecraft:crafting_table"],
    outputs: ["minecraft:iron_pickaxe"],
    tags: ["tool", "pickaxe", "mining", "workstation"],
    summary: "Craft one iron pickaxe from 3 iron ingots and 2 sticks at a crafting table.",
    facts: {
      recipe: { "minecraft:iron_ingot": 3, "minecraft:stick": 2 },
      workstation: "minecraft:crafting_table",
      prerequisiteToolChain: ["minecraft:wooden_pickaxe", "minecraft:stone_pickaxe"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.stone_pickaxe",
    topic: "crafting",
    inputs: ["#minecraft:stone_tool_materials", "minecraft:stick", "minecraft:crafting_table"],
    outputs: ["minecraft:stone_pickaxe"],
    tags: ["tool", "pickaxe", "mining", "workstation"],
    summary: "Craft one stone pickaxe from 3 stone-tier materials and 2 sticks at a crafting table.",
    facts: {
      recipe: { "#minecraft:stone_tool_materials": 3, "minecraft:stick": 2 },
      workstation: "minecraft:crafting_table",
      prerequisiteToolChain: ["minecraft:wooden_pickaxe"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.torch",
    topic: "crafting",
    inputs: ["minecraft:coal", "minecraft:charcoal", "minecraft:stick"],
    outputs: ["minecraft:torch"],
    tags: ["lighting", "mining", "crafting", "safety"],
    summary: "Craft torches from sticks and coal or charcoal; 1 coal plus 1 stick produces 4 torches.",
    facts: {
      recipeOptions: [
        { "minecraft:coal": 1, "minecraft:stick": 1, outputCount: 4 },
        { "minecraft:charcoal": 1, "minecraft:stick": 1, outputCount: 4 },
      ],
      nearbyCoalCanSatisfyTorchRequests: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:mining.diamond_preparation",
    topic: "mining",
    inputs: ["minecraft:iron_pickaxe", "minecraft:torch", "minecraft:ladder", "#minecraft:logs", "minecraft:food"],
    outputs: ["minecraft:diamond", "minecraft:deepslate_diamond_ore"],
    tags: ["diamond", "deep-mining", "expedition", "inventory-cleanup"],
    summary: "Deep diamond mining should prepare an iron pickaxe, torches, food, spare wood, and safe vertical access before descending.",
    facts: {
      targetY: -58,
      minimumFoodReserve: 16,
      spareWoodTarget: 64,
      placeTorchesWhileDescending: true,
      discardLowValueStoneWhenInventoryFull: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.white_bed",
    topic: "crafting",
    inputs: ["#minecraft:wool", "#minecraft:planks", "minecraft:crafting_table"],
    outputs: ["minecraft:white_bed"],
    tags: ["bed", "sleep", "spawn", "sheep", "workstation"],
    summary: "Craft a bed from 3 matching wool and 3 planks at a crafting table, then place it near the home or requested room.",
    facts: {
      recipe: { "#minecraft:wool": 3, "#minecraft:planks": 3 },
      woolSources: ["shear sheep with shears", "kill sheep when allowed"],
      workstation: "minecraft:crafting_table",
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.bucket",
    topic: "crafting",
    inputs: ["minecraft:iron_ingot", "minecraft:crafting_table"],
    outputs: ["minecraft:bucket"],
    tags: ["bucket", "water", "farm", "utility", "workstation"],
    summary: "Craft a bucket from 3 iron ingots at a crafting table; farm, lava, and water workflows should search storage first, then mine and smelt iron only when needed.",
    facts: {
      recipe: { "minecraft:iron_ingot": 3 },
      workstation: "minecraft:crafting_table",
      prerequisiteChains: ["search home storage", "mine iron ore or raw iron", "smelt iron ingots", "craft bucket", "fill from reachable water source"],
      preferExistingItem: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.shears",
    topic: "crafting",
    inputs: ["minecraft:iron_ingot", "minecraft:crafting_table"],
    outputs: ["minecraft:shears"],
    tags: ["shears", "wool", "sheep", "bed", "utility"],
    summary: "Craft shears from 2 iron ingots, then use them on sheep for renewable wool when the player asks for beds or wool without killing livestock.",
    facts: {
      recipe: { "minecraft:iron_ingot": 2 },
      workstation: "minecraft:crafting_table",
      preferredWoolStrategy: "shear sheep when shears can be made safely",
      fallbackWoolStrategy: "hunt sheep only when allowed by the active safety rules",
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.stone_hoe",
    topic: "crafting",
    inputs: ["#minecraft:stone_tool_materials", "minecraft:stick", "minecraft:crafting_table"],
    outputs: ["minecraft:stone_hoe"],
    tags: ["hoe", "farm", "tool", "workstation"],
    summary: "Craft a stone hoe from 2 stone-tier materials and 2 sticks at a crafting table; farm setup should reuse any existing usable hoe first.",
    facts: {
      recipe: { "#minecraft:stone_tool_materials": 2, "minecraft:stick": 2 },
      workstation: "minecraft:crafting_table",
      preferExistingTool: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:farming.crop_field",
    topic: "farming",
    inputs: ["minecraft:hoe", "minecraft:water_bucket", "#minecraft:seeds", "minecraft:farmland"],
    outputs: ["minecraft:wheat", "minecraft:carrot", "minecraft:potato"],
    tags: ["farm", "facility", "water", "hoe", "planting", "harvest"],
    summary: "A reusable crop field needs tilled farmland, nearby water, seeds or crops, and a recorded facility position for later planting and harvesting.",
    facts: {
      waterHydratesFarmlandRadius: 4,
      shouldRegisterFacility: true,
      prerequisites: ["craft or find hoe", "craft or find bucket", "fill bucket with water", "place water", "till soil", "plant crop"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:ranch.livestock_pen",
    topic: "ranching",
    inputs: ["minecraft:fence", "minecraft:fence_gate", "minecraft:lead", "minecraft:wheat", "minecraft:carrot"],
    outputs: ["minecraft:porkchop", "minecraft:beef", "minecraft:mutton", "minecraft:wool"],
    tags: ["ranch", "facility", "food", "livestock", "breeding"],
    summary: "A livestock ranch should record the pen as a facility, lure or lead animals home, close the gate, then breed, shear, or cull according to the player's request.",
    facts: {
      supportedAnimals: ["minecraft:pig", "minecraft:cow", "minecraft:sheep"],
      shouldRegisterFacility: true,
      foodSources: { "minecraft:cow": "minecraft:wheat", "minecraft:sheep": "minecraft:wheat", "minecraft:pig": "minecraft:carrot" },
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:storage.home_chest",
    topic: "storage",
    inputs: ["minecraft:chest", "#minecraft:logs", "#minecraft:planks"],
    outputs: ["minecraft:chest"],
    tags: ["home", "storage", "facility", "inventory"],
    summary: "Home storage should be registered as a facility and used to deposit surplus blocks while keeping food, tools, weapons, armor, and active-task materials.",
    facts: {
      shouldRegisterFacility: true,
      keepCategories: ["food", "tools", "weapons", "armor", "active-task-materials"],
      storeSurplusCategories: ["low-value-stone", "spare-blocks", "low-tier-equipment"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:building.blueprint_facility_memory",
    topic: "building",
    inputs: ["minecraft:blueprint", "minecraft:building_materials", "minecraft:home"],
    outputs: ["minecraft:structure"],
    tags: ["building", "blueprint", "facility", "memory", "resume"],
    summary: "Blueprint builds should query matching remembered facilities first, resume from the last failed block when retrying, and register the completed footprint for later reuse.",
    facts: {
      queryBeforeBuild: true,
      shouldRegisterFacility: true,
      resumeFailedBuildFromCheckpoint: true,
      clearReplaceableObstaclesAndRetrySameIndex: true,
      supportedBuiltinSkills: [
        "build.basic-shelter",
        "build.stone-cottage",
        "build.watchtower",
        "build.storage-room",
        "build.crop-farm",
        "build.animal-pen",
        "build.tree-farm",
      ],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:redstone.cobblestone_generator",
    topic: "redstone",
    inputs: ["minecraft:cobblestone", "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:bucket"],
    outputs: ["minecraft:cobblestone"],
    tags: ["redstone", "cobblestone-generator", "facility", "blueprint"],
    summary: "A safe cobblestone generator workflow prepares buckets, water, lava, and nonflammable blocks, builds from the local blueprint, and records it as a redstone facility.",
    facts: {
      skillId: "build.cobblestone-generator",
      needsWater: true,
      needsLava: true,
      shouldRegisterFacility: true,
      avoidWoodNearLava: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:redstone.mob_farm",
    topic: "redstone",
    inputs: ["minecraft:cobblestone", "minecraft:water_bucket", "minecraft:trapdoor", "minecraft:torch"],
    outputs: ["minecraft:bone", "minecraft:string", "minecraft:gunpowder", "minecraft:rotten_flesh"],
    tags: ["redstone", "mob-farm", "facility", "blueprint", "safety"],
    summary: "A basic dark mob farm is a large survival build: prepare blocks, water, lighting, safe access, and register the finished facility for later collection.",
    facts: {
      skillId: "build.mob-farm",
      shouldRegisterFacility: true,
      keepBuildAreaLitUntilSpawnRoomIsSealed: true,
      neverClaimToPlaceSpawnerBlocks: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:dragon.shared_riding",
    topic: "dragon",
    inputs: ["bookofdragons:dragon", "saintsdragons:dragon", "minecraft:saddle"],
    outputs: ["minecraft:travel"],
    tags: ["dragon", "bookofdragons", "saintsdragons", "ride", "follow", "landing"],
    summary: "Supported dragon adapters can expose riding, landing, recall, combat assist, and shared-ride state; record safe landing areas and prefer landing before dismounting.",
    facts: {
      supportedMods: ["bookofdragons", "saintsdragons"],
      recordLandingFacility: true,
      dismountSafety: "land or descend before releasing a rider",
      actions: ["observe", "mount", "share-ride", "land", "fly-to", "recall", "assist-combat", "dismount"],
      sharedRideSeating: { player: "front", companion: "rear" },
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:equipment.auto_equip",
    topic: "combat",
    inputs: ["minecraft:armor", "minecraft:weapon", "minecraft:shield"],
    outputs: ["minecraft:defense"],
    tags: ["equipment", "armor", "weapon", "auto-equip", "survival"],
    summary: "NPC survival workflows should auto-equip better armor and weapons, keep food and active tools, and store lower-tier spare gear in home storage.",
    facts: {
      preferHigherArmorValue: true,
      preferHigherWeaponDamage: true,
      keepFoodReserve: true,
      storeLowTierSpareEquipment: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:food.find_food",
    topic: "food",
    inputs: ["minecraft:food", "minecraft:animal", "minecraft:crop"],
    outputs: ["minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton", "minecraft:bread"],
    tags: ["food", "survival", "hunt", "forage", "ranch"],
    summary: "Food goals can hunt nearby livestock, harvest crops, cook raw meat when fuel and furnace are available, or establish a ranch for renewable food.",
    facts: {
      preferCookedFoodWhenFurnaceAvailable: true,
      maintainBackpackFoodReserve: true,
      lowFoodThreshold: 10,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:workstation.crafting_table",
    topic: "crafting",
    inputs: ["#minecraft:planks"],
    outputs: ["minecraft:crafting_table"],
    tags: ["workstation", "crafting", "facility"],
    summary: "Before crafting, reuse a reachable nearby crafting table or registered workstation; if none is reachable, craft and place one without blocking the path.",
    facts: {
      recipe: { "#minecraft:planks": 4 },
      preferExistingFacility: true,
      avoidBlockingPath: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:smelting.iron_ingot",
    topic: "smelting",
    inputs: ["minecraft:raw_iron", "minecraft:iron_ore", "minecraft:furnace", "minecraft:fuel"],
    outputs: ["minecraft:iron_ingot"],
    tags: ["smelting", "iron", "furnace", "mining"],
    summary: "Iron tools require iron ingots; smelt raw iron or iron ore in a furnace with fuel before crafting.",
    facts: {
      fuelTags: ["minecraft:coals", "#minecraft:logs", "#minecraft:planks"],
      workstation: "minecraft:furnace",
      outputFromRawIron: "minecraft:iron_ingot",
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.tool_material_families",
    topic: "crafting",
    inputs: ["#minecraft:logs", "#minecraft:planks", "#minecraft:stone_tool_materials", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"],
    outputs: [
      "minecraft:wooden_pickaxe",
      "minecraft:stone_pickaxe",
      "minecraft:iron_pickaxe",
      "minecraft:diamond_pickaxe",
      "minecraft:iron_sword",
      "minecraft:shield",
    ],
    tags: ["tool", "weapon", "axe", "shovel", "hoe", "sword", "shield", "progression"],
    summary: "Tool and weapon requests should reuse storage first, then resolve missing materials through the wood, stone, iron, gold, or diamond progression chain before crafting.",
    facts: {
      sharedStickTools: ["pickaxe", "axe", "shovel", "hoe", "sword"],
      woodChain: ["logs", "planks", "sticks", "wooden tools"],
      stoneChain: ["wooden pickaxe when needed", "stone-tier material", "stone tools"],
      ironChain: ["stone pickaxe when needed", "mine raw iron", "smelt iron ingots", "iron tools and armor"],
      diamondChain: ["iron pickaxe", "deep mine diamonds", "diamond tools and armor"],
      shieldRecipe: { "minecraft:iron_ingot": 1, "#minecraft:planks": 6 },
      avoidGenericIds: ["minecraft:pickaxe", "minecraft:axe", "minecraft:shovel", "minecraft:hoe", "minecraft:melee_weapon"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:equipment.iron_gear_set",
    topic: "combat",
    inputs: ["minecraft:raw_iron", "minecraft:iron_ingot", "minecraft:stick", "#minecraft:planks", "minecraft:furnace", "minecraft:crafting_table"],
    outputs: [
      "minecraft:iron_sword",
      "minecraft:iron_pickaxe",
      "minecraft:shield",
      "minecraft:iron_helmet",
      "minecraft:iron_chestplate",
      "minecraft:iron_leggings",
      "minecraft:iron_boots",
    ],
    tags: ["equipment", "iron", "armor", "weapon", "shield", "auto-equip", "storage"],
    summary: "An iron equipment set prepares or smelts enough iron, crafts sword, pickaxe, shield, and armor, then lets the NPC equip better gear and store lower-tier spares.",
    facts: {
      skillId: "craft.iron-equipment",
      defaultRawIronTarget: 32,
      craftOutputsInOrder: [
        "minecraft:iron_sword",
        "minecraft:iron_pickaxe",
        "minecraft:shield",
        "minecraft:iron_helmet",
        "minecraft:iron_chestplate",
        "minecraft:iron_leggings",
        "minecraft:iron_boots",
      ],
      autoEquipAfterCraft: true,
      storeLowTierSpareEquipment: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:farming.facility_reuse_operations",
    topic: "farming",
    inputs: ["minecraft:hoe", "minecraft:water_bucket", "#minecraft:seeds", "minecraft:farm"],
    outputs: ["minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot"],
    tags: ["farm", "facility", "reuse", "plant", "harvest", "cycle"],
    summary: "Planting, harvesting, and crop-cycle requests should reuse a recorded crop-farm facility; only build a new farm when no suitable facility exists.",
    facts: {
      queryFacilityBeforeOperation: true,
      facilityType: "farm",
      requiredTags: ["crop"],
      supportedActions: ["plant", "harvest", "cycle"],
      buildIfMissing: true,
      doNotCreateDuplicateFarmWhenFacilityExists: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:ranch.facility_reuse_operations",
    topic: "ranching",
    inputs: ["minecraft:ranch", "minecraft:wheat", "minecraft:carrot", "minecraft:shears"],
    outputs: ["minecraft:wool", "minecraft:beef", "minecraft:porkchop", "minecraft:mutton"],
    tags: ["ranch", "facility", "reuse", "breed", "shear", "cull", "livestock"],
    summary: "Breeding, shearing, and culling requests should reuse a recorded ranch facility; establish a new pen only when no suitable ranch exists.",
    facts: {
      queryFacilityBeforeOperation: true,
      facilityType: "ranch",
      requiredTags: ["livestock"],
      supportedActions: ["breed", "shear", "cull"],
      shearRequires: "minecraft:shears",
      buildIfMissing: true,
      doNotCreateDuplicateRanchWhenFacilityExists: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:mining.local_ore_vein_priority",
    topic: "mining",
    inputs: ["minecraft:coal_ore", "minecraft:iron_ore", "minecraft:deepslate_diamond_ore", "minecraft:pickaxe"],
    outputs: ["minecraft:coal", "minecraft:raw_iron", "minecraft:diamond"],
    tags: ["mining", "nearby-resource", "ore-vein", "complete-vein", "expedition"],
    summary: "Ore gathering should finish reachable nearby veins before expanding to distant search regions; torch requests should mine visible coal before charcoal fallback.",
    facts: {
      preferNearbyOreBeforeExpedition: true,
      completeReachableVeinBeforeMovingFarther: true,
      torchCoalPriority: "nearby coal ore, then stored coal, then charcoal",
      expandSearchOnlyAfterLocalCandidatesExhausted: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:storage.inventory_pressure_cleanup",
    topic: "storage",
    inputs: ["minecraft:cobblestone", "minecraft:deepslate", "minecraft:dirt", "minecraft:storage"],
    outputs: ["minecraft:free_inventory_slots"],
    tags: ["storage", "inventory", "cleanup", "mining", "discard", "multi-stack"],
    summary: "When inventory space blocks an active task, reserve tools, weapons, armor, food, and task materials, then store or discard as many low-value stone stacks as needed.",
    facts: {
      keepCategories: ["food", "tools", "weapons", "armor", "active-task-materials"],
      lowValueDiscardCandidates: ["minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel"],
      mayDiscardMultipleStacks: true,
      preferHomeStorageWhenReachable: true,
      neverDiscardRequestedOutput: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.ladder",
    topic: "crafting",
    inputs: ["minecraft:stick", "minecraft:crafting_table"],
    outputs: ["minecraft:ladder"],
    tags: ["ladder", "mining", "travel", "safe-access", "vertical-shaft"],
    summary: "Ladders are the default safe vertical-access item for shaft-style mining; seven sticks craft three ladders at a crafting table.",
    facts: {
      recipe: { "minecraft:stick": 7, outputCount: 3 },
      workstation: "minecraft:crafting_table",
      useFor: ["safe mine descent", "return path", "no-cheat vertical travel"],
      prepareBeforeDeepMining: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:crafting.furnace",
    topic: "crafting",
    inputs: ["#minecraft:stone_tool_materials", "minecraft:crafting_table"],
    outputs: ["minecraft:furnace"],
    tags: ["furnace", "smelting", "workstation", "iron", "food"],
    summary: "A furnace is crafted from eight stone-tier blocks and should be reused as a workstation before the agent crafts a new one.",
    facts: {
      recipe: { "#minecraft:stone_tool_materials": 8 },
      workstation: "minecraft:crafting_table",
      preferExistingFacility: true,
      usedFor: ["smelt iron", "cook meat", "make charcoal"],
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:smelting.charcoal",
    topic: "smelting",
    inputs: ["#minecraft:logs", "minecraft:furnace", "minecraft:fuel"],
    outputs: ["minecraft:charcoal"],
    tags: ["charcoal", "torch", "fuel", "fallback", "wood"],
    summary: "When no reachable coal or stored coal is available, smelt logs into charcoal and use it as the fallback torch ingredient.",
    facts: {
      fallbackFor: "minecraft:coal",
      workstation: "minecraft:furnace",
      oneLogProduces: 1,
      torchRecipeEquivalent: true,
      onlyAfterNearbyCoalAndStorageFail: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:agent.workchain_prerequisite_resolution",
    topic: "other",
    inputs: ["minecraft:goal", "minecraft:world_state", "minecraft:facility", "minecraft:inventory"],
    outputs: ["minecraft:work_chain"],
    tags: ["agent", "workchain", "prerequisite", "recovery", "facility", "storage"],
    summary: "Every high-level goal should resolve prerequisites by checking inventory, remembered facilities, nearby blocks, storage, lower-tier tools, and failure checkpoints before starting distant work.",
    facts: {
      order: ["inventory", "equipped gear", "nearby facility", "remembered facility", "home storage", "nearby resource", "craft lower-tier tool", "gather missing resource", "resume failed checkpoint"],
      appliesToAllGoals: true,
      doNotRestartFromScratchAfterFailure: true,
      convertNaturalLanguageToSkillsOrTasks: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:storage.fetch_from_facility",
    topic: "storage",
    inputs: ["minecraft:storage", "minecraft:item_request", "minecraft:home"],
    outputs: ["minecraft:item_delivery"],
    tags: ["storage", "fetch", "chest", "delivery", "facility", "home"],
    summary: "If the player says an item is in a chest or asks for stored items, query remembered storage first, path to the chest, withdraw only the requested amount, then deliver or continue the active chain.",
    facts: {
      queryStorageBeforeGathering: true,
      withdrawExactRequestedCountWhenPossible: true,
      preserveFoodToolsWeaponsArmor: true,
      registerUnknownChestWhenObserved: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:food.auto_eat_reserve",
    topic: "food",
    inputs: ["minecraft:food", "minecraft:hunger", "minecraft:health"],
    outputs: ["minecraft:survival"],
    tags: ["food", "auto-eat", "reserve", "hunger", "health"],
    summary: "The companion should keep food in inventory, avoid eating when hunger is full, and automatically eat when hunger drops below half or health recovery needs saturation.",
    facts: {
      keepFoodReserve: true,
      targetFoodStacks: 1,
      doNotEatWhenHungerFull: true,
      autoEatBelowFoodLevel: 10,
      preferCookedFood: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:farming.crop_sources",
    topic: "farming",
    inputs: ["minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot_seeds"],
    outputs: ["minecraft:crop_cycle"],
    tags: ["crop", "seed", "plant", "harvest", "village", "grass"],
    summary: "Crop goals should search storage and existing farms first, then gather seeds or crop items from nearby sources before building a duplicate farm.",
    facts: {
      sourcePriority: ["remembered farm", "home storage", "nearby mature crops", "grass for wheat seeds", "village farm"],
      plantAfterBuildingFarm: true,
      recordCropTypePerFacility: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:ranch.livestock_luring",
    topic: "ranching",
    inputs: ["minecraft:wheat", "minecraft:carrot", "minecraft:lead", "minecraft:fence", "minecraft:fence_gate"],
    outputs: ["minecraft:livestock"],
    tags: ["ranch", "lure", "lead", "breeding", "cow", "sheep", "pig"],
    summary: "Livestock collection should prepare lure food or leads, bring animals to a recorded pen, close the gate, and only cull animals when the requested food target needs it.",
    facts: {
      lures: { "minecraft:cow": "minecraft:wheat", "minecraft:sheep": "minecraft:wheat", "minecraft:pig": "minecraft:carrot" },
      preferExistingRanch: true,
      closeGateBeforeReleasingAnimals: true,
      keepBreedingPairWhenCulling: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:travel.recall_and_follow_priority",
    topic: "travel",
    inputs: ["minecraft:player", "minecraft:npc", "minecraft:task"],
    outputs: ["minecraft:position_recovery"],
    tags: ["recall", "follow", "stop", "priority", "teleport", "no-cheat"],
    summary: "Recall, stop, follow, and player-protection commands are control-priority actions; they should pause active work, return the NPC, then resume only when safe.",
    facts: {
      priorityControls: ["emergency-stop", "recall", "follow", "protect-player"],
      pauseActiveGoalBeforeRecall: true,
      teleportOnlyWhenCheatsAvailable: true,
      otherwisePathfindBack: true,
      resumeInterruptedGoalAfterSafety: true,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:mining.staircase_branch_mining",
    topic: "mining",
    inputs: ["minecraft:pickaxe", "minecraft:torch", "minecraft:food", "#minecraft:logs"],
    outputs: ["minecraft:ore", "minecraft:mine_facility"],
    tags: ["mining", "staircase", "branch", "torch", "return-path", "diamond"],
    summary: "No-cheat mining should prefer a safe staircase or ladder shaft, place torches regularly, remember the entrance, and branch at the target layer until the ore target is met.",
    facts: {
      rememberEntranceAsFacility: true,
      placeTorchIntervalBlocks: 8,
      keepReturnPathReachable: true,
      branchMineAtTargetLayer: true,
      diamondTargetY: -58,
    },
  }),
  builtinVanillaRecord({
    id: "minecraft:fishing.food_and_loot",
    topic: "food",
    inputs: ["minecraft:fishing_rod", "minecraft:water"],
    outputs: ["minecraft:cod", "minecraft:salmon", "minecraft:treasure"],
    tags: ["fishing", "food", "water", "rod", "backup"],
    summary: "Fishing is a fallback food workflow when water is reachable and a rod can be made or found; fish can be cooked when a furnace and fuel are available.",
    facts: {
      requires: ["minecraft:fishing_rod", "reachable water"],
      cookFishWhenFurnaceAvailable: true,
      fallbackFoodStrategy: true,
    },
  }),
]);

const CHINESE_QUERY_ALIASES: readonly [RegExp, readonly string[]][] = [
  [/钻石镐/u, ["diamond", "pickaxe", "minecraft:diamond_pickaxe"]],
  [/铁镐/u, ["iron", "pickaxe", "minecraft:iron_pickaxe"]],
  [/石镐/u, ["stone", "pickaxe", "minecraft:stone_pickaxe"]],
  [/木镐/u, ["wooden", "pickaxe", "minecraft:wooden_pickaxe"]],
  [/钻石/u, ["diamond", "deep-mining"]],
  [/火把|照明/u, ["torch", "lighting"]],
  [/煤矿|煤炭|煤/u, ["coal", "ore", "nearby-resource"]],
  [/木头|原木/u, ["logs", "wood", "spareWoodTarget"]],
  [/梯子/u, ["ladder", "safe-access"]],
  [/工作台/u, ["crafting_table", "workstation"]],
  [/熔炉/u, ["furnace", "smelting"]],
  [/箱子|仓库/u, ["chest", "storage", "home_chest"]],
  [/取|拿|提取/u, ["fetch", "withdraw", "delivery", "requested"]],
  [/农田|田地|农场/u, ["farm", "crop", "farmland"]],
  [/种植|播种/u, ["plant", "seed"]],
  [/收获/u, ["harvest", "cycle"]],
  [/以后|复用|继续用|已有|记住/u, ["reuse", "facility", "doNotCreateDuplicate"]],
  [/水桶|铁桶|桶/u, ["bucket", "water_bucket"]],
  [/锄头|锄/u, ["hoe"]],
  [/牧场|畜牧|猪牛羊|牲畜/u, ["ranch", "livestock"]],
  [/羊毛/u, ["wool", "sheep"]],
  [/剪刀|剪羊毛|剪毛/u, ["shears", "shear"]],
  [/床|出生点|复活点/u, ["bed", "spawn"]],
  [/食物|吃饭|饥饿|饱食度/u, ["food", "hunger", "auto-eat"]],
  [/肉|猪排|牛排|羊肉/u, ["meat", "porkchop", "beef", "mutton"]],
  [/钓鱼|鱼竿/u, ["fishing", "fishing_rod"]],
  [/建造|建筑|蓝图/u, ["building", "blueprint"]],
  [/房子|小屋/u, ["basic-shelter", "stone-cottage"]],
  [/刷石机/u, ["cobblestone-generator", "redstone"]],
  [/刷怪|刷怪塔/u, ["mob-farm", "redstone"]],
  [/树场/u, ["tree-farm"]],
  [/背包|清理|丢弃/u, ["inventory", "cleanup", "discard"]],
  [/圆石|石头/u, ["cobblestone", "stone"]],
  [/装备|防具|护甲/u, ["equipment", "armor", "auto-equip"]],
  [/武器|剑/u, ["weapon", "sword"]],
  [/龙|骑龙/u, ["dragon", "ride"]],
  [/共骑|同骑|一起骑/u, ["shared", "share-ride"]],
  [/召回|跟随|停止/u, ["recall", "follow", "priority"]],
  [/动作链|工作链|前置|缺材料/u, ["workchain", "prerequisite", "recovery"]],
];

function tokenize(value: string): string[] {
  const lowered = value.toLocaleLowerCase("en-US");
  const tokens: string[] = [...(lowered.match(/[\p{L}\p{N}:_#.-]+/gu) ?? [])];
  for (const [pattern, aliases] of CHINESE_QUERY_ALIASES) {
    if (pattern.test(value)) tokens.push(...aliases);
  }
  return [...new Set(tokens)]
    .filter((token) => token.length > 0)
    .slice(0, 64);
}

function searchableText(record: KnowledgeRecord): string {
  return [
    record.id,
    record.topic,
    record.source,
    record.summary,
    ...record.inputs,
    ...record.outputs,
    ...record.tags,
    JSON.stringify(record.facts),
  ].join(" ").toLocaleLowerCase("en-US");
}

function sourcePriority(record: KnowledgeRecord): number {
  return SOURCE_PRIORITY[record.source] ?? 99;
}

function rankRecord(record: KnowledgeRecord, tokens: readonly string[]): number {
  if (tokens.length === 0) return 1;
  const text = searchableText(record);
  let score = 0;
  for (const token of tokens) {
    if (record.id.toLocaleLowerCase("en-US").includes(token)) score += 6;
    if (record.outputs.some((output) => output.toLocaleLowerCase("en-US").includes(token))) score += 5;
    if (record.inputs.some((input) => input.toLocaleLowerCase("en-US").includes(token))) score += 3;
    if (record.tags.some((tag) => tag.toLocaleLowerCase("en-US").includes(token))) score += 2;
    if (text.includes(token)) score += 1;
  }
  return score;
}

export class GameplayKnowledgeIndex {
  readonly #records: KnowledgeRecord[];

  constructor(dynamicRecords: readonly KnowledgeRecord[] = []) {
    const byId = new Map<string, KnowledgeRecord>();
    for (const record of BUILTIN_GAMEPLAY_KNOWLEDGE) byId.set(record.id, record);
    for (const record of dynamicRecords) byId.set(record.id, knowledgeRecordSchema.parse(record));
    this.#records = [...byId.values()];
  }

  query(input: { query?: string; topics?: readonly KnowledgeTopic[]; limit?: number } = {}): KnowledgeRecord[] {
    const tokens = tokenize(input.query ?? "");
    const topics = new Set(input.topics ?? []);
    const limit = Math.max(1, Math.min(128, Math.trunc(input.limit ?? 64)));
    return this.#records
      .filter((record) => topics.size === 0 || topics.has(record.topic))
      .map((record) => ({ record, score: rankRecord(record, tokens) }))
      .filter(({ score }) => tokens.length === 0 || score > 0)
      .sort((left, right) => {
        if (right.score !== left.score) return right.score - left.score;
        const source = sourcePriority(left.record) - sourcePriority(right.record);
        if (source !== 0) return source;
        return left.record.id.localeCompare(right.record.id, "en-US");
      })
      .slice(0, limit)
      .map(({ record }) => structuredClone(record));
  }
}
