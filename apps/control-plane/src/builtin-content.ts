import type { BuildBlock, BuildPlanDraft, DeclarativeSkillDraft } from "@mc/protocol";

export const BUILTIN_CONTENT_AUTHOR = "Minecraft Codex Companion contributors";
export const BUILTIN_CONTENT_LICENSE = "CC0-1.0";
export const BUILTIN_CONTENT_VERSION = "1.0.0";

export const BUILTIN_BUILD_IDS = {
  cobblestoneGenerator: "00000000-0000-4000-8000-000000000101",
  basicShelter: "00000000-0000-4000-8000-000000000102",
  cropFarm: "00000000-0000-4000-8000-000000000103",
  storageRoom: "00000000-0000-4000-8000-000000000104",
  stoneCottage: "00000000-0000-4000-8000-000000000105",
  animalPen: "00000000-0000-4000-8000-000000000106",
  watchtower: "00000000-0000-4000-8000-000000000107",
  mobFarm: "00000000-0000-4000-8000-000000000108",
  treeFarm: "00000000-0000-4000-8000-000000000109",
} as const;

export interface BuiltInBuildTemplate {
  id: string;
  name: string;
  draft: BuildPlanDraft;
}

function block(x: number, y: number, z: number, blockId: string, properties: Record<string, string> = {}): BuildBlock {
  return { position: { x, y, z }, blockId, properties };
}

function rectangularFloor(width: number, depth: number, blockId: string): BuildBlock[] {
  const blocks: BuildBlock[] = [];
  for (let z = 0; z < depth; z += 1) {
    for (let x = 0; x < width; x += 1) blocks.push(block(x, 0, z, blockId));
  }
  return blocks;
}

function basicShelterBlocks(): BuildBlock[] {
  const blocks = rectangularFloor(7, 7, "minecraft:oak_planks");
  for (let y = 1; y <= 3; y += 1) {
    for (let x = 0; x < 7; x += 1) {
      if (!(x === 3 && y <= 2)) blocks.push(block(x, y, 0, "minecraft:oak_planks"));
      blocks.push(block(x, y, 6, "minecraft:oak_planks"));
    }
    for (let z = 1; z < 6; z += 1) {
      blocks.push(block(0, y, z, "minecraft:oak_planks"));
      blocks.push(block(6, y, z, "minecraft:oak_planks"));
    }
  }
  // Windows are data-only block states; no block-entity NBT is accepted.
  for (const [x, z] of [[0, 3], [6, 3], [3, 6]] as const) {
    const index = blocks.findIndex((entry) => entry.position.x === x && entry.position.y === 2 && entry.position.z === z);
    if (index >= 0) blocks[index] = block(x, 2, z, "minecraft:glass");
  }
  for (let z = 0; z < 7; z += 1) {
    for (let x = 0; x < 7; x += 1) blocks.push(block(x, 4, z, "minecraft:oak_slab", { type: "bottom", waterlogged: "false" }));
  }
  return blocks;
}

function cropFarmBlocks(): BuildBlock[] {
  const blocks: BuildBlock[] = [];
  for (let z = 0; z < 9; z += 1) {
    for (let x = 0; x < 9; x += 1) {
      const border = x === 0 || x === 8 || z === 0 || z === 8;
      blocks.push(block(x, 0, z, border ? "minecraft:oak_planks" : "minecraft:dirt"));
    }
  }
  // A safe dry irrigation channel. Fluid placement remains an explicit in-game action.
  for (let z = 1; z < 8; z += 1) {
    const index = blocks.findIndex((entry) => entry.position.x === 4 && entry.position.z === z);
    if (index >= 0) blocks[index] = block(4, 0, z, "minecraft:oak_slab", { type: "bottom", waterlogged: "false" });
  }
  return blocks;
}

function storageRoomBlocks(): BuildBlock[] {
  const blocks = rectangularFloor(7, 5, "minecraft:cobblestone");
  for (let y = 1; y <= 3; y += 1) {
    for (let x = 0; x < 7; x += 1) {
      if (!(x === 3 && y <= 2)) blocks.push(block(x, y, 0, "minecraft:oak_planks"));
      blocks.push(block(x, y, 4, "minecraft:oak_planks"));
    }
    for (let z = 1; z < 4; z += 1) {
      blocks.push(block(0, y, z, "minecraft:oak_planks"));
      blocks.push(block(6, y, z, "minecraft:oak_planks"));
    }
  }
  for (let z = 0; z < 5; z += 1) {
    for (let x = 0; x < 7; x += 1) blocks.push(block(x, 4, z, "minecraft:oak_slab", { type: "bottom", waterlogged: "false" }));
  }
  for (const [x, z] of [[1, 1], [1, 2], [1, 3], [5, 1], [5, 2], [5, 3]] as const) {
    blocks.push(block(x, 1, z, "minecraft:barrel", { facing: "up", open: "false" }));
  }
  return blocks;
}

