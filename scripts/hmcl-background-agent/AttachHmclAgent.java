import com.sun.tools.attach.VirtualMachine;

public final class AttachHmclAgent {
    private AttachHmclAgent() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected: <pid> <agent-jar> <status-file>");
        }

        VirtualMachine machine = VirtualMachine.attach(arguments[0]);
        try {
            machine.loadAgent(arguments[1], arguments[2]);
        } finally {
            machine.detach();
        }
    }
}
