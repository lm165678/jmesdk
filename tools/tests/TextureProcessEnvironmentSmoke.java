import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Compare the same encoder child from JVMs with and without RenderDoc loaded. */
public final class TextureProcessEnvironmentSmoke {
    private static NativeLibrary renderdoc;
    public static void main(String[] args) throws Exception {
        if (!args[0].equals("off")) {
            renderdoc = NativeLibrary.getInstance("E:/RenderDoc/renderdoc.dll");
            PointerByReference api = new PointerByReference();
            if (renderdoc.getFunction("RENDERDOC_GetAPI").invokeInt(new Object[]{10400, api}) != 1) {
                throw new AssertionError("RenderDoc API unavailable");
            }
            Function get = Function.getFunction(api.getValue().getPointer(3L * Native.POINTER_SIZE));
            System.out.println("HookIntoChildren=" + get.invokeInt(new Object[]{7}));
            if (args[0].equals("nochildren")) {
                Function set = Function.getFunction(api.getValue().getPointer(Native.POINTER_SIZE));
                set.invokeInt(new Object[]{7, 0});
            }
        }
        Path executable = Path.of(args[1]).toAbsolutePath();
        Path input = Path.of(args[2]).toAbsolutePath();
        Path out = Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(out);
        int failed = 0;
        for (int i = 0; i < 5; i++) {
            Process process = new ProcessBuilder(executable.toString(), "-fd", "BC3", "-EncodeWith",
                    "CPU", "-miplevels", "11", input.toString(), out.resolve(args[0] + i + ".dds").toString())
                    .directory(executable.getParent().toFile()).redirectErrorStream(true)
                    .redirectOutput(out.resolve(args[0] + i + ".log").toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Timed out");
            }
            System.out.println(args[0] + " attempt=" + i + " exit=" + process.exitValue());
            if (process.exitValue() != 0) {
                failed++;
            }
        }
        System.out.println("RESULT failed=" + failed);
    }
}
