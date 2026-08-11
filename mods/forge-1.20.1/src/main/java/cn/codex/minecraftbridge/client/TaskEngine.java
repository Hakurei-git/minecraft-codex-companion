package cn.codex.minecraftbridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class TaskEngine {
    @FunctionalInterface
    public interface ProgressSink {
        void send(String taskId, double progress, String message);
    }

    @FunctionalInterface
    public interface ResultSink {
        void send(String taskId, boolean ok, String message, String code);
    }

    private enum BackgroundMode { NONE, FOLLOW, GUARD }

    private final ProgressSink progressSink;
    private final ResultSink resultSink;
    private final BridgeConfig config;
    private final BaritoneAdapter baritone = new BaritoneAdapter();
    private ActiveTask active;
    private BackgroundMode backgroundMode = BackgroundMode.NONE;
    private String backgroundPlayer = "";
    private double backgroundDistance = 3;
    private double guardRadius = 12;
    private String status = "待命";
    private long ticks;

    public TaskEngine(ProgressSink progressSink, ResultSink resultSink, BridgeConfig config) {
        this.progressSink = progressSink;
        this.resultSink = resultSink;
        this.config = config;
    }

    public String status() {
        return status;
    }

    public void start(JsonObject task, JsonObject buildPlan) {
        if (active != null) fail(active, "新任务替换了当前任务", "TASK_REPLACED");
        JsonObject spec = task.getAsJsonObject("spec");
        ActiveTask next = new ActiveTask(task.get("id").getAsString(), spec, buildPlan);
        String kind = next.kind();
        status = "正在执行 " + kind;
        switch (kind) {
            case "follow" -> {
                backgroundMode = BackgroundMode.FOLLOW;
                backgroundPlayer = string(spec, "player", config.ownerName);
                backgroundDistance = number(spec, "distance", 3);
                if (baritone.available()) baritone.execute("follow player " + backgroundPlayer);
                complete(next, "已开始跟随 " + backgroundPlayer);
            }
            case "guard" -> {
                backgroundMode = BackgroundMode.GUARD;
                backgroundPlayer = string(spec, "player", config.ownerName);
                guardRadius = number(spec, "radius", 12);
                complete(next, "已进入护卫模式");
            }
            default -> active = next;
        }
    }

    public void cancel(String taskId, String reason) {
        if (active != null && active.id.equals(taskId)) fail(active, reason, "CANCELLED");
        releaseKeys(Minecraft.getInstance());
        baritone.cancel();
    }

    public void emergencyStop() {
        if (active != null) fail(active, "紧急停止", "EMERGENCY_STOP");
        backgroundMode = BackgroundMode.NONE;
        backgroundPlayer = "";
        status = "已急停";
        releaseKeys(Minecraft.getInstance());
        baritone.cancel();
    }

    public void connectionLost() {
        if (active != null) {
            closeTaskContainer(active);
            active = null;
            status = "桥接已断开";
        }
        releaseKeys(Minecraft.getInstance());
        baritone.cancel();
    }

    public void tick(Minecraft minecraft) {
        ticks++;
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) return;
        if (backgroundMode == BackgroundMode.FOLLOW && active == null) tickFollow(minecraft);
        if (backgroundMode == BackgroundMode.GUARD && active == null) tickGuard(minecraft);
        ActiveTask task = active;
        if (task == null) return;
        task.ticks++;
        try {
            switch (task.kind()) {
                case "move" -> tickMove(minecraft, task, target(task.spec.getAsJsonObject("target")));
                case "explore" -> tickExplore(minecraft, task);
                case "gather" -> tickGather(minecraft, task);
                case "combat" -> tickCombat(minecraft, task);
                case "farm" -> tickFarm(minecraft, task);
                case "build" -> tickBuild(minecraft, task);
                case "dragon" -> tickDragon(minecraft, task);
                case "craft" -> tickCraft(minecraft, task);
                case "smelt" -> tickSmelt(minecraft, task);
                case "store" -> tickStore(minecraft, task);
                case "macro" -> fail(task, "未找到已注册的声明式技能", "MACRO_NOT_FOUND");
                default -> fail(task, "不支持的任务类型 " + task.kind(), "UNSUPPORTED_TASK");
            }
        } catch (RuntimeException error) {
            fail(task, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), "TASK_EXCEPTION");
        }
    }

    private void tickMove(Minecraft minecraft, ActiveTask task, Vec3 target) {
        Player player = minecraft.player;
        double distance = player.position().distanceTo(target);
        if (task.startDistance < 0) task.startDistance = Math.max(1, distance);
        if (!task.issued) {
            task.issued = true;
            task.usedBaritone = baritone.execute(String.format(Locale.ROOT, "goto %d %d %d", (int) target.x, (int) target.y, (int) target.z));
        }
        if (distance <= 1.8) {
            releaseKeys(minecraft);
            complete(task, "已到达目标位置");
            return;
        }
        if (!task.usedBaritone) driveToward(minecraft, target);
        if (task.ticks % 10 == 0) {
            double progress = 1 - Math.min(1, distance / task.startDistance);
            progress(task, progress, "移动中，距离 " + Math.round(distance) + " 格");
        }
        if (task.ticks > 20 * 60 * 5) fail(task, "移动超时", "NAVIGATION_TIMEOUT");
    }

    private void tickExplore(Minecraft minecraft, ActiveTask task) {
        if (task.target == null) {
            double radius = number(task.spec, "radius", 64);
            String direction = string(task.spec, "direction", "any");
            double dx = direction.equals("west") ? -radius : direction.equals("east") ? radius : direction.equals("any") ? radius : 0;
            double dz = direction.equals("north") ? -radius : direction.equals("south") ? radius : direction.equals("any") ? radius * 0.6 : 0;
            task.target = minecraft.player.position().add(dx, 0, dz);
        }
        tickMove(minecraft, task, task.target);
    }

    private void tickGather(Minecraft minecraft, ActiveTask task) {
        String itemId = string(task.spec, "itemId", "");
        int count = (int) number(task.spec, "count", 1);
        if (task.initialCount < 0) task.initialCount = inventoryCount(minecraft.player, itemId);
        int gathered = inventoryCount(minecraft.player, itemId) - task.initialCount;
        if (gathered >= count) {
            complete(task, "已采集 " + gathered + " 个 " + itemId);
            return;
        }
        if (!task.issued) {
            task.issued = true;
            task.usedBaritone = baritone.execute("mine " + count + " " + itemId);
            if (!task.usedBaritone) {
                fail(task, "采集任务需要安装 Baritone", "BARITONE_REQUIRED");
                return;
            }
        }
        if (task.ticks % 20 == 0) progress(task, Math.min(0.95, gathered / (double) count), "已采集 " + gathered + "/" + count);
        if (task.ticks > 40 && !baritone.isPathing() && gathered < count) fail(task, "没有找到足够的目标方块", "RESOURCE_NOT_FOUND");
        if (task.ticks > 20 * 60 * 10) fail(task, "采集超时", "GATHER_TIMEOUT");
    }

    private void tickFarm(Minecraft minecraft, ActiveTask task) {
        int radius = (int) number(task.spec, "radius", 12);
        if (!task.issued) {
            task.issued = true;
            task.usedBaritone = baritone.execute("farm " + radius);
            if (!task.usedBaritone) {
                fail(task, "农务任务需要安装 Baritone", "BARITONE_REQUIRED");
                return;
            }
        }
        double progress = Math.min(1, task.ticks / 1200.0);
        if (task.ticks % 40 == 0) progress(task, progress, "正在照料农田");
        if (task.ticks >= 1200) {
            baritone.cancel();
            complete(task, "本轮农务已完成");
        }
    }

    private void tickCraft(Minecraft minecraft, ActiveTask task) {
        String itemId = string(task.spec, "itemId", "");
        int requested = (int) number(task.spec, "count", 1);
        if (task.ticks > 20 * 60 * 3) {
            fail(task, "制作等待超时", "CRAFT_TIMEOUT");
            return;
        }
        if (task.initialCount < 0) {
            task.initialCount = inventoryCount(minecraft.player, itemId);
            RecipeChoice choice = findCraftRecipe(minecraft, itemId);
            if (choice == null) {
                fail(task, "没有找到可制作 " + itemId + " 的配方", "RECIPE_NOT_FOUND");
                return;
            }
            task.recipeWire = choice.wireRecipe;
            task.recipe = choice.recipe;
            task.requiresCraftingTable = choice.requiresCraftingTable;
        }

        int crafted = inventoryCount(minecraft.player, itemId) - task.initialCount;
        if (crafted >= requested) {
            complete(task, "已制作 " + crafted + " 个 " + itemId);
            return;
        }

        AbstractContainerMenu menu;
        if (task.requiresCraftingTable) {
            if (!(minecraft.player.containerMenu instanceof CraftingMenu)) {
                if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                    minecraft.player.closeContainer();
                    return;
                }
                if (task.workstation == null) {
                    task.workstation = findNearbyBlock(minecraft, List.of("minecraft:crafting_table"), 16, 5);
                }
                if (task.workstation == null) {
                    fail(task, "附近没有工作台", "WORKSTATION_NOT_FOUND");
                    return;
                }
                if (!approachAndOpen(minecraft, task, task.workstation)) return;
                if (!(minecraft.player.containerMenu instanceof CraftingMenu)) return;
            }
            task.openedContainer = true;
            menu = minecraft.player.containerMenu;
        } else {
            if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                minecraft.player.closeContainer();
                task.lastActionTick = task.ticks;
                return;
            }
            menu = minecraft.player.inventoryMenu;
        }

        int gridSize = task.requiresCraftingTable ? 9 : 4;
        if (!task.menuInitialized) {
            for (int slot = 0; slot <= gridSize; slot++) {
                if (!menu.getSlot(slot).getItem().isEmpty()) {
                    fail(task, "制作网格中已有物品，请先收起后重试", "CRAFTING_GRID_BUSY");
                    return;
                }
            }
            task.menuInitialized = true;
        }

        if (task.transferPending) {
            int now = inventoryCount(minecraft.player, itemId);
            if (now > task.lastInventoryCount) {
                task.transferPending = false;
                task.recipeIssued = false;
                task.stalledTicks = 0;
            } else if (task.ticks - task.lastActionTick > 12) {
                fail(task, "背包没有空间接收制作结果", "INVENTORY_FULL");
            }
            return;
        }

        ItemStack output = menu.getSlot(0).getItem();
        if (!output.isEmpty()) {
            if (!itemId(output).equals(itemId)) {
                fail(task, "工作台产物与请求不一致", "CRAFT_RESULT_MISMATCH");
                return;
            }
            task.recipeIssued = false;
            task.lastInventoryCount = inventoryCount(minecraft.player, itemId);
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, minecraft.player);
            task.transferPending = true;
            task.lastActionTick = task.ticks;
            return;
        }

        if (task.recipeIssued) {
            if (task.ticks - task.lastActionTick > 24) {
                fail(task, "当前材料不足，或服务器未解锁该配方", "MISSING_INGREDIENTS");
            }
            return;
        }
        if (task.ticks - task.lastActionTick < 4) return;
        if (!placeRecipe(minecraft, menu, task.recipeWire, task.recipe)) {
            fail(task, "无法向服务器提交制作配方", "RECIPE_PLACEMENT_FAILED");
            return;
        }
        task.recipeIssued = true;
        task.lastActionTick = task.ticks;
        progress(task, Math.min(0.95, crafted / (double) requested), "正在制作 " + crafted + "/" + requested);
    }

    private RecipeChoice findCraftRecipe(Minecraft minecraft, String itemId) {
        Collection<?> recipes = minecraft.level.getRecipeManager().getRecipes();
        RecipeChoice tableChoice = null;
        for (Object wireRecipe : recipes) {
            Recipe<?> recipe = unwrapRecipe(wireRecipe);
            if (recipe == null) continue;
            String typeName = recipe.getType().toString().toLowerCase(Locale.ROOT);
            if (!typeName.contains("crafting") && !recipe.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("craft")) continue;
            ItemStack stack = recipe.getResultItem(minecraft.level.registryAccess());
            if (stack.isEmpty() || !itemId(stack).equals(itemId)) continue;
            boolean fitsTwo = recipe.canCraftInDimensions(2, 2);
            boolean fitsThree = fitsTwo || recipe.canCraftInDimensions(3, 3);
            if (!fitsThree) continue;
            RecipeChoice choice = new RecipeChoice(wireRecipe, recipe, !fitsTwo);
            if (fitsTwo) return choice;
            if (tableChoice == null) tableChoice = choice;
        }
        return tableChoice;
    }

    private Recipe<?> unwrapRecipe(Object wireRecipe) {
        if (wireRecipe instanceof Recipe<?> recipe) return recipe;
        for (Method method : wireRecipe.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !Recipe.class.isAssignableFrom(method.getReturnType())) continue;
            try {
                Object result = method.invoke(wireRecipe);
                if (result instanceof Recipe<?> recipe) return recipe;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean placeRecipe(Minecraft minecraft, AbstractContainerMenu menu, Object wireRecipe, Object recipe) {
        for (Method method : minecraft.gameMode.getClass().getMethods()) {
            if (method.getParameterCount() != 3
                || method.getParameterTypes()[0] != int.class
                || method.getParameterTypes()[2] != boolean.class) continue;
            Object argument = method.getParameterTypes()[1].isInstance(wireRecipe) ? wireRecipe : recipe;
            if (!method.getParameterTypes()[1].isInstance(argument)) continue;
            try {
                method.invoke(minecraft.gameMode, menu.containerId, argument, false);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private void tickSmelt(Minecraft minecraft, ActiveTask task) {
        String itemId = string(task.spec, "itemId", "");
        int requested = (int) number(task.spec, "count", 1);
        if (task.ticks > 20L * (60 + requested * 12L)) {
            fail(task, "烧炼等待超时", "SMELT_TIMEOUT");
            return;
        }
        if (task.initialCount < 0) {
            task.initialCount = inventoryCount(minecraft.player, itemId);
            if (task.initialCount < requested) {
                fail(task, "背包中缺少 " + requested + " 个 " + itemId, "MISSING_INPUT");
                return;
            }
            task.fuel = findFuel(minecraft.player, requested);
            if (task.fuel == null) {
                fail(task, "背包中没有足够的熔炉燃料", "MISSING_FUEL");
                return;
            }
            task.workstation = findNearbyBlock(minecraft, List.of("minecraft:furnace"), 16, 5);
            if (task.workstation == null) {
                task.workstation = findNearbyBlock(minecraft, List.of("minecraft:blast_furnace", "minecraft:smoker"), 16, 5);
            }
            if (task.workstation == null) {
                fail(task, "附近没有熔炉、烟熏炉或高炉", "WORKSTATION_NOT_FOUND");
                return;
            }
        }

        if (!(minecraft.player.containerMenu instanceof AbstractFurnaceMenu)) {
            if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                minecraft.player.closeContainer();
                return;
            }
            if (!approachAndOpen(minecraft, task, task.workstation)) return;
            if (!(minecraft.player.containerMenu instanceof AbstractFurnaceMenu)) return;
        }
        task.openedContainer = true;
        AbstractContainerMenu menu = minecraft.player.containerMenu;

        if (!task.menuInitialized) {
            if (!menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(1).getItem().isEmpty() || !menu.getSlot(2).getItem().isEmpty()) {
                fail(task, "该熔炉正在使用中，请选择空熔炉", "FURNACE_BUSY");
                return;
            }
            int fuelMoved = moveItemIntoSlot(minecraft, menu, task.fuel.itemId, 1, Math.min(64, task.fuel.count));
            if (fuelMoved <= 0) {
                fail(task, "无法把燃料放入熔炉", "FUEL_TRANSFER_FAILED");
                return;
            }
            task.fuelLoadedCount = fuelMoved;
            task.menuInitialized = true;
            task.lastProgressTick = task.ticks;
        }

        if (task.fuelLoadedCount < task.fuel.count) {
            int moved = moveItemIntoSlot(
                minecraft,
                menu,
                task.fuel.itemId,
                1,
                Math.min(64, task.fuel.count - task.fuelLoadedCount)
            );
            task.fuelLoadedCount += moved;
        }

        ItemStack output = menu.getSlot(2).getItem();
        if (!output.isEmpty()) {
            int before = output.getCount();
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, minecraft.player);
            int after = menu.getSlot(2).getItem().isEmpty() ? 0 : menu.getSlot(2).getItem().getCount();
            int moved = Math.max(0, before - after);
            if (moved > 0) {
                task.producedCount += moved;
                task.lastProgressTick = task.ticks;
                task.stalledTicks = 0;
            } else if (++task.stalledTicks > 12) {
                fail(task, "背包没有空间接收烧炼产物", "INVENTORY_FULL");
                return;
            }
        }

        if (task.producedCount >= requested) {
            complete(task, "已烧炼 " + task.producedCount + " 个 " + itemId);
            return;
        }

        if (menu.getSlot(0).getItem().isEmpty() && task.loadedCount < requested) {
            int batch = Math.min(64, requested - task.loadedCount);
            int moved = moveItemIntoSlot(minecraft, menu, itemId, 0, batch);
            if (moved <= 0) {
                fail(task, "无法把烧炼材料放入熔炉", "INPUT_TRANSFER_FAILED");
                return;
            }
            task.loadedCount += moved;
            task.lastProgressTick = task.ticks;
        }

        if (task.ticks % 20 == 0) {
            progress(task, Math.min(0.99, task.producedCount / (double) requested), "已烧炼 " + task.producedCount + "/" + requested);
        }
        if (task.ticks - task.lastProgressTick > 20 * 30) {
            fail(task, "熔炉没有产生结果，请检查材料与熔炉类型是否匹配", "SMELT_NO_RECIPE");
        }
    }

    private FuelChoice findFuel(Player player, int smeltCount) {
        List<FuelChoice> fuels = List.of(
            new FuelChoice("minecraft:coal_block", 80, 1),
            new FuelChoice("minecraft:dried_kelp_block", 20, 1),
            new FuelChoice("minecraft:blaze_rod", 12, 1),
            new FuelChoice("minecraft:coal", 8, 1),
            new FuelChoice("minecraft:charcoal", 8, 1),
            new FuelChoice("minecraft:oak_log", 1.5, 1),
            new FuelChoice("minecraft:spruce_log", 1.5, 1),
            new FuelChoice("minecraft:birch_log", 1.5, 1),
            new FuelChoice("minecraft:jungle_log", 1.5, 1),
            new FuelChoice("minecraft:acacia_log", 1.5, 1),
            new FuelChoice("minecraft:dark_oak_log", 1.5, 1),
            new FuelChoice("minecraft:mangrove_log", 1.5, 1),
            new FuelChoice("minecraft:cherry_log", 1.5, 1),
            new FuelChoice("minecraft:oak_planks", 1.5, 1),
            new FuelChoice("minecraft:spruce_planks", 1.5, 1),
            new FuelChoice("minecraft:birch_planks", 1.5, 1),
            new FuelChoice("minecraft:jungle_planks", 1.5, 1),
            new FuelChoice("minecraft:acacia_planks", 1.5, 1),
            new FuelChoice("minecraft:dark_oak_planks", 1.5, 1),
            new FuelChoice("minecraft:mangrove_planks", 1.5, 1),
            new FuelChoice("minecraft:cherry_planks", 1.5, 1)
        );
        for (FuelChoice fuel : fuels) {
            int needed = (int) Math.ceil(smeltCount / fuel.itemsPerFuel);
            if (inventoryCount(player, fuel.itemId) >= needed) return new FuelChoice(fuel.itemId, fuel.itemsPerFuel, needed);
        }
        return null;
    }

    private void tickStore(Minecraft minecraft, ActiveTask task) {
        String requestedId = task.spec.has("itemId") && !task.spec.get("itemId").isJsonNull()
            ? task.spec.get("itemId").getAsString()
            : null;
        if (task.ticks > 20 * 60 * 2) {
            fail(task, "整理物品等待超时", "STORE_TIMEOUT");
            return;
        }
        if (task.initialCount < 0) {
            task.initialCount = requestedId == null ? inventoryTotal(minecraft.player) : inventoryCount(minecraft.player, requestedId);
            int requested = task.spec.has("count") && !task.spec.get("count").isJsonNull()
                ? task.spec.get("count").getAsInt()
                : task.initialCount;
            if (task.initialCount < requested || requested <= 0) {
                fail(task, requestedId == null ? "背包中没有可整理物品" : "背包中没有足够的 " + requestedId, "MISSING_ITEMS");
                return;
            }
            task.requestedCount = requested;
            task.workstation = findNearbyBlock(
                minecraft,
                List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"),
                16,
                5
            );
            if (task.workstation == null) task.workstation = findNearbyShulkerBox(minecraft, 16, 5);
            if (task.workstation == null) {
                fail(task, "附近没有箱子、木桶或潜影盒", "CONTAINER_NOT_FOUND");
                return;
            }
        }

        if (!isStorageMenu(minecraft)) {
            if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                minecraft.player.closeContainer();
                return;
            }
            if (!approachAndOpen(minecraft, task, task.workstation)) return;
            if (!isStorageMenu(minecraft)) return;
        }
        task.openedContainer = true;
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        int moved = depositItems(minecraft, menu, requestedId, task.requestedCount - task.storedCount);
        task.storedCount += moved;
        if (task.storedCount >= task.requestedCount) {
            complete(task, "已把 " + task.storedCount + " 个物品整理到附近容器");
            return;
        }
        if (moved <= 0) {
            fail(task, "附近容器没有足够空间", "CONTAINER_FULL");
            return;
        }
        progress(task, task.storedCount / (double) task.requestedCount, "已存放 " + task.storedCount + "/" + task.requestedCount);
    }

    private void tickCombat(Minecraft minecraft, ActiveTask task) {
        LivingEntity target = nearestHostile(minecraft.player, number(task.spec, "maxDistance", 24));
        if (target == null) {
            task.clearTicks++;
            releaseKeys(minecraft);
            if (task.clearTicks >= 30) complete(task, "附近威胁已清除");
            return;
        }
        task.clearTicks = 0;
        attack(minecraft, target);
        if (task.ticks % 10 == 0) progress(task, Math.min(0.9, task.ticks / 200.0), "正在应对 " + target.getDisplayName().getString());
        if (task.ticks > 20 * 60 * 3) fail(task, "战斗超时，已停止追击", "COMBAT_TIMEOUT");
    }

    private void tickBuild(Minecraft minecraft, ActiveTask task) {
        if (task.buildPlan == null) {
            fail(task, "桥接命令缺少已确认建筑计划", "BUILD_PLAN_MISSING");
            return;
        }
        if (task.buildBlocks == null) task.prepareBuild();
        if (task.buildIndex >= task.buildBlocks.size()) {
            releaseKeys(minecraft);
            complete(task, "建筑已完成");
            return;
        }
        JsonObject block = task.buildBlocks.get(task.buildIndex);
        String blockId = string(block, "blockId", "minecraft:air");
        BlockPos target = task.absoluteBlock(block);
        String current = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(target).getBlock()).toString();
        if (blockId.equals("minecraft:air") || current.equals(blockId)) {
            task.buildIndex++;
            task.placeAttempts = 0;
            return;
        }
        BlockState currentState = minecraft.level.getBlockState(target);
        if (!currentState.canBeReplaced()) {
            fail(task, "目标位置已有不可替换方块：" + target.toShortString(), "BUILD_SITE_BLOCKED");
            return;
        }
        if (minecraft.player.position().distanceTo(Vec3.atCenterOf(target)) > 4.5) {
            driveToward(minecraft, Vec3.atCenterOf(target));
            return;
        }
        releaseKeys(minecraft);
        if (!selectItem(minecraft, blockId)) {
            fail(task, "背包中缺少 " + blockId, "MISSING_MATERIAL");
            return;
        }
        Direction supportDirection = findSupport(minecraft, target);
        if (supportDirection == null) {
            fail(task, "方块没有可点击的支撑面：" + target.toShortString(), "NO_BUILD_SUPPORT");
            return;
        }
        BlockPos support = target.relative(supportDirection);
        Direction face = supportDirection.getOpposite();
        Vec3 hit = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        lookAt(minecraft.player, hit);
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        task.placeAttempts++;
        if (task.placeAttempts > 20) fail(task, "无法放置方块 " + blockId, "PLACE_FAILED");
        if (task.ticks % 5 == 0) progress(task, task.buildIndex / (double) task.buildBlocks.size(), "建造中 " + task.buildIndex + "/" + task.buildBlocks.size());
    }

    private void tickDragon(Minecraft minecraft, ActiveTask task) {
        String action = string(task.spec, "action", "observe");
        if (action.equals("dismount")) {
            if (!minecraft.player.isPassenger()) {
                releaseKeys(minecraft);
                complete(task, "当前已经处于下马状态");
                return;
            }
            minecraft.options.keyShift.setDown(true);
            if (task.ticks > 40) fail(task, "服务器没有确认下马动作", "DISMOUNT_FAILED");
            return;
        }
        if (action.equals("care-for-egg")) {
            tickDragonEggCare(minecraft, task);
            return;
        }

        Entity target = resolveDragonTarget(minecraft, task);
        if (target == null) {
            fail(task, "附近没有找到目标龙", "DRAGON_NOT_FOUND");
            return;
        }
        if (action.equals("observe")) {
            Integer command = dragonCommand(target);
            String health = target instanceof LivingEntity living
                ? String.format(Locale.ROOT, "，生命 %.1f/%.1f", living.getHealth(), living.getMaxHealth())
                : "";
            String commandText = command == null ? "" : "，命令状态 " + dragonCommandName(command);
            complete(task, "已观察 " + target.getDisplayName().getString() + "（" + entityId(target) + "）" + health + commandText);
            return;
        }
        if (minecraft.player.distanceTo(target) > 4) {
            driveToward(minecraft, target.position());
            return;
        }
        releaseKeys(minecraft);
        lookAt(minecraft.player, target.getEyePosition());

        if (action.equals("follow") || action.equals("stay")) {
            tickDragonCommand(minecraft, task, target, action.equals("stay") ? 1 : 2);
            return;
        }
        if (action.equals("mount")) {
            tickDragonMount(minecraft, task, target);
            return;
        }
        if (action.equals("feed") || action.equals("heal") || action.equals("tame")) {
            tickDragonItemInteraction(minecraft, task, target, action);
            return;
        }
        fail(task, "不支持的龙类动作 " + action, "DRAGON_ACTION_UNSUPPORTED");
    }

    private void tickDragonCommand(Minecraft minecraft, ActiveTask task, Entity target, int desiredCommand) {
        Integer current = dragonCommand(target);
        if (current == null) {
            fail(task, "该龙模组没有提供可验证的跟随/停留命令", "DRAGON_COMMAND_UNSUPPORTED");
            return;
        }
        Object canCommand = invokeOneArg(target, "canOwnerCommand", minecraft.player);
        if (canCommand instanceof Boolean allowed && !allowed) {
            fail(task, "只有龙的主人才能切换跟随或停留", "DRAGON_NOT_OWNED");
            return;
        }
        if (current == desiredCommand) {
            releaseKeys(minecraft);
            complete(task, desiredCommand == 1 ? "龙已进入停留状态" : "龙已进入跟随状态");
            return;
        }
        if (task.interactionAttempts >= 3) {
            fail(task, "三次交互后仍未切换到目标命令", "DRAGON_COMMAND_FAILED");
            return;
        }
        if (task.interactionStage == 0) {
            InteractionHand hand = selectEmptyHand(minecraft);
            if (hand == null) {
                fail(task, "切换龙命令需要空手，请留出一个空快捷栏或副手槽", "EMPTY_HAND_REQUIRED");
                return;
            }
            task.interactionHand = hand;
            task.lastDragonCommand = current;
            minecraft.options.keyShift.setDown(true);
            task.interactionStage = 1;
            task.lastActionTick = task.ticks;
            return;
        }
        if (task.interactionStage == 1 && task.ticks - task.lastActionTick >= 2) {
            minecraft.gameMode.interact(minecraft.player, target, task.interactionHand);
            minecraft.player.swing(task.interactionHand);
            minecraft.options.keyShift.setDown(false);
            task.interactionAttempts++;
            task.interactionStage = 2;
            task.lastActionTick = task.ticks;
            return;
        }
        if (task.interactionStage == 2) {
            Integer synced = dragonCommand(target);
            if (synced != null && synced != task.lastDragonCommand) {
                task.interactionStage = 0;
                task.lastActionTick = task.ticks;
            } else if (task.ticks - task.lastActionTick > 16) {
                task.interactionStage = 0;
                task.lastActionTick = task.ticks;
            }
        }
    }

    private void tickDragonMount(Minecraft minecraft, ActiveTask task, Entity target) {
        if (minecraft.player.getVehicle() == target) {
            complete(task, "已骑乘 " + target.getDisplayName().getString());
            return;
        }
        if (!task.issued) {
            InteractionHand hand = selectEmptyHand(minecraft);
            if (hand == null) {
                fail(task, "骑乘交互需要空手，请留出一个空快捷栏或副手槽", "EMPTY_HAND_REQUIRED");
                return;
            }
            minecraft.options.keyShift.setDown(false);
            minecraft.gameMode.interact(minecraft.player, target, hand);
            minecraft.player.swing(hand);
            task.issued = true;
            task.lastActionTick = task.ticks;
            return;
        }
        if (task.ticks - task.lastActionTick > 30) {
            fail(task, "无法骑乘目标；请检查所有权、鞍具和龙的成长状态", "MOUNT_FAILED");
        }
    }

    private void tickDragonItemInteraction(Minecraft minecraft, ActiveTask task, Entity target, String action) {
        if (action.equals("heal") && target instanceof LivingEntity living && living.getHealth() >= living.getMaxHealth() - 0.01f) {
            complete(task, target.getDisplayName().getString() + " 当前不需要治疗");
            return;
        }
        if (!task.issued) {
            String foodId = selectDragonFood(minecraft, target, action.equals("heal"));
            if (foodId == null) {
                fail(task, "背包中没有该龙可接受的肉类、鱼类或龙粮", "DRAGON_FOOD_MISSING");
                return;
            }
            task.interactionItemId = foodId;
            task.lastInventoryCount = inventoryCount(minecraft.player, foodId);
            task.initialHealth = target instanceof LivingEntity living ? living.getHealth() : -1;
            minecraft.options.keyShift.setDown(false);
            minecraft.gameMode.interact(minecraft.player, target, InteractionHand.MAIN_HAND);
            minecraft.player.swing(InteractionHand.MAIN_HAND);
            task.issued = true;
            task.lastActionTick = task.ticks;
            return;
        }
        if (task.ticks - task.lastActionTick < 12) return;
        int remaining = inventoryCount(minecraft.player, task.interactionItemId);
        float health = target instanceof LivingEntity living ? living.getHealth() : task.initialHealth;
        boolean accepted = remaining < task.lastInventoryCount || health > task.initialHealth + 0.01f;
        if (!accepted) {
            fail(task, "目标没有接受当前护理物品", "DRAGON_ITEM_REJECTED");
            return;
        }
        String message = switch (action) {
            case "feed" -> "已喂养 " + target.getDisplayName().getString();
            case "heal" -> "已为 " + target.getDisplayName().getString() + " 提供治疗食物";
            default -> "已完成一次驯服互动；是否完全驯服以龙的当前状态为准";
        };
        complete(task, message);
    }

    private void tickDragonEggCare(Minecraft minecraft, ActiveTask task) {
        String targetId = string(task.spec, "targetId", "");
        Entity egg = findDragonEggEntity(minecraft, targetId);
        if (egg != null) {
            if (minecraft.player.distanceTo(egg) > 4) {
                driveToward(minecraft, egg.position());
                return;
            }
            releaseKeys(minecraft);
            lookAt(minecraft.player, egg.getEyePosition());
            minecraft.options.keyShift.setDown(false);
            minecraft.gameMode.interact(minecraft.player, egg, InteractionHand.MAIN_HAND);
            minecraft.player.swing(InteractionHand.MAIN_HAND);
            Integer current = invokeInt(egg, "getCurrentHatchTime");
            Integer total = invokeInt(egg, "getTotalHatchTime");
            String detail = current != null && total != null ? "，孵化 " + current + "/" + total : "";
            complete(task, "已检查龙蛋 " + entityId(egg) + detail);
            return;
        }

        if (task.workstation == null) task.workstation = findDragonEggBlock(minecraft, 24, 8);
        if (task.workstation == null) {
            fail(task, "附近没有找到龙蛋实体或龙蛋方块", "DRAGON_EGG_NOT_FOUND");
            return;
        }
        if (minecraft.player.position().distanceTo(Vec3.atCenterOf(task.workstation)) > 4) {
            driveToward(minecraft, Vec3.atCenterOf(task.workstation));
            return;
        }
        releaseKeys(minecraft);
        BlockEntity blockEntity = minecraft.level.getBlockEntity(task.workstation);
        Object progressValue = blockEntity == null ? null : invokeNoArg(blockEntity, "getHatchProgress");
        String detail = progressValue instanceof Number value
            ? String.format(Locale.ROOT, "，孵化进度 %.1f", value.doubleValue())
            : "";
        complete(task, "已检查龙蛋方块 " + blockId(minecraft, task.workstation) + detail);
    }

    private void tickFollow(Minecraft minecraft) {
        Player owner = findPlayer(minecraft, backgroundPlayer);
        if (owner == null) return;
        double distance = minecraft.player.distanceTo(owner);
        if (!baritone.available()) {
            if (distance > backgroundDistance) driveToward(minecraft, owner.position());
            else releaseKeys(minecraft);
        }
        status = "正在跟随 " + backgroundPlayer;
    }

    private void tickGuard(Minecraft minecraft) {
        Player owner = findPlayer(minecraft, backgroundPlayer);
        Player center = owner == null ? minecraft.player : owner;
        LivingEntity hostile = nearestHostile(center, guardRadius);
        if (hostile != null) {
            attack(minecraft, hostile);
            status = "正在护卫 " + backgroundPlayer;
        } else {
            releaseKeys(minecraft);
            if (owner != null && minecraft.player.distanceTo(owner) > Math.max(4, guardRadius * 0.5)) driveToward(minecraft, owner.position());
            status = "护卫待命";
        }
    }

    private LivingEntity nearestHostile(Player center, double radius) {
        return center.level().getEntitiesOfClass(
                LivingEntity.class,
                center.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && entity != center && isHostile(entity)
            ).stream()
            .min(Comparator.comparingDouble(center::distanceToSqr))
            .orElse(null);
    }

    private boolean isHostile(LivingEntity entity) {
        if (entity instanceof Player) return config.allowPvp;
        if (entity instanceof Monster) return true;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return config.hostileEntityAllowlist.stream().anyMatch(id::equalsIgnoreCase);
    }

    private void attack(Minecraft minecraft, LivingEntity target) {
        double distance = minecraft.player.distanceTo(target);
        lookAt(minecraft.player, target.getEyePosition());
        if (distance > 3.1) {
            driveToward(minecraft, target.position());
            return;
        }
        releaseKeys(minecraft);
        if (minecraft.player.getAttackStrengthScale(0) >= 0.9f) {
            minecraft.gameMode.attack(minecraft.player, target);
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void driveToward(Minecraft minecraft, Vec3 target) {
        Player player = minecraft.player;
        lookAt(player, target.add(0, player.getEyeHeight() * 0.5, 0));
        Vec3 delta = target.subtract(player.position());
        double length = Math.max(0.001, Math.sqrt(delta.x * delta.x + delta.z * delta.z));
        BlockPos ahead = BlockPos.containing(player.getX() + delta.x / length * 1.4, player.getY() - 1, player.getZ() + delta.z / length * 1.4);
        boolean deepDrop = minecraft.level.getBlockState(ahead).isAir() && minecraft.level.getBlockState(ahead.below()).isAir();
        minecraft.options.keyUp.setDown(!deepDrop);
        minecraft.options.keySprint.setDown(!deepDrop);
        minecraft.options.keyJump.setDown(!deepDrop && (player.horizontalCollision || target.y > player.getY() + 0.6));
    }

    private void lookAt(Player player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-89, Math.min(89, pitch)));
    }

    private void releaseKeys(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyAttack.setDown(false);
        minecraft.options.keyUse.setDown(false);
        minecraft.options.keyShift.setDown(false);
    }

    private Player findPlayer(Minecraft minecraft, String name) {
        return minecraft.level.players().stream()
            .filter(player -> player.getGameProfile().getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    private Entity resolveDragonTarget(Minecraft minecraft, ActiveTask task) {
        if (task.entityTarget != null) {
            Entity existing = minecraft.level.getEntities(
                    minecraft.player,
                    minecraft.player.getBoundingBox().inflate(config.observeRadius * 2),
                    entity -> entity.isAlive() && entity.getUUID().equals(task.entityTarget)
                ).stream()
                .findFirst()
                .orElse(null);
            if (existing != null) return existing;
            return null;
        }
        Entity found = findDragonTarget(minecraft, string(task.spec, "targetId", ""));
        if (found != null) task.entityTarget = found.getUUID();
        return found;
    }

    private Entity findDragonTarget(Minecraft minecraft, String targetId) {
        return minecraft.level.getEntities(
                minecraft.player,
                minecraft.player.getBoundingBox().inflate(config.observeRadius),
                entity -> entity.isAlive() && !isDragonEggEntity(entity)
                    && (targetId.isBlank() ? isDragonLike(entity) : matchesEntityTarget(entity, targetId))
            ).stream()
            .min(Comparator.comparingDouble(minecraft.player::distanceToSqr))
            .orElse(null);
    }

    private Entity findDragonEggEntity(Minecraft minecraft, String targetId) {
        return minecraft.level.getEntities(
                minecraft.player,
                minecraft.player.getBoundingBox().inflate(config.observeRadius),
                entity -> entity.isAlive() && isDragonEggEntity(entity)
                    && (targetId.isBlank() || matchesEntityTarget(entity, targetId))
            ).stream()
            .min(Comparator.comparingDouble(minecraft.player::distanceToSqr))
            .orElse(null);
    }

    private boolean matchesEntityTarget(Entity entity, String targetId) {
        return entity.getUUID().toString().equalsIgnoreCase(targetId)
            || Integer.toString(entity.getId()).equals(targetId)
            || entityId(entity).equalsIgnoreCase(targetId)
            || entity.getDisplayName().getString().equalsIgnoreCase(targetId);
    }

    private String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private boolean isDragonEggEntity(Entity entity) {
        String id = entityId(entity).toLowerCase(Locale.ROOT);
        String className = entity.getClass().getName().toLowerCase(Locale.ROOT);
        return id.contains("dragon_egg") || className.contains("dragonegg");
    }

    private boolean isDragonLike(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        String id = entityId(entity).toLowerCase(Locale.ROOT);
        String name = entity.getDisplayName().getString().toLowerCase(Locale.ROOT);
        return id.contains("dragon")
            || id.contains("night_fury")
            || id.contains("nightfury")
            || id.contains("deadly_nadder")
            || id.contains("gronckle")
            || id.contains("zippleback")
            || id.contains("nightmare")
            || id.contains("skrill")
            || id.contains("whispering_death")
            || id.contains("cindervane")
            || id.contains("ignivorus")
            || id.contains("nulljaw")
            || id.contains("raevyx")
            || id.contains("stegonaut")
            || id.contains("varasuchus")
            || id.contains("volitans")
            || name.contains("dragon")
            || name.contains("龙");
    }

    private Integer dragonCommand(Entity entity) {
        return invokeInt(entity, "getCommand");
    }

    private String dragonCommandName(int command) {
        return switch (command) {
            case 0 -> "游荡";
            case 1 -> "停留";
            case 2 -> "跟随";
            default -> Integer.toString(command);
        };
    }

    private String selectDragonFood(Minecraft minecraft, Entity target, boolean healing) {
        String targetId = entityId(target).toLowerCase(Locale.ROOT);
        List<String> foods = new ArrayList<>();
        if (healing) foods.add("saintsdragons:hearty_dragon_meal");
        if (targetId.contains("nulljaw")) foods.add("minecraft:chorus_fruit");
        foods.addAll(List.of(
            "minecraft:cod",
            "minecraft:salmon",
            "minecraft:chicken",
            "minecraft:beef",
            "minecraft:mutton",
            "minecraft:porkchop",
            "minecraft:tropical_fish",
            "minecraft:pufferfish",
            "saintsdragons:hearty_dragon_meal",
            "minecraft:cooked_cod",
            "minecraft:cooked_salmon",
            "minecraft:cooked_chicken",
            "minecraft:cooked_beef",
            "minecraft:cooked_mutton",
            "minecraft:cooked_porkchop",
            "minecraft:rabbit",
            "minecraft:cooked_rabbit",
            "minecraft:rotten_flesh"
        ));
        for (String food : foods) {
            if (selectItem(minecraft, food)) return food;
        }
        return null;
    }

    private InteractionHand selectEmptyHand(Minecraft minecraft) {
        Player player = minecraft.player;
        if (player.getMainHandItem().isEmpty()) return InteractionHand.MAIN_HAND;
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().selected = slot;
                return InteractionHand.MAIN_HAND;
            }
        }
        return player.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
    }

    private int inventoryCount(Player player, String itemId) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) count += stack.getCount();
        }
        return count;
    }

    private boolean selectItem(Minecraft minecraft, String itemId) {
        Player player = minecraft.player;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                player.getInventory().selected = slot;
                return true;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                int selected = player.getInventory().selected;
                minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, selected, ClickType.SWAP, player);
                return true;
            }
        }
        return false;
    }

    private String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private int inventoryTotal(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) count += stack.getCount();
        return count;
    }

    private String blockId(Minecraft minecraft, BlockPos position) {
        return BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(position).getBlock()).toString();
    }

    private BlockPos findNearbyBlock(Minecraft minecraft, List<String> ids, int radius, int verticalRadius) {
        BlockPos center = minecraft.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = center.offset(x, y, z);
                    if (!ids.contains(blockId(minecraft, candidate))) continue;
                    double distance = minecraft.player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearbyShulkerBox(Minecraft minecraft, int radius, int verticalRadius) {
        return findNearbyBlockBySuffix(minecraft, radius, verticalRadius, "_shulker_box");
    }

    private BlockPos findDragonEggBlock(Minecraft minecraft, int radius, int verticalRadius) {
        BlockPos center = minecraft.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = center.offset(x, y, z);
                    String id = blockId(minecraft, candidate).toLowerCase(Locale.ROOT);
                    if (!id.contains("egg") || (!id.contains("dragon") && !id.startsWith("saintsdragons:"))) continue;
                    double distance = minecraft.player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findNearbyBlockBySuffix(Minecraft minecraft, int radius, int verticalRadius, String suffix) {
        BlockPos center = minecraft.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = center.offset(x, y, z);
                    if (!blockId(minecraft, candidate).endsWith(suffix)) continue;
                    double distance = minecraft.player.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean approachAndOpen(Minecraft minecraft, ActiveTask task, BlockPos position) {
        if (minecraft.player.position().distanceTo(Vec3.atCenterOf(position)) > 4) {
            driveToward(minecraft, Vec3.atCenterOf(position));
            return false;
        }
        releaseKeys(minecraft);
        if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) return true;
        if (task.lastActionTick == 0 || task.ticks - task.lastActionTick >= 10) {
            Vec3 hit = Vec3.atCenterOf(position).add(0, 0.45, 0);
            lookAt(minecraft.player, hit);
            minecraft.gameMode.useItemOn(
                minecraft.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, position, false)
            );
            minecraft.player.swing(InteractionHand.MAIN_HAND);
            task.lastActionTick = task.ticks;
        }
        return false;
    }

    private boolean isStorageMenu(Minecraft minecraft) {
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        return menu != minecraft.player.inventoryMenu
            && !(menu instanceof CraftingMenu)
            && !(menu instanceof AbstractFurnaceMenu);
    }

    private int moveItemIntoSlot(Minecraft minecraft, AbstractContainerMenu menu, String itemId, int targetIndex, int requested) {
        int movedTotal = 0;
        Slot target = menu.getSlot(targetIndex);
        if (!target.getItem().isEmpty() && !itemId(target.getItem()).equals(itemId)) return 0;
        List<Integer> sources = playerSlotIndices(menu, minecraft.player);
        for (int sourceIndex : sources) {
            if (movedTotal >= requested || !target.getItem().isEmpty() && target.getItem().getCount() >= target.getItem().getMaxStackSize()) break;
            Slot source = menu.getSlot(sourceIndex);
            ItemStack stack = source.getItem();
            if (stack.isEmpty() || !itemId(stack).equals(itemId)) continue;
            int capacity = target.getItem().isEmpty()
                ? stack.getMaxStackSize()
                : target.getItem().getMaxStackSize() - target.getItem().getCount();
            int amount = Math.min(Math.min(stack.getCount(), requested - movedTotal), capacity);
            if (amount <= 0) break;
            int before = stack.getCount();
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, minecraft.player);
            if (amount == before && target.getItem().isEmpty()) {
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetIndex, 0, ClickType.PICKUP, minecraft.player);
            } else {
                for (int index = 0; index < amount; index++) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetIndex, 1, ClickType.PICKUP, minecraft.player);
                }
            }
            if (!menu.getCarried().isEmpty()) {
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, minecraft.player);
            }
            int after = source.getItem().isEmpty() ? 0 : source.getItem().getCount();
            movedTotal += Math.max(0, before - after);
        }
        return movedTotal;
    }

    private int depositItems(Minecraft minecraft, AbstractContainerMenu menu, String itemId, int requested) {
        int movedTotal = 0;
        List<Integer> sources = playerSlotIndices(menu, minecraft.player);
        for (int sourceIndex : sources) {
            if (movedTotal >= requested) break;
            Slot source = menu.getSlot(sourceIndex);
            ItemStack stack = source.getItem();
            if (stack.isEmpty() || itemId != null && !itemId(stack).equals(itemId)) continue;
            int before = stack.getCount();
            int remaining = requested - movedTotal;
            if (before <= remaining) {
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.QUICK_MOVE, minecraft.player);
            } else {
                int targetIndex = emptyContainerSlot(menu, minecraft.player, stack);
                if (targetIndex < 0) break;
                minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, minecraft.player);
                for (int index = 0; index < remaining; index++) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetIndex, 1, ClickType.PICKUP, minecraft.player);
                }
                if (!menu.getCarried().isEmpty()) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, minecraft.player);
                }
            }
            int after = source.getItem().isEmpty() ? 0 : source.getItem().getCount();
            movedTotal += Math.max(0, before - after);
        }
        return movedTotal;
    }

    private List<Integer> playerSlotIndices(AbstractContainerMenu menu, Player player) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.getSlot(index).container == player.getInventory()) result.add(index);
        }
        return result;
    }

    private int emptyContainerSlot(AbstractContainerMenu menu, Player player, ItemStack stack) {
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            if (slot.container != player.getInventory() && slot.getItem().isEmpty() && slot.mayPlace(stack)) return index;
        }
        return -1;
    }

    private Object invokeNoArg(Object target, String name) {
        return invokeMethod(target, name);
    }

    private Object invokeOneArg(Object target, String name, Object argument) {
        return invokeMethod(target, name, argument);
    }

    private Integer invokeInt(Object target, String name) {
        Object result = invokeNoArg(target, name);
        return result instanceof Number number ? number.intValue() : null;
    }

    private boolean invokeBoolean(Object target, String name, Object... arguments) {
        return Boolean.TRUE.equals(invokeMethod(target, name, arguments));
    }

    private Object invokeMethod(Object target, String name, Object... arguments) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < arguments.length; index++) {
                if (!compatibleParameter(parameterTypes[index], arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;
            try {
                return method.invoke(target, arguments);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean compatibleParameter(Class<?> parameter, Object argument) {
        if (argument == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isInstance(argument);
        return parameter == int.class && argument instanceof Integer
            || parameter == boolean.class && argument instanceof Boolean
            || parameter == double.class && argument instanceof Double
            || parameter == float.class && argument instanceof Float
            || parameter == long.class && argument instanceof Long
            || parameter == short.class && argument instanceof Short
            || parameter == byte.class && argument instanceof Byte
            || parameter == char.class && argument instanceof Character;
    }

    private Direction findSupport(Minecraft minecraft, BlockPos target) {
        for (Direction direction : List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP)) {
            BlockState state = minecraft.level.getBlockState(target.relative(direction));
            if (!state.isAir() && !state.canBeReplaced()) return direction;
        }
        return null;
    }

    private void progress(ActiveTask task, double progress, String message) {
        if (active == task) progressSink.send(task.id, Math.max(0, Math.min(1, progress)), message);
    }

    private void complete(ActiveTask task, String message) {
        closeTaskContainer(task);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) releaseKeys(minecraft);
        if (active == task) active = null;
        status = backgroundMode == BackgroundMode.NONE ? "待命" : backgroundMode == BackgroundMode.FOLLOW ? "正在跟随 " + backgroundPlayer : "护卫待命";
        resultSink.send(task.id, true, message, null);
    }

    private void fail(ActiveTask task, String message, String code) {
        closeTaskContainer(task);
        if (active == task) active = null;
        status = "任务失败";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) releaseKeys(minecraft);
        baritone.cancel();
        resultSink.send(task.id, false, message, code);
    }

    private void closeTaskContainer(ActiveTask task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!task.openedContainer || minecraft.player == null) return;
        if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) minecraft.player.closeContainer();
        task.openedContainer = false;
    }

    private Vec3 target(JsonObject value) {
        return new Vec3(number(value, "x", 0), number(value, "y", 0), number(value, "z", 0));
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
    }

    private static final class ActiveTask {
        private final String id;
        private final JsonObject spec;
        private final JsonObject buildPlan;
        private long ticks;
        private boolean issued;
        private boolean usedBaritone;
        private double startDistance = -1;
        private int initialCount = -1;
        private int clearTicks;
        private Vec3 target;
        private List<JsonObject> buildBlocks;
        private int buildIndex;
        private int placeAttempts;
        private BlockPos buildOrigin;
        private Object recipeWire;
        private Object recipe;
        private boolean requiresCraftingTable;
        private boolean recipeIssued;
        private boolean menuInitialized;
        private boolean transferPending;
        private boolean openedContainer;
        private long lastActionTick;
        private long lastProgressTick;
        private int lastInventoryCount;
        private int stalledTicks;
        private int producedCount;
        private int loadedCount;
        private int fuelLoadedCount;
        private int requestedCount;
        private int storedCount;
        private FuelChoice fuel;
        private BlockPos workstation;
        private UUID entityTarget;
        private int interactionAttempts;
        private int interactionStage;
        private int lastDragonCommand = -1;
        private InteractionHand interactionHand = InteractionHand.MAIN_HAND;
        private String interactionItemId = "";
        private float initialHealth = -1;

        private ActiveTask(String id, JsonObject spec, JsonObject buildPlan) {
            this.id = id;
            this.spec = spec;
            this.buildPlan = buildPlan;
        }

        private String kind() {
            return string(spec, "kind", "unknown");
        }

        private void prepareBuild() {
            buildBlocks = new ArrayList<>();
            JsonArray raw = buildPlan.getAsJsonArray("blocks");
            for (JsonElement element : raw) buildBlocks.add(element.getAsJsonObject());
            buildBlocks.sort(Comparator.comparingDouble(block -> number(block.getAsJsonObject("position"), "y", 0)));
            JsonObject origin = buildPlan.getAsJsonObject("origin");
            buildOrigin = BlockPos.containing(number(origin, "x", 0), number(origin, "y", 0), number(origin, "z", 0));
        }

        private BlockPos absoluteBlock(JsonObject block) {
            JsonObject relative = block.getAsJsonObject("position");
            return buildOrigin.offset(
                (int) Math.round(number(relative, "x", 0)),
                (int) Math.round(number(relative, "y", 0)),
                (int) Math.round(number(relative, "z", 0))
            );
        }
    }

    private static final class RecipeChoice {
        private final Object wireRecipe;
        private final Object recipe;
        private final boolean requiresCraftingTable;

        private RecipeChoice(Object wireRecipe, Object recipe, boolean requiresCraftingTable) {
            this.wireRecipe = wireRecipe;
            this.recipe = recipe;
            this.requiresCraftingTable = requiresCraftingTable;
        }
    }

    private static final class FuelChoice {
        private final String itemId;
        private final double itemsPerFuel;
        private final int count;

        private FuelChoice(String itemId, double itemsPerFuel, int count) {
            this.itemId = itemId;
            this.itemsPerFuel = itemsPerFuel;
            this.count = count;
        }
    }
}
