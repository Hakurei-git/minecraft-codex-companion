import type { CompanionAction, TaskSpec } from "@mc/protocol";

const DEFAULT_WOOD_COUNT = 8;
const MAX_CHAT_WOOD_COUNT = 64;
const GENERIC_LOG_SELECTOR = "#minecraft:logs";
const EAT_COMMAND = /^\s*(?:你\s*)?(?:先\s*)?(?:把\s*)?(?:(?<count>\d{1,2}|[一二两三四五六七八九十]+)\s*(?:个|片|份)?\s*)?(?:(?<food>金苹果|西瓜片?|瓜|腐肉|面包|牛排|熟牛肉|苹果|胡萝卜|烤土豆|土豆|食物|东西)\s*)?(?:吃了|吃掉(?:啦|了)?|吃一下|吃吧|吃(?:点)?|吃到饱|怎么(?:还)?不吃(?:呀|啊)?)(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const PROVISION_FOOD_COMMAND = /^\s*(?:你\s*)?(?:(?<recipientPrefix>给我)\s*)?(?:帮我\s*)?(?:去\s*)?(?:寻找|找|弄|准备|收集|获取)\s*(?:(?<count>\d{1,2}|[一二两三四五六七八九十]+)\s*(?:个|片|份)?\s*)?(?:点儿|一点|点|一些|些|几)?\s*(?:食物|吃的|口粮)(?:\s*(?<source>打猎|狩猎|猎取|采集))?\s*(?:[，,]\s*)?(?<destination>(?:(?:放|存)(?:回到|回|到|进|在)?(?:家里|家中|家附近|附近)?(?:的)?(?:箱子|仓库)(?:里|中)?)|(?:(?:给|交给|拿给|带给|送给)我)|(?:(?:你自己|自己)?(?:留着|留在背包|放在背包|当口粮)))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const MEAT_PROVISION_HINT = /(?:肉类|生肉|熟肉|牛肉|猪肉|羊肉|肉)/u;
const MEAT_PROVISION_ACTION = /(?:寻找|找|弄|准备|收集|获取|猎取|打猎|狩猎|给我|交给我|拿给我|带给我|送给我|我要|我需要|我想要)/u;
const RANCH_COMMAND = /^(?=.*(?:养|饲养|畜牧|牵(?:回来|过来|回家)|带(?:回来|过来|回家)))(?=.*(?:猪牛羊|牲畜|猪|牛|羊)).*$/u;
const WOOD_COMMAND = /^\s*(?:(?:现在|先|赶紧|快)\s*)?(?:(?:可以|能不能|能否|能|请|麻烦)\s*)?(?:你\s*)?(?:帮我\s*)?(?:去\s*)?(?:撸|砍|采集|采|收集|弄|搞|整|获取|找)\s*(?:一下|一趟)?\s*(?:(?<count>\d{1,4}|[一二两三四五六七八九十百]+)\s*(?:个|块|根|组)?\s*)?(?:点儿|一点|点|一些|些|几)?\s*(?:木头|木材|原木)(?:\s*(?<trailingCount>\d{1,4}|[一二两三四五六七八九十百]+)\s*(?:个|块|根|组)?)?(?:\s*(?:给|交给|拿给|送给)\s*(?:我|[\p{L}\p{N}_-]+))?\s*(?:吧|叭|呀|啊|喵)?\s*[！!。.]?\s*$/u;

export type DeterministicChatAction =
  | { operation: "control"; action: CompanionAction; reply: string }
  | {
      operation: "task";
      spec: TaskSpec;
      reply: string;
      context?: "build-selection";
      replaceConflictingDelivery?: boolean;
    }
  | { operation: "resume-build"; reply: string }
  | { operation: "resume-goal"; reply: string }
  | { operation: "reply"; reply: string; context: "build-menu" | "build-menu-cancel" }
  | { operation: "inspect"; scope: "activity" | "vitals" | "inventory" | "full" }
  | { operation: "home-memory"; action: "corner-one" | "corner-two" | "rescan"; reply: string }
  | {
      operation: "inspect-item-history";
      items: Array<{ itemId: string; itemName: string }>;
    };

const FOLLOW_COMMAND = /^\s*(?:你\s*)?(?:(?:先|快|赶紧|继续|一直)\s*)?(?:跟着我|跟紧我|跟我走|跟上我|随我来|follow\s+me)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/iu;
const BOAT_FOLLOW_COMMAND = /^\s*(?:你\s*)?(?:快|现在|先)?\s*(?:上船|坐船)(?:\s*(?:跟着我|跟我走|跟随我|一起走))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const FOLLOW_COMPLAINT = /^\s*(?:怎么|为什么)\s*(?:还是|还|又)?\s*(?:不|没在?)\s*(?:跟着我|跟紧我|跟上我)(?:了)?\s*(?:吧|呀|啊|喵)?\s*[？?！!。.]?\s*$/iu;
const STAY_COMMAND = /^\s*(?:你\s*)?(?:先\s*)?(?:在这(?:里)?待着|原地(?:等|等待|待命)|别跟了|停在这(?:里)?|stay)\s*[！!。.]?\s*$/iu;
const RECALL_COMMAND = /^\s*(?:你\s*)?(?:快\s*)?(?:回来|回到我身边|到我身边来|召回|recall)\s*[！!。.]?\s*$/iu;
const HOME_CORNER_ONE_COMMAND = /^\s*(?:(?:记录|设置|标记)(?:一下)?(?:房屋|屋子|家)(?:范围|边界)?(?:的)?|(?:把|将)(?:这里|当前位置)\s*(?:设为|记录为|记为)(?:房屋|屋子|家)(?:范围|边界)?(?:的)?)(?:第一个角|第一角|角一|起点)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const HOME_CORNER_TWO_COMMAND = /^\s*(?:(?:记录|设置|标记)(?:一下)?(?:房屋|屋子|家)(?:范围|边界)?(?:的)?|(?:把|将)(?:这里|当前位置)\s*(?:设为|记录为|记为)(?:房屋|屋子|家)(?:范围|边界)?(?:的)?)(?:第二个角|第二角|角二|终点|对角)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const HOME_RESCAN_COMMAND = /^\s*(?:重新|再次)?(?:扫描|识别|记录)(?:一下)?(?:我的)?(?:房屋|屋子|家)(?:范围|边界)?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const VITALS_QUESTION = /^(?=.*(?:你|npc|ai|同伴))(?=.*(?:状态|生命|血量|多少血|几滴血|饱食度|饿不饿|饿了|回血|健康)).*(?:[？?]|多少|怎么样|如何|吗|没满|不回血|饿).*/iu;
const INVENTORY_QUESTION = /^(?=.*(?:你|npc|ai|同伴))(?=.*(?:背包|包里|装备|主手|副手|拿着|物品|东西|武器|工具)).*(?:[？?]|什么|哪些|有什么|看到|看见|认识).*/iu;
const FULL_STATUS_QUESTION = /^\s*(?:查看|汇报|告诉我|说一下)?\s*(?:你|npc|ai|同伴)?\s*(?:现在|当前)?\s*(?:完整)?状态\s*(?:吧|呀|啊|喵)?\s*[？?！!。.]?\s*$/iu;
const ACTIVITY_QUESTION = /^\s*(?:(?:你|npc|ai|同伴)\s*)?(?:(?:现在|当前|这会儿)\s*)?(?:(?:在|正在)\s*)?(?:干什么(?:是否有动作了?)?|做什么|忙什么|执行什么|有什么动作|有没有动作|是否有动作|在执行什么任务|执行的什么任务|任务(?:进度)?(?:做到哪(?:里|儿)?|到哪(?:里|儿)?|怎么样)|做到哪(?:里|儿)?了)\s*(?:了|呢|呀|啊|吗|喵)?\s*[？?！!。.]?\s*$/iu;
const ITEM_HISTORY_QUESTION = /(?:去哪(?:里|儿)?了?|哪去了|去了哪(?:里|儿)?|为什么(?:没有|没|不见)|怎么(?:没有|没了|不见|变成|又?合成)|不见了?|没有了?|没了|还在吗|还有吗|是不是(?:被)?(?:扔|丢|用|消耗)|是否(?:被)?(?:扔|丢|用|消耗)|被(?:扔|丢|用|消耗)|扔(?:掉|了|了吗)|丢(?:掉|了|了吗)|用(?:掉|了|了吗)|消耗(?:掉|了|了吗)|变成|又?合成)/u;
const MOUNT_DRAGON_COMMAND = /^\s*(?:你\s*)?(?:快|现在|先)?\s*(?:上龙|骑上龙|骑龙)(?:\s*(?:跟着我|跟我走|跟随我))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const SHARE_RIDE_DRAGON_COMMAND = /^\s*(?:你\s*)?(?:快|现在|先)?\s*(?:和我|跟我|一起|一同)?\s*(?:同骑|共骑|一起骑|一同骑|共享座位|双人骑乘|同乘)(?:\s*(?:龙|这只龙))?(?:\s*(?:跟着我|跟我走|跟随我))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const DISMOUNT_DRAGON_COMMAND = /^\s*(?:你\s*)?(?:从龙上)?\s*(?:下来|下龙|别骑了)(?:\s*(?:跟着我|跟我走))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const RECALL_DRAGON_COMMAND = /^\s*(?:你\s*)?(?:快|现在)?\s*(?:召回|叫回|喊回)(?:你)?(?:的)?龙\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const LAND_DRAGON_COMMAND = /^\s*(?:你\s*)?(?:让龙|骑龙)?\s*(?:降落|落地)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const DRAGON_ASSIST_COMMAND = /^\s*(?:你\s*)?(?:让龙|骑龙)?\s*(?:协战|帮我打|保护我|攻击目标)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const ORGANIZE_STORAGE_COMMAND = /^(?=.*(?:箱子|仓库))(?=.*(?:整理|分类|收拾|存放|放进|存进|放回|存回))(?=.*(?:背包|多余|东西|物品|材料|装备)).*$/u;
const RETRIEVE_COMMAND = /^\s*(?:你\s*)?(?:去)?(?:从)?(?:家里|家附近)?(?:的)?(?:箱子|仓库)(?:里)?\s*(?:帮我)?(?:拿|取|找|带)(?<deliveryPrefix>出来|出|来|给我)?\s*(?:(?<count>\d{1,3}|[一二两三四五六七八九十百]+)\s*(?:个|块|根|片|份)?\s*)?(?<item>原木|木头|圆石|腐肉|西瓜片?|面包|苹果|小麦|火把)(?:\s*(?<deliverySuffix>给我|交给我|拿给我|送给我))?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const FISH_COMMAND = /^\s*(?:你\s*)?(?:去|帮我)?\s*(?:钓鱼|钓)(?:(?<count>\d{1,2}|[一二两三四五六七八九十]+)\s*(?:次|条|个)?)?\s*(?:鱼)?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const SLEEP_COMMAND = /^\s*(?:你\s*)?(?:去|快|现在)?\s*(?:睡觉|睡一觉|上床睡觉|睡到天亮|休息一晚)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const DROP_COMMAND = /^\s*(?:你\s*)?(?:把)?(?:(?<count>\d{1,3}|[一二两三四五六七八九十百]+)\s*(?:个|块|根|片|份)?\s*)?(?<item>原木|木头|圆石|腐肉|西瓜片?|面包|苹果|小麦|火把)\s*(?:丢|扔|抛)(?<toPlayer>给我|给玩家)?(?:掉|出去)?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const BUILD_MENU_COMMAND = /^\s*(?:你\s*)?(?:(?:现在|先|快|我想|我要)\s*)?(?:给我\s*)?(?:帮我\s*)?(?:建造|搭建|盖点东西|造点东西|开始建造)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const BUILD_INTENT = /(?:建造|搭建|施工|根据图纸|照着图纸|盖|造|起|建)/u;
const BUILD_DEFAULT_INTENT = /(?:你来建造|你来施工|拿去建造|拿去盖|根据图纸|照着图纸)/u;
const BUILD_DELIVERY_CORRECTION = /(?:不要|别再|不许|老是|总是).*(?:木头|木材|建材|材料).*(?:给我|交给我|丢给我|扔给我)/u;
const CONTINUE_BUILD_COMMAND = /^(?=.*(?:继续|接着|续建|接续|恢复|还没(?:建|盖|造)完|没(?:建|盖|造)完|施工|不动|没动|失败点))(?=.*(?:建造|搭建|建|盖|造|施工|刷怪|刷石机|住宅|房屋|房子|小屋|基地|农田|农场|仓库|围栏|塔|树场)).*$/u;
const BUILD_PROGRESS_QUERY = /^(?=.*(?:怎么|为什么))(?=.*(?:还|又))(?=.*(?:没|没有|不))(?=.*(?:开始|继续|动|施工|建造|搭建|盖|造))(?=.*(?:房屋|房子|小屋|住宅|建筑|施工|建造|搭建|盖|造)).*$/u;
const CONTINUE_GOAL_COMMAND = /^\s*(?:(?:继续|接着|恢复)(?:(?:之前|当前|上次)?(?:的)?(?:任务|目标)|畜牧|养殖|种田|种植|采集|挖矿|钓鱼|远征|探险))(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const CRAFT_KIT_COMMAND = /^\s*(?:你\s*)?(?:给我\s*)?(?:帮我\s*)?(?:制作|做|打造|合成|生产|来|整|弄)\s*(?:一|1)?\s*(?:套|组|些)?\s*(?<kit>基础工具|新手工具|工具套装|工具|铁质装备|铁装备|铁甲套装|防具|护甲|盔甲|装备|建筑材料)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const CRAFT_ITEM_COMMAND = /^\s*(?:你\s*)?(?:(?:给我\s*)?(?:帮我\s*)?(?:制作|做|打造|合成|生产|来|整|弄|搓|造)|(?:我\s*)?(?:想要|需要|要)|给我(?:来|做|制作)?)\s*(?:(?<count>\d{1,3}|[一二两三四五六七八九十百]+)\s*)?(?:个|把|件|张|组|些)?\s*(?<item>工作台|木镐|石镐|铁镐|金镐|钻石镐|镐子|镐|木斧|石斧|铁斧|金斧|钻石斧|斧子|斧|木铲|石铲|铁铲|金铲|钻石铲|铲子|铲|木锄|石锄|铁锄|金锄|钻石锄|锄头|锄|熔炉|木剑|石剑|铁剑|金剑|钻石剑|剑|武器|弓|箭|钓鱼竿|鱼竿|剪刀|铁桶|桶|打火石|盾牌|铁头盔|铁胸甲|铁护腿|铁靴|金头盔|金胸甲|金护腿|金靴|钻石头盔|钻石胸甲|钻石护腿|钻石靴|箱子|火把|床)\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const CRAFT_DELIVERY_HINT = /(?:给我|交给我|拿给我|送给我|丢给我|扔给我|给玩家|我\s*(?:想要|需要|要)|(?:^|\s)来\s*(?:个|把|件)?)/u;
const FARM_COMMAND = /^\s*(?:你\s*)?(?:去|帮我)?\s*(?<action>种|播种|种植|照料|打理|收割)\s*(?:一下|点|一些|些)?\s*(?<crop>小麦|胡萝卜|土豆|马铃薯|甜菜根)?\s*(?:农田|农场|作物|种子)?\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u;
const EXPEDITION_HINT = /(?:远征|远程采集|远距离采集|去远处(?:采集|收集|挖|砍|找))/u;
const EXPEDITION_RESOURCE = /(?:(?<count>\d{1,4}|[一二两三四五六七八九十百]+)\s*(?:个|块|根|组|份)?\s*)?(?<item>原木|木头|圆石|原铁|铁矿石?|煤炭?|沙子?|泥土|原铜|铜矿石?|原金|金矿石?)/u;
const WALK_ONLY_HINT = /(?:(?:请|要)?(?:全程)?(?:走路|步行)(?:去)?|(?:不要|别|不许)(?:使用)?(?:传送|瞬移))/gu;

const ITEM_NAME_TO_ID: Record<string, string> = {
  金苹果: "minecraft:golden_apple",
  西瓜: "minecraft:melon_slice",
  西瓜片: "minecraft:melon_slice",
  瓜: "minecraft:melon_slice",
  腐肉: "minecraft:rotten_flesh",
  面包: "minecraft:bread",
  牛排: "minecraft:cooked_beef",
  熟牛肉: "minecraft:cooked_beef",
  苹果: "minecraft:apple",
  胡萝卜: "minecraft:carrot",
  烤土豆: "minecraft:baked_potato",
  土豆: "minecraft:potato",
  原木: GENERIC_LOG_SELECTOR,
  木头: GENERIC_LOG_SELECTOR,
  圆石: "minecraft:cobblestone",
  原铁: "minecraft:raw_iron",
  铁矿: "minecraft:raw_iron",
  铁矿石: "minecraft:raw_iron",
  煤: "minecraft:coal",
  煤炭: "minecraft:coal",
  沙: "minecraft:sand",
  沙子: "minecraft:sand",
  泥土: "minecraft:dirt",
  原铜: "minecraft:raw_copper",
  铜矿: "minecraft:raw_copper",
  铜矿石: "minecraft:raw_copper",
  原金: "minecraft:raw_gold",
  金矿: "minecraft:raw_gold",
  金矿石: "minecraft:raw_gold",
  小麦: "minecraft:wheat",
  火把: "minecraft:torch",
  工作台: "minecraft:crafting_table",
  木镐: "minecraft:wooden_pickaxe",
  石镐: "minecraft:stone_pickaxe",
  铁镐: "minecraft:iron_pickaxe",
  金镐: "minecraft:golden_pickaxe",
  钻石镐: "minecraft:diamond_pickaxe",
  镐子: "minecraft:pickaxe",
  镐: "minecraft:pickaxe",
  木斧: "minecraft:wooden_axe",
  石斧: "minecraft:stone_axe",
  铁斧: "minecraft:iron_axe",
  金斧: "minecraft:golden_axe",
  钻石斧: "minecraft:diamond_axe",
  斧子: "minecraft:axe",
  斧: "minecraft:axe",
  木铲: "minecraft:wooden_shovel",
  石铲: "minecraft:stone_shovel",
  铁铲: "minecraft:iron_shovel",
  金铲: "minecraft:golden_shovel",
  钻石铲: "minecraft:diamond_shovel",
  铲子: "minecraft:shovel",
  铲: "minecraft:shovel",
  木锄: "minecraft:wooden_hoe",
  石锄: "minecraft:stone_hoe",
  铁锄: "minecraft:iron_hoe",
  金锄: "minecraft:golden_hoe",
  钻石锄: "minecraft:diamond_hoe",
  锄头: "minecraft:hoe",
  锄: "minecraft:hoe",
  熔炉: "minecraft:furnace",
  木剑: "minecraft:wooden_sword",
  石剑: "minecraft:stone_sword",
  铁剑: "minecraft:iron_sword",
  金剑: "minecraft:golden_sword",
  钻石剑: "minecraft:diamond_sword",
  剑: "minecraft:melee_weapon",
  武器: "minecraft:melee_weapon",
  弓: "minecraft:bow",
  箭: "minecraft:arrow",
  钓鱼竿: "minecraft:fishing_rod",
  鱼竿: "minecraft:fishing_rod",
  剪刀: "minecraft:shears",
  铁桶: "minecraft:bucket",
  桶: "minecraft:bucket",
  打火石: "minecraft:flint_and_steel",
  盾牌: "minecraft:shield",
  铁头盔: "minecraft:iron_helmet",
  铁胸甲: "minecraft:iron_chestplate",
  铁护腿: "minecraft:iron_leggings",
  铁靴: "minecraft:iron_boots",
  金头盔: "minecraft:golden_helmet",
  金胸甲: "minecraft:golden_chestplate",
  金护腿: "minecraft:golden_leggings",
  金靴: "minecraft:golden_boots",
  钻石头盔: "minecraft:diamond_helmet",
  钻石胸甲: "minecraft:diamond_chestplate",
  钻石护腿: "minecraft:diamond_leggings",
  钻石靴: "minecraft:diamond_boots",
  箱子: "minecraft:chest",
  床: "minecraft:white_bed",
};

const ITEM_HISTORY_NAME_TO_ID: ReadonlyArray<readonly [string, string]> = [
  ["钻石镐", "minecraft:diamond_pickaxe"],
  ["铁矿石", "minecraft:raw_iron"],
  ["煤矿石", "minecraft:coal"],
  ["煤炭", "minecraft:coal"],
  ["铁锭", "minecraft:iron_ingot"],
  ["铁粒", "minecraft:iron_nugget"],
  ["原铁", "minecraft:raw_iron"],
  ["钻石", "minecraft:diamond"],
  ["火把", "minecraft:torch"],
  ["圆石", "minecraft:cobblestone"],
  ["木头", GENERIC_LOG_SELECTOR],
  ["原木", GENERIC_LOG_SELECTOR],
  ["煤", "minecraft:coal"],
];

function itemHistoryQuery(message: string): Array<{ itemId: string; itemName: string }> {
  if (!ITEM_HISTORY_QUESTION.test(message)) return [];
  const matches: Array<{ itemId: string; itemName: string }> = [];
  const seen = new Set<string>();
  for (const [itemName, itemId] of ITEM_HISTORY_NAME_TO_ID) {
    if (!message.includes(itemName) || seen.has(itemId)) continue;
    seen.add(itemId);
    matches.push({ itemId, itemName });
  }
  return matches;
}

const BUILD_SKILLS: Record<string, string> = {
  基础黑暗刷怪塔: "build.mob-farm",
  黑暗刷怪塔: "build.mob-farm",
  基础刷怪场: "build.mob-farm",
  刷怪场: "build.mob-farm",
  刷怪塔: "build.mob-farm",
  刷怪笼: "build.mob-farm",
  自动树场: "build.tree-farm",
  树场: "build.tree-farm",
  林场: "build.tree-farm",
  瞭望塔: "build.watchtower",
  哨塔: "build.watchtower",
  高塔: "build.watchtower",
  动物围栏: "build.animal-pen",
  牧场: "build.animal-pen",
  围栏: "build.animal-pen",
  石砖小屋: "build.stone-cottage",
  石屋: "build.stone-cottage",
  刷石机: "build.cobblestone-generator",
  基础住宅: "build.basic-shelter",
  住宅: "build.basic-shelter",
  房屋: "build.basic-shelter",
  房子: "build.basic-shelter",
  小房子: "build.basic-shelter",
  小屋: "build.basic-shelter",
  木屋: "build.basic-shelter",
  安全屋: "build.basic-shelter",
  庇护所: "build.basic-shelter",
  基地: "build.basic-shelter",
  家: "build.basic-shelter",
  农田: "build.crop-farm",
  农场: "build.crop-farm",
  分类仓库: "build.storage-room",
  仓库: "build.storage-room",
  储物间: "build.storage-room",
};

const BUILD_MENU_OPTIONS = [
  { key: "1", label: "基础住宅", skillId: "build.basic-shelter", aliases: ["基础住宅", "住宅", "房子", "木屋"] },
  { key: "2", label: "石砖小屋", skillId: "build.stone-cottage", aliases: ["石砖小屋", "石屋"] },
  { key: "3", label: "刷石机", skillId: "build.cobblestone-generator", aliases: ["刷石机"] },
  { key: "4", label: "安全农田", skillId: "build.crop-farm", aliases: ["安全农田", "农田", "农场"] },
  { key: "5", label: "分类仓库", skillId: "build.storage-room", aliases: ["分类仓库", "仓库", "储物间"] },
  { key: "6", label: "动物围栏", skillId: "build.animal-pen", aliases: ["动物围栏", "围栏", "牧场"] },
  { key: "7", label: "瞭望塔", skillId: "build.watchtower", aliases: ["瞭望塔", "哨塔", "高塔"] },
  { key: "8", label: "黑暗刷怪塔", skillId: "build.mob-farm", aliases: ["黑暗刷怪塔", "刷怪塔", "刷怪场", "刷怪笼"] },
  { key: "9", label: "自动树场", skillId: "build.tree-farm", aliases: ["自动树场", "树场", "林场"] },
] as const;

const BUILD_MENU_REPLY = "建造选项：1基础住宅；2石砖小屋；3刷石机；4安全农田；5分类仓库；6动物围栏；7瞭望塔；8黑暗刷怪塔（不放刷怪笼）；9自动树场。回复数字或名称，回复“取消”退出。";

const BUILD_MATERIAL_HINTS = [
  ["深色橡树", "minecraft:dark_oak_planks", "深色橡木"],
  ["深色橡木", "minecraft:dark_oak_planks", "深色橡木"],
  ["诡异菌木", "minecraft:warped_planks", "诡异菌木"],
  ["诡异木", "minecraft:warped_planks", "诡异菌木"],
  ["绯红菌木", "minecraft:crimson_planks", "绯红菌木"],
  ["绯红木", "minecraft:crimson_planks", "绯红菌木"],
  ["金合欢木", "minecraft:acacia_planks", "金合欢木"],
  ["金合欢", "minecraft:acacia_planks", "金合欢木"],
  ["红树木", "minecraft:mangrove_planks", "红树木"],
  ["红树林木", "minecraft:mangrove_planks", "红树木"],
  ["红树", "minecraft:mangrove_planks", "红树木"],
  ["丛林木", "minecraft:jungle_planks", "丛林木"],
  ["云杉木", "minecraft:spruce_planks", "云杉木"],
  ["云杉", "minecraft:spruce_planks", "云杉木"],
  ["白桦木", "minecraft:birch_planks", "白桦木"],
  ["白桦", "minecraft:birch_planks", "白桦木"],
  ["樱花木", "minecraft:cherry_planks", "樱花木"],
  ["樱木", "minecraft:cherry_planks", "樱花木"],
  ["竹材", "minecraft:bamboo_planks", "竹材"],
  ["竹木", "minecraft:bamboo_planks", "竹材"],
  ["橡木", "minecraft:oak_planks", "橡木"],
  ["深板岩砖", "minecraft:deepslate_bricks", "深板岩砖"],
  ["深板岩", "minecraft:deepslate_bricks", "深板岩砖"],
  ["黑石砖", "minecraft:polished_blackstone_bricks", "磨制黑石砖"],
  ["黑石", "minecraft:polished_blackstone_bricks", "磨制黑石砖"],
  ["石砖", "minecraft:stone_bricks", "石砖"],
  ["红砖", "minecraft:bricks", "红砖"],
  ["砖块", "minecraft:bricks", "红砖"],
] as const;

const CRAFT_KIT_SKILLS: Record<string, string> = {
  基础工具: "craft.starter-tools",
  新手工具: "craft.starter-tools",
  工具套装: "craft.starter-tools",
  工具: "craft.starter-tools",
  铁质装备: "craft.iron-equipment",
  铁装备: "craft.iron-equipment",
  铁甲套装: "craft.iron-equipment",
  防具: "craft.iron-equipment",
  护甲: "craft.iron-equipment",
  盔甲: "craft.iron-equipment",
  装备: "craft.iron-equipment",
  建筑材料: "craft.building-materials",
};

const CROP_NAME_TO_ID: Record<string, string> = {
  小麦: "minecraft:wheat",
  胡萝卜: "minecraft:carrots",
  土豆: "minecraft:potatoes",
  马铃薯: "minecraft:potatoes",
  甜菜根: "minecraft:beetroots",
};

function chineseInteger(value: string): number | null {
  if (/^\d+$/.test(value)) return Number(value);
  const digits: Record<string, number> = {
    一: 1,
    二: 2,
    两: 2,
    三: 3,
    四: 4,
    五: 5,
    六: 6,
    七: 7,
    八: 8,
    九: 9,
  };
  if (value === "十") return 10;
  if (value === "百") return 100;
  if (value.includes("百")) {
    const [hundredsText, remainderText = ""] = value.split("百", 2);
    const hundreds = digits[hundredsText || "一"];
    const remainder = remainderText ? chineseInteger(remainderText) : 0;
    return hundreds === undefined || remainder === null ? null : hundreds * 100 + remainder;
  }
  if (value.includes("十")) {
    const [tensText, onesText = ""] = value.split("十", 2);
    const tens = tensText ? digits[tensText] : 1;
    const ones = onesText ? digits[onesText] : 0;
    return tens === undefined || ones === undefined ? null : tens * 10 + ones;
  }
  return value.length === 1 ? digits[value] ?? null : null;
}

function lastRequestedCount(message: string, fallback: number, maximum: number): number {
  const matches = [...message.matchAll(/(?<count>\d{1,4}|[一二两三四五六七八九十百]+)\s*(?:个|份|块|片|组|把)?/gu)];
  const raw = matches.at(-1)?.groups?.count;
  const parsed = raw ? chineseInteger(raw) : fallback;
  return Math.min(maximum, Math.max(1, parsed ?? fallback));
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function normalizeAddressedMessage(message: string, companionName = ""): string {
  const names = [companionName.trim(), "codex", "claude", "克劳德", "反重力", "antigravity"]
    .filter((name, index, values) => name && values.indexOf(name) === index)
    .map(escapeRegExp);
  const address = names.join("|");
  return message
    .normalize("NFKC")
    .replace(new RegExp(`^\\s*(?:${address})\\s*[,，:：]\\s*`, "iu"), "")
    .replace(new RegExp(`\\s*(?:${address})\\s*$`, "iu"), "")
    .trim();
}

type BuildMacroSpec = Extract<TaskSpec, { kind: "macro" }>;
type MaterialPreference = NonNullable<BuildMacroSpec["materialPreference"]>;

function buildMaterialPreference(message: string): { preference?: MaterialPreference; label: string } {
  const material = BUILD_MATERIAL_HINTS.find(([hint]) => message.includes(hint));
  const source: MaterialPreference["source"] = /(?:背包|包里|手头|这些|现有)/u.test(message)
    ? "inventory"
    : /(?:家里|家中|仓库|箱子)/u.test(message)
      ? "home"
      : /(?:附近|就地取材|当地|周围)/u.test(message)
        ? "nearby"
        : "auto";
  const allowMixed = /(?:混搭|混合材料|拼色)/u.test(message);
  if (!material && source === "auto" && !allowMixed) return { label: "" };
  return {
    preference: {
      source,
      ...(material ? { preferredBlockId: material[1] } : {}),
      allowMixed,
    },
    label: material?.[2] ?? (source === "inventory"
      ? "背包现有材料"
      : source === "home"
        ? "家中库存材料"
        : source === "nearby"
          ? "附近可取得材料"
          : "自动选材"),
  };
}

function buildIntent(message: string): { structure: string; skillId: string; correction: boolean } | null {
  const correction = BUILD_DELIVERY_CORRECTION.test(message);
  if (!BUILD_INTENT.test(message) && !correction) return null;
  const structure = Object.keys(BUILD_SKILLS)
    .sort((left, right) => right.length - left.length)
    .find((candidate) => message.includes(candidate));
  if (structure) return { structure, skillId: BUILD_SKILLS[structure]!, correction };
  const hasMaterialHint = BUILD_MATERIAL_HINTS.some(([hint]) => message.includes(hint))
    || /(?:木头|木材|建材|材料)/u.test(message);
  if (!correction && !BUILD_DEFAULT_INTENT.test(message) && !(hasMaterialHint && BUILD_INTENT.test(message))) {
    return null;
  }
  return { structure: "基础住宅", skillId: "build.basic-shelter", correction };
}

export function parseBuildMenuSelection(
  message: string,
  sender: string,
  companionName = "",
): DeterministicChatAction | null {
  const normalized = normalizeAddressedMessage(message, companionName)
    .replace(/^[Tt](?=(?:选择|选|建造|盖|造|[1-9一二三四五六七八九]))/u, "")
    .replace(/^\s*(?:选择|选|建造|搭建|盖|造)\s*/u, "")
    .replace(/\s*(?:吧|呀|啊|喵)?\s*[！!。.]?\s*$/u, "")
    .trim();
  if (/^(?:取消|算了|不建了|退出)$/u.test(normalized)) {
    return { operation: "reply", reply: "已取消本次建造选择。", context: "build-menu-cancel" };
  }
  const chineseKeys: Record<string, string> = {
    一: "1", 二: "2", 三: "3", 四: "4", 五: "5", 六: "6", 七: "7", 八: "8", 九: "9",
  };
  const leadingKey = normalized.match(/^(?<key>[1-9一二三四五六七八九])(?:\s|$)/u)?.groups?.key;
  const key = leadingKey ? chineseKeys[leadingKey] ?? leadingKey : normalized;
  const option = BUILD_MENU_OPTIONS.find((candidate) => (
    candidate.key === key || candidate.aliases.some((alias) => alias === normalized)
  ));
  if (!option) return null;
  const requestedBy = sender.trim() || "player";
  return {
    operation: "task",
    spec: {
      kind: "macro",
      skillId: option.skillId,
      arguments: {},
      requestedBy,
      note: `聊天建造菜单选择 ${option.key}：${option.label}`,
    },
    reply: option.skillId === "build.mob-farm"
      ? "已选择黑暗刷怪塔；不会生成或放置刷怪笼。任务已排队并开始执行。"
      : `已选择${option.label}，任务已排队并开始执行。`,
    context: "build-selection",
  };
}

export function parseDeterministicChatAction(message: string, sender: string, companionName = ""): DeterministicChatAction | null {
  const normalized = normalizeAddressedMessage(message, companionName)
    .replace(/^t(?=(?:给我|帮我|我想要|我需要|我要|做|制作|打造|合成|生产|来|整|弄|搓|建造|搭建|建|盖|造|起|种|播种|种植|收割|砍|挖|远征|远程))/iu, "");
  if (HOME_CORNER_ONE_COMMAND.test(normalized)) {
    return { operation: "home-memory", action: "corner-one", reply: "已记录房屋第一个角，请走到房屋对角后输入“记录房屋第二个角”。" };
  }
  if (HOME_CORNER_TWO_COMMAND.test(normalized)) {
    return { operation: "home-memory", action: "corner-two", reply: "已记录房屋第二个角并保存手动房屋边界。" };
  }
  if (HOME_RESCAN_COMMAND.test(normalized)) {
    return { operation: "home-memory", action: "rescan", reply: "已重新读取床锚点并更新自动识别的房屋范围。" };
  }
  const itemHistoryItems = itemHistoryQuery(normalized);
  if (itemHistoryItems.length > 0) {
    return { operation: "inspect-item-history", items: itemHistoryItems };
  }
  if (FOLLOW_COMMAND.test(normalized) || FOLLOW_COMPLAINT.test(normalized) || BOAT_FOLLOW_COMMAND.test(normalized)) {
    return { operation: "control", action: "follow", reply: "好，我现在跟着你。" };
  }
  if (STAY_COMMAND.test(normalized)) {
    return { operation: "control", action: "stay", reply: "好，我在这里等你。" };
  }
  if (RECALL_COMMAND.test(normalized)) {
    return { operation: "control", action: "recall", reply: "我回到你身边了。" };
  }
  if (MOUNT_DRAGON_COMMAND.test(normalized)) {
    return {
      operation: "task",
      spec: { kind: "macro", skillId: "dragon.mount-and-follow", arguments: { targetId: "" }, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
      reply: "好，我去骑上你的龙跟随你。",
    };
  }
  if (SHARE_RIDE_DRAGON_COMMAND.test(normalized)) {
    return {
      operation: "task",
      spec: { kind: "macro", skillId: "dragon.shared-ride", arguments: { targetId: "" }, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
      reply: "好，我会让你坐前座、我坐后座，一起骑龙；下龙前会先确认安全落地。",
    };
  }
  if (DISMOUNT_DRAGON_COMMAND.test(normalized)) {
    return { operation: "task", spec: { kind: "dragon", action: "dismount", requestedBy: sender.trim() || "player", note: message.slice(0, 500) }, reply: "好，我现在下龙。" };
  }
  if (RECALL_DRAGON_COMMAND.test(normalized)) {
    return { operation: "task", spec: { kind: "dragon", action: "recall", requestedBy: sender.trim() || "player", note: message.slice(0, 500) }, reply: "好，我现在召回你的龙。" };
  }
  if (LAND_DRAGON_COMMAND.test(normalized)) {
    return { operation: "task", spec: { kind: "dragon", action: "land", requestedBy: sender.trim() || "player", note: message.slice(0, 500) }, reply: "好，我让龙降落。" };
  }
  if (DRAGON_ASSIST_COMMAND.test(normalized)) {
    return { operation: "task", spec: { kind: "dragon", action: "assist-combat", requestedBy: sender.trim() || "player", note: message.slice(0, 500) }, reply: "好，我让龙协助战斗。" };
  }
  if (ORGANIZE_STORAGE_COMMAND.test(normalized)) {
    return {
      operation: "task",
      spec: { kind: "organize-storage", radius: 24, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
      reply: "好，我回家把背包里多余的物品分类存进箱子。",
    };
  }
  if (RANCH_COMMAND.test(normalized)) {
    const countText = normalized.match(/(?<count>\d{1,2}|[一二两三四五六七八九十]+)\s*(?:只|头|个)?/u)?.groups?.count;
    const mixed = /猪牛羊|牲畜/u.test(normalized);
    const animalType = mixed ? "any" as const
      : /猪/u.test(normalized) ? "minecraft:pig" as const
      : /羊/u.test(normalized) ? "minecraft:sheep" as const
      : "minecraft:cow" as const;
    const count = Math.min(24, Math.max(2, countText ? chineseInteger(countText) ?? 2 : mixed ? 6 : 2));
    return {
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.establish-ranch",
        arguments: { animalType, count, radius: 128 },
        requestedBy: sender.trim() || "player",
        note: message.slice(0, 500),
      },
      reply: `好，我先建造围栏，再寻找拴绳并牵回 ${count} 只${mixed ? "猪牛羊" : animalType === "minecraft:pig" ? "猪" : animalType === "minecraft:sheep" ? "羊" : "牛"}。`,
    };
  }
  if (BUILD_MENU_COMMAND.test(normalized)) {
    return { operation: "reply", reply: BUILD_MENU_REPLY, context: "build-menu" };
  }
  if (CONTINUE_BUILD_COMMAND.test(normalized) || BUILD_PROGRESS_QUERY.test(normalized)) {
    return {
      operation: "resume-build",
      reply: "好，我从上次失败点继续建造；已完成的方块不会重做。",
    };
  }
  if (CONTINUE_GOAL_COMMAND.test(normalized)) {
    return { operation: "resume-goal", reply: "好，我恢复上一个暂停的目标，并从已保存的步骤继续。" };
  }
  const build = buildIntent(normalized);
  if (build) {
    const material = buildMaterialPreference(normalized);
    return {
      operation: "task",
      spec: {
        kind: "macro",
        skillId: build.skillId,
        arguments: {},
        ...(material.preference ? { materialPreference: material.preference } : {}),
        requestedBy: sender.trim() || "player",
        note: message.slice(0, 500),
      },
      reply: build.structure === "刷怪笼"
        ? "好，我按安全模板建造黑暗刷怪塔；不会生成或放置刷怪笼。"
        : `好，我按安全模板开始建造${build.structure}${material.label ? `，采用${material.label}` : ""}。`,
      ...(build.correction ? { replaceConflictingDelivery: true } : {}),
    };
  }
  const craftKit = normalized.match(CRAFT_KIT_COMMAND);
  if (craftKit) {
    const kit = craftKit.groups?.kit ?? "";
    const skillId = CRAFT_KIT_SKILLS[kit];
    if (skillId) {
      return {
        operation: "task",
        spec: { kind: "macro", skillId, arguments: {}, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
        reply: `好，我按真实配方开始制作${kit}。`,
      };
    }
  }
  const craftItem = normalized.match(CRAFT_ITEM_COMMAND);
  if (craftItem) {
    const itemName = craftItem.groups?.item ?? "";
    const itemId = ITEM_NAME_TO_ID[itemName];
    const rawCount = craftItem.groups?.count;
    const count = Math.min(256, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 1 : 1));
    if (itemId) {
      if (itemName === "床") {
        return {
          operation: "task",
          spec: {
            kind: "macro",
            skillId: "life.craft-and-place-bed",
            arguments: {},
            requestedBy: sender.trim() || "player",
            note: message.slice(0, 500),
          },
          reply: "好，我会补齐木板和羊毛，制作床后放到家附近。",
        };
      }
      const deliverTo = CRAFT_DELIVERY_HINT.test(normalized) ? sender.trim() || "player" : undefined;
      return {
        operation: "task",
        spec: {
          kind: "craft",
          itemId,
          count,
          ...(deliverTo ? { deliverTo } : {}),
          requestedBy: sender.trim() || "player",
          note: message.slice(0, 500),
        },
        reply: deliverTo ? `好，我开始制作 ${count} 个${itemName}，做好就交给你。` : `好，我开始制作 ${count} 个${itemName}。`,
      };
    }
  }
  const farm = normalized.match(FARM_COMMAND);
  if (farm) {
    const actionText = farm.groups?.action ?? "照料";
    const cropName = farm.groups?.crop ?? "小麦";
    return {
      operation: "task",
      spec: {
        kind: "farm",
        cropId: CROP_NAME_TO_ID[cropName] ?? "minecraft:wheat",
        action: actionText === "收割" ? "harvest" : "cycle",
        radius: 12,
        requestedBy: sender.trim() || "player",
        note: message.slice(0, 500),
      },
      reply: actionText === "收割" ? `好，我去收割${cropName}。` : `好，我去照料${cropName}农田。`,
    };
  }
  if (EXPEDITION_HINT.test(normalized)) {
    const expedition = normalized.match(EXPEDITION_RESOURCE);
    const itemName = expedition?.groups?.item ?? "";
    const itemId = ITEM_NAME_TO_ID[itemName];
    if (itemId) {
      const rawCount = expedition?.groups?.count;
      const count = Math.min(4096, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 16 : 16));
      const requestedBy = sender.trim() || "player";
      return {
        operation: "task",
        spec: {
          kind: "macro",
          skillId: "life.expedition-and-deliver",
          arguments: { itemId, count, player: requestedBy },
          requestedBy,
          note: message.slice(0, 500),
        },
        reply: `好，我开始远征采集 ${count} 个${itemName}，完成后回来交给你。`,
      };
    }
  }
  const retrieve = normalized.match(RETRIEVE_COMMAND);
  if (retrieve) {
    const rawCount = retrieve.groups?.count;
    const count = Math.min(4096, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 1 : 1));
    const itemName = retrieve.groups?.item ?? "";
    const itemId = ITEM_NAME_TO_ID[itemName];
    if (itemId) {
      const deliveryPrefix = retrieve.groups?.deliveryPrefix ?? "";
      const deliverToPlayer = deliveryPrefix === "来" || deliveryPrefix === "给我"
        || Boolean(retrieve.groups?.deliverySuffix);
      const requestedBy = sender.trim() || "player";
      if (deliverToPlayer) {
        return {
          operation: "task",
          spec: {
            kind: "macro",
            skillId: "life.retrieve-and-deliver",
            arguments: { itemId, count, player: requestedBy },
            requestedBy,
            note: message.slice(0, 500),
          },
          reply: `好，我去家里的箱子取 ${count} 个${itemName}，然后拿给你。`,
        };
      }
      return {
        operation: "task",
        spec: { kind: "retrieve", itemId, count, requestedBy, note: message.slice(0, 500) },
        reply: `好，我去家里的箱子取 ${count} 个${itemName}。`,
      };
    }
  }
  const fish = normalized.match(FISH_COMMAND);
  if (fish) {
    const rawCount = fish.groups?.count;
    const count = Math.min(64, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 1 : 1));
    return {
      operation: "task",
      spec: { kind: "fish", count, radius: 24, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
      reply: `好，我去附近水边钓 ${count} 次鱼。`,
    };
  }
  if (SLEEP_COMMAND.test(normalized)) {
    return {
      operation: "task",
      spec: { kind: "sleep", radius: 32, requestedBy: sender.trim() || "player", note: message.slice(0, 500) },
      reply: "好，我去家或附近找床睡到天亮。",
    };
  }
  const drop = normalized.match(DROP_COMMAND);
  if (drop) {
    const rawCount = drop.groups?.count;
    const count = Math.min(4096, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 1 : 1));
    const itemName = drop.groups?.item ?? "";
    const itemId = ITEM_NAME_TO_ID[itemName];
    if (itemId) {
      return {
        operation: "task",
        spec: {
          kind: "drop",
          itemId,
          count,
          ...(drop.groups?.toPlayer ? { player: sender.trim() || "player" } : {}),
          requestedBy: sender.trim() || "player",
          note: message.slice(0, 500),
        },
        reply: drop.groups?.toPlayer ? `好，我把 ${count} 个${itemName}丢给你。` : `好，我把 ${count} 个${itemName}丢出去。`,
      };
    }
  }
  if (ACTIVITY_QUESTION.test(normalized)) return { operation: "inspect", scope: "activity" };
  if (FULL_STATUS_QUESTION.test(normalized)) return { operation: "inspect", scope: "full" };
  if (INVENTORY_QUESTION.test(normalized)) return { operation: "inspect", scope: "inventory" };
  if (VITALS_QUESTION.test(normalized)) return { operation: "inspect", scope: "vitals" };
  const eat = normalized.match(EAT_COMMAND);
  if (eat) {
    const rawCount = eat.groups?.count;
    const count = Math.min(64, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 1 : 1));
    const requestedBy = sender.trim() || "player";
    const foodName = eat.groups?.food ?? "";
    const itemId = ITEM_NAME_TO_ID[foodName];
    const foodLabel = /瓜/u.test(foodName) ? "西瓜" : foodName;
    const eatUntilFull = /吃到饱/u.test(normalized);
    return {
      operation: "task",
      spec: {
        kind: "eat",
        ...(itemId ? { itemId } : {}),
        count: eatUntilFull ? 64 : count,
        requestedBy,
        note: message.slice(0, 500),
      },
      reply: itemId ? `好，我现在把${foodLabel}吃掉。` : "好，我现在吃点东西。",
    };
  }
  if (!/腐肉/u.test(normalized)
    && MEAT_PROVISION_HINT.test(normalized)
    && MEAT_PROVISION_ACTION.test(normalized)) {
    const count = lastRequestedCount(normalized, 8, 64);
    const requestedBy = sender.trim() || "player";
    const destination = /(?:箱子|仓库)/u.test(normalized) ? "home-storage" as const
      : /(?:给我|交给我|拿给我|带给我|送给我|我要|我需要|我想要)/u.test(normalized) ? "player" as const
      : "backpack" as const;
    const destinationReply = destination === "home-storage"
      ? "，烹饪后存回家中箱子"
      : destination === "player"
        ? "，烹饪后带回来交给你"
        : "，烹饪后留作口粮";
    return {
      operation: "task",
      spec: {
        kind: "provision-food",
        count,
        source: "hunt",
        foodCategory: "meat",
        destination,
        ...(destination === "player" ? { player: requestedBy } : {}),
        requestedBy,
        note: message.slice(0, 500),
      },
      reply: `好，我去安全猎取 ${count} 份肉${destinationReply}。`,
    };
  }
  const provisionFood = normalized.match(PROVISION_FOOD_COMMAND);
  if (provisionFood) {
    const rawCount = provisionFood.groups?.count;
    const count = Math.min(64, Math.max(1, rawCount ? chineseInteger(rawCount) ?? 8 : 8));
    const sourceText = provisionFood.groups?.source ?? "";
    const source = /打猎|狩猎|猎取/u.test(sourceText) ? "hunt" as const
      : sourceText === "采集" ? "forage" as const
      : "auto" as const;
    const destinationText = provisionFood.groups?.destination ?? "";
    const destination = /箱子|仓库/u.test(destinationText) ? "home-storage" as const
      : provisionFood.groups?.recipientPrefix === "给我" || /给我/u.test(destinationText) ? "player" as const
      : "backpack" as const;
    const destinationReply = destination === "home-storage"
      ? `，完成后存回家中箱子`
      : destination === "player"
        ? `，完成后带回来交给你`
        : `，并在背包保留 ${count} 份口粮`;
    return {
      operation: "task",
      spec: {
        kind: "provision-food",
        count,
        source,
        foodCategory: source === "hunt" ? "meat" : source === "forage" ? "plant" : "any",
        destination,
        ...(destination === "player" ? { player: sender.trim() || "player" } : {}),
        requestedBy: sender.trim() || "player",
        note: message.slice(0, 500),
      },
      reply: source === "hunt"
        ? `好，我去安全猎取 ${count} 份食物${destinationReply}。`
        : `好，我先检查已有口粮，再去寻找 ${count} 份食物${destinationReply}。`,
    };
  }
  const walkOnly = (normalized.match(WALK_ONLY_HINT)?.length ?? 0) > 0;
  const woodCommandWithoutMovementHint = normalized
    .replace(WALK_ONLY_HINT, "")
    .replace(/^[\s，,]+|[\s，,]+$/gu, "");
  const keepInBackpack = /(?:先|暂时)?(?:留在|放在)(?:你)?(?:自己)?(?:背包|包里)|不用(?:给|交给|拿给|送给)我/u.test(woodCommandWithoutMovementHint);
  const woodCommandWithoutStorageHint = keepInBackpack
    ? woodCommandWithoutMovementHint.replace(/[，,]?\s*(?:(?:先|暂时)?(?:留在|放在)(?:你)?(?:自己)?(?:背包|包里)|不用(?:给|交给|拿给|送给)我)\s*$/u, "")
    : woodCommandWithoutMovementHint;
  const woodCommandText = woodCommandWithoutStorageHint
    .replace(/^[\s，,]+|[\s，,]+$/gu, "");
  const match = woodCommandText.match(WOOD_COMMAND);
  if (!match) return null;
  const rawCount = match.groups?.count ?? match.groups?.trailingCount;
  const parsedCount = rawCount ? chineseInteger(rawCount) : DEFAULT_WOOD_COUNT;
  const count = Math.min(MAX_CHAT_WOOD_COUNT, Math.max(1, parsedCount ?? DEFAULT_WOOD_COUNT));
  const requestedBy = sender.trim() || "player";
  if (!keepInBackpack) {
    return {
      operation: "task",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: {
          itemId: GENERIC_LOG_SELECTOR,
          count,
          player: requestedBy,
          ...(walkOnly ? { movement: "walk" } : {}),
        },
        requestedBy,
        note: message.slice(0, 500),
      },
      reply: walkOnly
        ? `好，我全程步行去采集 ${count} 个原木，采完交给你。`
        : `好，我去采集 ${count} 个原木，采完交给你。`,
    };
  }
  return {
    operation: "task",
    spec: {
      kind: "gather",
      itemId: GENERIC_LOG_SELECTOR,
      count,
      ...(walkOnly ? { movement: "walk" } : {}),
      requestedBy,
      note: message.slice(0, 500),
    },
    reply: walkOnly
      ? `好，我全程步行去采集 ${count} 个原木。`
      : `好，我去采集 ${count} 个原木。`,
  };
}
