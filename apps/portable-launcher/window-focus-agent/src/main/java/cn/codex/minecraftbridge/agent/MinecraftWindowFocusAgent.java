package cn.codex.minecraftbridge.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.Base64;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Prevents GLFW windows created by the HMCL-launched game JVM from taking
 * foreground focus. The agent applies no-focus creation hints, reasserts the
 * in-memory GLFW focus-on-show flag immediately before a window is shown,
 * temporarily applies the Win32 no-activate extended style while the native
 * show call runs, and suppresses explicit GLFW focus requests. It never calls
 * native activation, hierarchy, keyboard, mouse, or cursor APIs.
 */
public final class MinecraftWindowFocusAgent {
    static final String ENABLE_PROPERTY = "mc.codex.preventWindowFocus";
    static final String AUDIT_PROPERTY = "mc.codex.windowFocusAudit";
    static final String POLICY_VERSION = "glfw-create-show-noactivate-and-explicit-focus-v3";
    static final String TARGET_CLASS = "org/lwjgl/glfw/GLFW";
    static final String TARGET_INTERNAL_NAME = "org/lwjgl/glfw/GLFW";
    static final String GLFW_NATIVE_WIN32_INTERNAL_NAME = "org/lwjgl/glfw/GLFWNativeWin32";
    static final String USER32_INTERNAL_NAME = "org/lwjgl/system/windows/User32";
    static final int GLFW_FOCUSED = 131073;
    static final int GLFW_FOCUS_ON_SHOW = 131084;
    static final int GWL_EXSTYLE = -20;
    static final long WS_EX_NOACTIVATE = 0x08000000L;
    private static final String CREATE_WINDOW_BYTE_BUFFER = "(IILjava/nio/ByteBuffer;JJ)J";
    private static final String CREATE_WINDOW_CHAR_SEQUENCE = "(IILjava/lang/CharSequence;JJ)J";
    private static final String WINDOW_HANDLE_VOID = "(J)V";
    private static final int SHOW_GUARD_HWND_LOCAL = 4;
    private static final int SHOW_GUARD_STYLE_LOCAL = 6;

