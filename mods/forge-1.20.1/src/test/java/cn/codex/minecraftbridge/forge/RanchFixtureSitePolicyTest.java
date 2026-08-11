package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RanchFixtureSitePolicyTest {
    @Test
    void searchesOutToTheNpcRanchCommandRadius() {
        assertEquals(12, RanchFixtureSitePolicy.MIN_RADIUS);
        assertEquals(128, RanchFixtureSitePolicy.MAX_RADIUS);
        assertEquals(4, RanchFixtureSitePolicy.RADIUS_STEP);
    }

    @Test
    void keepsCandidateArcsDenseAsSearchRadiusExpands() {
        for (int radius = RanchFixtureSitePolicy.MIN_RADIUS;
             radius <= RanchFixtureSitePolicy.MAX_RADIUS;
             radius += RanchFixtureSitePolicy.RADIUS_STEP) {
            int samples = RanchFixtureSitePolicy.angularSamples(radius);
            double arcStep = Math.PI * 2.0D * radius / samples;
            assertTrue(samples >= 16);
            assertEquals(0, samples % 8);
            assertTrue(arcStep <= RanchFixtureSitePolicy.MAX_ARC_STEP);
        }
    }
}
