package com.jme3.gde.core.renderdoc;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.awt.*;
import com.jme3.gde.core.scene.PreviewPanelsContext;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Isolated userdir/preferences: never changes the running SDK's settings. */
public final class RenderDocSmoke extends SimpleApplication {
    public static final class Dialogs extends org.openide.DialogDisplayer {
        static int confirmations;
        public Object notify(org.openide.NotifyDescriptor descriptor) {
            if (descriptor instanceof org.openide.NotifyDescriptor.Confirmation) {
                confirmations++;
                return org.openide.NotifyDescriptor.NO_OPTION;
            }
            throw new AssertionError("Unexpected dialog: " + descriptor.getMessage());
        }
        public java.awt.Dialog createDialog(org.openide.DialogDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }
    }
    public static final class TestPreferencesFactory implements java.util.prefs.PreferencesFactory {
        private final java.util.prefs.Preferences root = new MemoryPreferences(null, "");
        public java.util.prefs.Preferences userRoot() {
            return root;
        }
        public java.util.prefs.Preferences systemRoot() {
            return root;
        }
    }

    private static final class MemoryPreferences extends java.util.prefs.AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        MemoryPreferences(java.util.prefs.AbstractPreferences parent, String name) {
            super(parent, name);
        }
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }
        protected String getSpi(String key) {
            return values.get(key);
        }
        protected void removeSpi(String key) {
            values.remove(key);
        }
        protected void removeNodeSpi() {
            values.clear();
        }
        protected String[] keysSpi() {
            return values.keySet().toArray(new String[0]);
        }
        protected String[] childrenNamesSpi() {
            return new String[0];
        }
        protected java.util.prefs.AbstractPreferences childSpi(String name) {
            return new MemoryPreferences(this, name);
        }
        protected void syncSpi() {
        }
        protected void flushSpi() {
        }
    }
    private final CompletableFuture<Void> captured = new CompletableFuture<>();
    private int frames;

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        Geometry cube = new Geometry("RenderDoc verification cube", new Box(1, 1, 1));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.Orange);
        cube.setMaterial(material);
        rootNode.attachChild(cube);
        cam.setLocation(new com.jme3.math.Vector3f(0, 0, 5));
    }

    @Override
    public void update() {
        boolean capture = RenderDocSupport.beginFrame();
        boolean success = false;
        try {
            super.update();
            success = true;
        } finally {
            if (capture) {
                RenderDocSupport.endFrame(success);
                captured.complete(null);
            }
        }
        frames++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", TestPreferencesFactory.class.getName());
        Path exe = Paths.get(args[0]);
        if (RenderDocSupport.validate(exe.toString()) != null) {
            throw new AssertionError(RenderDocSupport.validate(exe.toString()));
        }
        if (RenderDocSupport.validate(exe.resolveSibling("missing.exe").toString()) == null) {
            throw new AssertionError("Missing executable accepted");
        }
        RenderDocSupport.PREFS.clear();
        if (!RenderDocSupport.automatic()) {
            throw new AssertionError("Automatic injection must default on");
        }
        RenderDocSupport.PREFS.put("executable", exe.toString());
        RenderDocSupport.PREFS.putBoolean("autoInject", true);
        RenderDocSupport.initializeBeforeGraphics();
        if (!RenderDocSupport.available()) {
            throw new AssertionError(RenderDocSupport.status());
        }
        JToolBar toolbar = new JToolBar();
        SwingUtilities.invokeAndWait(() -> {
            RenderDocOptions options = new RenderDocOptions();
            var registration = RenderDocOptions.class.getAnnotation(
                    org.netbeans.spi.options.OptionsPanelController.TopLevelRegistration.class);
            if (!registration.iconBase().equals("com/jme3/gde/core/renderdoc/renderdoc-48.png")
                    || RenderDocOptions.class.getResource("/" + registration.iconBase()) == null) {
                throw new AssertionError("RenderDoc options must use the bundled RenderDoc icon");
            }
            options.update();
            if (options.isChanged()) {
                throw new AssertionError("Loaded options must be clean");
            }
            options.applyChanges();
            try {
                var field = RenderDocOptions.class.getDeclaredField("executable");
                field.setAccessible(true);
                JTextField path = (JTextField) field.get(options);
                path.setText("missing.exe");
                if (!options.isChanged()) {
                    throw new AssertionError("Path edit must mark settings dirty");
                }
                options.cancel();
                if (!path.getText().equals(exe.toString())) {
                    throw new AssertionError("Cancel must restore saved path");
                }
                path.postActionEvent();
                if (Dialogs.confirmations != 1 || !RenderDocSupport.path().equals(exe.toString())) {
                    throw new AssertionError("Enter must save and prompt for restart");
                }
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
            RenderDocSupport.installButton(toolbar);
            if (!toolbar.getComponent(0).isVisible()) {
                throw new AssertionError("Connected capture button hidden");
            }
        });
        RenderDocSupport.PREFS.putBoolean("autoInject", false);
        if (RenderDocSupport.available()) {
            throw new AssertionError("Disabled injection exposes capture");
        }
        RenderDocSupport.PREFS.putBoolean("autoInject", true);
        RenderDocSupport.PREFS.put("executable", "missing.exe");
        if (RenderDocSupport.available()) {
            throw new AssertionError("Changed invalid path exposes capture");
        }
        RenderDocSupport.PREFS.put("executable", exe.toString());
        System.out.println("RENDERDOC_SETTINGS_AND_NATIVE_API_PASS");
        RenderDocSmoke app = new RenderDocSmoke();
        com.jme3.gde.core.scene.SdkNativeLibraries.configure();
        AppSettings settings = new AppSettings(true);
        settings.setGraphicsDebug(RenderDocSupport.available());
        if (!settings.isGraphicsDebug()) {
            throw new AssertionError("RenderDoc verification must enable FrameGraph debug groups");
        }
        settings.setCustomRenderer(PreviewPanelsContext.class);
        settings.setWidth(400);
        settings.setHeight(300);
        settings.setFrameRate(30);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        JFrame[] window = new JFrame[1];
        try {
            app.start();
            app.enqueue(() -> null).get(30, TimeUnit.SECONDS);
            AwtPanel panel = ((AwtPanelsContext) app.getContext()).createPanel(PaintMode.Accelerated, true);
            SwingUtilities.invokeAndWait(() -> {
                window[0] = new JFrame("RenderDoc capture verification");
                window[0].add(panel);
                window[0].setSize(400, 300);
                window[0].setVisible(true);
            });
            app.enqueue(() -> {
                panel.attachTo(true, app.getViewPort());
                return null;
            }).get(30, TimeUnit.SECONDS);
            Thread.sleep(2000);
            SwingUtilities.invokeAndWait(() -> ((JButton) toolbar.getComponent(0)).doClick());
            app.captured.get(30, TimeUnit.SECONDS);
            Path captures = Paths.get(System.getProperty("netbeans.user"), "renderdoc-captures");
            try (var paths = Files.list(captures)) {
                Path capture = paths.filter(p -> p.toString().endsWith(".rdc"))
                        .max(java.util.Comparator.comparing(Path::toString)).orElseThrow();
                if (Files.size(capture) < 1024) {
                    throw new AssertionError("Empty capture");
                }
                System.out.println("RENDERDOC_AWT_CAPTURE_PASS " + capture);
            }
            Thread.sleep(5000); // Give the background replay launcher time to start before exiting.
            boolean replay = ProcessHandle.allProcesses().anyMatch(p -> p.info().command()
                    .map(c -> c.toLowerCase(java.util.Locale.ROOT).endsWith("qrenderdoc.exe")).orElse(false));
            if (!replay) {
                throw new AssertionError("Replay process was not observed");
            }
            System.out.println("RENDERDOC_REPLAY_PROCESS_PASS");
        } catch (Throwable ex) {
            ex.printStackTrace();
            System.exit(1);
        } finally {
            if (app.getContext() != null) {
                app.stop(true);
            }
            SwingUtilities.invokeAndWait(() -> {
                if (window[0] != null) {
                    window[0].dispose();
                }
            });
        }
        System.exit(0);
    }
}