    private MinecraftWindowFocusAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"))) {
            return;
        }
        try {
            instrumentation.addTransformer(new FocusPolicyTransformer(), false);
            writeAudit("ready", null, InstrumentationSummary.empty(), null);
        } catch (Throwable error) {
            writeAudit("registration-error", null, InstrumentationSummary.empty(), error);
            throw new IllegalStateException("Unable to register the Minecraft no-focus startup policy", error);
        }
    }

    static byte[] transformClassBytes(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className) || classBytes == null) {
            return null;
        }

        InstrumentationSummary summary = inspectClassBytes(classBytes);
        if (!summary.isSupported()) {
            throw new IllegalStateException(
                    "Required GLFW focus-policy methods were not found: " + summary.describe()
            );
        }

        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                boolean createWindow = "glfwCreateWindow".equals(name)
                        && (CREATE_WINDOW_BYTE_BUFFER.equals(descriptor)
                        || CREATE_WINDOW_CHAR_SEQUENCE.equals(descriptor));
                boolean showWindow = "glfwShowWindow".equals(name) && WINDOW_HANDLE_VOID.equals(descriptor);
                boolean focusWindow = "glfwFocusWindow".equals(name) && WINDOW_HANDLE_VOID.equals(descriptor);
                if (focusWindow) {
                    MethodVisitor replacement = super.visitMethod(access, name, descriptor, signature, exceptions);
                    replacement.visitCode();
                    replacement.visitInsn(Opcodes.RETURN);
                    replacement.visitMaxs(0, 2);
                    replacement.visitEnd();
                    return null;
                }

                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!createWindow && !showWindow) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (createWindow) {
                            emitDisabledHint(this, GLFW_FOCUSED);
                            emitDisabledHint(this, GLFW_FOCUS_ON_SHOW);
                        } else {
                            emitShowTimeFocusGuard(this);
                            emitWin32NoActivateGuard(this);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (showWindow && opcode == Opcodes.RETURN) {
                            emitWin32NoActivateRestore(this);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void emitDisabledHint(MethodVisitor visitor, int hint) {
        visitor.visitLdcInsn(hint);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TARGET_INTERNAL_NAME,
                "glfwWindowHint",
                "(II)V",
                false
        );
    }

    private static void emitShowTimeFocusGuard(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.LLOAD, 0);
        visitor.visitLdcInsn(GLFW_FOCUS_ON_SHOW);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TARGET_INTERNAL_NAME,
                "glfwSetWindowAttrib",
                "(JII)V",
                false
        );
    }

    private static void emitWin32NoActivateGuard(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.LLOAD, 0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                GLFW_NATIVE_WIN32_INTERNAL_NAME,
                "glfwGetWin32Window",
                "(J)J",
                false
        );
        visitor.visitVarInsn(Opcodes.LSTORE, SHOW_GUARD_HWND_LOCAL);

        visitor.visitVarInsn(Opcodes.LLOAD, SHOW_GUARD_HWND_LOCAL);
        visitor.visitLdcInsn(GWL_EXSTYLE);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                USER32_INTERNAL_NAME,
                "GetWindowLongPtr",
                "(JI)J",
                false
        );
        visitor.visitVarInsn(Opcodes.LSTORE, SHOW_GUARD_STYLE_LOCAL);

        visitor.visitVarInsn(Opcodes.LLOAD, SHOW_GUARD_HWND_LOCAL);
        visitor.visitLdcInsn(GWL_EXSTYLE);
        visitor.visitVarInsn(Opcodes.LLOAD, SHOW_GUARD_STYLE_LOCAL);
        visitor.visitLdcInsn(WS_EX_NOACTIVATE);
        visitor.visitInsn(Opcodes.LOR);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                USER32_INTERNAL_NAME,
                "SetWindowLongPtr",
                "(JIJ)J",
                false
        );
        visitor.visitInsn(Opcodes.POP2);
    }

    private static void emitWin32NoActivateRestore(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.LLOAD, SHOW_GUARD_HWND_LOCAL);
        visitor.visitLdcInsn(GWL_EXSTYLE);
        visitor.visitVarInsn(Opcodes.LLOAD, SHOW_GUARD_STYLE_LOCAL);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                USER32_INTERNAL_NAME,
                "SetWindowLongPtr",
                "(JIJ)J",
                false
        );
        visitor.visitInsn(Opcodes.POP2);
    }

    private static final class FocusPolicyTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
        ) throws IllegalClassFormatException {
            if (!TARGET_CLASS.equals(className)) {
                return null;
            }
            try {
                byte[] transformed = transformClassBytes(className, classfileBuffer);
                writeAudit("instrumented", className, inspectClassBytes(classfileBuffer), null);
                return transformed;
            } catch (Throwable error) {
                writeAudit("instrumentation-error", className, InstrumentationSummary.empty(), error);
                IllegalClassFormatException failure = new IllegalClassFormatException(
                        "Unable to apply the Minecraft no-focus startup policy"
                );
                failure.initCause(error);
                throw failure;
            }
        }
    }

    static InstrumentationSummary inspectClassBytes(byte[] classBytes) {
        int[] createCount = {0};
        int[] showCount = {0};
        int[] focusCount = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if ("glfwCreateWindow".equals(name)
                        && (CREATE_WINDOW_BYTE_BUFFER.equals(descriptor)
                        || CREATE_WINDOW_CHAR_SEQUENCE.equals(descriptor))) {
                    createCount[0]++;
                } else if ("glfwShowWindow".equals(name) && WINDOW_HANDLE_VOID.equals(descriptor)) {
                    showCount[0]++;
                } else if ("glfwFocusWindow".equals(name) && WINDOW_HANDLE_VOID.equals(descriptor)) {
                    focusCount[0]++;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new InstrumentationSummary(createCount[0], showCount[0], focusCount[0]);
    }

    static final class InstrumentationSummary {
        final int createMethodCount;
        final int showMethodCount;
        final int focusMethodCount;

        InstrumentationSummary(int createMethodCount, int showMethodCount, int focusMethodCount) {
            this.createMethodCount = createMethodCount;
            this.showMethodCount = showMethodCount;
            this.focusMethodCount = focusMethodCount;
        }

        static InstrumentationSummary empty() {
            return new InstrumentationSummary(0, 0, 0);
        }

        boolean isSupported() {
            return createMethodCount >= 2 && showMethodCount >= 1 && focusMethodCount >= 1;
        }

        String describe() {
            return "create=" + createMethodCount + ",show=" + showMethodCount + ",focus=" + focusMethodCount;
        }
    }

    private static void writeAudit(
            String event,
            String className,
            InstrumentationSummary summary,
            Throwable error
    ) {
        Path auditPath = auditPath();
        if (auditPath == null) {
            return;
        }
        String line = "{\"event\":\"" + json(event)
                + "\",\"pid\":" + ProcessHandle.current().pid()
                + ",\"timestamp\":\"" + Instant.now()
                + "\",\"class\":" + (className == null ? "null" : "\"" + json(className) + "\"")
                + ",\"methodCount\":" + summary.createMethodCount
                + ",\"createMethodCount\":" + summary.createMethodCount
                + ",\"showMethodCount\":" + summary.showMethodCount
                + ",\"focusMethodCount\":" + summary.focusMethodCount
                + ",\"noActivateStyleGuard\":true"
                + ",\"policyVersion\":\"" + POLICY_VERSION + "\""
                + ",\"error\":" + (error == null ? "null" : "\"" + json(error.getClass().getSimpleName()) + "\"")
                + "}\n";
        try {
            Files.writeString(
                    auditPath,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Audit output is diagnostic. The bytecode policy itself remains fail closed.
        }
    }

    private static Path auditPath() {
        String encoded = System.getProperty(AUDIT_PROPERTY, "").trim();
        if (encoded.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            Path path = Path.of(decoded);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
