import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MinecraftBridgeDiagnosticAgentV2 {
    private static final String STATUS_PREFIX = "minecraft-bridge-state-";

    private MinecraftBridgeDiagnosticAgentV2() {
    }

    public static void agentmain(String argument, Instrumentation instrumentation) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("format", "1");
        state.put("eventsClassLoaded", "false");
        state.put("clientPresent", "false");
        state.put("configReady", "false");
        state.put("autoReconnect", "false");
        state.put("sessionActive", "false");
        state.put("connecting", "false");
        state.put("socketPresent", "false");
        state.put("announcedSocketPresent", "false");
        state.put("ticksPositive", "false");
        state.put("connectionAttemptsPositive", "false");
        state.put("lastConnectionFailureCategory", "none");
        state.put("tcpConnectSucceeded", "false");
        state.put("webSocketUpgradeSucceeded", "false");
        state.put("probeFailureCategory", "not-run");
        state.put("errorType", "none");

        Path statusPath = null;
        try {
            statusPath = validateStatusPath(argument);
            Class<?> eventsClass = findLoadedClass(
                instrumentation,
                "cn.codex.minecraftbridge.client.ClientBridgeEvents"
            );
            if (eventsClass != null) {
                state.put("eventsClassLoaded", "true");
                Field clientField = eventsClass.getDeclaredField("CLIENT");
                clientField.setAccessible(true);
                Object client = clientField.get(null);
                if (client != null) {
                    state.put("clientPresent", "true");
                    Class<?> clientClass = client.getClass();
                    Object config = field(clientClass, client, "config");
                    if (config != null) {
                        Method isReady = config.getClass().getMethod("isReady");
                        state.put("configReady", booleanText(isReady.invoke(config)));
                        state.put("autoReconnect", booleanText(field(config.getClass(), config, "autoReconnect")));
                        ProbeResult probe = probeLoopback(String.valueOf(field(config.getClass(), config, "serverUrl")));
                        state.put("tcpConnectSucceeded", booleanText(probe.tcpConnected));
                        state.put("webSocketUpgradeSucceeded", booleanText(probe.upgraded));
                        state.put("probeFailureCategory", probe.failureCategory);
                    }
                    state.put("sessionActive", booleanText(field(clientClass, client, "sessionActive")));
                    state.put("connecting", booleanText(field(clientClass, client, "connecting")));
                    state.put("socketPresent", booleanText(field(clientClass, client, "socket") != null));
                    state.put(
                        "announcedSocketPresent",
                        booleanText(field(clientClass, client, "announcedSocket") != null)
                    );
                    Object ticks = field(clientClass, client, "ticks");
                    state.put("ticksPositive", booleanText(ticks instanceof Number && ((Number) ticks).longValue() > 0));
                    Object attempts = field(clientClass, client, "connectionAttempts");
                    state.put(
                        "connectionAttemptsPositive",
                        booleanText(attempts instanceof Number && ((Number) attempts).longValue() > 0)
                    );
                    Object failureCategory = field(clientClass, client, "lastConnectionFailureCategory");
                    String category = String.valueOf(failureCategory);
                    state.put(
                        "lastConnectionFailureCategory",
                        category.matches("[a-z0-9-]+") ? category : "invalid"
                    );
                }
            }
        } catch (Throwable error) {
            state.put("errorType", safeType(error));
        }

        if (statusPath != null) {
            try {
                StringBuilder output = new StringBuilder();
                state.forEach((key, value) -> output.append(key).append('=').append(value).append('\n'));
                Files.writeString(
                    statusPath,
                    output.toString(),
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            } catch (Throwable ignored) {
                // The caller times out without receiving a status file.
            }
        }
    }

    private static Path validateStatusPath(String argument) throws Exception {
        Path path = Path.of(argument).toAbsolutePath().normalize();
        String fileName = path.getFileName().toString();
        if (!fileName.startsWith(STATUS_PREFIX) || !fileName.endsWith(".status")
            || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid-status-name");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("invalid-status-parent");
        }
        return path;
    }

    private static Class<?> findLoadedClass(Instrumentation instrumentation, String name) {
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getName().equals(name)) return candidate;
        }
        return null;
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String booleanText(Object value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    private static String safeType(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_$]+") ? name : "UnknownError";
    }

    private static ProbeResult probeLoopback(String rawUrl) {
        boolean tcpConnected = false;
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (!"ws".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("127.0.0.1") || host.equals("::1") || host.equalsIgnoreCase("localhost"))
                || !"/bridge".equals(uri.getPath())) {
                return new ProbeResult(false, false, "invalid-config");
            }
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress()) return new ProbeResult(false, false, "non-loopback");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 3_000);
                tcpConnected = true;
                socket.setSoTimeout(3_000);
                OutputStream output = socket.getOutputStream();
                String request = "GET /bridge HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
                output.write(request.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                byte[] header = readHeader(socket.getInputStream());
                String response = new String(header, StandardCharsets.US_ASCII);
                boolean upgraded = response.startsWith("HTTP/1.1 101 ") || response.startsWith("HTTP/1.0 101 ");
                return new ProbeResult(true, upgraded, upgraded ? "none" : "upgrade-rejected");
            }
        } catch (SocketTimeoutException error) {
            return new ProbeResult(tcpConnected, false, "timeout");
        } catch (ConnectException error) {
            return new ProbeResult(false, false, "connect-refused");
        } catch (SecurityException error) {
            return new ProbeResult(tcpConnected, false, "access-denied");
        } catch (IOException error) {
            return new ProbeResult(tcpConnected, false, "other-io");
        } catch (RuntimeException error) {
            return new ProbeResult(tcpConnected, false, "invalid-config");
        }
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < 16 * 1024) {
            int value = input.read();
            if (value < 0) throw new IOException("closed");
            header.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) return header.toByteArray();
        }
        throw new IOException("oversized");
    }

    private record ProbeResult(boolean tcpConnected, boolean upgraded, String failureCategory) {
    }
}
