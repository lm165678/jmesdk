package com.jme3.gde.texturetools;

import com.jme3.material.skin.SkinLutGenerator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import org.openide.filesystems.FileUtil;

/** EDT-owned controls; only immutable parameter snapshots leave the EDT. */
final class SkinLutPanel extends JPanel {
    private final JPanel parameters = new JPanel(new BorderLayout(8, 8));
    private final JComboBox<String> preset = new JComboBox<>();
    private final JComboBox<Integer> width = new JComboBox<>(new Integer[]{128, 256, 512});
    private final JComboBox<Integer> height = new JComboBox<>(new Integer[]{64, 128, 256});
    private final JComboBox<Integer> samples = new JComboBox<>(new Integer[]{64, 128, 256});
    private final JSpinner curvature = spinner(1.0, .001, 10.0, .1);
    private final JSpinner[] distances = {spinner(1.0, .01, 20.0, .1),
        spinner(1.0, .01, 20.0, .1), spinner(1.0, .01, 20.0, .1)};
    private final DefaultTableModel lobes = new DefaultTableModel(new String[]{"Variance (mm²)", "R", "G", "B"}, 0) {
        @Override
        public Class<?> getColumnClass(int column) {
            return Double.class;
        }
    };
    private final JTable table = new JTable(lobes);
    private final JTextField root = new JTextField();
    private final JTextField outputKey = new JTextField("Textures/Skin/SkinLut");
    private final JButton generate = new JButton("Generate");
    private final JButton export = new JButton("Export assets...");
    private final JLabel preview = new JLabel("Generating linear LUT...", JLabel.CENTER);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private SwingWorker<?, ?> worker;
    private SkinLutGenerator.Result result;
    private SkinLutGenerator.Profile generatedProfile;
    private int generatedSamples;
    private String generatedDescription;
    private boolean busy;
    private boolean publishing;
    private boolean closed;
    private boolean resetting;
    private int profileVersion;

