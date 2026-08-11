import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HmclCrashProbeAgent {
    private static final Pattern EXCEPTION_TYPE = Pattern.compile(
            "(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+"
                    + "[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)"
    );
    private static final Pattern STACK_METHOD = Pattern.compile(
            "(?m)^\\s*at\\s+((?:org\\.jackhuang\\.hmcl|java|jdk|sun)"
                    + "\\.[A-Za-z0-9_.$<>]+)\\([^\\r\\n]*\\)"
    );

    private HmclCrashProbeAgent() {
    }

    public static void agentmain(String statusPath, Instrumentation instrumentation) {
        ClassLoader loader = findHmclClassLoader(instrumentation);
        if (loader == null) {
            writeStatus(statusPath, "ERROR:HMCL_CLASSLOADER_NOT_FOUND");
            return;
        }
        try {
            Class<?> platformClass = Class.forName("javafx.application.Platform", true, loader);
            platformClass.getMethod("runLater", Runnable.class).invoke(
                    null,
                    (Runnable) () -> inspectCrashWindow(statusPath, loader)
            );
        } catch (Throwable error) {
            writeStatus(statusPath, "ERROR:CRASH_PROBE_DISPATCH_FAILED");
        }
    }

    private static ClassLoader findHmclClassLoader(Instrumentation instrumentation) {
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass.getName().equals("org.jackhuang.hmcl.Launcher")) {
                return loadedClass.getClassLoader();
            }
        }
        return null;
    }

    private static void inspectCrashWindow(String statusPath, ClassLoader loader) {
        try {
            Class<?> windowClass = Class.forName("javafx.stage.Window", true, loader);
            Object windows = windowClass.getMethod("getWindows").invoke(null);
            StringBuilder diagnostics = new StringBuilder("CRASHES:");
            for (Object window : (Iterable<?>) windows) {
                String className = window.getClass().getName();
                if (className.equals("org.jackhuang.hmcl.ui.CrashWindow")) {
                    Object scene = window.getClass().getMethod("getScene").invoke(window);
                    Object root = scene == null
                            ? null
                            : scene.getClass().getMethod("getRoot").invoke(scene);
                    Object textArea = findTextArea(root, loader);
                    if (textArea != null) {
                        String crashText = (String) textArea.getClass().getMethod("getText")
                                .invoke(textArea);
                        appendSanitizedCrash(diagnostics, "app", crashText, null);
                    }
                } else if (className.equals("org.jackhuang.hmcl.ui.GameCrashWindow")) {
                    Field logsField = window.getClass().getDeclaredField("logs");
                    logsField.setAccessible(true);
                    StringBuilder gameLogs = new StringBuilder();
                    for (Object log : (Iterable<?>) logsField.get(window)) {
                        Object value = log.getClass().getMethod("getLog").invoke(log);
                        if (value != null) {
                            gameLogs.append(value).append('\n');
                        }
                    }
                    Field exitTypeField = window.getClass().getDeclaredField("exitType");
                    exitTypeField.setAccessible(true);
                    Object exitType = exitTypeField.get(window);
                    appendSanitizedCrash(
                            diagnostics,
                            "game",
                            gameLogs.toString(),
                            exitType == null ? null : exitType.toString()
                    );
                }
            }
            if (diagnostics.length() == "CRASHES:".length()) {
                writeStatus(statusPath, "ERROR:CRASH_WINDOW_NOT_FOUND");
                return;
            }
            writeStatus(statusPath, diagnostics.toString());
        } catch (Throwable error) {
            writeStatus(statusPath, "ERROR:CRASH_PROBE_FAILED");
        }
    }

    private static Object findTextArea(Object node, ClassLoader loader) throws Exception {
        if (node == null) {
            return null;
        }
        if (node.getClass().getName().equals("javafx.scene.control.TextArea")) {
            return node;
        }
        Class<?> parentClass = Class.forName("javafx.scene.Parent", true, loader);
        if (!parentClass.isInstance(node)) {
            return null;
        }
        Object children = parentClass.getMethod("getChildrenUnmodifiable").invoke(node);
        for (Object child : (Iterable<?>) children) {
            Object found = findTextArea(child, loader);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static String sanitizeCrashText(String crashText) {
        Set<String> exceptionTypes = collectMatches(EXCEPTION_TYPE, crashText, 16);
        Set<String> stackMethods = collectMatches(STACK_METHOD, crashText, 40);
        return "types=" + String.join(",", exceptionTypes)
                + ";frames=" + String.join(",", stackMethods)
                + ";signals=" + String.join(",", classifyCrashSignals(crashText));
    }

    private static void appendSanitizedCrash(
            StringBuilder diagnostics,
            String kind,
            String crashText,
            String exitType
    ) {
        if (diagnostics.length() > "CRASHES:".length()) {
            diagnostics.append('|');
        }
        diagnostics.append(kind).append('[');
        if (exitType != null) {
            diagnostics.append("exit=")
                    .append(exitType.replaceAll("[^A-Za-z0-9_.-]", "_"))
                    .append(';');
        }
        diagnostics.append(sanitizeCrashText(crashText)).append(']');
    }

    private static Set<String> classifyCrashSignals(String crashText) {
        String value = crashText == null ? "" : crashText;
        Set<String> signals = new LinkedHashSet<>();
        addSignal(signals, value, "(?i)could not find or load main class", "MAIN_CLASS_MISSING");
        addSignal(signals, value, "UnsupportedClassVersionError", "JAVA_VERSION");
        addSignal(signals, value, "OutOfMemoryError", "OUT_OF_MEMORY");
        addSignal(signals, value, "(?:NoSuchFile|FileNotFound)Exception", "MISSING_FILE");
        addSignal(signals, value, "AccessDeniedException", "ACCESS_DENIED");
        addSignal(signals, value, "InvalidPathException", "INVALID_PATH");
        addSignal(signals, value, "(?i)(?:MixinApplyError|mixin.{0,40}(?:failed|error))", "MIXIN");
        addSignal(
                signals,
                value,
                "(?:ModLoadingException|LoadingFailedException)",
                "MOD_LOADING"
        );
        addSignal(signals, value, "(?i)unrecognized VM option", "JVM_OPTION");
        addSignal(
                signals,
                value,
                "(?i)(?:invalid maximum heap size|could not reserve enough space)",
                "MEMORY_CONFIG"
        );
        addSignal(
                signals,
                value,
                "(?i)(?:error opening zip file|ZipException)",
                "CORRUPT_ARCHIVE"
        );
        addSignal(signals, value, "UnsatisfiedLinkError", "NATIVE_LIBRARY");
        addSignal(signals, value, "(?i)no space left", "DISK_FULL");
        addSignal(
                signals,
                value,
                "(?i)could not create the Java Virtual Machine",
                "JVM_START"
        );
        addSignal(signals, value, "(?i)cannot find the path specified", "MISSING_PATH");
        return signals;
    }

    private static void addSignal(
            Set<String> signals,
            String value,
            String pattern,
            String signal
    ) {
        if (Pattern.compile(pattern).matcher(value).find()) {
            signals.add(signal);
        }
    }

    private static Set<String> collectMatches(Pattern pattern, String value, int limit) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find() && matches.size() < limit) {
            matches.add(matcher.groupCount() == 0 ? matcher.group() : matcher.group(1));
        }
        return matches;
    }

    private static void writeStatus(String statusPath, String status) {
        if (statusPath == null || statusPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    Path.of(statusPath),
                    status,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
            // The probe intentionally has no secondary output channel.
        }
    }
}
