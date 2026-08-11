import minecraftData from "minecraft-data";
import { goals, Movements } from "mineflayer-pathfinder";
import { Vec3 } from "vec3";

const { GoalFollow, GoalNear } = goals;

function bareId(id) {
  return String(id).replace(/^minecraft:/, "");
}

function sleep(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new Error("Task cancelled"));
      return;
    }
    const timer = setTimeout(resolve, milliseconds);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(signal.reason ?? new Error("Task cancelled"));
    }, { once: true });
  });
}

async function abortable(operation, signal, onAbort) {
  if (signal.aborted) throw signal.reason ?? new Error("Task cancelled");
  return new Promise((resolve, reject) => {
    const abort = () => {
      try { onAbort?.(); } catch { /* best effort */ }
      reject(signal.reason ?? new Error("Task cancelled"));
    };
    signal.addEventListener("abort", abort, { once: true });
    Promise.resolve(operation).then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

function inventoryCount(bot, itemName) {
  return bot.inventory.items()
    .filter((item) => item.name === bareId(itemName))
    .reduce((total, item) => total + item.count, 0);
}

function nearest(bot, predicate, radius = 32) {
  return Object.values(bot.entities)
    .filter((entity) => entity !== bot.entity && entity.position && entity.position.distanceTo(bot.entity.position) <= radius && predicate(entity))
    .sort((left, right) => left.position.distanceTo(bot.entity.position) - right.position.distanceTo(bot.entity.position))[0] ?? null;
}

function isHostile(entity) {
  const kind = String(entity.kind ?? entity.mobType ?? "").toLowerCase();
  return entity.type === "mob" && (kind.includes("hostile") || kind.includes("monster"));
}

function blockMatches(block, names) {
  return block && names.includes(block.name);
}

export class MineflayerTaskRunner {
  #bot;
  #config;
  #status = "待命";
  #backgroundTimer = null;
  #eating = false;

  constructor(bot, config) {
    this.#bot = bot;
    this.#config = config;
    const data = minecraftData(bot.version);
    bot.pathfinder.setMovements(new Movements(bot, data));
    setInterval(() => void this.#maintainFood(), 2_000).unref();
  }

  get status() {
    return this.#status;
  }

  async run(task, buildPlan, callbacks, signal) {
    this.stopForeground();
    const spec = task.spec;
    this.#status = `正在执行 ${spec.kind}`;
    switch (spec.kind) {
      case "follow": return this.#follow(spec, callbacks);
      case "guard": return this.#guard(spec, callbacks);
      case "move": return this.#move(spec, callbacks, signal);
      case "explore": return this.#explore(spec, callbacks, signal);
      case "gather": return this.#gather(spec, callbacks, signal);
      case "craft": return this.#craft(spec, callbacks, signal);
      case "smelt": return this.#smelt(spec, callbacks, signal);
      case "farm": return this.#farm(spec, callbacks, signal);
      case "store": return this.#store(spec, callbacks, signal);
      case "eat": return this.#eat(spec, callbacks, signal);
      case "combat": return this.#combat(spec, callbacks, signal);
      case "dragon": return this.#dragon(spec, callbacks, signal);
      case "build": return this.#build(buildPlan, callbacks, signal);
      default: throw new Error(`Mineflayer worker does not support ${spec.kind}`);
    }
  }

  stopForeground() {
    this.#bot.pathfinder.stop();
    this.#bot.collectBlock?.cancelTask?.();
    this.#bot.pvp?.stop?.();
    this.#bot.clearControlStates();
  }

  stopAll() {
    this.stopForeground();
    if (this.#backgroundTimer) clearInterval(this.#backgroundTimer);
    this.#backgroundTimer = null;
    this.#bot.pathfinder.setGoal(null);
    this.#status = "已急停";
  }

  async #goto(position, range, signal) {
    const goal = new GoalNear(Math.floor(position.x), Math.floor(position.y), Math.floor(position.z), range);
    await abortable(this.#bot.pathfinder.goto(goal), signal, () => this.#bot.pathfinder.stop());
  }

  #follow(spec, callbacks) {
    this.#stopBackground();
    const player = this.#bot.players[spec.player]?.entity;
    if (!player) throw new Error(`找不到玩家 ${spec.player}`);
    this.#bot.pathfinder.setGoal(new GoalFollow(player, spec.distance), true);
    this.#status = `正在跟随 ${spec.player}`;
    callbacks.onProgress(1, this.#status);
    return `已开始跟随 ${spec.player}`;
  }

  #guard(spec, callbacks) {
    this.#stopBackground();
    const tick = () => {
      const owner = this.#bot.players[spec.player]?.entity;
      const center = owner?.position ?? this.#bot.entity.position;
      const target = nearest(this.#bot, (entity) => isHostile(entity) && entity.position.distanceTo(center) <= spec.radius, spec.radius * 2);
      if (target) {
        this.#status = `正在护卫 ${spec.player}`;
        this.#bot.pvp.attack(target);
      } else if (owner && this.#bot.entity.position.distanceTo(owner.position) > Math.max(4, spec.radius / 2)) {
        this.#bot.pathfinder.setGoal(new GoalFollow(owner, 3), true);
        this.#status = `护卫跟随 ${spec.player}`;
      } else {
        this.#bot.pvp.stop();
        this.#status = "护卫待命";
      }
    };
    tick();
    this.#backgroundTimer = setInterval(tick, 500);
    this.#backgroundTimer.unref();
    callbacks.onProgress(1, "已进入护卫模式");
    return `已进入 ${spec.radius} 格护卫范围`;
  }

  async #move(spec, callbacks, signal) {
    const start = this.#bot.entity.position.distanceTo(new Vec3(spec.target.x, spec.target.y, spec.target.z));
    callbacks.onProgress(0.05, `距离目标 ${Math.round(start)} 格`);
    await this.#goto(spec.target, 1, signal);
    callbacks.onProgress(1, "已到达目标");
    this.#status = "待命";
    return "已到达目标位置";
  }

  async #explore(spec, callbacks, signal) {
    const directions = {
      north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0], any: [1, 0.6],
    };
    const [dx, dz] = directions[spec.direction] ?? directions.any;
    const start = this.#bot.entity.position;
    await this.#goto({ x: start.x + dx * spec.radius, y: start.y, z: start.z + dz * spec.radius }, 3, signal);
    callbacks.onProgress(1, `已探索 ${spec.radius} 格`);
    this.#status = "待命";
    return `已完成 ${spec.radius} 格探索`;
  }

  async #gather(spec, callbacks, signal) {
    const data = minecraftData(this.#bot.version);
    const block = data.blocksByName[bareId(spec.itemId)];
    if (!block) throw new Error(`找不到方块 ${spec.itemId}`);
    const initial = inventoryCount(this.#bot, spec.itemId);
    while (inventoryCount(this.#bot, spec.itemId) - initial < spec.count) {
      const positions = this.#bot.findBlocks({ matching: block.id, maxDistance: 64, count: Math.min(16, spec.count) });
      if (!positions.length) throw new Error(`附近没有找到 ${spec.itemId}`);
      for (const position of positions) {
        const target = this.#bot.blockAt(position);
        if (!target) continue;
        await abortable(this.#bot.collectBlock.collect(target), signal, () => this.#bot.collectBlock.cancelTask?.());
        const gathered = inventoryCount(this.#bot, spec.itemId) - initial;
        callbacks.onProgress(Math.min(1, gathered / spec.count), `已采集 ${gathered}/${spec.count}`);
        if (gathered >= spec.count) break;
      }
    }
    this.#status = "待命";
    return `已采集 ${spec.count} 个 ${spec.itemId}`;
  }

  async #craft(spec, callbacks, signal) {
    const data = minecraftData(this.#bot.version);
    const item = data.itemsByName[bareId(spec.itemId)];
    if (!item) throw new Error(`找不到物品 ${spec.itemId}`);
    const initial = inventoryCount(this.#bot, spec.itemId);
    const tableId = data.blocksByName.crafting_table?.id;
    const table = tableId ? this.#bot.findBlock({ matching: tableId, maxDistance: 32 }) : null;
    const recipe = this.#bot.recipesFor(item.id, null, 1, table)[0] ?? this.#bot.recipesFor(item.id, null, 1, null)[0];
    if (!recipe) throw new Error(`当前材料无法制作 ${spec.itemId}`);
    const resultPerCraft = Math.max(1, Number(recipe.result?.count ?? 1));
    const craftOperations = Math.ceil(spec.count / resultPerCraft);
    if (table) await this.#goto(table.position, 3, signal);
    callbacks.onProgress(0.2, "正在制作");
    await abortable(this.#bot.craft(recipe, craftOperations, table), signal, () => this.#bot.closeWindow(this.#bot.currentWindow));
    const crafted = inventoryCount(this.#bot, spec.itemId) - initial;
    if (crafted < spec.count) throw new Error(`只制作出 ${crafted}/${spec.count} 个 ${spec.itemId}`);
    callbacks.onProgress(1, "制作完成");
    this.#status = "待命";
    return `已制作 ${crafted} 个 ${spec.itemId}`;
  }

  async #smelt(spec, callbacks, signal) {
    const data = minecraftData(this.#bot.version);
    const furnaceIds = ["furnace", "blast_furnace", "smoker"].map((name) => data.blocksByName[name]?.id).filter(Boolean);
    const furnaceBlock = this.#bot.findBlock({ matching: furnaceIds, maxDistance: 32 });
    if (!furnaceBlock) throw new Error("附近没有熔炉、烟熏炉或高炉");
    const input = this.#bot.inventory.items().find((item) => item.name === bareId(spec.itemId));
    if (!input || input.count < spec.count) throw new Error(`背包中缺少 ${spec.count} 个 ${spec.itemId}`);
    const fuels = ["coal", "charcoal", "coal_block", "blaze_rod", "oak_planks", "spruce_planks"];
    const fuel = this.#bot.inventory.items().find((item) => fuels.includes(item.name));
    if (!fuel) throw new Error("背包中没有可用燃料");
    await this.#goto(furnaceBlock.position, 3, signal);
    const furnace = await abortable(this.#bot.openFurnace(furnaceBlock), signal);
    try {
      await abortable(furnace.putInput(input.type, null, spec.count), signal);
      await abortable(furnace.putFuel(fuel.type, null, Math.min(fuel.count, Math.ceil(spec.count / 8))), signal);
      let produced = 0;
      const deadline = Date.now() + Math.max(90_000, spec.count * 12_000);
      while (produced < spec.count && Date.now() < deadline) {
        await sleep(1_000, signal);
        const output = furnace.outputItem();
        if (output) {
          produced += output.count;
          await abortable(furnace.takeOutput(), signal);
        }
        callbacks.onProgress(Math.min(0.99, produced / spec.count), `已烧炼 ${produced}/${spec.count}`);
      }
      if (produced < spec.count) throw new Error("烧炼等待超时");
    } finally {
      furnace.close();
    }
    callbacks.onProgress(1, "烧炼完成");
    this.#status = "待命";
    return `已烧炼 ${spec.count} 个 ${spec.itemId}`;
  }

  async #farm(spec, callbacks, signal) {
    const cropName = bareId(spec.cropId);
    const seedNames = {
      wheat: "wheat_seeds", carrots: "carrot", potatoes: "potato", beetroot: "beetroot_seeds",
    };
    const seedName = seedNames[cropName] ?? cropName;
    const blocks = this.#bot.findBlocks({
      matching: (block) => block.name === cropName || (block.name === "farmland" && this.#bot.blockAt(block.position.offset(0, 1, 0))?.name === "air"),
      maxDistance: spec.radius,
      count: 256,
    });
    let handled = 0;
    for (const position of blocks) {
      if (signal.aborted) throw signal.reason ?? new Error("Task cancelled");
      const block = this.#bot.blockAt(position);
      if (!block) continue;
      if (block.name === cropName && spec.action !== "plant") {
        const age = Number(block.getProperties?.().age ?? block.metadata ?? 0);
        const matureAt = ["beetroots", "nether_wart", "sweet_berry_bush"].includes(block.name) ? 3 : 7;
        if (age < matureAt && spec.action !== "harvest") continue;
        await this.#goto(block.position, 3, signal);
        await abortable(this.#bot.dig(block), signal, () => this.#bot.stopDigging());
        if (spec.action === "harvest") {
          handled += 1;
          continue;
        }
      }
      const farmland = block.name === "farmland" ? block : this.#bot.blockAt(position.offset(0, -1, 0));
      if (!farmland || farmland.name !== "farmland") continue;
      const seed = this.#bot.inventory.items().find((item) => item.name === seedName);
      if (!seed) throw new Error(`背包中缺少种子 ${seedName}`);
      await this.#goto(farmland.position, 3, signal);
      await abortable(this.#bot.equip(seed, "hand"), signal);
      await abortable(this.#bot.placeBlock(farmland, new Vec3(0, 1, 0)), signal);
      handled += 1;
      callbacks.onProgress(Math.min(0.99, handled / Math.max(1, blocks.length)), `已处理 ${handled} 处农田`);
    }
    callbacks.onProgress(1, `农务完成，共处理 ${handled} 处`);
    this.#status = "待命";
    return `已完成 ${spec.action}，处理 ${handled} 处农田`;
  }

  async #store(spec, callbacks, signal) {
    const data = minecraftData(this.#bot.version);
    const containerIds = ["chest", "trapped_chest", "barrel"]
      .map((name) => data.blocksByName[name]?.id)
      .filter(Boolean);
    const block = this.#bot.findBlock({ matching: containerIds, maxDistance: 32 });
    if (!block) throw new Error("附近没有箱子或木桶");
    await this.#goto(block.position, 3, signal);
    const container = await abortable(this.#bot.openContainer(block), signal);
    try {
      const candidates = this.#bot.inventory.items().filter((item) => !spec.itemId || item.name === bareId(spec.itemId));
      if (!candidates.length) throw new Error(spec.itemId ? `背包中没有 ${spec.itemId}` : "背包中没有可整理物品");
      let remaining = spec.count ?? Number.POSITIVE_INFINITY;
      for (const item of candidates) {
        const count = Math.min(item.count, remaining);
        await abortable(container.deposit(item.type, null, count), signal);
        remaining -= count;
        callbacks.onProgress(spec.count ? Math.min(1, (spec.count - remaining) / spec.count) : 0.8, `已存放 ${item.displayName}`);
        if (remaining <= 0) break;
      }
    } finally {
      container.close();
    }
    callbacks.onProgress(1, "物品已入库");
    this.#status = "待命";
    return "物品已整理到附近容器";
  }

  async #combat(spec, callbacks, signal) {
    const expected = bareId(spec.targetType).toLowerCase();
    const target = nearest(this.#bot, (entity) => {
      if (!isHostile(entity)) return false;
      if (expected === "hostile" || expected === "monster" || expected === "any") return true;
      return String(entity.name ?? entity.mobType ?? "").toLowerCase().includes(expected);
    }, spec.maxDistance);
    if (!target) return "附近没有符合条件的敌对目标";
    this.#bot.pvp.attack(target);
    const deadline = Date.now() + 60_000;
    while (target.isValid !== false && target.health !== 0 && Date.now() < deadline) {
      await sleep(500, signal);
      callbacks.onProgress(0.5, `正在应对 ${target.displayName ?? target.name}`);
    }
    this.#bot.pvp.stop();
    if (Date.now() >= deadline) throw new Error("战斗超时，已停止追击");
    callbacks.onProgress(1, "威胁已清除");
    this.#status = "待命";
    return "威胁已清除";
  }

  async #dragon(spec, callbacks, signal) {
    const target = spec.targetId
      ? this.#bot.entities[spec.targetId]
      : nearest(this.#bot, (entity) => {
        const name = String(entity.name ?? entity.mobType ?? "").toLowerCase();
        return entity.type === "mob" && (name.includes("dragon") || name.includes("wyrm") || name.includes("\u9f99"));
      }, 48);
    if (!target) throw new Error("\u9644\u8fd1\u6ca1\u6709\u627e\u5230\u9f99\u7c7b\u76ee\u6807");

    if (spec.action === "observe") {
      callbacks.onProgress(1, `\u5df2\u5b9a\u4f4d\u9f99\u7c7b\u5b9e\u4f53 ${target.id ?? target.uuid ?? ""}`);
      this.#status = "\u5f85\u547d";
      return "\u5df2\u5b9a\u4f4d\u9f99\u7c7b\u5b9e\u4f53";
    }

    await this.#goto(target.position, 4, signal);
    if (["feed", "heal", "tame", "care-for-egg"].includes(spec.action)) {
      const held = this.#bot.inventory.items().find((item) => {
        const name = item.name.toLowerCase();
        return spec.action === "heal"
          ? /meat|fish|bone|milk|heal/.test(name)
          : /dragon|egg|meat|fish|bone/.test(name);
      });
      if (!held) throw new Error("\u80cc\u5305\u4e2d\u6ca1\u6709\u5408\u9002\u7684\u9f99\u7c7b\u62a4\u7406\u7269\u54c1");
      await abortable(this.#bot.equip(held, "hand"), signal);
      if (typeof this.#bot.activateEntity !== "function") throw new Error("\u5f53\u524d\u670d\u52a1\u5668\u4e0d\u652f\u6301\u5b9e\u4f53\u4ea4\u4e92");
      await abortable(this.#bot.activateEntity(target), signal);
      callbacks.onProgress(1, `\u5df2\u5b8c\u6210\u9f99\u7c7b\u64cd\u4f5c ${spec.action}`);
      this.#status = "\u5f85\u547d";
      return `\u5df2\u5b8c\u6210\u9f99\u7c7b\u64cd\u4f5c ${spec.action}`;
    }
    if (spec.action === "follow") {
      this.#stopBackground();
      this.#bot.pathfinder.setGoal(new GoalFollow(target, 4), true);
      this.#status = "\u6b63\u5728\u8ddf\u968f\u9f99\u7c7b";
      callbacks.onProgress(1, this.#status);
      return "\u5df2\u5f00\u59cb\u8ddf\u968f\u9f99\u7c7b";
    }
    if (spec.action === "stay") {
      this.#stopBackground();
      this.#bot.pathfinder.setGoal(new GoalNear(Math.floor(target.position.x), Math.floor(target.position.y), Math.floor(target.position.z), 2));
      this.#status = "\u9f99\u7c7b\u7b49\u5f85";
      callbacks.onProgress(1, this.#status);
      return "\u5df2\u5b89\u6392\u9f99\u7c7b\u7559\u5728\u539f\u5730";
    }
    if (spec.action === "mount") {
      if (typeof this.#bot.mount === "function") await abortable(this.#bot.mount(target), signal);
      else if (typeof this.#bot.activateEntity === "function") await abortable(this.#bot.activateEntity(target), signal);
      else throw new Error("\u5f53\u524d\u670d\u52a1\u5668\u4e0d\u652f\u6301\u9a91\u4e58\u4ea4\u4e92");
      callbacks.onProgress(1, "\u5df2\u5b8c\u6210\u9a91\u4e58");
      this.#status = "\u5f85\u547d";
      return "\u5df2\u5b8c\u6210\u9a91\u4e58";
    }
    if (spec.action === "dismount") {
      if (typeof this.#bot.dismount === "function") await abortable(this.#bot.dismount(), signal);
      else throw new Error("\u5f53\u524d\u670d\u52a1\u5668\u4e0d\u652f\u6301\u4e0b\u9a6c");
      callbacks.onProgress(1, "\u5df2\u4e0b\u9a6c");
      this.#status = "\u5f85\u547d";
      return "\u5df2\u4e0b\u9a6c";
    }
    throw new Error(`Mineflayer worker does not support dragon action ${spec.action}`);
  }

  async #build(plan, callbacks, signal) {
    if (!plan?.confirmed) throw new Error("建筑计划缺失或尚未确认");
    const origin = plan.origin;
    const blocks = [...plan.blocks].sort((left, right) => left.position.y - right.position.y);
    const faces = [new Vec3(0, -1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(0, 1, 0)];
    for (let index = 0; index < blocks.length; index += 1) {
      const entry = blocks[index];
      if (entry.blockId === "minecraft:air") continue;
      const target = new Vec3(
        Math.round(origin.x + entry.position.x),
        Math.round(origin.y + entry.position.y),
        Math.round(origin.z + entry.position.z),
      );
      if (this.#bot.blockAt(target)?.name === bareId(entry.blockId)) continue;
      const item = this.#bot.inventory.items().find((candidate) => candidate.name === bareId(entry.blockId));
      if (!item) throw new Error(`背包中缺少 ${entry.blockId}`);
      await this.#goto(target, 4, signal);
      let support = null;
      let face = null;
      for (const offset of faces) {
        const candidate = this.#bot.blockAt(target.plus(offset));
        if (candidate && !blockMatches(candidate, ["air", "water", "lava"])) {
          support = candidate;
          face = offset.scaled(-1);
          break;
        }
      }
      if (!support) throw new Error(`方块 ${target} 没有支撑面`);
      await abortable(this.#bot.equip(item, "hand"), signal);
      await abortable(this.#bot.placeBlock(support, face), signal);
      callbacks.onProgress((index + 1) / blocks.length, `建造中 ${index + 1}/${blocks.length}`);
    }
    this.#status = "待命";
    return `建筑 ${plan.name} 已完成`;
  }

  #stopBackground() {
    if (this.#backgroundTimer) clearInterval(this.#backgroundTimer);
    this.#backgroundTimer = null;
    this.#bot.pvp?.stop?.();
    this.#bot.pathfinder.setGoal(null);
  }

  async #maintainFood() {
    if (this.#eating || this.#bot.food > 12 || this.#bot.health <= 0 || this.#bot.currentWindow) return;
    const food = this.#bot.inventory.items()
      .filter((item) => Number(item.foodPoints ?? item.food?.foodPoints ?? 0) > 0)
      .sort((left, right) => Number(right.foodPoints ?? right.food?.foodPoints ?? 0) - Number(left.foodPoints ?? left.food?.foodPoints ?? 0))[0];
    if (!food) return;
    this.#eating = true;
    try {
      await this.#bot.equip(food, "hand");
      await this.#bot.consume();
    } catch {
      // Food maintenance retries on the next interval.
    } finally {
      this.#eating = false;
    }
  }

  async #eat(spec, callbacks, signal) {
    const requested = Math.max(1, Number(spec.count ?? 1));
    let eaten = 0;
    this.#eating = true;
    try {
      while (eaten < requested) {
        const food = this.#bot.inventory.items().find((item) => (
          Number(item.foodPoints ?? item.food?.foodPoints ?? 0) > 0
          && (!spec.itemId || `minecraft:${item.name}` === spec.itemId || item.name === spec.itemId)
        ));
        if (!food) throw new Error(`背包中没有可食用的 ${spec.itemId ?? "食物"}`);
        await abortable(this.#bot.equip(food, "hand"), signal);
        await abortable(this.#bot.consume(), signal);
        eaten++;
        callbacks.onProgress(eaten / requested, `已吃 ${eaten}/${requested}`);
      }
    } finally {
      this.#eating = false;
    }
    this.#status = "待命";
    return `已吃下 ${eaten} 份 ${spec.itemId ?? "食物"}`;
  }
}