function cobblestoneGeneratorBlocks(): BuildBlock[] {
  // Water drops into x=2 instead of spreading toward the lava. The lava then
  // flows from x=4 into x=3 and forms a mineable cobblestone block there.
  return [
    ...rectangularFloor(7, 3, "minecraft:cobblestone"),
    ...Array.from({ length: 7 }, (_, x) => block(x, 1, 0, "minecraft:cobblestone")),
    ...Array.from({ length: 7 }, (_, x) => block(x, 1, 2, "minecraft:cobblestone")),
    ...[0, 1, 4, 5, 6].map((x) => block(x, 1, 1, "minecraft:cobblestone")),
    // A replaceable cobblestone backstop keeps the generator survival-safe;
    // requiring obsidian here made the built-in skill impossible to complete
    // from its documented bucket-only special prerequisites.
    block(3, 1, 1, "minecraft:cobblestone"),
    ...Array.from({ length: 7 }, (_, x) => block(x, 2, 0, "minecraft:cobblestone")),
    ...Array.from({ length: 7 }, (_, x) => block(x, 2, 2, "minecraft:cobblestone")),
    block(0, 2, 1, "minecraft:cobblestone"),
    block(6, 2, 1, "minecraft:cobblestone"),
    block(1, 2, 1, "minecraft:water", { level: "0" }),
    block(4, 2, 1, "minecraft:lava", { level: "0" }),
  ];
}

function stoneCottageBlocks(): BuildBlock[] {
  const blocks = rectangularFloor(7, 7, "minecraft:stone_bricks");
  for (let y = 1; y <= 3; y += 1) {
    for (let x = 0; x < 7; x += 1) {
      if (!(x === 3 && y <= 2)) {
        const window = x === 3 && y === 2;
        blocks.push(block(x, y, 0, window ? "minecraft:glass" : "minecraft:stone_bricks"));
      }
      blocks.push(block(x, y, 6, x === 3 && y === 2 ? "minecraft:glass" : "minecraft:stone_bricks"));
    }
    for (let z = 1; z < 6; z += 1) {
      const window = z === 3 && y === 2;
      blocks.push(block(0, y, z, window ? "minecraft:glass" : "minecraft:stone_bricks"));
      blocks.push(block(6, y, z, window ? "minecraft:glass" : "minecraft:stone_bricks"));
    }
  }
  for (let z = 0; z < 7; z += 1) {
    for (let x = 0; x < 7; x += 1) {
      blocks.push(block(x, 4, z, "minecraft:stone_brick_slab", { type: "bottom", waterlogged: "false" }));
    }
  }
  return blocks;
}

function animalPenBlocks(): BuildBlock[] {
  const blocks: BuildBlock[] = [];
  for (let x = 0; x < 9; x += 1) {
    if (x === 4) {
      blocks.push(block(x, 0, 0, "minecraft:oak_fence_gate", {
        facing: "south", in_wall: "false", open: "false",
      }));
    } else {
      blocks.push(block(x, 0, 0, "minecraft:oak_fence"));
    }
    blocks.push(block(x, 0, 8, "minecraft:oak_fence"));
  }
  for (let z = 1; z < 8; z += 1) {
    blocks.push(block(0, 0, z, "minecraft:oak_fence"));
    blocks.push(block(8, 0, z, "minecraft:oak_fence"));
  }
  return blocks;
}

