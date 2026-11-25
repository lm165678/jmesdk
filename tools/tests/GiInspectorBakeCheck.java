package com.jme3.gde.core.sceneexplorer.nodes.actions.impl;

import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.lookup.*;

/** Native AWT regression for closing and recreating a volume inspector during a bake. */
public final class GiInspectorBakeCheck {
    private static JFrame frame;
    private static JPanel holder;
    private static JPanel inspector;
    private static SceneApplication app;
    private static DataObject file;
    private static GiProbeVolume volume;
    private static GiProbeVolume other;
    private static Map<GiProbeVolume, BakeGiProbeVolumeAction.BakeSession> bakes;

    public static class TestLookup extends ProxyLookup {
        public TestLookup() {
            super(Lookups.fixed(GiInspectorBakeCheck.class.getClassLoader()),
                    Lookups.metaInfServices(GiInspectorBakeCheck.class.getClassLoader()));
        }
    }

    public static void main(String[] args) {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        try {
            run(Path.of(args[0]).toAbsolutePath());
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void run(Path scratch) throws Exception {
        var field = BakeGiProbeVolumeAction.class.getDeclaredField("BAKES");
        field.setAccessible(true);
        bakes = (Map<GiProbeVolume, BakeGiProbeVolumeAction.BakeSession>) field.get(null);
        Path work = Files.createTempDirectory(scratch, "scene-");
        Path disk = Files.createFile(work.resolve("scene.dat"));
        FileUtil.refreshFor(work.toFile());
        var folder = FileUtil.toFileObject(FileUtil.normalizeFile(work.toFile()));
        file = DataObject.find(folder.getFileObject(disk.getFileName().toString()));
        var manager = new ProjectAssetManager(folder);
        app = SceneApplication.getApplication();
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("GI inspector test");
            frame.add(app.getMainPanel(), BorderLayout.CENTER);
            holder = new JPanel(new BorderLayout());
            frame.add(holder, BorderLayout.EAST);
            frame.setSize(760, 620);
            frame.setLocation(-10000, -10000);
            frame.setVisible(true);
        });
        app.enqueue(() -> {
            Node scene = new Node("Bake scene");
            volume = new GiProbeVolume();
            volume.setName("Fixture volume");
            volume.setCoverage(new Vector3f(8, 8, 8));
            volume.setProbeCounts(new Vector3f(4, 4, 4));
            scene.attachChild(volume);
            other = new GiProbeVolume();
            other.setName("Queued volume");
            other.setCoverage(new Vector3f(8, 8, 8));
            other.setProbeCounts(new Vector3f(4, 4, 4));
            other.setLocalTranslation(2, 0, 0);
            scene.attachChild(other);
            var floor = new Geometry("Other object", new Box(3, .1f, 3));
            floor.setLocalTranslation(0, 1, 0);
            floor.setMobility(com.jme3.scene.Mobility.Static);
            floor.setUserData(com.jme3.gde.core.assets.LightmapUvGenerator.RESOLUTION_KEY, 64);
            floor.setMaterial(new com.jme3.material.Material(app.getAssetManager(), "Common/MatDefs/Light/PBRLighting.j3md"));
            scene.attachChild(floor);
            scene.updateGeometricState();
            var request = new SceneRequest(new Object(), scene, manager);
            request.setDataObject(file);
            request.getJmeNode().setReadOnly(false);
            var current = SceneApplication.class.getDeclaredField("currentSceneRequest");
            current.setAccessible(true);
            current.set(app, request);
            return null;
        }).get(30, TimeUnit.SECONDS);
        verifyLightmapMipPanel();
        verifyWorldSettingsPanel();
        showVolume(volume);
        waitFor(() -> button(inspector, "Cancel") != null && startButton().isEnabled(), "First inspector initialization");
        SwingUtilities.invokeAndWait(() -> {
            JCheckBox seams = seamOption();
            require(seams.isSelected(), "Lightmap seam smoothing must default on");
            seams.doClick(0);
        });
        showVolume(volume);
        waitFor(() -> startButton().isEnabled(), "Seam settings restored");
        SwingUtilities.invokeAndWait(() -> require(!seamOption().isSelected(), "Reopened panel lost seam setting"));
        var first = startAndHide(volume);
        showVolume(other);
        waitFor(() -> startButton().isEnabled(), "Second volume inspector initialization");
        SwingUtilities.invokeAndWait(() -> require(seamOption().isSelected(), "Seam setting leaked across volumes"));
        var queued = startAndHide(other);
        require(queued.message().startsWith("Waiting"), "Second volume did not queue: " + queued.message());
        waitFor(() -> !first.isRunning(), "Bake while hidden");
        require(first.message().startsWith("Bake complete. Saved"), first.message());
        waitFor(() -> !queued.isRunning(), "Queued bake did not advance");
        require(queued.message().startsWith("Bake complete. Saved"), queued.message());
        require(first.message().contains("Lightmap seam smoothing: off."), first.message());
        require(queued.message().contains("Lightmap seam smoothing: on."), queued.message());
        try (var revisions = Files.list(work.resolve("GI/Streaming"))) {
            require(revisions.filter(Files::isDirectory).count() == 2, "Expected separate fine brick asset revisions");
        }
        var previous = app.enqueue(() -> {
            require(volume.getStreamingData() != null && other.getStreamingData() != null
                    && volume.getStreamingData() != other.getStreamingData(), "Overlapping volumes lost their distinct bake directories");
            var bytes = new java.io.ByteArrayOutputStream();
            new com.jme3.export.binary.BinaryExporter().save(app.getCurrentSceneRequest().getRootNode(), bytes);
            var importer = new com.jme3.export.binary.BinaryImporter();
            importer.setAssetManager(app.getAssetManager());
            var restored = (Node) importer.load(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            restored.updateGeometricState();
            require(((GiProbeVolume) restored.getChild("Fixture volume")).getStreamingData() != null
                    && ((GiProbeVolume) restored.getChild("Queued volume")).getStreamingData() != null,
                    "Round trip lost an overlapping bake directory");
            require(Boolean.FALSE.equals(restored.getChild("Fixture volume").getUserData("jme.sdk.giBake.smoothLightmapSeams")),
                    "J3O round trip lost the seam smoothing setting");
            var world = restored.getControl(com.jme3.post.WorldSettings.class);
            require(world.isLightmapMipEnabled(), "Round trip lost the Lightmap mipmaps switch");
            var settings = world.getGiStreaming();
            require(settings.getMaxFineProbes() == 2048 && settings.getPreloadDistance() == 32
                    && settings.getAtlasMiB() == 32, "Round trip lost the SceneComposer settings");
            return volume.getStreamingData();
        }).get(10, TimeUnit.SECONDS);
        showVolume(volume);
        waitFor(() -> startButton().isEnabled(), "Reopened inspector initialization");
        var second = startAndHide(volume);
        showVolume(other);
        waitFor(() -> startButton().isEnabled(), "Queued-cancel inspector initialization");
        var discarded = startAndHide(other);
        require(discarded.message().startsWith("Waiting"), "Cancelable job was not queued");
        showVolume(other);
        SwingUtilities.invokeAndWait(() -> button(inspector, "Cancel").doClick(0));
        require("Queued bake cancelled.".equals(discarded.message()) && second.isRunning(),
                "Cancel queue item affected the running bake");
        waitFor(() -> second.message().startsWith("Completed probe passes:"), "Progress while hidden");
        showVolume(volume);
        SwingUtilities.invokeAndWait(() -> {
            JButton cancel = button(inspector, "Cancel");
            require(cancel.isEnabled() && !startButton().isEnabled(), "Reopened panel did not attach to bake");
            require(label(inspector, "Completed probe passes:"), "No resumed progress in reopened panel");
            cancel.doClick(0);
        });
        waitFor(() -> !second.isRunning(), "Cancel on reopened panel");
        require(second.message().startsWith("Cancelled"), second.message());
        require(app.enqueue(() -> volume.getStreamingData() == previous).get(10, TimeUnit.SECONDS),
                "Cancellation replaced prior bake");
        waitFor(() -> startButton().isEnabled() && !button(inspector, "Cancel").isEnabled(), "Inspector did not reset");
        System.out.println("GI_INSPECTOR_BACKGROUND_PROGRESS_QUEUE_CANCEL_PASS");
    }

    private static void verifyLightmapMipPanel() throws Exception {
        Class<?> type = Class.forName("com.jme3.gde.scenecomposer.EditorLightmapPanel");
        var constructor = type.getDeclaredConstructor(Runnable.class);
        constructor.setAccessible(true);
        var clear = new java.util.concurrent.atomic.AtomicBoolean();
        var option = new java.util.concurrent.atomic.AtomicReference<JCheckBox>();
        for (boolean initial : new boolean[]{false, true, false}) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JPanel panel = (JPanel) constructor.newInstance((Runnable) () -> clear.set(true));
                    option.set(button(panel, "Use Lightmap mipmaps", JCheckBox.class));
                    require(option.get() != null, "No Lightmap mipmaps checkbox");
                    holder.removeAll();
                    holder.add(panel);
                    holder.revalidate();
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException(error);
                }
            });
            waitFor(() -> option.get().isEnabled(), "Lightmap settings panel did not load");
            SwingUtilities.invokeAndWait(() -> {
                require(option.get().isSelected() == initial, "Lightmap mipmaps checkbox did not restore its state");
                option.get().doClick(0);
            });
            require(app.enqueue(() -> app.getCurrentSceneRequest().getRootNode()
                    .getControl(com.jme3.post.WorldSettings.class).isLightmapMipEnabled() != initial)
                    .get(10, TimeUnit.SECONDS), "Lightmap mipmaps toggle did not reach the active scene");
        }
        require(!clear.get(), "Mipmap toggle cleared the Lightmap bindings");
        SwingUtilities.invokeAndWait(() -> require(file.isModified(), "Changed mipmaps preference was not marked for save"));
        System.out.println("LIGHTMAP_MIP_INSPECTOR_SWITCH_PASS");
    }

    private static void verifyWorldSettingsPanel() throws Exception {
        var constructor = Class.forName("com.jme3.gde.scenecomposer.EditorGiStreamingPanel").getDeclaredConstructor();
        constructor.setAccessible(true);
        var view = new java.util.concurrent.atomic.AtomicReference<JPanel>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                JPanel panel = (JPanel) constructor.newInstance();
                view.set(panel);
                holder.removeAll();
                holder.add(panel);
                holder.revalidate();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
        waitFor(() -> button(view.get(), "Apply").isEnabled(), "SceneComposer World Settings panel did not load");
        SwingUtilities.invokeAndWait(() -> {
            var inputs = java.util.Arrays.stream(view.get().getComponents())
                    .filter(JSpinner.class::isInstance).map(JSpinner.class::cast).toList();
            inputs.get(0).setValue(32d);
            inputs.get(1).setValue(2048);
            inputs.get(2).setValue(32);
            button(view.get(), "Apply").doClick(0);
        });
        require(app.enqueue(() -> app.getCurrentSceneRequest().getRootNode()
                .getControl(com.jme3.post.WorldSettings.class).getGiStreaming().getMaxFineProbes() == 2048)
                .get(10, TimeUnit.SECONDS), "World Settings did not reach the scene");
    }

    private static void showVolume(GiProbeVolume target) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            inspector = new BakeGiProbeVolumeAction().createPanel(target, file);
            holder.removeAll();
            holder.add(new JScrollPane(inspector));
            holder.revalidate();
            holder.repaint();
        });
    }

    private static BakeGiProbeVolumeAction.BakeSession startAndHide(GiProbeVolume target) throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<BakeGiProbeVolumeAction.BakeSession>();
        SwingUtilities.invokeAndWait(() -> {
            startButton().doClick(0);
            captured.set(bakes.get(target));
            require(captured.get() != null && captured.get().isRunning(), "Bake not started");
            holder.removeAll();
            holder.add(new JLabel("Other object selected"));
            holder.revalidate();
            holder.repaint();
        });
        return captured.get();
    }

    private static void waitFor(BooleanSupplier predicate, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> ready.set(predicate.getAsBoolean()));
            if (ready.get()) {
                return;
            }
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(failure);
    }

    private static JButton startButton() {
        return button(inspector, com.jme3.gde.core.util.EditorMessages.text("GiBakeApply"));
    }

    private static JCheckBox seamOption() {
        JCheckBox option = button(inspector, "Smooth Lightmap seams", JCheckBox.class);
        require(option != null, "Missing Lightmap seam smoothing option");
        return option;
    }

    private static JButton button(Container parent, String title) {
        return button(parent, title, JButton.class);
    }

    private static <T extends AbstractButton> T button(Container parent, String title, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && title.equals(type.cast(child).getText())) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T match = button(container, title, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static boolean label(Container parent, String prefix) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label && label.getText().startsWith(prefix)) {
                return true;
            }
            if (child instanceof Container nested && label(nested, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String text) {
        if (!condition) {
            throw new AssertionError(text);
        }
    }
}
