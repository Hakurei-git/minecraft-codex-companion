import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

public final class InProcessJUnitRunner {
    private InProcessJUnitRunner() {
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the compiled test classes directory");
        }
        Path testClasses = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(testClasses)) {
            throw new IllegalArgumentException("Compiled test classes directory is unavailable");
        }

        Class<?> discoverySelectorsType = Class.forName(
            "org.junit.platform.engine.discovery.DiscoverySelectors"
        );
        Collection<?> selectors = (Collection<?>) discoverySelectorsType
            .getMethod("selectClasspathRoots", Set.class)
            .invoke(null, Set.of(testClasses));
        Object selector = selectors.stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No test classpath root was selected"));

        Class<?> discoverySelectorType = Class.forName("org.junit.platform.engine.DiscoverySelector");
        Object selectorArray = Array.newInstance(discoverySelectorType, 1);
        Array.set(selectorArray, 0, selector);
        Class<?> requestBuilderType = Class.forName(
            "org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder"
        );
        Object requestBuilder = requestBuilderType.getMethod("request").invoke(null);
        requestBuilderType
            .getMethod("selectors", selectorArray.getClass())
            .invoke(requestBuilder, selectorArray);
        Object request = requestBuilderType.getMethod("build").invoke(requestBuilder);

        Class<?> summaryListenerType = Class.forName(
            "org.junit.platform.launcher.listeners.SummaryGeneratingListener"
        );
        Object summaryListener = summaryListenerType.getConstructor().newInstance();
        Class<?> executionListenerType = Class.forName(
            "org.junit.platform.launcher.TestExecutionListener"
        );
        Object listenerArray = Array.newInstance(executionListenerType, 1);
        Array.set(listenerArray, 0, summaryListener);
        Object launcher = Class.forName("org.junit.platform.launcher.core.LauncherFactory")
            .getMethod("create")
            .invoke(null);
        Class<?> launcherType = Class.forName("org.junit.platform.launcher.Launcher");
        Class<?> requestType = Class.forName("org.junit.platform.launcher.LauncherDiscoveryRequest");
        launcherType
            .getMethod("execute", requestType, listenerArray.getClass())
            .invoke(launcher, request, listenerArray);

        Object summary = summaryListenerType.getMethod("getSummary").invoke(summaryListener);
        Class<?> summaryType = Class.forName(
            "org.junit.platform.launcher.listeners.TestExecutionSummary"
        );
        long found = (long) summaryType.getMethod("getTestsFoundCount").invoke(summary);
        long succeeded = (long) summaryType.getMethod("getTestsSucceededCount").invoke(summary);
        long failed = (long) summaryType.getMethod("getTestsFailedCount").invoke(summary);
        System.out.println("TESTS_FOUND=" + found);
        System.out.println("TESTS_SUCCEEDED=" + succeeded);
        System.out.println("TESTS_FAILED=" + failed);

        Collection<?> failures = (Collection<?>) summaryType.getMethod("getFailures").invoke(summary);
        Class<?> failureType = Class.forName(
            "org.junit.platform.launcher.listeners.TestExecutionSummary$Failure"
        );
        Class<?> testIdentifierType = Class.forName("org.junit.platform.launcher.TestIdentifier");
        for (Object failure : failures) {
            Object identifier = failureType.getMethod("getTestIdentifier").invoke(failure);
            Throwable exception = (Throwable) failureType.getMethod("getException").invoke(failure);
            String displayName = (String) testIdentifierType
                .getMethod("getDisplayName")
                .invoke(identifier);
            System.out.println(
                "FAILED_TEST=" + displayName + ";CAUSE=" + exception.getClass().getName()
            );
        }
        if (failed != 0) System.exit(1);
    }
}
