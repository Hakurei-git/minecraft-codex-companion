package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DragonAdapterCommandPolicyTest {
    @Test
    void bookOfDragonsUsesTwoForFollow() {
        DragonAdapter adapter = new BookOfDragonsAdapter();
        assertEquals(2, adapter.followCommand());
        assertEquals(1, adapter.stayCommand());
    }

    @Test
    void saintsDragonsUsesZeroForFollowAndOneForSit() {
        DragonAdapter adapter = new SaintsDragonsAdapter();
        assertEquals(0, adapter.followCommand());
        assertEquals(1, adapter.stayCommand());
    }
}
