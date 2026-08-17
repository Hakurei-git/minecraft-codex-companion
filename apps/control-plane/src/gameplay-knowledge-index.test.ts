import { describe, expect, it } from "vitest";
import { GameplayKnowledgeIndex } from "./gameplay-knowledge-index.js";

describe("GameplayKnowledgeIndex", () => {
  it("answers core vanilla prerequisite queries from the local built-in knowledge pack", () => {
    const index = new GameplayKnowledgeIndex();
    const diamondPickaxe = index.query({ query: "diamond pickaxe prerequisites", topics: ["crafting"] });
    expect(diamondPickaxe[0]).toMatchObject({
      id: "minecraft:crafting.diamond_pickaxe",
      outputs: ["minecraft:diamond_pickaxe"],
      facts: {
        recipe: { "minecraft:diamond": 3, "minecraft:stick": 2 },
        workstation: "minecraft:crafting_table",
      },
    });

    const mining = index.query({ query: "prepare for diamond mining torches spare wood", topics: ["mining"] });
    expect(mining.map((record) => record.id)).toContain("minecraft:mining.diamond_preparation");
    expect(mining.find((record) => record.id === "minecraft:mining.diamond_preparation")?.facts).toMatchObject({
      targetY: -58,
      spareWoodTarget: 64,
      placeTorchesWhileDescending: true,
    });
  });

  it("finds resource substitution knowledge for torches and workstations", () => {
    const index = new GameplayKnowledgeIndex();
    const torches = index.query({ query: "64 torches coal nearby", topics: ["crafting"] });
    expect(torches.map((record) => record.id)).toContain("minecraft:crafting.torch");
    expect(torches.find((record) => record.id === "minecraft:crafting.torch")?.facts).toMatchObject({
      nearbyCoalCanSatisfyTorchRequests: true,
    });

    const workstation = index.query({ query: "reuse nearby crafting table before crafting", topics: ["crafting"] });
    expect(workstation[0]).toMatchObject({
      id: "minecraft:workstation.crafting_table",
      facts: { preferExistingFacility: true, avoidBlockingPath: true },
    });
  });

  it("covers local building, redstone, dragon, and equipment agent knowledge without network lookup", () => {
    const index = new GameplayKnowledgeIndex();

    expect(index.query({ query: "resume blueprint build and remember facility", topics: ["building"] })[0]).toMatchObject({
      id: "minecraft:building.blueprint_facility_memory",
      facts: {
        queryBeforeBuild: true,
        shouldRegisterFacility: true,
        resumeFailedBuildFromCheckpoint: true,
      },
    });
    expect(index.query({ query: "safe cobblestone generator lava water facility", topics: ["redstone"] })[0]).toMatchObject({
      id: "minecraft:redstone.cobblestone_generator",
      facts: {
        skillId: "build.cobblestone-generator",
        avoidWoodNearLava: true,
      },
    });
    expect(index.query({ query: "dragon shared riding landing recall bookofdragons saintsdragons", topics: ["dragon"] })[0]).toMatchObject({
      id: "minecraft:dragon.shared_riding",
      facts: {
        supportedMods: ["bookofdragons", "saintsdragons"],
        recordLandingFacility: true,
        sharedRideSeating: { player: "front", companion: "rear" },
      },
    });
    expect(index.query({ query: "auto equip better armor weapon store low tier", topics: ["combat"] })[0]).toMatchObject({
      id: "minecraft:equipment.auto_equip",
      facts: {
        preferHigherArmorValue: true,
        storeLowTierSpareEquipment: true,
      },
    });
  });

  it("covers broader official gameplay chains for tools, facilities, nearby ores, and inventory pressure", () => {
    const index = new GameplayKnowledgeIndex();

    expect(index.query({ query: "tool material progression iron sword shield diamond tools", topics: ["crafting"] })[0]).toMatchObject({
      id: "minecraft:crafting.tool_material_families",
      facts: {
        ironChain: expect.arrayContaining(["mine raw iron", "smelt iron ingots"]),
        diamondChain: expect.arrayContaining(["iron pickaxe", "deep mine diamonds"]),
      },
    });
    expect(index.query({ query: "iron equipment set auto equip armor shield", topics: ["combat"] })[0]).toMatchObject({
      id: "minecraft:equipment.iron_gear_set",
      facts: {
        skillId: "craft.iron-equipment",
        autoEquipAfterCraft: true,
      },
    });
    expect(index.query({ query: "reuse farm facility harvest plant cycle", topics: ["farming"] })[0]).toMatchObject({
      id: "minecraft:farming.facility_reuse_operations",
      facts: {
        doNotCreateDuplicateFarmWhenFacilityExists: true,
      },
    });
    expect(index.query({ query: "reuse ranch facility breed shear cull", topics: ["ranching"] })[0]).toMatchObject({
      id: "minecraft:ranch.facility_reuse_operations",
      facts: {
        doNotCreateDuplicateRanchWhenFacilityExists: true,
      },
    });
    expect(index.query({ query: "nearby coal ore complete vein before expedition", topics: ["mining"] })[0]).toMatchObject({
      id: "minecraft:mining.local_ore_vein_priority",
      facts: {
        completeReachableVeinBeforeMovingFarther: true,
      },
    });
    expect(index.query({ query: "discard multiple stacks low value stone inventory full", topics: ["storage"] })[0]).toMatchObject({
      id: "minecraft:storage.inventory_pressure_cleanup",
      facts: {
        mayDiscardMultipleStacks: true,
        neverDiscardRequestedOutput: true,
      },
    });
  });

  it("covers expanded vanilla gameplay knowledge for prerequisites, storage fetch, recall, and Chinese queries", () => {
    const index = new GameplayKnowledgeIndex();

    expect(index.query({ query: "safe ladder shaft before diamond mining", topics: ["crafting"] })[0]).toMatchObject({
      id: "minecraft:crafting.ladder",
      facts: {
        recipe: { "minecraft:stick": 7, outputCount: 3 },
        prepareBeforeDeepMining: true,
      },
    });
    expect(index.query({ query: "no coal make charcoal torch fallback", topics: ["smelting"] })[0]).toMatchObject({
      id: "minecraft:smelting.charcoal",
      facts: {
        fallbackFor: "minecraft:coal",
        onlyAfterNearbyCoalAndStorageFail: true,
      },
    });
    expect(index.query({ query: "箱子里有铁就先去箱子取", topics: ["storage"] })[0]).toMatchObject({
      id: "minecraft:storage.fetch_from_facility",
      facts: {
        queryStorageBeforeGathering: true,
        withdrawExactRequestedCountWhenPossible: true,
      },
    });
    expect(index.query({ query: "停止目标然后召回跟随", topics: ["travel"] })[0]).toMatchObject({
      id: "minecraft:travel.recall_and_follow_priority",
      facts: {
        pauseActiveGoalBeforeRecall: true,
        resumeInterruptedGoalAfterSafety: true,
      },
    });
    expect(index.query({ query: "所有动作链都要先检查前置缺材料", topics: ["other"] })[0]).toMatchObject({
      id: "minecraft:agent.workchain_prerequisite_resolution",
      facts: {
        appliesToAllGoals: true,
        doNotRestartFromScratchAfterFailure: true,
      },
    });
  });

  it("maps Chinese gameplay requests onto the local knowledge pack instead of falling back to arbitrary records", () => {
    const index = new GameplayKnowledgeIndex();

    expect(index.query({ query: "我要钻石镐，要先准备梯子火把食物", topics: ["mining"] })[0]).toMatchObject({
      id: "minecraft:mining.diamond_preparation",
    });
    expect(index.query({ query: "建造农田并且以后可以播种收获", topics: ["farming"] })[0]).toMatchObject({
      id: "minecraft:farming.facility_reuse_operations",
    });
    expect(index.query({ query: "共骑一只龙并安全降落", topics: ["dragon"] })[0]).toMatchObject({
      id: "minecraft:dragon.shared_riding",
    });
  });

  it("lets observed or datapack knowledge override built-in records with the same id", () => {
    const index = new GameplayKnowledgeIndex([{
      id: "minecraft:crafting.diamond_pickaxe",
      version: "1.0.0",
      gameVersion: "1.20.1",
      source: "world-state",
      topic: "crafting",
      inputs: ["minecraft:diamond", "minecraft:stick"],
      outputs: ["minecraft:diamond_pickaxe"],
      tags: ["fixture"],
      summary: "Observed fixture recipe wins over the built-in record.",
      facts: { observed: true },
      confidence: "observed",
      updatedAt: "2026-08-16T00:00:00.000Z",
    }]);
    expect(index.query({ query: "diamond pickaxe", topics: ["crafting"] })[0]).toMatchObject({
      source: "world-state",
      facts: { observed: true },
    });
  });
});
