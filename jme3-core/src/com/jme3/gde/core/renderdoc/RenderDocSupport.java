package com.jme3.gde.core.renderdoc;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;
import java.util.prefs.Preferences;
import javax.swing.*;
import org.openide.*;
import org.openide.modules.Places;
import org.openide.util.*;

/**
 * Built-in Windows/WGL RenderDoc integration. Automatic injection defaults to
 * enabled, but does nothing until qrenderdoc.exe and adjacent renderdoc.dll exist.
 * The library is loaded once before graphics initialization and never unloaded.
 * Disabling injection hides capture immediately; restart removes the hooks.
 * Captures persist in the SDK user directory under renderdoc-captures.
 * <pre>
 * 第一步：打开 Tools > Options > RenderDoc，保留自动注入勾选。
 * 第二步：选择 qrenderdoc.exe，按回车保存并确认重启 SDK。
 * 第三步：打开场景，点击 3D Viewer 顶部相机按钮；捕获后自动打开 .rdc。
 * 第四步：集成可用时预览自动开启 GraphicsDebug，使 FrameGraph 的 ViewPort/Pass 分组显示在 RenderDoc 中。
 * 第五步：取消自动注入并应用可隐藏按钮；重启后恢复无注入、无调试分组的运行。
 * </pre>
 * UI belongs to EDT; begin/end belong to the render thread with current HGLRC.
 * GraphicsDebug is enabled before preview context creation only when this
 * integration is available; injection alone does not emit engine markers.
 */
public final class RenderDocSupport {
    private static final Logger LOG = Logger.getLogger(RenderDocSupport.class.getName());
    static final Preferences PREFS = NbPreferences.forModule(RenderDocSupport.class);
    private static final AtomicBoolean REQUEST = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static boolean initialized;
    private static NativeLibrary library;
    private static NativeLibrary gl;
    private static volatile Pointer api;
    private static volatile String loadedPath;
    private static volatile String status = "Restart SDK to apply RenderDoc settings.";
    private static Pointer device;
    private static int previousCount;

    private RenderDocSupport() {
    }

    static String path() {
        return PREFS.get("executable", "");
    }

    static boolean automatic() {
        return PREFS.getBoolean("autoInject", true);
    }

    static String validate(String value) {
        if (!Platform.isWindows()) {
            return "Automatic injection currently supports Windows OpenGL only.";
        }
        if (value.trim().isEmpty()) {
            return "Select qrenderdoc.exe to enable capture.";
        }
        File exe = new File(value.trim());
        if (!exe.isFile() || !exe.getName().equalsIgnoreCase("qrenderdoc.exe")) {
            return "Select an existing qrenderdoc.exe.";
        }
        if (!new File(exe.getParentFile(), "renderdoc.dll").isFile()) {
            return "renderdoc.dll must be beside qrenderdoc.exe.";
        }
        return null;
    }

