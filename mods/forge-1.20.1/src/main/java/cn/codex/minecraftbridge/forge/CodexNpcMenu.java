package cn.codex.minecraftbridge.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public final class CodexNpcMenu extends AbstractContainerMenu {
    private static final int NPC_SLOTS = CodexNpcEntity.INVENTORY_SIZE;
    private final CodexNpcEntity npc;

    public CodexNpcMenu(int containerId, Inventory playerInventory, CodexNpcEntity npc) {
        super(ModMenus.CODEX_NPC.get(), containerId);
        this.npc = npc;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new SlotItemHandler(npc.inventory(), column + row * 9, 8 + column * 18, 18 + row * 18));
            }
        }
        addSlot(new SlotItemHandler(npc.inventory(), CodexNpcEntity.MAIN_HAND_SLOT, 174, 18));
        addSlot(new SlotItemHandler(npc.inventory(), CodexNpcEntity.OFF_HAND_SLOT, 192, 18));
        addSlot(equipmentSlot(CodexNpcEntity.HEAD_SLOT, 174, 40, EquipmentSlot.HEAD));
        addSlot(equipmentSlot(CodexNpcEntity.CHEST_SLOT, 192, 40, EquipmentSlot.CHEST));
        addSlot(equipmentSlot(CodexNpcEntity.LEGS_SLOT, 174, 62, EquipmentSlot.LEGS));
        addSlot(equipmentSlot(CodexNpcEntity.FEET_SLOT, 192, 62, EquipmentSlot.FEET));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 86 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 144));
        }
    }

    public static CodexNpcMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        Entity entity = playerInventory.player.level().getEntity(data.readInt());
        if (!(entity instanceof CodexNpcEntity npc)) throw new IllegalStateException("Codex NPC is not available");
        return new CodexNpcMenu(containerId, playerInventory, npc);
    }

    public CodexNpcEntity npc() {
        return npc;
    }

    private SlotItemHandler equipmentSlot(int index, int x, int y, EquipmentSlot equipmentSlot) {
        return new SlotItemHandler(npc.inventory(), index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.canEquip(equipmentSlot, npc);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }

    @Override
    public boolean stillValid(Player player) {
        return npc.isAlive() && npc.isOwnedBy(player) && player.distanceToSqr(npc) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = getSlot(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack current = slot.getItem();
        ItemStack original = current.copy();
        if (index < NPC_SLOTS) {
            if (!moveItemStackTo(current, NPC_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(current, 0, CodexNpcEntity.BACKPACK_SIZE, false)) {
            return ItemStack.EMPTY;
        }
        if (current.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }
}
