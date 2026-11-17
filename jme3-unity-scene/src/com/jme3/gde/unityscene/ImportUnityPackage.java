package com.jme3.gde.unityscene;

import com.jme3.gde.core.assets.SpatialAssetDataObject;
import com.jme3.gde.core.assets.actions.ConvertModel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;

/** Restores assets off EDT, then delegates optional J3O conversion to the existing workflow. */
@ActionID(category = "jMonkeyPlatform", id = "com.jme3.gde.unityscene.ImportUnityPackage")
@ActionRegistration(displayName = "#CTL_ImportUnityPackage")
@ActionReference(path = "Loaders/application/x-unity-package/Actions", position = 100)
@Messages("CTL_ImportUnityPackage=Import Unity Package...")
public final class ImportUnityPackage implements ActionListener {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final UnityPackageDataObject context;

    public ImportUnityPackage(UnityPackageDataObject context) {
        this.context = context;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (!BUSY.compareAndSet(false, true)) {
            JOptionPane.showMessageDialog(null, "A Unity package import is already running.");
            return;
        }
        RequestProcessor.getDefault().post(() -> {
            try {
                var file = FileUtil.toFile(context.getPrimaryFile());
                if (file == null) {
                    throw new java.io.IOException("Import requires a local project asset file.");
                }
                Path root = UnityPackageImporter.unpack(file.toPath());
                FileUtil.refreshFor(root.getParent().toFile());
                List<SpatialAssetDataObject> scenes = new ArrayList<>();
                try (var files = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                        String name = path.getFileName().toString();
                        if (name.endsWith(".unity") || name.endsWith(".prefab")) {
                            var object = FileUtil.toFileObject(path.toFile());
                            if (object != null && DataObject.find(object) instanceof SpatialAssetDataObject) {
                                scenes.add((SpatialAssetDataObject) DataObject.find(object));
                            }
                        }
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    String message = "Imported into:\n" + root + "\n";
                    if (scenes.isEmpty()) {
                        JOptionPane.showMessageDialog(null, message
                                + "No .unity/.prefab files found. Convert supported models individually.");
                    } else if (JOptionPane.showConfirmDialog(null, message + scenes.size()
                            + " scenes/prefabs found. Convert them to J3O now?\n"
                            + "Choose No to select individual files in the project tree.",
                            "Unity package imported", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        new ConvertModel(scenes).actionPerformed(event);
                    }
                });
            } catch (Exception ex) {
                Logger.getLogger(ImportUnityPackage.class.getName()).log(Level.WARNING, "Unity package import failed", ex);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Unity package import failed: " + ex.getMessage(), "Unity import", JOptionPane.ERROR_MESSAGE));
            } finally {
                BUSY.set(false);
            }
        });
    }
}
