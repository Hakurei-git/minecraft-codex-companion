package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityMaterialAcquisitionPolicyTest {
    @Test
    void routesSupportedVanillaEntityIngredientsWithoutPretendingScuteIsImmediate() {
        assertEquals(EntityMaterialAcquisitionPolicy.Route.SHEAR_WHITE_SHEEP,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:white_wool"));
        assertEquals(EntityMaterialAcquisitionPolicy.Route.HUNT_COW,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:leather"));
        assertEquals(EntityMaterialAcquisitionPolicy.Route.HUNT_CHICKEN,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:feather"));
        assertEquals(EntityMaterialAcquisitionPolicy.Route.HUNT_SLIME,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:slime_ball"));
        assertEquals(EntityMaterialAcquisitionPolicy.Route.UNSUPPORTED,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:scute"));
        assertEquals(EntityMaterialAcquisitionPolicy.Route.UNSUPPORTED,
            EntityMaterialAcquisitionPolicy.routeFor("minecraft:red_wool"));
    }

    @Test
    void passiveAnimalsKeepOwnershipAndHerdProtections() {
        assertTrue(EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            true, true, false, false, false, false, 3, false));
        assertFalse(EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            true, true, false, false, false, false, 2, false));
        assertTrue(EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            true, true, false, false, false, false, 1, true));
        assertFalse(EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            true, true, false, false, true, false, 5, true));
        assertFalse(EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            true, true, false, false, false, true, 5, false));
    }

    @Test
    void slimeRouteStillProtectsNamedAndHomeAreaEntities() {
        assertTrue(EntityMaterialAcquisitionPolicy.mayUseSlime(true, false, false));
        assertFalse(EntityMaterialAcquisitionPolicy.mayUseSlime(true, true, false));
        assertFalse(EntityMaterialAcquisitionPolicy.mayUseSlime(true, false, true));
    }
}