function watchtowerBlocks(): BuildBlock[] {
  const blocks = rectangularFloor(5, 5, "minecraft:cobblestone");
  for (let y = 1; y <= 8; y += 1) {
    if (y !== 4 && y !== 8) {
      for (const [x, z] of [[0, 0], [4, 0], [0, 4], [4, 4]] as const) {
        blocks.push(block(x, y, z, "minecraft:oak_log", { axis: "y" }));
      }
    }
    if (y !== 4 && y !== 8) blocks.push(block(2, y, 0, "minecraft:oak_planks"));
    if (y <= 7) {
      blocks.push(block(2, y, 1, "minecraft:ladder", { facing: "south", waterlogged: "false" }));
    }
  }
  for (const y of [4, 8]) {
    for (let z = 0; z < 5; z += 1) {
      for (let x = 0; x < 5; x += 1) {
        if (!(x === 2 && z === 1)) blocks.push(block(x, y, z, "minecraft:oak_planks"));
      }
    }
  }
  for (let x = 0; x < 5; x += 1) {
    blocks.push(block(x, 9, 0, "minecraft:oak_fence"));
    blocks.push(block(x, 9, 4, "minecraft:oak_fence"));
  }
  for (let z = 1; z < 4; z += 1) {
    blocks.push(block(0, 9, z, "minecraft:oak_fence"));
    blocks.push(block(4, 9, z, "minecraft:oak_fence"));
  }
  return blocks;
}

function mobFarmBlocks(): BuildBlock[] {
  const blocks = rectangularFloor(7, 7, "minecraft:cobblestone");
  // A 23-block fall shaft with a two-wide collection opening at its base.
  for (let y = 1; y <= 22; y += 1) {
    for (let x = 2; x <= 5; x += 1) {
      for (let z = 2; z <= 5; z += 1) {
        const chute = (x === 3 || x === 4) && (z === 3 || z === 4);
        const collectionOpening = y <= 2 && z === 2 && (x === 3 || x === 4);
        if (!chute && !collectionOpening) blocks.push(block(x, y, z, "minecraft:cobblestone"));
      }
    }
  }
  // Passive-wander spawning deck. It uses no spawner, commands, NBT or water source.
  for (let x = -4; x <= 9; x += 1) {
    for (let z = -4; z <= 9; z += 1) {
      const chute = (x === 3 || x === 4) && (z === 3 || z === 4);
      if (!chute) blocks.push(block(x, 23, z, "minecraft:cobblestone"));
    }
  }
  for (let y = 24; y <= 26; y += 1) {
    for (let x = -4; x <= 9; x += 1) {
      blocks.push(block(x, y, -4, "minecraft:cobblestone"));
      blocks.push(block(x, y, 9, "minecraft:cobblestone"));
    }
    for (let z = -3; z <= 8; z += 1) {
      blocks.push(block(-4, y, z, "minecraft:cobblestone"));
      blocks.push(block(9, y, z, "minecraft:cobblestone"));
    }
  }
  for (let x = -4; x <= 9; x += 1) {
    for (let z = -4; z <= 9; z += 1) blocks.push(block(x, 27, z, "minecraft:cobblestone"));
  }
  return blocks;
}

function treeFarmBlocks(): BuildBlock[] {
  const blocks: BuildBlock[] = [];
  const planting = new Set(["2,2", "5,2", "8,2", "11,2", "2,6", "5,6", "8,6", "11,6"]);
  for (let z = 0; z < 9; z += 1) {
    for (let x = 0; x < 13; x += 1) {
      const border = x === 0 || x === 12 || z === 0 || z === 8;
      const blockId = border
        ? "minecraft:cobblestone"
        : planting.has(`${x},${z}`)
          ? "minecraft:dirt"
          : "minecraft:oak_planks";
      blocks.push(block(x, 0, z, blockId));
    }
  }
  return blocks;
}

