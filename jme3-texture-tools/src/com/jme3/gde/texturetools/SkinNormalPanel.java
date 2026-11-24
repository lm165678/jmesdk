package com.jme3.gde.texturetools;

import com.jme3.material.skin.SkinNormalFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.filesystems.FileUtil;

/** CPU normal-map filtering UI. Swing state belongs to EDT, I/O/convolution to workers. */
final class SkinNormalPanel extends JPanel {
    private final Callable<SkinGenerationSettings> settings;
    private final Supplier<String> profileName;
    private final JTextField source = new JTextField();
    private final JComboBox<Integer> resolution = new JComboBox<>(new Integer[]{256, 512, 1024, 2048});
    private final JSpinner density = new JSpinner(new SpinnerNumberModel(4.0, .01, 100.0, .5));
    private final JComboBox<String> quality = new JComboBox<>(new String[]{"Exact Gaussian", "Fast Gaussian approximation"});
    private final JComboBox<SkinNormalFilter.Boundary> boundary = new JComboBox<>(SkinNormalFilter.Boundary.values());
    private final JComboBox<String> convention = new JComboBox<>(new String[]{"OpenGL (+Y)", "DirectX (-Y)"});
    private final JComboBox<String> outputMode = new JComboBox<>(new String[]{"Two normals: detail + diffuse", "Four normals: detail + R/G/B"});
    private final JComboBox<String> channel = new JComboBox<>(new String[]{"Detailed input", "Smooth basis", "Diffuse red", "Diffuse green", "Diffuse blue"});
    private final JComboBox<String> previewMode = new JComboBox<>(new String[]{"Normal RGB", "Diffuse lighting", "RGB diffuse lighting"});
    private final JSlider lightAngle = new JSlider(-180, 180, 30);
    private final JTextField root = new JTextField();
    private final JTextField key = new JTextField("Textures/Skin/SkinNormals");
    private final JButton generate = new JButton("Generate");
    private final JButton cancel = new JButton("Cancel");
    private final JButton export = new JButton("Export assets...");
    private final JLabel profile = new JLabel();
    private final JLabel status = new JLabel("Choose an RGB normal map. Profile is shared with the LUT tab.");
    private final JProgressBar progress = new JProgressBar();
    private final SkinTexturePreview preview = new SkinTexturePreview();
    private final JPanel input = new JPanel(new GridLayout(0, 2, 6, 8));
    private SkinNormalAssets.Product product;
    private SwingWorker<?, ?> worker;
    private SwingWorker<BufferedImage, Void> previewWorker;
    private int previewRevision;
    private boolean busy;
    private boolean closed;
    private boolean publishing;
    private int revision;

