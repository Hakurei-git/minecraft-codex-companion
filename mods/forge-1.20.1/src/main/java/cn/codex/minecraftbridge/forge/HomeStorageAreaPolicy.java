package cn.codex.minecraftbridge.forge;

/** Storage expansion uses the detected building, never an unverified bed-radius fallback. */
final class HomeStorageAreaPolicy {
    static final int OUTSIDE_MARGIN = 10;
    record Cell(int x, int y, int z) {}
    record Area(Cell min, Cell max, int groundY) {
        boolean contains(Cell cell) {
            return cell.x() >= min.x() && cell.x() <= max.x()
                && cell.y() >= min.y() && cell.y() <= max.y()
                && cell.z() >= min.z() && cell.z() <= max.z();
        }
    }

    private HomeStorageAreaPolicy() {}

    static boolean allows(Area area, Cell cell, boolean verifiedHome, boolean sameDimension,
                          boolean indoor, boolean allowOutside) {
        if (!verifiedHome || !sameDimension || area == null) return false;
        if (indoor && area.contains(cell)) return true;
        if (!allowOutside || Math.abs(cell.y() - area.groundY()) > 3) return false;
        int dx = Math.max(0, Math.max(area.min().x() - cell.x(), cell.x() - area.max().x()));
        int dz = Math.max(0, Math.max(area.min().z() - cell.z(), cell.z() - area.max().z()));
        // A circular ten-block margin also bounds the diagonal corners.
        return dx * dx + dz * dz <= OUTSIDE_MARGIN * OUTSIDE_MARGIN;
    }
}