export const BUILTIN_BUILD_TEMPLATES: readonly BuiltInBuildTemplate[] = [
  {
    id: BUILTIN_BUILD_IDS.cobblestoneGenerator,
    name: "安全基础刷石机（自动放置流体）",
    draft: { name: "安全基础刷石机（自动放置流体）", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: cobblestoneGeneratorBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.basicShelter,
    name: "七格基础住宅",
    draft: { name: "七格基础住宅", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: basicShelterBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.cropFarm,
    name: "九格安全农田",
    draft: { name: "九格安全农田", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: cropFarmBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.storageRoom,
    name: "分类仓库",
    draft: { name: "分类仓库", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: storageRoomBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.stoneCottage,
    name: "石砖小屋",
    draft: { name: "石砖小屋", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: stoneCottageBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.animalPen,
    name: "九格动物围栏",
    draft: { name: "九格动物围栏", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: animalPenBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.watchtower,
    name: "木石瞭望塔",
    draft: { name: "木石瞭望塔", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: watchtowerBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.mobFarm,
    name: "基础黑暗刷怪塔（无刷怪笼）",
    draft: { name: "基础黑暗刷怪塔（无刷怪笼）", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: mobFarmBlocks() },
  },
  {
    id: BUILTIN_BUILD_IDS.treeFarm,
    name: "规则自动树场",
    draft: { name: "规则自动树场", source: "demo", origin: { x: 0, y: 0, z: 0 }, blocks: treeFarmBlocks() },
  },
] as const;

const nextToCompanion = { placement: "companion", offset: { x: 3, y: 0, z: 3 } } as const;

export const ADDITIONAL_BUILTIN_SKILL_DRAFTS: readonly DeclarativeSkillDraft[] = [
  {
    id: "life.craft-and-place-bed",
    name: "制作并安放床",
    description: "检查背包和家中仓库，按真实生存流程补齐木板、羊毛与剪刀，制作床并在玩家复活点附近安全放置。",
    parameters: [],
    steps: [{
      label: "补齐材料、制作并安放床",
      task: { kind: "craft", itemId: "minecraft:white_bed", count: 1, placeAtHome: true },
    }],
  },
  {
    id: "build.cobblestone-generator",
    name: "建造基础刷石机",
    description: "在角色旁边自动建造无命令、无 NBT 的可用刷石机，并通过真实右键交互放入水源与岩浆源；生存模式会自动补齐圆石，但背包中仍需各有一个对应桶。",
    parameters: [],
    steps: [{ label: "建造并注入刷石机流体", task: { kind: "build", planId: BUILTIN_BUILD_IDS.cobblestoneGenerator, ...nextToCompanion } }],
  },
  {
    id: "build.basic-shelter",
    name: "建造基础住宅",
    description: "在接令时锁定的位置建造住宅；生存模式由同一个可恢复建造任务按所选材料族查仓、制作、熔炼或采集，创造模式直接施工。",
    parameters: [],
    steps: [{ label: "建造基础住宅", task: { kind: "build", planId: BUILTIN_BUILD_IDS.basicShelter, ...nextToCompanion } }],
  },
  {
    id: "build.crop-farm",
    name: "建造并照料农田",
    description: "在接令时锁定的位置建造并执行农务循环；生存材料由可恢复建造任务按选定调色板补齐，灌溉水仍需显式补充。",
    parameters: [
      { name: "cropId", description: "作物方块或种子 ID", type: "string", required: false, defaultValue: "minecraft:wheat" },
      { name: "radius", description: "农务半径", type: "integer", required: false, defaultValue: 12, minimum: 1, maximum: 64 },
    ],
    steps: [
      { label: "建造农田框架", task: { kind: "build", planId: BUILTIN_BUILD_IDS.cropFarm, ...nextToCompanion, sitePolicy: "outdoor" } },
      { label: "照料并补种已有农田", task: { kind: "farm", cropId: "${cropId}", action: "plant", radius: "${radius}" } },
    ],
  },
  {
    id: "build.storage-room",
    name: "建造并整理仓库",
    description: "建造分类仓库并整理家园容器；生存材料由同一个建造任务按选定材料族与真实配方补齐。",
    parameters: [],
    steps: [
      { label: "建造分类仓库", task: { kind: "build", planId: BUILTIN_BUILD_IDS.storageRoom, ...nextToCompanion } },
      { label: "整理家园仓库", task: { kind: "organize-storage", radius: 24 } },
    ],
  },
  {
    id: "build.stone-cottage",
    name: "建造石砖小屋",
    description: "在接令时锁定的位置建造石砖与玻璃小屋；缺少材料时由建造任务的本地材料链补齐。",
    parameters: [],
    steps: [{ label: "建造石砖小屋", task: { kind: "build", planId: BUILTIN_BUILD_IDS.stoneCottage, ...nextToCompanion } }],
  },
  {
    id: "build.animal-pen",
    name: "建造动物围栏",
    description: "建造带栅栏门的九格动物围栏，不生成或移动生物。",
    parameters: [],
    steps: [{
      label: "建造动物围栏",
      task: { kind: "build", planId: BUILTIN_BUILD_IDS.animalPen, ...nextToCompanion, sitePolicy: "outdoor" },
    }],
  },
  {
    id: "life.establish-ranch",
    name: "建立牲畜牧场",
    description: "先按安全蓝图建造围栏，再用真实拴绳交互扩大范围寻找成年猪牛羊并逐只牵回；保护幼崽、命名、驯服和已有拴绳的动物。",
    parameters: [
      { name: "animalType", description: "目标牲畜类型", type: "string", required: false, defaultValue: "any", enumValues: ["any", "minecraft:pig", "minecraft:cow", "minecraft:sheep"] },
      { name: "count", description: "围栏内目标牲畜总数", type: "integer", required: false, defaultValue: 2, minimum: 2, maximum: 24 },
      { name: "radius", description: "远程寻找半径", type: "integer", required: false, defaultValue: 128, minimum: 16, maximum: 512 },
    ],
    steps: [
      {
        label: "建造动物围栏",
        task: { kind: "build", planId: BUILTIN_BUILD_IDS.animalPen, ...nextToCompanion, sitePolicy: "outdoor" },
      },
      { label: "牵回并安置牲畜", task: { kind: "ranch", action: "establish", animalType: "${animalType}", count: "${count}", radius: "${radius}" } },
    ],
  },
  {
    id: "build.watchtower",
    name: "建造瞭望塔",
    description: "建造带内部梯子和顶部护栏的木石瞭望塔。",
    parameters: [],
    steps: [{ label: "建造瞭望塔", task: { kind: "build", planId: BUILTIN_BUILD_IDS.watchtower, ...nextToCompanion } }],
  },
  {
    id: "build.mob-farm",
    name: "建造基础黑暗刷怪塔",
    description: "用普通圆石建造黑暗平台、坠落井和收集开口；不生成、不获取也不放置刷怪笼，不使用命令或 NBT。",
    parameters: [],
    steps: [{ label: "建造黑暗刷怪塔", task: { kind: "build", planId: BUILTIN_BUILD_IDS.mobFarm, ...nextToCompanion } }],
  },
  {
    id: "build.tree-farm",
    name: "建造规则树场",
    description: "建造带八个种植位的规则树场基座；树苗种类和后续砍伐可再用种植、采集命令指定。",
    parameters: [],
    steps: [{ label: "建造规则树场", task: { kind: "build", planId: BUILTIN_BUILD_IDS.treeFarm, ...nextToCompanion } }],
  },
  {
    id: "craft.starter-tools",
    name: "制作基础工具",
    description: "使用游戏内配方制作工作台、木镐、石镐、石斧和熔炉，不运行任何外部程序。",
    parameters: [],
    steps: [
      { label: "制作工作台", task: { kind: "craft", itemId: "minecraft:crafting_table", count: 1 } },
      { label: "制作木镐", task: { kind: "craft", itemId: "minecraft:wooden_pickaxe", count: 1 } },
      { label: "制作石镐", task: { kind: "craft", itemId: "minecraft:stone_pickaxe", count: 1 } },
      { label: "制作石斧", task: { kind: "craft", itemId: "minecraft:stone_axe", count: 1 } },
      { label: "制作熔炉", task: { kind: "craft", itemId: "minecraft:furnace", count: 1 } },
    ],
  },
  {
    id: "craft.iron-equipment",
    name: "制作铁质装备套装",
    description: "烧炼铁原料并制作铁剑、铁镐、盾牌和整套铁甲，随后由 NPC 自动装备评分更高的防具。",
    parameters: [
      { name: "ironInput", description: "可烧炼的铁原料 ID", type: "string", required: false, defaultValue: "minecraft:raw_iron" },
      { name: "ironCount", description: "烧炼数量", type: "integer", required: false, defaultValue: 32, minimum: 24, maximum: 64 },
    ],
    steps: [
      { label: "烧炼铁原料", task: { kind: "smelt", itemId: "${ironInput}", count: "${ironCount}" } },
      { label: "制作铁剑", task: { kind: "craft", itemId: "minecraft:iron_sword", count: 1 } },
      { label: "制作铁镐", task: { kind: "craft", itemId: "minecraft:iron_pickaxe", count: 1 } },
      { label: "制作盾牌", task: { kind: "craft", itemId: "minecraft:shield", count: 1 } },
      { label: "制作铁头盔", task: { kind: "craft", itemId: "minecraft:iron_helmet", count: 1 } },
      { label: "制作铁胸甲", task: { kind: "craft", itemId: "minecraft:iron_chestplate", count: 1 } },
      { label: "制作铁护腿", task: { kind: "craft", itemId: "minecraft:iron_leggings", count: 1 } },
      { label: "制作铁靴", task: { kind: "craft", itemId: "minecraft:iron_boots", count: 1 } },
    ],
  },
  {
    id: "craft.building-materials",
    name: "制作建筑材料",
    description: "按参数制作常用木板、台阶和箱子，供住宅、农田与仓库模板使用。",
    parameters: [
      { name: "plankId", description: "木板物品 ID", type: "string", required: false, defaultValue: "minecraft:oak_planks" },
      { name: "slabId", description: "木台阶物品 ID", type: "string", required: false, defaultValue: "minecraft:oak_slab" },
      { name: "count", description: "主要建筑材料数量", type: "integer", required: false, defaultValue: 64, minimum: 8, maximum: 256 },
    ],
    steps: [
      { label: "制作木板", task: { kind: "craft", itemId: "${plankId}", count: "${count}" } },
      { label: "制作木台阶", task: { kind: "craft", itemId: "${slabId}", count: 32 } },
      { label: "制作箱子", task: { kind: "craft", itemId: "minecraft:chest", count: 4 } },
    ],
  },
  {
    id: "dragon.bookofdragons-field-kit",
    name: "《龙之书》野外套装",
    description: "为 bookofdragons 制作驯龙哨、维京铁砧、防火鳞甲和响尾杖；物品 ID 已从本地模组配方核对。",
    parameters: [],
    steps: [
      { label: "制作驯龙哨", task: { kind: "craft", itemId: "bookofdragons:dragon_whistle", count: 1 } },
      { label: "制作维京铁砧", task: { kind: "craft", itemId: "bookofdragons:viking_anvil", count: 1 } },
      { label: "制作防火鳞甲", task: { kind: "craft", itemId: "bookofdragons:fireproof_scalemail", count: 1 } },
      { label: "制作响尾杖", task: { kind: "craft", itemId: "bookofdragons:rattlestaff", count: 1 } },
    ],
  },
  {
    id: "dragon.egg-care",
    name: "照料龙蛋",
    description: "寻找并照料当前双龙整合包中的龙蛋，不生成物品、不修改模组文件。",
    parameters: [
      { name: "targetId", description: "龙蛋实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [{ label: "照料龙蛋", task: { kind: "dragon", action: "care-for-egg", targetId: "${targetId}" } }],
  },
  {
    id: "dragon.heal-and-follow",
    name: "治疗并跟随",
    description: "观察目标龙、使用模组认可的治疗食物治疗，并切换为跟随主人。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "观察龙类", task: { kind: "dragon", action: "observe", targetId: "${targetId}" } },
      { label: "治疗龙类", task: { kind: "dragon", action: "heal", targetId: "${targetId}" } },
      { label: "命令龙跟随", task: { kind: "dragon", action: "follow", targetId: "${targetId}" } },
    ],
  },
  {
    id: "dragon.saintsdragons-care-kit",
    name: "Saint's Dragons 照料套装",
    description: "为 saintsdragons 制作龙刷、丰盛龙餐与龙族图鉴，并执行一次观察和喂养。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "制作龙刷", task: { kind: "craft", itemId: "saintsdragons:dragon_brush", count: 1 } },
      { label: "制作丰盛龙餐", task: { kind: "craft", itemId: "saintsdragons:hearty_dragon_meal", count: 4 } },
      { label: "制作龙族图鉴", task: { kind: "craft", itemId: "saintsdragons:draconic_codex", count: 1 } },
      { label: "观察龙类", task: { kind: "dragon", action: "observe", targetId: "${targetId}" } },
      { label: "喂养龙类", task: { kind: "dragon", action: "feed", targetId: "${targetId}" } },
    ],
  },
  {
    id: "dragon.saintsdragons-binder",
    name: "制作 Saint's Dragons 龙之契",
    description: "按龙种制作 saintsdragons 的 binder；仅允许当前双龙模组实装配方中的物品 ID。",
    parameters: [
      {
        name: "binderId",
        description: "龙之契物品 ID",
        type: "string",
        required: false,
        defaultValue: "saintsdragons:cindervane_binder",
        enumValues: [
          "saintsdragons:cindervane_binder",
          "saintsdragons:ignivorus_binder",
          "saintsdragons:nulljaw_binder",
          "saintsdragons:raevyx_binder",
          "saintsdragons:stegonaut_binder",
          "saintsdragons:varasuchus_binder",
          "saintsdragons:volitans_binder",
        ],
      },
    ],
    steps: [{ label: "制作龙之契", task: { kind: "craft", itemId: "${binderId}", count: 1 } }],
  },
] as const;
