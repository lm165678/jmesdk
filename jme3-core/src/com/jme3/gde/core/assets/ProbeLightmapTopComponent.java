package com.jme3.gde.core.assets;

import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.FastMath;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;

/**
 * Read-only probe lightmap viewer. It tone maps linear HDR for display on the CPU.
 * <pre>
 * 第一步：在项目树或资源浏览器中双击 GI/Lightmaps 下的 J3O。
 * 第二步：在 Irradiance 调整曝光，或切换 Coverage 和 Sky Visibility。
 * 第三步：关闭预览标签；烘焙资产及场景引用保持原样。
 * </pre>
 */
public final class ProbeLightmapTopComponent extends TopComponent {
    public enum Channel {
        IRRADIANCE("Irradiance"), COVERAGE("Coverage"),
        SKY_VISIBILITY("Sky Visibility"), BENT_DIRECTION("Bent Direction"),
        INDIRECT_DIRECTION("Indirect Direction");

        private final String text;

        Channel(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final FileObject file;
    private final JComboBox<Channel> channel = new JComboBox<>();
    private final JSpinner exposure = new JSpinner(new SpinnerNumberModel(0.0, -10.0, 10.0, 0.5));
    private final JLabel status = new JLabel("Loading lightmap…");
    private final JPanel surface = new JPanel() {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (picture == null) {
                return;
            }
            double scale = Math.min((double) getWidth() / picture.getWidth(), (double) getHeight() / picture.getHeight());
            int w = (int) (picture.getWidth() * scale);
            int h = (int) (picture.getHeight() * scale);
            Graphics2D painter = (Graphics2D) graphics.create();
            try {
                painter.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                painter.drawImage(picture, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } finally {
                painter.dispose();
            }
        }
    };
    private ProbeLightmapAsset asset;
    private SwingWorker<?, ?> task;
    private BufferedImage picture;

    private ProbeLightmapTopComponent(FileObject file) {
        this.file = file;
        associateLookup(Lookups.singleton(file));
        setDisplayName("Lightmap: " + file.getNameExt());
        setToolTipText(file.getPath());
        setLayout(new BorderLayout());
        var controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
        controls.add(channel);
        controls.add(new JLabel("Exposure (EV)"));
        controls.add(exposure);
        add(controls, BorderLayout.NORTH);
        add(surface, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        channel.addActionListener(event -> requestImage());
        exposure.addChangeListener(event -> requestImage());
        loadAsset();
    }

    public static void openPreview(FileObject file) {
        Runnable open = () -> {
            for (TopComponent component : TopComponent.getRegistry().getOpened()) {
                if (component instanceof ProbeLightmapTopComponent editor && editor.file.equals(file)) {
                    editor.requestActive();
                    return;
                }
            }
            var editor = new ProbeLightmapTopComponent(file);
            editor.open();
            editor.requestActive();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            open.run();
        } else {
            SwingUtilities.invokeLater(open);
        }
    }

    private void loadAsset() {
        channel.setEnabled(false);
        exposure.setEnabled(false);
        var reader = new SwingWorker<ProbeLightmapAsset, Void>() {
            @Override
            protected ProbeLightmapAsset doInBackground() throws IOException {
                return readLightmap(file);
            }

            @Override
            protected void done() {
                if (task != this) {
                    return;
                }
                try {
                    ProbeLightmapAsset loaded = get();
                    Channel first = primaryChannel(file);
                    channel.addItem(first);
                    if (first == Channel.IRRADIANCE) {
                        channel.addItem(Channel.COVERAGE);
                        if (loaded.hasSkyBand()) {
                            channel.addItem(Channel.SKY_VISIBILITY);
                            channel.addItem(Channel.BENT_DIRECTION);
                        }
                    }
                    asset = loaded;
                    channel.setEnabled(true);
                    requestImage();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    status.setText("Cannot open Lightmap: " + failure.getCause().getMessage());
                } catch (CancellationException ignored) {
                }
            }
        };
        task = reader;
        reader.execute();
    }

    private void requestImage() {
        Channel selected = (Channel) channel.getSelectedItem();
        if (asset == null || selected == null) {
            return;
        }
        var source = asset;
        float ev = ((Number) exposure.getValue()).floatValue();
        exposure.setEnabled(selected == Channel.IRRADIANCE);
        if (task != null) {
            task.cancel(true);
        }
        var render = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return renderImage(source, selected, ev);
            }

            @Override
            protected void done() {
                if (task != this) {
                    return;
                }
                try {
                    picture = get();
                    if (picture != null) {
                        status.setText(picture.getWidth() + " × " + picture.getHeight() + "   RGBA16F · " + selected);
                        surface.repaint();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    status.setText("Cannot preview Lightmap: " + failure.getCause().getMessage());
                } catch (CancellationException ignored) {
                }
            }
        };
        task = render;
        render.execute();
    }

    @Override
    protected void componentClosed() {
        var previous = task;
        task = null;
        if (previous != null) {
            previous.cancel(true);
        }
        asset = null;
        picture = null;
    }

    @Override
    public int getPersistenceType() {
        return PERSISTENCE_NEVER;
    }

    public static ProbeLightmapAsset readLightmap(FileObject file) throws IOException {
        try (var input = file.getInputStream()) {
            var importer = new BinaryImporter();
            importer.setClassFilter(name -> name.equals(ProbeLightmapAsset.class.getName())
                    || name.equals(com.jme3.texture.Texture2D.class.getName())
                    || name.equals(com.jme3.texture.Image.class.getName()));
            return ProbeLightmapAsset.fromSaved(importer.load(input));
        }
    }

    /** Selects the view for a generated directional companion or a regular Lightmap. */
    public static Channel primaryChannel(FileObject file) {
        return file.getName().endsWith("-direction") ? Channel.INDIRECT_DIRECTION : Channel.IRRADIANCE;
    }

    public static BufferedImage renderImage(ProbeLightmapAsset asset, Channel channel, float exposure) {
        var image = asset.getTexture().getImage();
        var pixels = image.getData(0).duplicate().order(ByteOrder.nativeOrder());
        int width = image.getWidth();
        int height = image.getHeight() / (asset.hasSkyBand() ? 2 : 1);
        boolean sky = channel == Channel.SKY_VISIBILITY || channel == Channel.BENT_DIRECTION;
        boolean signedDirection = sky || channel == Channel.INDIRECT_DIRECTION;
        if (sky && !asset.hasSkyBand()) {
            throw new IllegalArgumentException("This lightmap has no sky band");
        }
        var picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        float gain = (float) Math.pow(2, exposure);
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            for (int x = 0; x < width; x++) {
                int texel = (y * width + x) * 8;
                float coverage = f16(pixels, texel + 6);
                int sample = sky ? texel + width * height * 8 : texel;
                int color = 0;
                if (channel == Channel.COVERAGE || channel == Channel.SKY_VISIBILITY) {
                    int value = toChannel(sky && coverage > 0 ? f16(pixels, sample + 6) : coverage);
                    color = value * 0x010101;
                } else if (coverage > 0 || channel == Channel.INDIRECT_DIRECTION) {
                    for (int c = 0; c < 3; c++) {
                        float value = f16(pixels, sample + c * 2);
                        color = color << 8 | toChannel(signedDirection ? value * .5f + .5f : toneMap(value * gain));
                    }
                }
                picture.setRGB(x, height - y - 1, color);
            }
        }
        return picture;
    }

    private static float f16(java.nio.ByteBuffer pixels, int offset) {
        return FastMath.convertHalfToFloat(pixels.getShort(offset));
    }

    private static float toneMap(float value) {
        float mapped = 1f - (float) Math.exp(-Math.max(0, value));
        return mapped <= .0031308f ? mapped * 12.92f : 1.055f * (float) Math.pow(mapped, 1.0 / 2.4) - .055f;
    }

    private static int toChannel(float value) {
        return Math.round(255 * Math.max(0, Math.min(1, value)));
    }
}
