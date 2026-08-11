package cn.codex.minecraftbridge.forge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Versioned, bounded persistence format for the NPC scheduler.  This class is
 * deliberately independent from Minecraft classes so corrupt world data can be
 * rejected before it reaches navigation or inventory code.
 */
public final class NpcTaskPersistence {
    public static final int VERSION = 1;
    public static final int MAX_QUEUED_TASKS = 32;
    public static final int MAX_SERIALIZED_CHARS = 64 * 1024 * 1024;
    public static final int MAX_SERIALIZED_BYTES = 96 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 32 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private NpcTaskPersistence() {}

    public record WorkState(
        String id,
        String kind,
        JsonObject spec,
        JsonObject plan,
        String resumeStance,
        int priority,
        String pauseReason,
        JsonObject checkpoint
    ) {}

    public record SchedulerState(
        int version,
        String lifecycle,
        WorkState active,
        List<WorkState> paused,
        WorkState recoverableBuild
    ) {
        public SchedulerState {
            paused = paused == null ? List.of() : List.copyOf(paused);
        }

        public SchedulerState(int version, String lifecycle, WorkState active, List<WorkState> paused) {
            this(version, lifecycle, active, paused, null);
        }
    }

    public static String encode(SchedulerState state) {
        SchedulerState safe = validate(state);
        String encoded = GSON.toJson(safe);
        if (encoded.length() > MAX_SERIALIZED_CHARS) {
            throw new IllegalArgumentException("NPC task checkpoint is too large");
        }
        return encoded;
    }

    public static SchedulerState decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return empty();
        if (encoded.length() > MAX_SERIALIZED_CHARS) {
            throw new IllegalArgumentException("NPC task checkpoint is too large");
        }
        try {
            return validate(GSON.fromJson(encoded, SchedulerState.class));
        } catch (JsonParseException | NullPointerException error) {
            throw new IllegalArgumentException("Invalid NPC task checkpoint", error);
        }
    }

    public static byte[] encodeCompressed(SchedulerState state) {
        String encoded = encode(state);
        byte[] source = encoded.getBytes(StandardCharsets.UTF_8);
        if (source.length > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("NPC task checkpoint is too large");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, 1024 * 1024));
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(source);
            }
            byte[] compressed = output.toByteArray();
            if (compressed.length > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("NPC task checkpoint is too large");
            }
            return compressed;
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to compress NPC task checkpoint", error);
        }
    }

    public static SchedulerState decodeCompressed(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return empty();
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("NPC task checkpoint is too large");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(compressed.length * 4, 1024 * 1024));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] chunk = new byte[8192];
                int total = 0;
                int read;
                while ((read = gzip.read(chunk)) >= 0) {
                    total += read;
                    if (total > MAX_SERIALIZED_BYTES) {
                        throw new IllegalArgumentException("NPC task checkpoint is too large");
                    }
                    output.write(chunk, 0, read);
                }
            }
            String encoded = decodeUtf8(output.toByteArray());
            return decode(encoded);
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid compressed NPC task checkpoint", error);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid UTF-8 NPC task checkpoint", error);
        }
    }

    public static SchedulerState empty() {
        return new SchedulerState(VERSION, "ready", null, List.of(), null);
    }

    private static SchedulerState validate(SchedulerState state) {
        if (state == null) throw new IllegalArgumentException("Missing NPC task checkpoint");
        if (state.version() != VERSION) throw new IllegalArgumentException("Unsupported NPC task checkpoint version " + state.version());
        String lifecycle = normalizeLifecycle(state.lifecycle());
        WorkState active = state.active() == null ? null : validateWork(state.active());
        List<WorkState> paused = state.paused() == null ? List.of() : state.paused();
        if (paused.size() > MAX_QUEUED_TASKS) throw new IllegalArgumentException("Too many queued NPC tasks");
        List<WorkState> safePaused = new ArrayList<>(paused.size());
        for (WorkState work : paused) safePaused.add(validateWork(work));
        WorkState recoverableBuild = state.recoverableBuild() == null
            ? null
            : validateWork(state.recoverableBuild());
        if (recoverableBuild != null && !"build".equals(recoverableBuild.kind())) {
            throw new IllegalArgumentException("Recoverable task checkpoint is not a build");
        }
        return new SchedulerState(VERSION, lifecycle, active, safePaused, recoverableBuild);
    }

    private static WorkState validateWork(WorkState work) {
        if (work == null || blank(work.id()) || blank(work.kind()) || work.spec() == null) {
            throw new IllegalArgumentException("NPC task checkpoint contains an incomplete task");
        }
        if (!work.spec().has("kind") || !work.kind().equals(work.spec().get("kind").getAsString())) {
            throw new IllegalArgumentException("NPC task kind does not match its specification");
        }
        if (work.id().length() > 256 || work.kind().length() > 64) {
            throw new IllegalArgumentException("NPC task identifier is too long");
        }
        int priority = Math.max(0, Math.min(1000, work.priority()));
        String reason = work.pauseReason() == null ? "" : trim(work.pauseReason(), 256);
        String stance = blank(work.resumeStance()) ? "FOLLOW" : work.resumeStance();
        JsonObject plan = work.plan() == null ? new JsonObject() : work.plan().deepCopy();
        JsonObject checkpoint = work.checkpoint() == null ? new JsonObject() : work.checkpoint().deepCopy();
        return new WorkState(work.id(), work.kind(), work.spec().deepCopy(), plan, stance, priority, reason, checkpoint);
    }

    private static String normalizeLifecycle(String lifecycle) {
        if ("downed".equals(lifecycle) || "recovering".equals(lifecycle)) return lifecycle;
        return "ready";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
