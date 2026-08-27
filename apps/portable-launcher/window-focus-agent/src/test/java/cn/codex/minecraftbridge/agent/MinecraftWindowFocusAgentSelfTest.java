package cn.codex.minecraftbridge.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class MinecraftWindowFocusAgentSelfTest {
    private MinecraftWindowFocusAgentSelfTest() {
    }

    public static void main(String[] arguments) {
        byte[] fixture = createGlfwFixture();
        if (MinecraftWindowFocusAgent.transformClassBytes("example/Unrelated", fixture) != null) {
            throw new AssertionError("Unrelated classes must remain untouched");
        }

        assertFocusPolicy(fixture, "synthetic GLFW fixture");
        if (arguments.length != 2) {
            throw new AssertionError("Expected pinned LWJGL 3.3.1 and 3.3.3 JAR paths");
        }
        assertFocusPolicy(readGlfwClass(Path.of(arguments[0])), "LWJGL 3.3.1");
        assertFocusPolicy(readGlfwClass(Path.of(arguments[1])), "LWJGL 3.3.3");
        System.out.println("window-focus-agent-self-test: ok (synthetic, LWJGL 3.3.1, LWJGL 3.3.3)");
    }

    private static void assertFocusPolicy(byte[] original, String label) {
        MinecraftWindowFocusAgent.InstrumentationSummary summary =
                MinecraftWindowFocusAgent.inspectClassBytes(original);
        if (!summary.isSupported()) {
            throw new AssertionError(label + " is not supported: " + summary.describe());
        }
        byte[] transformed = MinecraftWindowFocusAgent.transformClassBytes(
                MinecraftWindowFocusAgent.TARGET_CLASS,
                original
        );
        assertVerifierAccepts(transformed, label);
        int[] hintCalls = {0};
        int[] showGuardCalls = {0};
        int[] nativeWindowCalls = {0};
        int[] getWindowStyleCalls = {0};
        int[] setWindowStyleCalls = {0};
        int[] focusedConstants = {0};
        int[] focusOnShowConstants = {0};
        int[] noActivateConstants = {0};
        int[] extendedStyleConstants = {0};
        int[] styleOrOperations = {0};
        int[] focusMethodCalls = {0};
        int[] focusMethodReturns = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                boolean showWindow = "glfwShowWindow".equals(name) && "(J)V".equals(descriptor);
                boolean focusWindow = "glfwFocusWindow".equals(name) && "(J)V".equals(descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (Integer.valueOf(MinecraftWindowFocusAgent.GLFW_FOCUSED).equals(value)) {
                            focusedConstants[0]++;
                        }
                        if (Integer.valueOf(MinecraftWindowFocusAgent.GLFW_FOCUS_ON_SHOW).equals(value)) {
                            focusOnShowConstants[0]++;
                        }
                        if (Long.valueOf(MinecraftWindowFocusAgent.WS_EX_NOACTIVATE).equals(value)) {
                            noActivateConstants[0]++;
                        }
                        if (Integer.valueOf(MinecraftWindowFocusAgent.GWL_EXSTYLE).equals(value)) {
                            extendedStyleConstants[0]++;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (focusWindow && opcode == Opcodes.RETURN) focusMethodReturns[0]++;
                        if (showWindow && opcode == Opcodes.LOR) styleOrOperations[0]++;
                    }

                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String invokedName,
                            String invokedDescriptor,
                            boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && MinecraftWindowFocusAgent.TARGET_INTERNAL_NAME.equals(owner)
                                && "glfwWindowHint".equals(invokedName)
                                && "(II)V".equals(invokedDescriptor)) {
                            hintCalls[0]++;
                        }
                        if (showWindow
                                && opcode == Opcodes.INVOKESTATIC
                                && MinecraftWindowFocusAgent.TARGET_INTERNAL_NAME.equals(owner)
                                && "glfwSetWindowAttrib".equals(invokedName)
                                && "(JII)V".equals(invokedDescriptor)) {
                            showGuardCalls[0]++;
                        }
                        if (showWindow
                                && opcode == Opcodes.INVOKESTATIC
                                && MinecraftWindowFocusAgent.GLFW_NATIVE_WIN32_INTERNAL_NAME.equals(owner)
                                && "glfwGetWin32Window".equals(invokedName)
                                && "(J)J".equals(invokedDescriptor)) {
                            nativeWindowCalls[0]++;
                        }
                        if (showWindow
                                && opcode == Opcodes.INVOKESTATIC
                                && MinecraftWindowFocusAgent.USER32_INTERNAL_NAME.equals(owner)
                                && "GetWindowLongPtr".equals(invokedName)
                                && "(JI)J".equals(invokedDescriptor)) {
                            getWindowStyleCalls[0]++;
                        }
                        if (showWindow
                                && opcode == Opcodes.INVOKESTATIC
                                && MinecraftWindowFocusAgent.USER32_INTERNAL_NAME.equals(owner)
                                && "SetWindowLongPtr".equals(invokedName)
                                && "(JIJ)J".equals(invokedDescriptor)) {
                            setWindowStyleCalls[0]++;
                        }
                        if (focusWindow) focusMethodCalls[0]++;
                    }
                };
            }
        }, 0);

        assertEquals(4, hintCalls[0], label + ": both create-window overloads must receive both no-focus hints");
        assertEquals(1, showGuardCalls[0], label + ": show must reassert the in-memory no-focus flag");
        assertEquals(1, nativeWindowCalls[0], label + ": show must resolve the native Win32 window once");
        assertEquals(1, getWindowStyleCalls[0], label + ": show must preserve the original extended style");
        assertEquals(2, setWindowStyleCalls[0], label + ": show must apply and restore the no-activate style");
        assertEquals(2, focusedConstants[0], label + ": GLFW_FOCUSED must be disabled for both overloads");
        assertEquals(3, focusOnShowConstants[0], label + ": create and show must disable GLFW_FOCUS_ON_SHOW");
        assertEquals(1, noActivateConstants[0], label + ": show must apply WS_EX_NOACTIVATE");
        assertEquals(3, extendedStyleConstants[0], label + ": show must read, update, and restore GWL_EXSTYLE");
        assertEquals(1, styleOrOperations[0], label + ": no-activate must preserve all existing style bits");
        assertEquals(0, focusMethodCalls[0], label + ": explicit focus must not invoke native code");
        assertEquals(1, focusMethodReturns[0], label + ": explicit focus must return immediately");
    }

    private static byte[] createGlfwFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MinecraftWindowFocusAgent.TARGET_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null
        );
        MethodVisitor hint = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glfwWindowHint",
                "(II)V",
                null,
                null
        );
        hint.visitCode();
        hint.visitInsn(Opcodes.RETURN);
        hint.visitMaxs(0, 2);
        hint.visitEnd();
        MethodVisitor attribute = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glfwSetWindowAttrib",
                "(JII)V",
                null,
                null
        );
        attribute.visitCode();
        attribute.visitInsn(Opcodes.RETURN);
        attribute.visitMaxs(0, 4);
        attribute.visitEnd();
        addShowWindow(writer);
        addNativeDelegate(writer, "glfwFocusWindow", "nativeFocusWindow");
        addCreateWindow(writer, "(IILjava/nio/ByteBuffer;JJ)J");
        addCreateWindow(writer, "(IILjava/lang/CharSequence;JJ)J");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addShowWindow(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glfwShowWindow",
                "(J)V",
                null,
                null
        );
        Label unchecked = new Label();
        method.visitCode();
        method.visitInsn(Opcodes.LCONST_0);
        method.visitVarInsn(Opcodes.LSTORE, 2);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitJumpInsn(Opcodes.IFEQ, unchecked);
        method.visitVarInsn(Opcodes.LLOAD, 0);
        method.visitInsn(Opcodes.POP2);
        method.visitLabel(unchecked);
        method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {Opcodes.LONG}, 0, null);
        method.visitVarInsn(Opcodes.LLOAD, 0);
        method.visitVarInsn(Opcodes.LLOAD, 2);
        method.visitInsn(Opcodes.POP2);
        method.visitInsn(Opcodes.POP2);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 4);
        method.visitEnd();
    }

    private static void assertVerifierAccepts(byte[] transformed, String label) {
        try {
            new ClassLoader(MinecraftWindowFocusAgentSelfTest.class.getClassLoader()) {
                void verify(byte[] bytes) {
                    String binaryName = MinecraftWindowFocusAgent.TARGET_CLASS.replace('/', '.');
                    Class<?> defined = defineClass(binaryName, bytes, 0, bytes.length);
                    resolveClass(defined);
                }
            }.verify(transformed);
        } catch (LinkageError error) {
            throw new AssertionError(label + " failed JVM bytecode verification", error);
        }
    }

    private static void addNativeDelegate(ClassWriter writer, String name, String target) {
        MethodVisitor nativeTarget = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                target,
                "(J)V",
                null,
                null
        );
        nativeTarget.visitCode();
        nativeTarget.visitInsn(Opcodes.RETURN);
        nativeTarget.visitMaxs(0, 2);
        nativeTarget.visitEnd();

        MethodVisitor wrapper = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name,
                "(J)V",
                null,
                null
        );
        wrapper.visitCode();
        wrapper.visitVarInsn(Opcodes.LLOAD, 0);
        wrapper.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                MinecraftWindowFocusAgent.TARGET_INTERNAL_NAME,
                target,
                "(J)V",
                false
        );
        wrapper.visitInsn(Opcodes.RETURN);
        wrapper.visitMaxs(2, 2);
        wrapper.visitEnd();
    }

    private static void addCreateWindow(ClassWriter writer, String descriptor) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glfwCreateWindow",
                descriptor,
                null,
                null
        );
        method.visitCode();
        method.visitInsn(Opcodes.LCONST_0);
        method.visitInsn(Opcodes.LRETURN);
        method.visitMaxs(2, 7);
        method.visitEnd();
    }

    private static byte[] readGlfwClass(Path jarPath) {
        try (ZipFile archive = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = archive.getEntry("org/lwjgl/glfw/GLFW.class");
            if (entry == null) throw new AssertionError("GLFW.class is missing from " + jarPath);
            return archive.getInputStream(entry).readAllBytes();
        } catch (IOException error) {
            throw new AssertionError("Unable to read pinned LWJGL fixture " + jarPath, error);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
