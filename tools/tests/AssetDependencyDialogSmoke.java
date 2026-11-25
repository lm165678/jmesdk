package com.jme3.gde.assetbrowser;

import com.jme3.gde.core.assets.ProjectAssetManager;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.openide.filesystems.*;

/** Real Swing dependency dialog and reveal-button regression without starting SDK. */
public final class AssetDependencyDialogSmoke {
    public static final class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(new org.netbeans.modules.openide.util.NbMutexEventProvider(),
                    AssetDependencyDialogSmoke.class.getClassLoader()),
                    org.openide.util.lookup.Lookups.metaInfServices(AssetDependencyDialogSmoke.class.getClassLoader()));
        }
    }
    private static Field field(String name) throws Exception {
        Field result = AssetContentPanel.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static JButton find(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && button.getText().equals(text)) {
                return button;
            }
            if (component instanceof Container container) {
                JButton result = find(container, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        Path disk = Files.createTempDirectory("dependency-dialog-");
        Files.createDirectories(disk.resolve("Shaders"));
        Files.writeString(disk.resolve("Shaders/main.frag"), "#import \"Shaders/helper.glsllib\"");
        Files.writeString(disk.resolve("Shaders/helper.glsllib"), "// helper");
        LocalFileSystem fs = new LocalFileSystem();
        fs.setRootDirectory(disk.toFile());
        FileObject root = fs.getRoot();
        var manager = new ProjectAssetManager(root);
        manager.registerLocator(disk.toString(), com.jme3.asset.plugins.FileLocator.class);
        AtomicReference<AssetContentPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                AssetContentPanel content = new AssetContentPanel();
                panel.set(content);
                field("manager").set(content, manager);
                field("active").set(content, true);
                field("thumbnails").set(content, new AssetThumbnails());
                Class<?> entry = Class.forName(AssetContentPanel.class.getName() + "$Entry");
                Constructor<?> constructor = entry.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object selected = constructor.newInstance(root.getFileObject("Shaders/main.frag"),
                        "Shaders/main.frag", AssetKind.SHADER);
                Method show = AssetContentPanel.class.getDeclaredMethod("showDependencies", entry);
                show.setAccessible(true);
                show.invoke(content, selected);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        long deadline = System.nanoTime() + 15_000_000_000L;
        AtomicReference<JDialog> dialog = new AtomicReference<>();
        AtomicReference<JButton> link = new AtomicReference<>();
        while (link.get() == null && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog candidate && candidate.isDisplayable()) {
                        JButton button = find(candidate, "Shaders/helper.glsllib");
                        if (button != null) {
                            dialog.set(candidate);
                            link.set(button);
                        }
                    }
                }
            });
            Thread.sleep(30);
        }
        if (link.get() == null) {
            throw new AssertionError("Dependency dialog did not publish the indirect reference");
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (!link.get().isEnabled() || link.get().getActionListeners().length == 0) {
                    throw new AssertionError("Referenced asset has no open action");
                }
                JButton reveal = find(dialog.get(), "\u2192");
                if (reveal == null || !reveal.isEnabled()) {
                    throw new AssertionError("Reveal action unavailable");
                }
                reveal.doClick();
                JList<?> grid = (JList<?>) field("grid").get(panel.get());
                if (!"Shaders".equals(field("directory").get(panel.get())) || grid.getSelectedValue() == null
                        || !grid.getSelectedValue().toString().equals("helper.glsllib")) {
                    throw new AssertionError("Arrow failed to select the referenced asset");
                }
                panel.get().stop();
                if (dialog.get().isDisplayable()) {
                    throw new AssertionError("Stopping browser leaked a dependency dialog");
                }
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        System.out.println("ASSET_DEPENDENCY_DIALOG_PASS (real dialog, open wiring, reveal, cleanup)");
        System.exit(0);
    }
}
