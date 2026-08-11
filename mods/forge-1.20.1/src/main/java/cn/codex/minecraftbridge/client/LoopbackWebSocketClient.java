package cn.codex.minecraftbridge.client;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class LoopbackWebSocketClient {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LoopbackWebSocketClient() {
    }

    static CompletableFuture<BridgeChannel> connect(
        URI uri,
        Duration timeout,
        Listener listener
    ) {
        CompletableFuture<BridgeChannel> result = new CompletableFuture<>();
        Thread thread = new Thread(
            () -> connectAndRead(uri, timeout, listener, result),
            "minecraft-codex-loopback-websocket"
        );
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static void connectAndRead(
        URI uri,
        Duration timeout,
        Listener listener,
        CompletableFuture<BridgeChannel> result
    ) {
        Channel channel = null;
        try {
            if (!BridgeConfig.isLoopbackBridgeUrl(uri.toString())) {
                throw new IllegalArgumentException("Only the local bridge endpoint is allowed");
            }
            String connectionHost = connectionHost(uri);
            InetAddress address = Arrays.stream(InetAddress.getAllByName(connectionHost))
                .filter(InetAddress::isLoopbackAddress)
                .findFirst()
                .orElseThrow(() -> new IOException("Bridge host did not resolve to loopback"));
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            Socket socket = new Socket();
            socket.connect(
                new InetSocketAddress(address, port),
                Math.toIntExact(Math.max(1, timeout.toMillis()))
            );
            socket.setTcpNoDelay(true);
            channel = handshake(socket, uri);
            listener.onOpen(channel);
            result.complete(channel);
            channel.readLoop(listener);
        } catch (Throwable error) {
            if (channel != null) {
                channel.closeQuietly();
                result.completeExceptionally(error);
                listener.onError(channel, error);
            } else {
                result.completeExceptionally(error);
            }
        }
    }

    private static Channel handshake(Socket socket, URI uri) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String connectionHost = connectionHost(uri);
        String host = connectionHost.contains(":") ? "[" + connectionHost + "]" : connectionHost;
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: " + host + ":" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        InputStream input = socket.getInputStream();
        String response = readHeaders(input);
        String[] lines = response.split("\\r\\n");
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] 101(?: .*)?")) {
            throw new IOException("Bridge did not accept the WebSocket upgrade");
        }
        String accepted = null;
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator <= 0) continue;
            String name = lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (name.equals("sec-websocket-accept")) {
                accepted = lines[index].substring(separator + 1).trim();
            }
        }
        if (!expectedAccept(key).equals(accepted)) {
            throw new IOException("Bridge returned an invalid WebSocket acceptance value");
        }
        return new Channel(socket, input, output);
    }

    static String connectionHost(URI uri) {
        String host = uri.getHost();
        if (host != null && host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int state = 0;
        while (output.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("Bridge closed during WebSocket handshake");
            output.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> state;
            };
            if (state == 4) return output.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("Bridge WebSocket handshake headers were too large");
    }

    static String expectedAccept(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable", impossible);
        }
    }

    static byte[] maskedFrame(int opcode, byte[] payload, byte[] mask) {
        if (mask.length != 4) throw new IllegalArgumentException("WebSocket mask must contain four bytes");
        int extended = payload.length < 126 ? 0 : payload.length <= 0xffff ? 2 : 8;
        ByteBuffer frame = ByteBuffer.allocate(2 + extended + 4 + payload.length);
        frame.put((byte) (0x80 | (opcode & 0x0f)));
        if (payload.length < 126) {
            frame.put((byte) (0x80 | payload.length));
        } else if (payload.length <= 0xffff) {
            frame.put((byte) (0x80 | 126));
            frame.putShort((short) payload.length);
        } else {
            frame.put((byte) (0x80 | 127));
            frame.putLong(payload.length);
        }
        frame.put(mask);
        for (int index = 0; index < payload.length; index++) {
            frame.put((byte) (payload[index] ^ mask[index % 4]));
        }
        return frame.array();
    }

    interface Listener {
        void onOpen(BridgeChannel channel);

        void onText(BridgeChannel channel, String message);

        void onClose(BridgeChannel channel, int statusCode, String reason);

        void onError(BridgeChannel channel, Throwable error);
    }

    private static final class Channel implements BridgeChannel {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private volatile boolean open = true;

        private Channel(Socket socket, InputStream input, OutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }

        @Override
        public boolean isOpen() {
            return open && !socket.isClosed();
        }

        @Override
        public CompletionStage<Void> sendText(String message) {
            return sendFrame(1, message.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public CompletionStage<Void> sendClose(int statusCode, String reason) {
            byte[] reasonBytes = reason == null
                ? new byte[0]
                : reason.getBytes(StandardCharsets.UTF_8);
            int allowed = Math.min(reasonBytes.length, 123);
            ByteBuffer payload = ByteBuffer.allocate(2 + allowed);
            payload.putShort((short) statusCode);
            payload.put(reasonBytes, 0, allowed);
            CompletionStage<Void> sent = sendFrame(8, payload.array());
            closeQuietly();
            return sent;
        }

        private synchronized CompletionStage<Void> sendFrame(int opcode, byte[] payload) {
            if (!isOpen()) return CompletableFuture.failedFuture(new IOException("Bridge is closed"));
            try {
                byte[] mask = new byte[4];
                RANDOM.nextBytes(mask);
                output.write(maskedFrame(opcode, payload, mask));
                output.flush();
                return CompletableFuture.completedFuture(null);
            } catch (IOException error) {
                closeQuietly();
                return CompletableFuture.failedFuture(error);
            }
        }

        private void readLoop(Listener listener) throws IOException {
            ByteArrayOutputStream fragmented = null;
            while (isOpen()) {
                int first = input.read();
                if (first < 0) throw new EOFException("Bridge closed the WebSocket stream");
                int second = readByte(input);
                boolean finished = (first & 0x80) != 0;
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7f;
                if (length == 126) {
                    length = (readByte(input) << 8) | readByte(input);
                } else if (length == 127) {
                    length = readLong(input);
                }
                if (length < 0 || length > MAX_MESSAGE_BYTES) {
                    throw new IOException("Bridge WebSocket frame was too large");
                }
                byte[] mask = masked ? readExactly(input, 4) : null;
                byte[] payload = readExactly(input, Math.toIntExact(length));
                if (mask != null) {
                    for (int index = 0; index < payload.length; index++) {
                        payload[index] = (byte) (payload[index] ^ mask[index % 4]);
                    }
                }

                if (opcode == 8) {
                    int status = payload.length >= 2
                        ? Short.toUnsignedInt(ByteBuffer.wrap(payload, 0, 2).getShort())
                        : 1000;
                    String reason = payload.length > 2
                        ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                        : "";
                    closeQuietly();
                    listener.onClose(this, status, reason);
                    return;
                }
                if (opcode == 9) {
                    sendFrame(10, payload);
                    continue;
                }
                if (opcode == 10) continue;
                if (opcode == 1) fragmented = new ByteArrayOutputStream();
                if (opcode != 0 && opcode != 1) throw new IOException("Unsupported WebSocket frame type");
                if (fragmented == null) throw new IOException("Unexpected WebSocket continuation frame");
                fragmented.write(payload);
                if (fragmented.size() > MAX_MESSAGE_BYTES) {
                    throw new IOException("Bridge WebSocket message was too large");
                }
                if (finished) {
                    listener.onText(this, fragmented.toString(StandardCharsets.UTF_8));
                    fragmented = null;
                }
            }
        }

        private void closeQuietly() {
            open = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private static int readByte(InputStream input) throws IOException {
            int value = input.read();
            if (value < 0) throw new EOFException("Bridge closed during a WebSocket frame");
            return value;
        }

        private static long readLong(InputStream input) throws IOException {
            long value = 0;
            for (int index = 0; index < 8; index++) value = (value << 8) | readByte(input);
            return value;
        }

        private static byte[] readExactly(InputStream input, int length) throws IOException {
            byte[] value = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(value, offset, length - offset);
                if (count < 0) throw new EOFException("Bridge closed during a WebSocket frame");
                offset += count;
            }
            return value;
        }
    }
}
