import { describe, expect, it } from "vitest";
import { parseBuildMenuSelection, parseDeterministicChatAction } from "./chat-action-intent.js";

describe("parseDeterministicChatAction", () => {
  it("turns common follow controls into immediate NPC actions", () => {
    expect(parseDeterministicChatAction("你先跟着我", "PlayerOne")).toMatchObject({
      operation: "control",
      action: "follow",
    });
    expect(parseDeterministicChatAction("原地等", "PlayerOne")).toMatchObject({
      operation: "control",
      action: "stay",
    });
    expect(parseDeterministicChatAction("快回来", "PlayerOne")).toMatchObject({
      operation: "control",
      action: "recall",
    });
    expect(parseDeterministicChatAction("Luna，快跟着我", "PlayerOne", "Luna")).toMatchObject({
      operation: "control",
      action: "follow",
    });
    expect(parseDeterministicChatAction("怎么还是不跟着我呀", "PlayerOne")).toMatchObject({
      operation: "control",
      action: "follow",
    });
    expect(parseDeterministicChatAction("上船跟着我", "PlayerOne")).toMatchObject({
      operation: "control",
      action: "follow",
    });
  });

  it("turns an uncounted wood request into gather and delivery", () => {
    expect(parseDeterministicChatAction("撸点木头给我", "PlayerOne")).toEqual({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 8, player: "PlayerOne" },
        requestedBy: "PlayerOne",
        note: "撸点木头给我",
      },
      reply: "好，我去采集 8 个原木，采完交给你。",
    });
  });

  it("keeps explicit walk-only wood requests on foot through T chat", () => {
    expect(parseDeterministicChatAction("走路去砍1个原木，留在背包，不要传送", "PlayerOne")).toEqual({
      operation: "task",
      spec: {
        kind: "gather",
        itemId: "#minecraft:logs",
        count: 1,
        movement: "walk",
        requestedBy: "PlayerOne",
        note: "走路去砍1个原木，留在背包，不要传送",
      },
      reply: "好，我全程步行去采集 1 个原木。",
    });
    expect(parseDeterministicChatAction("步行去砍1个原木给我，别瞬移", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 1, player: "PlayerOne", movement: "walk" },
      },
      reply: "好，我全程步行去采集 1 个原木，采完交给你。",
    });
    expect(parseDeterministicChatAction("不要传送，砍1个原木，留在背包", "PlayerOne")).toMatchObject({
      spec: { kind: "gather", movement: "walk" },
    });
  });

  it("turns an expedition request into the persistent remote gather workflow", () => {
    expect(parseDeterministicChatAction("去远征挖12个铁矿给我", "PlayerOne")).toEqual({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.expedition-and-deliver",
        arguments: { itemId: "minecraft:raw_iron", count: 12, player: "PlayerOne" },
        requestedBy: "PlayerOne",
        note: "去远征挖12个铁矿给我",
      },
      reply: "好，我开始远征采集 12 个铁矿，完成后回来交给你。",
    });
  });

  it("turns an explicit melon request into an immediate eat task", () => {
    expect(parseDeterministicChatAction("你先把瓜吃了", "PlayerOne")).toEqual({
      operation: "task",
      spec: {
        kind: "eat",
        itemId: "minecraft:melon_slice",
        count: 1,
        requestedBy: "PlayerOne",
        note: "你先把瓜吃了",
      },
      reply: "好，我现在把西瓜吃掉。",
    });
  });

  it("recognizes rotten flesh and stops naturally when eating to full", () => {
    expect(parseDeterministicChatAction("你把腐肉吃掉啦", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "eat", itemId: "minecraft:rotten_flesh", count: 1 },
      reply: "好，我现在把腐肉吃掉。",
    });
    expect(parseDeterministicChatAction("吃到饱", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "eat", count: 64 },
    });
  });

  it("preserves exact counts for specified food from Minecraft T chat", () => {
    expect(parseDeterministicChatAction("把3个腐肉吃掉", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "eat", itemId: "minecraft:rotten_flesh", count: 3 },
    });
    expect(parseDeterministicChatAction("把2片西瓜吃掉", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "eat", itemId: "minecraft:melon_slice", count: 2 },
    });
  });

  it("turns the natural T prompt into persistent food provisioning", () => {
    expect(parseDeterministicChatAction("去找些食物", "PlayerOne")).toEqual({
      operation: "task",
      spec: {
        kind: "provision-food",
        count: 8,
        source: "auto",
        foodCategory: "any",
        destination: "backpack",
        requestedBy: "PlayerOne",
        note: "去找些食物",
      },
      reply: "好，我先检查已有口粮，再去寻找 8 份食物，并在背包保留 8 份口粮。",
    });
    expect(parseDeterministicChatAction("去找12份食物打猎", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "provision-food", count: 12, source: "hunt" },
    });
    expect(parseDeterministicChatAction("去找些食物放到家里的箱子里", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "provision-food", count: 8, destination: "home-storage" },
      reply: expect.stringContaining("存回家中箱子"),
    });
    expect(parseDeterministicChatAction("去找十份吃的给我", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "provision-food", count: 10, destination: "player", player: "PlayerOne" },
      reply: expect.stringContaining("带回来交给你"),
    });
    expect(parseDeterministicChatAction("给我找些食物", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "provision-food", count: 8, destination: "player", player: "PlayerOne" },
      reply: expect.stringContaining("带回来交给你"),
    });
    expect(parseDeterministicChatAction("弄点肉给我，我要16个", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "provision-food",
        count: 16,
        source: "hunt",
        destination: "player",
        player: "PlayerOne",
      },
      reply: expect.stringContaining("16 份肉"),
    });
    expect(parseDeterministicChatAction("给我16个肉", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "provision-food",
        count: 16,
        source: "hunt",
        destination: "player",
        player: "PlayerOne",
      },
      reply: expect.stringContaining("16 份肉"),
    });
    expect(parseDeterministicChatAction("找十二份肉放进家里的箱子", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "provision-food", count: 12, source: "hunt", destination: "home-storage" },
    });
  });

  it("turns livestock prompts into a build-and-ranch action chain", () => {
    expect(parseDeterministicChatAction("建个围栏养两只牛", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.establish-ranch",
        arguments: { animalType: "minecraft:cow", count: 2, radius: 128 },
      },
    });
    expect(parseDeterministicChatAction("把猪牛羊牵过来养", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.establish-ranch",
        arguments: { animalType: "any", count: 6, radius: 128 },
      },
    });
  });

  it("turns home storage phrases into deterministic tasks", () => {
    expect(parseDeterministicChatAction("把背包里的多余东西放进家里的箱子", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "organize-storage", radius: 24 },
    });
    expect(parseDeterministicChatAction("从家里箱子拿16个原木", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "retrieve", itemId: "#minecraft:logs", count: 16 },
    });
    expect(parseDeterministicChatAction("从家里箱子拿16个原木给我", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.retrieve-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 16, player: "PlayerOne" },
      },
    });
    expect(parseDeterministicChatAction("从家里箱子拿来3个苹果", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.retrieve-and-deliver",
        arguments: { itemId: "minecraft:apple", count: 3, player: "PlayerOne" },
      },
    });
  });

  it("runs audited build templates without waiting for a model turn", () => {
    expect(parseDeterministicChatAction("帮我建个仓库", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.storage-room", arguments: {} },
    });
    expect(parseDeterministicChatAction("建一个刷石机", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.cobblestone-generator" },
    });
    expect(parseDeterministicChatAction("建个房子Luna", "PlayerOne", "Luna")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("建个房子小雪", "PlayerOne", "小雪")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("给我起个家", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("房子还没建完呢，继续建造", "PlayerOne")).toMatchObject({
      operation: "resume-build",
    });
    expect(parseDeterministicChatAction("怎么还没有开始建造房子", "PlayerOne")).toMatchObject({
      operation: "resume-build",
    });
  });

  it("understands natural build corrections and material-family preferences", () => {
    expect(parseDeterministicChatAction("用深色橡木建房", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "build.basic-shelter",
        materialPreference: {
          source: "auto",
          preferredBlockId: "minecraft:dark_oak_planks",
          allowMixed: false,
        },
      },
    });
    expect(parseDeterministicChatAction("拿这些深色橡树建", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "build.basic-shelter",
        materialPreference: {
          source: "inventory",
          preferredBlockId: "minecraft:dark_oak_planks",
        },
      },
    });
    expect(parseDeterministicChatAction("用附近的木头建个小屋", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        materialPreference: { source: "nearby" },
      },
    });
    expect(parseDeterministicChatAction("用深板岩砖建房", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        materialPreference: { preferredBlockId: "minecraft:deepslate_bricks" },
      },
    });
    expect(parseDeterministicChatAction("不要把木头给我，你来在附近建造小屋", "PlayerOne")).toMatchObject({
      operation: "task",
      replaceConflictingDelivery: true,
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("老是把木头给我呀", "PlayerOne")).toMatchObject({
      operation: "task",
      replaceConflictingDelivery: true,
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("你来建造", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
    expect(parseDeterministicChatAction("根据图纸造一个屋子", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.basic-shelter" },
    });
  });

  it("opens a build menu and parses a pending numeric or named selection", () => {
    expect(parseDeterministicChatAction("建造", "PlayerOne")).toMatchObject({
      operation: "reply",
      context: "build-menu",
      reply: expect.stringContaining("1基础住宅"),
    });
    expect(parseBuildMenuSelection("8", "PlayerOne")).toMatchObject({
      operation: "task",
      context: "build-selection",
      spec: { kind: "macro", skillId: "build.mob-farm" },
    });
    expect(parseBuildMenuSelection("动物围栏", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "build.animal-pen" },
    });
    expect(parseBuildMenuSelection("取消", "PlayerOne")).toEqual({
      operation: "reply",
      reply: "已取消本次建造选择。",
      context: "build-menu-cancel",
    });
    expect(parseBuildMenuSelection("今天吃什么", "PlayerOne")).toBeNull();
  });

  it("runs common crafting and farm commands deterministically", () => {
    expect(parseDeterministicChatAction("做一把铁剑", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:iron_sword", count: 1 },
    });
    expect(parseDeterministicChatAction("制作一套铁装备", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "craft.iron-equipment" },
    });
    expect(parseDeterministicChatAction("给我来个镐子", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:pickaxe", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("t给我来个石镐", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:stone_pickaxe", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("来个石镐", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:stone_pickaxe", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("给我来个箱子", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:chest", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("给我来个熔炉", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:furnace", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("合成武器", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:melee_weapon", count: 1 },
    });
    expect(parseDeterministicChatAction("我需要64个火把", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:torch", count: 64, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("造一个钓鱼竿", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:fishing_rod", count: 1 },
    });
    expect(parseDeterministicChatAction("给我做一把钓鱼竿", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:fishing_rod", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("T我想要一把钻石镐", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:diamond_pickaxe", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("Luna，给我做一把钻石镐", "PlayerOne", "Luna")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:diamond_pickaxe", count: 1, deliverTo: "PlayerOne" },
    });
    expect(parseDeterministicChatAction("给我一个剪刀", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "craft", itemId: "minecraft:shears", count: 1, deliverTo: "PlayerOne" },
    });
    for (const [message, itemId] of [
      ["做一把钻石镐", "minecraft:diamond_pickaxe"],
      ["给我来个金斧", "minecraft:golden_axe"],
      ["制作铁铲", "minecraft:iron_shovel"],
      ["打造钻石锄", "minecraft:diamond_hoe"],
      ["合成金剑", "minecraft:golden_sword"],
      ["做钻石胸甲", "minecraft:diamond_chestplate"],
      ["给我来个金头盔", "minecraft:golden_helmet"],
    ] as const) {
      expect(parseDeterministicChatAction(message, "PlayerOne")).toMatchObject({
        operation: "task",
        spec: { kind: "craft", itemId, count: 1 },
      });
    }
    expect(parseDeterministicChatAction("生产一套防具", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "craft.iron-equipment" },
    });
    expect(parseDeterministicChatAction("种点小麦", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "farm", cropId: "minecraft:wheat", action: "cycle", radius: 12 },
    });
    expect(parseDeterministicChatAction("帮我收割胡萝卜", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "farm", cropId: "minecraft:carrots", action: "harvest", radius: 12 },
    });
  });

  it("starts the recoverable craft-and-place bed workflow for common classifiers", () => {
    for (const message of ["帮我制作一个床", "制作一张床", "做床喵"]) {
      expect(parseDeterministicChatAction(message, "PlayerOne")).toMatchObject({
        operation: "task",
        spec: {
          kind: "macro",
          skillId: "life.craft-and-place-bed",
          arguments: {},
          requestedBy: "PlayerOne",
        },
      });
    }
  });

  it("turns fishing, sleeping and physical throwing into deterministic tasks", () => {
    expect(parseDeterministicChatAction("去钓3次鱼", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "fish", count: 3, radius: 24 },
    });
    expect(parseDeterministicChatAction("睡到天亮", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "sleep", radius: 32 },
    });
    expect(parseDeterministicChatAction("把7个腐肉丢给我", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "drop", itemId: "minecraft:rotten_flesh", count: 7, player: "PlayerOne" },
    });
  });

  it("routes self-status and inventory questions to an exact local snapshot", () => {
    for (const message of ["你在干什么", "AI现在有什么动作呀", "AI 干什么是否有动作了", "Luna，任务做到哪了"]) {
      expect(parseDeterministicChatAction(message, "PlayerOne", "Luna")).toEqual({
        operation: "inspect",
        scope: "activity",
      });
    }
    expect(parseDeterministicChatAction("你的饱食度现在多少呀", "PlayerOne")).toEqual({
      operation: "inspect",
      scope: "vitals",
    });
    expect(parseDeterministicChatAction("我给你的是什么武器呀", "PlayerOne")).toEqual({
      operation: "inspect",
      scope: "inventory",
    });
    expect(parseDeterministicChatAction("Luna，汇报完整状态", "PlayerOne", "Luna")).toEqual({
      operation: "inspect",
      scope: "full",
    });
  });

  it("routes item whereabouts questions to the local bounded transaction ledger", () => {
    expect(parseDeterministicChatAction("为什么没有煤炭，是不是扔掉了？", "PlayerOne", "Luna")).toEqual({
      operation: "inspect-item-history",
      items: [{ itemId: "minecraft:coal", itemName: "煤炭" }],
    });
    expect(parseDeterministicChatAction("铁锭怎么变成铁粒又合成铁锭了", "PlayerOne", "Luna")).toEqual({
      operation: "inspect-item-history",
      items: [
        { itemId: "minecraft:iron_ingot", itemName: "铁锭" },
        { itemId: "minecraft:iron_nugget", itemName: "铁粒" },
      ],
    });
  });

  it("accepts a natural sentence suffix when asking the NPC to eat", () => {
    expect(parseDeterministicChatAction("你把腐肉吃掉吧", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "eat", itemId: "minecraft:rotten_flesh", count: 1 },
    });
  });

  it("parses and caps explicit wood counts", () => {
    const action = parseDeterministicChatAction("帮我砍一百个木头", "PlayerOne");
    expect(action).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 64, player: "PlayerOne" },
      },
    });
  });

  it("defaults ordinary wood requests to gathering and returning the result", () => {
    expect(parseDeterministicChatAction("帮我砍16个木头吧", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 16, player: "PlayerOne" },
      },
    });
  });

  it("can explicitly keep gathered wood in the NPC backpack", () => {
    expect(parseDeterministicChatAction("帮我砍16个木头，先留在背包", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "gather", itemId: "#minecraft:logs", count: 16 },
    });
  });

  it("honors an explicit wood count when delivery is requested", () => {
    expect(parseDeterministicChatAction("帮我砍20个原木交给我", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 20, player: "PlayerOne" },
      },
    });
  });

  it("leaves casual conversation to the configured AI provider", () => {
    expect(parseDeterministicChatAction("今天想聊点什么？", "PlayerOne")).toBeNull();
  });

  it("turns common dragon riding phrases into deterministic tasks", () => {
    expect(parseDeterministicChatAction("上龙跟着我", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "macro", skillId: "dragon.mount-and-follow" },
    });
    expect(parseDeterministicChatAction("下来跟我走", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "dragon", action: "dismount" },
    });
    expect(parseDeterministicChatAction("召回你的龙", "PlayerOne")).toMatchObject({
      operation: "task",
      spec: { kind: "dragon", action: "recall" },
    });
  });
});
