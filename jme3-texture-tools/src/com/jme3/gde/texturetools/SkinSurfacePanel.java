package com.jme3.gde.texturetools;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.skin.SkinSurfaceBaker;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import javax.swing.text.Document;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

/** Offline UV0 surface baking; no edits to the model or live render scene.
 * 第一步：选择源 J3O 和目标皮肤材质，填写实际毫米/世界单位及厚度范围。
 * 第二步：Bake 在后台加载独立模型，生成厚度和焊接后平滑曲率。
 * 第三步：Export 写入线性 DDS 并自动绑定两张图、开启薄表面透射。
 * 未命中出口的开放表面按最大厚度处理；需要封闭、无重叠 UV 的模型才能可靠烘焙。
 * 使用的是模型保存的姿态；动画和按光向实时厚度不在此离线方案内。
 */
final class SkinSurfacePanel extends JPanel {
    private final JTextField root = new JTextField();
    private final JTextField model = new JTextField();
    private final JTextField material = new JTextField();
    private final JTextField key = new JTextField("Textures/Skin/SkinSurface");
    private final JComboBox<Integer> size = new JComboBox<>(new Integer[]{128, 256, 512, 1024});
    private final JSpinner units = new JSpinner(new SpinnerNumberModel(50.0, .001, 100000.0, 1.0));
    private final JSpinner maximum = new JSpinner(new SpinnerNumberModel(30.0, .001, 1000.0, 1.0));
    private final JSpinner padding = new JSpinner(new SpinnerNumberModel(8, 0, 64, 1));
    private final JButton bake = new JButton("Bake surface maps");
    private final JButton export = new JButton("Export and assign...");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel status = new JLabel("Choose a saved J3O using the target skin material.");
    private final JLabel preview = new JLabel("Thickness preview", JLabel.CENTER);
    private SwingWorker<?, ?> worker;
    private SkinSurfaceBaker.Result result;
    private float bakedUnits;
    private float bakedMaximum;
    private String bakedMaterial;
    private Path bakedRoot;
    private boolean publishing;
    private boolean closed;