    /** Called once before the SDK creates graphics contexts, never from Options. */
    public static synchronized void initializeBeforeGraphics() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!automatic()) {
            status = "Automatic injection is disabled.";
            return;
        }
        String executable = path();
        String error = validate(executable);
        if (error != null) {
            status = error;
            return;
        }
        try {
            library = NativeLibrary.getInstance(new File(new File(executable).getParentFile(),
                    "renderdoc.dll").getAbsolutePath());
            PointerByReference result = new PointerByReference();
            int ok = library.getFunction("RENDERDOC_GetAPI").invokeInt(new Object[]{10400, result});
            if (ok != 1 || result.getValue() == null) {
                throw new IllegalStateException("RenderDoc API 1.4.0 is unavailable.");
            }
            api = result.getValue();
            // The JVM owns crash reporting; RenderDoc must not replace its handler.
            function(10).invokeVoid(new Object[]{});
            gl = NativeLibrary.getInstance("opengl32");
            // Explicit SDK captures only; avoid overlapping global hotkey captures.
            function(5).invokeVoid(new Object[]{null, 0});
            function(6).invokeVoid(new Object[]{null, 0});
            loadedPath = executable;
            status = "RenderDoc connected. Captures open automatically.";
        } catch (RuntimeException | LinkageError ex) {
            api = null;
            status = "RenderDoc could not load: " + ex.getMessage();
            LOG.log(Level.WARNING, status, ex);
        }
    }

    // Stable ABI slots from renderdoc_app.h API 1.4.0: cdecl, native pointer stride.
    private static Function function(int slot) {
        return Function.getFunction(api.getPointer((long) slot * Native.POINTER_SIZE),
                Function.C_CONVENTION, "UTF-8");
    }

    public static boolean available() {
        return api != null && automatic() && path().equals(loadedPath) && validate(path()) == null;
    }

    static String status() {
        String error = validate(path());
        return error == null ? status : error;
    }

    /**
     * EDT-only; preference changes update visibility without late injection.
     * Retain the returned button when replacing the toolbar layout.
     * <pre>
     * // 第一步：安装截帧按钮。
     * JButton capture = RenderDocSupport.installButton(toolbar);
     * // 第二步：溢出布局重建工具栏时，将同一个按钮加入控件组。
     * OverflowToolbar.install(toolbar, new Component[]{capture, otherButton});
     * </pre>
     */
    public static JButton installButton(JToolBar toolbar) {
        JButton button = new JButton(new CaptureIcon());
        button.setToolTipText("Capture frame with RenderDoc");
        button.getAccessibleContext().setAccessibleName("Capture frame with RenderDoc");
        button.setFocusable(false);
        button.addActionListener(event -> {
            if (available() && BUSY.compareAndSet(false, true)) {
                REQUEST.set(true);
            }
        });
        toolbar.add(button);
        Runnable refresh = () -> {
            button.setVisible(available());
            button.setEnabled(!BUSY.get());
            toolbar.revalidate();
        };
        Timer timer = new Timer(500, event -> refresh.run());
        button.addHierarchyListener(event -> {
            if (button.isDisplayable()) {
                timer.start();
            } else {
                timer.stop();
            }
        });
        refresh.run();
        return button;
    }

    /** Render-thread only; returns whether this call owns a capture. */
    public static boolean beginFrame() {
        if (!REQUEST.getAndSet(false)) {
            return false;
        }
        try {
            if (!available()) {
                throw new IllegalStateException("RenderDoc settings changed. Restart SDK.");
            }
            if (function(20).invokeInt(new Object[]{}) != 0) {
                throw new IllegalStateException("Another RenderDoc capture is already running.");
            }
            device = gl.getFunction("wglGetCurrentContext", Function.ALT_CONVENTION)
                    .invokePointer(new Object[]{});
            if (device == null) {
                throw new IllegalStateException("No current WGL context in 3D Viewer.");
            }
            File directory = new File(Places.getUserDirectory(), "renderdoc-captures");
            function(11).invokeVoid(new Object[]{new File(directory,
                    "viewer-" + System.currentTimeMillis()).getAbsolutePath()});
            previousCount = function(13).invokeInt(new Object[]{});
            function(19).invokeVoid(new Object[]{device, null});
            if (function(20).invokeInt(new Object[]{}) == 0) {
                throw new IllegalStateException("RenderDoc did not start capturing this GL context.");
            }
            return true;
        } catch (RuntimeException | LinkageError ex) {
            BUSY.set(false);
            report(ex);
            return false;
        }
    }

    /** Always paired in finally; failed renders are discarded. */
    public static void endFrame(boolean success) {
        try {
            if (!success) {
                function(24).invokeInt(new Object[]{device, null});
                report(new IllegalStateException("Capture discarded because preview rendering failed. Check the first rendering error in the SDK log."));
                return;
            }
            if (function(21).invokeInt(new Object[]{device, null}) != 1) {
                throw new IllegalStateException("RenderDoc failed to save the frame.");
            }
            int count = function(13).invokeInt(new Object[]{});
            if (count <= previousCount) {
                throw new IllegalStateException("RenderDoc returned no new capture.");
            }
            IntByReference length = new IntByReference();
            function(14).invokeInt(new Object[]{count - 1, null, length, null});
            if (length.getValue() <= 0 || length.getValue() > 1048576) {
                throw new IllegalStateException("Invalid capture filename length.");
            }
            Memory name = new Memory(length.getValue() + 1L);
            if (function(14).invokeInt(new Object[]{count - 1, name, length, null}) != 1) {
                throw new IllegalStateException("Cannot read the capture filename.");
            }
            String capture = name.getString(0, "UTF-8");
            String executable = loadedPath;
            RequestProcessor.getDefault().post(() -> {
                try {
                    if (!Files.isRegularFile(Paths.get(capture))) {
                        throw new IllegalStateException("Capture file is missing: " + capture);
                    }
                    File launchLog = new File(new File(capture).getParentFile(), "replay-launch.log");
                    Process replay = new ProcessBuilder(executable, capture)
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog)).start();
                    if (replay.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && replay.exitValue() != 0) {
                        throw new IllegalStateException("RenderDoc exited with code " + replay.exitValue()
                                + ". Capture saved at " + capture + "; see " + launchLog);
                    }
                } catch (Exception ex) {
                    report(ex);
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            report(ex);
        } finally {
            device = null;
            BUSY.set(false);
        }
    }

    static void report(Throwable ex) {
        LOG.log(Level.WARNING, "RenderDoc", ex);
        SwingUtilities.invokeLater(() -> DialogDisplayer.getDefault().notify(
                new NotifyDescriptor.Message("RenderDoc: " + ex.getMessage(),
                        NotifyDescriptor.ERROR_MESSAGE)));
    }
}
