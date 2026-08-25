package cn.codex.minecraftbridge.forge;

/** Pure policy for keeping coordinate tasks attached to their source dimension. */
final class TaskDimensionPolicy {
    private TaskDimensionPolicy() {
    }

    static boolean isLocationBound(String kind) {
        return "move".equals(kind);
    }

    static String bind(String kind, String configuredDimension, String currentDimension) {
        if (!isLocationBound(kind)) return configuredDimension == null ? "" : configuredDimension;
        if (configuredDimension != null && !configuredDimension.isBlank()) return configuredDimension;
        return currentDimension == null ? "" : currentDimension;
    }

    static boolean shouldPause(String kind, String taskDimension, String currentDimension) {
        return isLocationBound(kind)
            && taskDimension != null
            && !taskDimension.isBlank()
            && currentDimension != null
            && !taskDimension.equals(currentDimension);
    }
}