    SkinSurfacePanel(Document assetRoot, Document materialKey) {
        super(new BorderLayout(8, 8));
        root.setDocument(assetRoot);
        material.setDocument(materialKey);
        model.setName("skin.surface.model");
        bake.setName("skin.surface.bake");
        export.setName("skin.surface.export");
        size.setSelectedItem(512);
        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 8));
        fields.add(new JLabel("Project assets folder"));
        fields.add(root);
        fields.add(new JLabel("Saved J3O model (asset key)"));
        JPanel modelRow = new JPanel(new BorderLayout(4, 0));
        modelRow.add(model, BorderLayout.CENTER);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseModel());
        modelRow.add(browse, BorderLayout.EAST);
        fields.add(modelRow);
        fields.add(new JLabel("Target skin material (from Normal tab)"));
        fields.add(material);
        fields.add(new JLabel("Resolution"));
        fields.add(size);
        fields.add(new JLabel("Millimeters per world unit"));
        fields.add(units);
        fields.add(new JLabel("Maximum normal thickness (mm)"));
        fields.add(maximum);
        fields.add(new JLabel("UV padding (texels)"));
        fields.add(padding);
        fields.add(new JLabel("Output key (no extension)"));
        fields.add(key);
        JPanel inputs = new JPanel(new BorderLayout());
        inputs.add(fields, BorderLayout.NORTH);
        inputs.add(new JLabel("<html>Linear R: white = maximum thickness, black = thin.<br>Alpha: resolved exit coverage; open/out-of-range regions stay opaque.<br>Thickness uses a conservative ray cone and a smooth connected mesh field.<br>Save changed material assignments before baking the model.<br>Export enables transmission; turn SkinTransmission off to compare.</html>"), BorderLayout.SOUTH);
        units.setToolTipText("Actual size in millimeters / model size in world units; not ear thickness.");
        maximum.setToolTipText("Thickness search range in millimeters. No exit in range means opaque, not a thin surface.");
        add(inputs, BorderLayout.WEST);
        add(preview, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        actions.add(bake);
        actions.add(export);
        actions.add(cancel);
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        bake.addActionListener(event -> bake());
        export.addActionListener(event -> export());
        cancel.addActionListener(event -> {
            if (worker != null && !publishing) {
                worker.cancel(true);
            }
        });
        export.setEnabled(false);
        cancel.setEnabled(false);
    }

    private void chooseModel() {
        var bridge = Lookup.getDefault().lookup(SkinAssetSelection.class);
        Path selected = bridge == null ? null : bridge.chooseModel(this);
        if (selected != null) {
            Path assets = bridge.projectForAsset(selected);
            if (assets != null) {
                root.setText(assets.toString());
                model.setText(assets.relativize(selected).toString().replace('\\', '/'));
            }
        }
    }

    private void bake() {
        if (worker != null || closed) {
            return;
        }
        final Path assets;
        final String target = material.getText().trim();
        final String source = model.getText().trim();
        final int resolution = (Integer) size.getSelectedItem();
        final float mm;
        final float range;
        try {
            units.commitEdit();
            maximum.commitEdit();
            assets = Path.of(root.getText()).toAbsolutePath().normalize();
            SkinNormalAssets.targetMaterial(assets, target);
            if (!Files.isRegularFile(assets.resolve(source))) {
                throw new IllegalArgumentException("Choose a saved model in the project");
            }
            mm = ((Number) units.getValue()).floatValue();
            range = ((Number) maximum.getValue()).floatValue();
        } catch (Exception ex) {
            status.setText("Cannot bake: " + ex.getMessage());
            return;
        }
        busy(true);
        status.setText("Baking UV0 thickness and smooth curvature on CPU...");
        worker = new SwingWorker<SkinSurfaceBaker.Result, Void>() {
            protected SkinSurfaceBaker.Result doInBackground() {
                var manager = new DesktopAssetManager(true);
                manager.registerLocator(assets.toString(), FileLocator.class);
                return SkinSurfaceBaker.bake(manager.loadModel(source), target, resolution, mm, range);
            }
            protected void done() {
                try {
                    if (closed) {
                        return;
                    }
                    result = get();
                    bakedRoot = assets;
                    bakedMaterial = target;
                    bakedUnits = mm;
                    bakedMaximum = range;
                    var image = new java.awt.image.BufferedImage(result.size, result.size, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < result.size; y++) {
                        for (int x = 0; x < result.size; x++) {
                            int pixel = y * result.size + x;
                            int value = Math.round(Math.max(0, Math.min(1, result.thickness[pixel] / range)) * 255);
                            image.setRGB(x, result.size - 1 - y, (value << 16) | (value << 8) | value);
                        }
                    }
                    preview.setIcon(new ImageIcon(image));
                    preview.setText("");
                    status.setText("Baked " + resolution + "x" + resolution + "; opaque/unresolved exits: " + result.missingExits
                            + "; overlapping UV samples: " + result.overlappingTexels);
                } catch (Exception ex) {
                    result = null;
                    status.setText("Bake stopped: " + (ex.getCause() == null ? ex : ex.getCause()));
                } finally {
                    worker = null;
                    if (!closed) {
                        busy(false);
                    }
                }
            }
        };
        worker.execute();
    }

    private void export() {
        if (result == null || worker != null || closed) {
            return;
        }
        if (!bakedRoot.equals(Path.of(root.getText()).toAbsolutePath().normalize())
                || !bakedMaterial.equals(material.getText().trim())) {
            status.setText("Destination changed; bake again for the selected model/material.");
            return;
        }
        final String output = key.getText().trim();
        final int border = ((Number) padding.getValue()).intValue();
        boolean exists = Files.exists(SkinLutExport.targets(bakedRoot, output).get(2));
        if (exists && JOptionPane.showConfirmDialog(this, "Replace generated surface maps?", "Existing maps",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        publishing = true;
        busy(true);
        final Path exportRoot = bakedRoot;
        final String exportMaterial = bakedMaterial;
        worker = new SwingWorker<SurfaceExport, Void>() {
            protected SurfaceExport doInBackground() throws Exception {
                String text = Files.readString(SkinNormalAssets.targetMaterial(bakedRoot, bakedMaterial));
                var matcher = java.util.regex.Pattern.compile("SkinMaxCurvature\\s*:\\s*([0-9.eE+-]+)").matcher(text);
                float curvature = matcher.find() ? Float.parseFloat(matcher.group(1)) : 1f;
                List<Path> paths = SkinSurfaceAssets.write(exportRoot, output, exportMaterial, result, bakedUnits, bakedMaximum,
                        curvature, border, exists);
                return new SurfaceExport(paths, Files.readString(SkinNormalAssets.targetMaterial(exportRoot, exportMaterial)));
            }
            protected void done() {
                try {
                    SurfaceExport published = get();
                    FileUtil.refreshFor(published.paths.stream().map(Path::toFile).toArray(java.io.File[]::new));
                    SkinAssetSelection bridge = Lookup.getDefault().lookup(SkinAssetSelection.class);
                    if (bridge != null) {
                        bridge.surfaceMaterialExported(exportRoot, exportMaterial);
                    }
                    SkinMaterialUpdates.publish(exportRoot.resolve(exportMaterial), published.materialText);
                    if (!closed) {
                        status.setText("Assigned thickness + curvature to " + bakedMaterial + "; SkinTransmission enabled.");
                    }
                } catch (Exception ex) {
                    if (!closed) {
                        status.setText("Export failed: " + ex.getMessage());
                    }
                } finally {
                    publishing = false;
                    worker = null;
                    if (!closed) {
                        busy(false);
                    }
                }
            }
        };
        worker.execute();
    }

    private static final class SurfaceExport {
        final List<Path> paths;
        final String materialText;

        SurfaceExport(List<Path> paths, String materialText) {
            this.paths = paths;
            this.materialText = materialText;
        }
    }

    private void busy(boolean value) {
        bake.setEnabled(!value);
        export.setEnabled(!value && result != null);
        cancel.setEnabled(value && !publishing);
        model.setEnabled(!value);
        material.setEnabled(!value);
        root.setEnabled(!value);
    }

    void close() {
        closed = true;
        if (worker != null && !publishing) {
            worker.cancel(true);
        }
    }
}