    SkinLutPanel() {
        super(new BorderLayout(10, 10));
        preset.setName("skin.preset");
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        width.setSelectedItem(256);
        height.setSelectedItem(128);
        samples.setSelectedItem(128);
        JPanel values = new JPanel(new GridLayout(0, 2, 8, 6));
        for (SkinLutGenerator.Preset item : SkinLutGenerator.Preset.values()) {
            preset.addItem(item.toString());
        }
        preset.addItem("Custom");
        addRow(values, "Diffusion profile", preset);
        addRow(values, "Width", width);
        addRow(values, "Height", height);
        addRow(values, "Integration samples", samples);
        addRow(values, "Maximum curvature (1/mm)", curvature);
        addRow(values, "Red diffusion distance scale", distances[0]);
        addRow(values, "Green diffusion distance scale", distances[1]);
        addRow(values, "Blue diffusion distance scale", distances[2]);
        parameters.add(values, BorderLayout.NORTH);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.setDefaultRenderer(Double.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setHorizontalAlignment(JLabel.RIGHT);
                setText(value instanceof Number ? Float.toString(((Number) value).floatValue()) : "");
            }
        });
        JScrollPane gaussianScroll = new JScrollPane(table);
        gaussianScroll.setColumnHeaderView(table.getTableHeader());
        parameters.add(gaussianScroll, BorderLayout.CENTER);
        JPanel edits = new JPanel(new GridLayout(2, 2, 6, 4));
        JButton add = new JButton("Add Gaussian");
        JButton remove = new JButton("Remove");
        JButton reset = new JButton("Reference profile");
        JButton load = new JButton("Load profile...");
        edits.add(add);
        edits.add(remove);
        edits.add(reset);
        edits.add(load);
        parameters.add(edits, BorderLayout.SOUTH);
        add.addActionListener(event -> {
            if (lobes.getRowCount() >= 64) {
                status.setText("Profiles support at most 64 Gaussian lobes.");
                return;
            }
            lobes.addRow(new Double[]{1.0, .1, .1, .1});
        });
        remove.addActionListener(event -> {
            if (table.getSelectedRow() >= 0 && lobes.getRowCount() > 1) {
                lobes.removeRow(table.convertRowIndexToModel(table.getSelectedRow()));
            }
        });
        reset.addActionListener(event -> resetProfile());
        preset.addActionListener(event -> {
            preset.setToolTipText(profileDescription());
            if (!resetting && preset.getSelectedIndex() < SkinLutGenerator.Preset.values().length) {
                applyProfile(SkinLutGenerator.Preset.values()[preset.getSelectedIndex()].createProfile());
            }
        });
        load.addActionListener(event -> loadProfile());
        JPanel display = new JPanel(new BorderLayout());
        preview.setName("skin.preview");
        preview.setOpaque(true);
        preview.setBackground(new Color(35, 38, 42));
        preview.setForeground(Color.LIGHT_GRAY);
        display.add(preview, BorderLayout.CENTER);
        display.add(new JLabel("X: N·L (-1 → 1)     Y: √(curvature / maximum), bottom → top"), BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, parameters, display);
        split.setResizeWeight(.45);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(4, 6));
        JPanel paths = new JPanel(new BorderLayout(6, 6));
        JPanel fields = new JPanel(new GridLayout(2, 2, 6, 6));
        addRow(fields, "Project assets folder", root);
        addRow(fields, "Output key (no extension)", outputKey);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseRoot());
        paths.add(fields, BorderLayout.CENTER);
        paths.add(browse, BorderLayout.EAST);
        bottom.add(paths, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        generate.setName("skin.generate");
        export.setName("skin.export");
        export.setEnabled(false);
        actions.add(generate);
        actions.add(export);
        progress.setPreferredSize(new java.awt.Dimension(130, 16));
        actions.add(progress);
        bottom.add(actions, BorderLayout.CENTER);
        JPanel messages = new JPanel(new GridLayout(0, 1));
        messages.add(new JLabel("Reference fit: GPU Gems 3. Other presets are artistic starting points, not measured population profiles."));
        messages.add(new JLabel("LUT stores diffusion data. Set skin pigmentation with the material's BaseColor / BaseColorMap."));
        messages.add(status);
        bottom.add(messages, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        resetProfile();
        width.addActionListener(event -> invalidateResult());
        height.addActionListener(event -> invalidateResult());
        samples.addActionListener(event -> invalidateResult());
        curvature.addChangeListener(event -> invalidateResult());
        for (JSpinner distance : distances) {
            distance.addChangeListener(event -> profileEdited());
        }
        lobes.addTableModelListener(event -> profileEdited());
        DocumentListener outputChanged = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateExport();
            }
            @Override
            public void removeUpdate(DocumentEvent event) {
                updateExport();
            }
            @Override
            public void changedUpdate(DocumentEvent event) {
                updateExport();
            }
        };
        root.getDocument().addDocumentListener(outputChanged);
        outputKey.getDocument().addDocumentListener(outputChanged);
        generate.addActionListener(event -> generate());
        export.addActionListener(event -> export());
        preview.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                showPreview();
            }
        });
        generate();
    }

    private static JSpinner spinner(double value, double minimum, double maximum, double step) {
        return new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
    }

    private static void addRow(JPanel panel, String text, Component value) {
        panel.add(new JLabel(text));
        panel.add(value);
    }

    private void resetProfile() {
        resetting = true;
        preset.setSelectedIndex(0);
        resetting = false;
        applyProfile(SkinLutGenerator.referenceProfile());
    }

    private void applyProfile(SkinLutGenerator.Profile profile) {
        resetting = true;
        lobes.setRowCount(0);
        for (int i = 0; i < profile.size(); i++) {
            lobes.addRow(new Double[]{(double) profile.variance(i), (double) profile.weight(i, 0),
                (double) profile.weight(i, 1), (double) profile.weight(i, 2)});
        }
        for (int c = 0; c < 3; c++) {
            distances[c].setValue((double) profile.distance(c));
        }
        resetting = false;
        invalidateResult();
    }

    private void invalidateResult() {
        if (!resetting) {
            result = null;
            export.setEnabled(false);
            status.setText("Parameters changed. Generate to update the LUT.");
            firePropertyChange("skinProfile", profileVersion, ++profileVersion);
        }
    }

    private void profileEdited() {
        if (!resetting) {
            resetting = true;
            preset.setSelectedItem("Custom");
            resetting = false;
            invalidateResult();
        }
    }

    private SkinLutGenerator.Profile readProfile() throws java.text.ParseException {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("Finish editing the Gaussian value");
        }
        float[] variances = new float[lobes.getRowCount()];
        float[][] weights = new float[variances.length][3];
        float[] scales = new float[3];
        for (int c = 0; c < 3; c++) {
            distances[c].commitEdit();
            scales[c] = ((Number) distances[c].getValue()).floatValue();
        }
        for (int i = 0; i < variances.length; i++) {
            variances[i] = ((Number) lobes.getValueAt(i, 0)).floatValue();
            for (int c = 0; c < 3; c++) {
                weights[i][c] = ((Number) lobes.getValueAt(i, c + 1)).floatValue();
            }
        }
        return new SkinLutGenerator.Profile(variances, weights, scales);
    }

    SkinLutGenerator.Profile profileSnapshot() throws java.text.ParseException {
        return readProfile();
    }

    String profileDescription() {
        return preset.getSelectedItem().toString();
    }

    SkinGenerationSettings snapshot() throws java.text.ParseException {
        curvature.commitEdit();
        return new SkinGenerationSettings(readProfile(), (Integer) width.getSelectedItem(),
                (Integer) height.getSelectedItem(), ((Number) curvature.getValue()).floatValue(),
                (Integer) samples.getSelectedItem(), profileDescription());
    }

    private void generate() {
        if (busy || closed) {
            return;
        }
        final SkinLutGenerator.Profile profile;
        final float maximum;
        try {
            profile = readProfile();
            curvature.commitEdit();
            maximum = ((Number) curvature.getValue()).floatValue();
        } catch (Exception ex) {
            status.setText("Invalid parameters: " + ex.getMessage());
            return;
        }
        int w = (Integer) width.getSelectedItem();
        int h = (Integer) height.getSelectedItem();
        int quality = (Integer) samples.getSelectedItem();
        String description = profileDescription();
        setBusy(true);
        status.setText("Generating on CPU...");
        worker = new SwingWorker<SkinLutGenerator.Result, Void>() {
            @Override
            protected SkinLutGenerator.Result doInBackground() {
                return SkinLutGenerator.generate(profile, w, h, maximum, quality);
            }
            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    result = get();
                    generatedProfile = profile;
                    generatedSamples = quality;
                    generatedDescription = description;
                    status.setText(w + " × " + h + " RGB16F | linear | " + maximum + " /mm | preview displayed as sRGB");
                    showPreview();
                } catch (Exception ex) {
                    result = null;
                    status.setText("Generation failed: " + cause(ex));
                } finally {
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void chooseRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the project's assets root");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            root.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadProfile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load exported .skin.properties");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        setBusy(true);
        worker = new SwingWorker<SkinLutExport.Settings, Void>() {
            @Override
            protected SkinLutExport.Settings doInBackground() throws Exception {
                return SkinLutExport.read(file);
            }
            @Override
            protected void done() {
                if (closed) {
                    return;
                }
                try {
                    SkinLutExport.Settings settings = get();
                    resetting = true;
                    preset.setSelectedItem("Custom");
                    select(width, settings.width);
                    select(height, settings.height);
                    select(samples, settings.samples);
                    curvature.setValue((double) settings.curvature);
                    for (int c = 0; c < 3; c++) {
                        distances[c].setValue((double) settings.profile.distance(c));
                    }
                    lobes.setRowCount(0);
                    for (int i = 0; i < settings.profile.size(); i++) {
                        lobes.addRow(new Double[]{(double) settings.profile.variance(i), (double) settings.profile.weight(i, 0),
                            (double) settings.profile.weight(i, 1), (double) settings.profile.weight(i, 2)});
                    }
                    resetting = false;
                    invalidateResult();
                    status.setText("Profile loaded. Generate to update the LUT.");
                } catch (Exception ex) {
                    status.setText("Cannot load profile: " + cause(ex));
                } finally {
                    resetting = false;
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private static void select(JComboBox<Integer> combo, int value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i) == value) {
                combo.setSelectedItem(value);
                return;
            }
        }
        combo.addItem(value);
        combo.setSelectedItem(value);
    }

    private void export() {
        if (result == null || busy || closed) {
            return;
        }
        final Path assetRoot;
        final String key = outputKey.getText().trim();
        final boolean replace;
        try {
            assetRoot = Path.of(root.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(assetRoot)) {
                throw new IllegalArgumentException("Select an existing project assets folder");
            }
            List<Path> targets = SkinLutExport.targets(assetRoot, key);
            boolean exists = targets.stream().anyMatch(Files::exists);
            replace = exists && JOptionPane.showConfirmDialog(this,
                    "Replace the generated LUT, material and settings?", "Existing skin assets",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            if (exists && !replace) {
                return;
            }
        } catch (Exception ex) {
            status.setText("Cannot export: " + ex.getMessage());
            return;
        }
        SkinLutGenerator.Result snapshot = result;
        SkinLutGenerator.Profile profile = generatedProfile;
        int quality = generatedSamples;
        String description = generatedDescription;
        publishing = true;
        setBusy(true);
        status.setText("Writing LUT, material and settings...");
        worker = new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return SkinLutExport.write(assetRoot, key, snapshot, profile, quality, description, replace);
            }
            @Override
            protected void done() {
                publishing = false;
                try {
                    List<Path> paths = get();
                    FileUtil.refreshFor(paths.stream().map(Path::toFile).toArray(java.io.File[]::new));
                    if (!closed) {
                        status.setText("Exported " + paths.get(1) + " (model and Lightmap files unchanged)");
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
        if (result == null) {
            return;
        }
        int w = Math.max(1, preview.getWidth() - 20);
        int h = Math.max(1, preview.getHeight() - 20);
        double scale = Math.min((double) w / result.getWidth(), (double) h / result.getHeight());
        w = Math.max(1, (int) (result.getWidth() * scale));
        h = Math.max(1, (int) (result.getHeight() * scale));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            int sourceY = result.getHeight() - 1 - Math.min(result.getHeight() - 1, y * result.getHeight() / h);
            for (int x = 0; x < w; x++) {
                int sourceX = Math.min(result.getWidth() - 1, x * result.getWidth() / w);
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    float linear = result.get(sourceX, sourceY, c);
                    double display = linear <= .0031308f ? 12.92 * linear : 1.055 * Math.pow(linear, 1.0 / 2.4) - .055;
                    rgb = (rgb << 8) | (int) Math.round(display * 255.0);
                }
                image.setRGB(x, y, rgb);
            }
        }
        preview.setText(null);
        preview.setIcon(new ImageIcon(image));
    }

    private void setBusy(boolean value) {
        busy = value;
        enableChildren(parameters, !value);
        root.setEnabled(!value);
        outputKey.setEnabled(!value);
        generate.setEnabled(!value);
        progress.setIndeterminate(value);
        updateExport();
    }

    private static void enableChildren(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof Container) {
                enableChildren((Container) child, enabled);
            }
        }
    }

    private void updateExport() {
        export.setEnabled(!busy && result != null && !root.getText().isBlank() && !outputKey.getText().isBlank());
    }

    private static String cause(Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        if (cause instanceof CancellationException) {
            return "cancelled";
        }
        StringBuilder message = new StringBuilder(cause.toString());
        for (Throwable suppressed : cause.getSuppressed()) {
            message.append("; ").append(suppressed.getMessage());
        }
        return message.toString();
    }

    void close() {
        closed = true;
        if (worker != null && !publishing) {
            worker.cancel(true);
        }
    }
}