    SkinNormalPanel(Callable<SkinGenerationSettings> settings, Supplier<String> profileName) {
        super(new BorderLayout(8, 8));
        this.settings = settings;
        this.profileName = profileName;
        setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        source.setName("skin.normal.source");
        root.setName("skin.normal.root");
        generate.setName("skin.normal.generate");
        export.setName("skin.normal.export");
        preview.setName("skin.normal.preview");
        resolution.setSelectedItem(512);
        JPanel sourceRow = new JPanel(new BorderLayout(4, 4));
        sourceRow.add(source, BorderLayout.CENTER);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseSource());
        sourceRow.add(browse, BorderLayout.EAST);
        addRow("Source normal PNG / JPEG", sourceRow);
        addRow("Diffuse output maximum size", resolution);
        addRow("Source texels per millimeter", density);
        addRow("Filter quality", quality);
        addRow("Image boundary", boundary);
        addRow("Normal convention", convention);
        addRow("Output mode", outputMode);
        JPanel controls = new JPanel(new BorderLayout());
        controls.add(input, BorderLayout.NORTH);
        JPanel notes = new JPanel(new GridLayout(0, 1));
        notes.add(new JLabel("<html>Uses the LUT tab's RGB diffusion profile.<br>Density assumes locally uniform UV scale.<br>UV islands need padding; this tool does not unwrap meshes.<br>Two-normal mode uses the filtered red normal as its smooth basis.<br>Four-normal mode binds each filtered RGB normal directly.</html>"));
        controls.add(notes, BorderLayout.CENTER);
        JPanel display = new JPanel(new BorderLayout(4, 4));
        JPanel previewOptions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        previewOptions.add(channel);
        previewOptions.add(previewMode);
        JButton fit = new JButton("Fit");
        fit.addActionListener(event -> preview.fit());
        previewOptions.add(fit);
        display.add(previewOptions, BorderLayout.NORTH);
        display.add(preview, BorderLayout.CENTER);
        JPanel light = new JPanel(new BorderLayout());
        light.add(new JLabel("Preview light angle"), BorderLayout.WEST);
        light.add(lightAngle, BorderLayout.CENTER);
        display.add(light, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, controls, display);
        split.setDividerLocation(400);
        split.setResizeWeight(.4);
        add(split, BorderLayout.CENTER);
        profile.setText("Profile: " + profileName.get());
        add(profile, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel paths = new JPanel(new GridLayout(2, 2, 6, 4));
        paths.add(new JLabel("Project assets folder"));
        JPanel rootRow = new JPanel(new BorderLayout(4, 4));
        rootRow.add(root, BorderLayout.CENTER);
        JButton chooseRoot = new JButton("Browse...");
        chooseRoot.addActionListener(event -> chooseRoot());
        rootRow.add(chooseRoot, BorderLayout.EAST);
        paths.add(rootRow);
        paths.add(new JLabel("Output asset key (no extension)"));
        paths.add(key);
        bottom.add(paths, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(generate);
        actions.add(cancel);
        actions.add(export);
        progress.setPreferredSize(new java.awt.Dimension(140, 16));
        actions.add(progress);
        bottom.add(actions, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        generate.addActionListener(event -> generate());
        cancel.addActionListener(event -> {
            if (worker != null && !publishing) {
                worker.cancel(true);
            }
        });
        export.addActionListener(event -> export());
        channel.addActionListener(event -> showPreview());
        previewMode.addActionListener(event -> showPreview());
        lightAngle.addChangeListener(event -> showPreview());
        source.getDocument().addDocumentListener(changed(this::inputsChanged));
        root.getDocument().addDocumentListener(changed(this::updateExport));
        key.getDocument().addDocumentListener(changed(this::updateExport));
        resolution.addActionListener(event -> inputsChanged());
        quality.addActionListener(event -> inputsChanged());
        boundary.addActionListener(event -> inputsChanged());
        density.addChangeListener(event -> inputsChanged());
        convention.addActionListener(event -> showPreview());
        outputMode.addActionListener(event -> showPreview());
        setBusy(false);
    }

    private void addRow(String text, java.awt.Component component) {
        input.add(new JLabel(text));
        input.add(component);
    }

    private static DocumentListener changed(Runnable task) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                task.run();
            }
            @Override
            public void removeUpdate(DocumentEvent event) {
                task.run();
            }
            @Override
            public void changedUpdate(DocumentEvent event) {
                task.run();
            }
        };
    }

    private void chooseSource() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a tangent-space RGB normal map");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Normal PNG / JPEG", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            source.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select the project assets root");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            root.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    void profileChanged() {
        profile.setText("Profile: " + profileName.get());
        inputsChanged();
    }

    private void inputsChanged() {
        revision++;
        product = null;
        previewRevision++;
        if (previewWorker != null) {
            previewWorker.cancel(true);
        }
        if (worker != null && busy && !publishing) {
            worker.cancel(true);
        }
        status.setText("Inputs changed. Generate normals using the shared LUT profile.");
        updateExport();
    }

    private void generate() {
        if (busy || closed) {
            return;
        }
        final Path path;
        final SkinGenerationSettings snapshot;
        final float texels;
        try {
            path = Path.of(source.getText().trim());
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Choose an existing normal map");
            }
            density.commitEdit();
            texels = ((Number) density.getValue()).floatValue();
            snapshot = settings.call();
        } catch (Exception ex) {
            status.setText("Cannot generate: " + ex.getMessage());
            return;
        }
        int version = revision;
        int size = (Integer) resolution.getSelectedItem();
        SkinNormalFilter.Boundary edge = (SkinNormalFilter.Boundary) boundary.getSelectedItem();
        SkinNormalFilter.Quality mode = quality.getSelectedIndex() == 0 ? SkinNormalFilter.Quality.EXACT : SkinNormalFilter.Quality.FAST;
        setBusy(true);
        status.setText("Filtering vector normals and generating matching LUT on CPU...");
        worker = new SwingWorker<SkinNormalAssets.Product, Void>() {
            @Override
            protected SkinNormalAssets.Product doInBackground() throws Exception {
                return SkinNormalAssets.generate(path, snapshot, size, texels, edge, mode);
            }
            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    SkinNormalAssets.Product value = get();
                    if (version == revision) {
                        product = value;
                        status.setText(value.normals.getWidth() + " × " + value.normals.getHeight()
                                + " | " + mode + " | effective density " + value.effectiveDensity + " texels/mm");
                        showPreview();
                    }
                } catch (Exception ex) {
                    product = null;
                    status.setText("Filtering stopped: " + cause(ex));
                } finally {
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void export() {
        if (product == null || busy || closed) {
            return;
        }
        final Path assetRoot;
        final String output = key.getText().trim();
        final boolean replace;
        try {
            assetRoot = Path.of(root.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(assetRoot)) {
                throw new IllegalArgumentException("Select an existing project assets folder");
            }
            List<Path> targets = SkinLutExport.targets(assetRoot, output);
            boolean exists = targets.stream().anyMatch(Files::exists);
            replace = exists && JOptionPane.showConfirmDialog(this, "Replace generated skin assets?",
                    "Existing skin assets", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            if (exists && !replace) {
                return;
            }
        } catch (Exception ex) {
            status.setText("Cannot export: " + ex.getMessage());
            return;
        }
        SkinNormalAssets.Product snapshot = product;
        boolean four = outputMode.getSelectedIndex() == 1;
        boolean directX = convention.getSelectedIndex() == 1;
        publishing = true;
        setBusy(true);
        status.setText("Writing normal maps, matching LUT and material...");
        worker = new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return SkinNormalAssets.write(assetRoot, output, snapshot, four, directX, replace);
            }
            @Override
            protected void done() {
                publishing = false;
                try {
                    List<Path> paths = get();
                    FileUtil.refreshFor(paths.stream().map(Path::toFile).toArray(java.io.File[]::new));
                    if (!closed) {
                        status.setText("Exported " + output + ".j3m with " + (four ? "four" : "two") + " normals and matching LUT");
                    }
                } catch (Exception ex) {
                    if (!closed) {
                        status.setText("Export failed: " + cause(ex));
                    }
                } finally {
                    if (!closed) {
                        setBusy(false);
                    }
                }
            }
        };
        worker.execute();
    }

    private void showPreview() {
        if (product == null) {
            return;
        }
        SkinNormalFilter.Result normals = product.normals;
        int selected = channel.getSelectedIndex();
        int mode = previewMode.getSelectedIndex();
        channel.setEnabled(mode != 2);
        double angle = Math.toRadians(lightAngle.getValue());
        boolean directX = convention.getSelectedIndex() == 1;
        boolean fourNormals = outputMode.getSelectedIndex() == 1;
        int version = ++previewRevision;
        if (previewWorker != null) {
            previewWorker.cancel(true);
        }
        previewWorker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return renderPreview(normals, selected, mode, angle, directX, fourNormals);
            }
            @Override
            protected void done() {
                if (!closed && version == previewRevision && !isCancelled()) {
                    try {
                        preview.setImage(get());
                    } catch (Exception ex) {
                        status.setText("Preview failed: " + cause(ex));
                    }
                }
            }
        };
        previewWorker.execute();
    }

    private static BufferedImage renderPreview(SkinNormalFilter.Result normals, int selected, int mode,
            double angle, boolean directX, boolean fourNormals) {
        double lx = Math.sin(angle) / Math.sqrt(1.09);
        double ly = .3 / Math.sqrt(1.09);
        double lz = Math.cos(angle) / Math.sqrt(1.09);
        if (directX) {
            ly = -ly;
        }
        BufferedImage image = new BufferedImage(normals.getWidth(), normals.getHeight(), BufferedImage.TYPE_INT_RGB);
        double[] normal = new double[3];
        for (int y = 0; y < normals.getHeight(); y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            for (int x = 0; x < normals.getWidth(); x++) {
                int rgb = 0;
                sampleNormal(normals, selected, x, y, fourNormals, normal);
                for (int c = 0; c < 3; c++) {
                    double encoded;
                    if (mode == 0) {
                        encoded = normal[c] * .5 + .5;
                    } else {
                        if (mode == 2) {
                            sampleNormal(normals, c + 2, x, y, fourNormals, normal);
                        }
                        double diffuse = Math.max(0.0, normal[0] * lx + normal[1] * ly + normal[2] * lz);
                        encoded = diffuse <= .0031308 ? diffuse * 12.92 : 1.055 * Math.pow(diffuse, 1.0 / 2.4) - .055;
                    }
                    rgb = (rgb << 8) | (int) Math.round(encoded * 255.0);
                }
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static void sampleNormal(SkinNormalFilter.Result normals, int selected, int x, int y,
            boolean fourNormals, double[] output) {
        int source = selected == 0 ? -1 : (selected == 1 ? 0 : selected - 2);
        double length = 0.0;
        for (int c = 0; c < 3; c++) {
            if (!fourNormals && selected >= 2) {
                double detail = selected == 2 ? .15 : (selected == 3 ? .5 : .85);
                output[c] = normals.get(0, x, y, c) * (1.0 - detail) + normals.get(-1, x, y, c) * detail;
            } else {
                output[c] = normals.get(source, x, y, c);
            }
            length += output[c] * output[c];
        }
        length = Math.sqrt(Math.max(length, 1e-16));
        for (int c = 0; c < 3; c++) {
            output[c] /= length;
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        source.setEnabled(!value);
        resolution.setEnabled(!value);
        density.setEnabled(!value);
        quality.setEnabled(!value);
        boundary.setEnabled(!value);
        generate.setEnabled(!value);
        cancel.setEnabled(value && !publishing);
        progress.setIndeterminate(value);
        updateExport();
    }

    private void updateExport() {
        export.setEnabled(!busy && product != null && !root.getText().isBlank() && !key.getText().isBlank());
    }

    private static String cause(Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        StringBuilder message = new StringBuilder(cause.toString());
        for (Throwable suppressed : cause.getSuppressed()) {
            message.append("; ").append(suppressed.getMessage());
        }
        return message.toString();
    }

    void close() {
        closed = true;
        if (previewWorker != null) {
            previewWorker.cancel(true);
        }
        if (worker != null && !publishing) {
            worker.cancel(true);
        }
    }
}
