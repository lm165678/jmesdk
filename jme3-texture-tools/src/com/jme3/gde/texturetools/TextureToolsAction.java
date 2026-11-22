package com.jme3.gde.texturetools;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

/**
 * Opens the shared compressed texture tool from Tools / Textures.
 * Encoding and CPU preview run in a SwingWorker, never on the EDT.
 * An activity bar remains visible while the worker runs; codecs do not expose percentages.
 * Decoded mips are cached up to 128 MiB per window; reopening clears the cache.
 * Fit shows the whole mip and follows window resizing. Wheel zoom leaves Fit mode;
 * drag to pan, or click Fit to restore it.
 * <pre>
 * 第一步：Tools → Textures → Compressed Texture Editor，打开 DDS、ASTC 或 ASTC KTX。
 * 第二步：选择 Mip 查看该层 CPU 解码预览，不要求 GPU 支持 ASTC。
 * 第三步：Encode image 选择原图、格式和 mip 数，输出独立文件。
 * 第四步：Replace mip 选择与当前层尺寸相同的图片，另存输出；原文件保持不变。
 * 第五步：重新打开原文件恢复预览；本工具不修改模型引用或自动资产映射。
 * </pre>
 */
@ActionID(category = "Textures", id = "com.jme3.gde.texturetools.TextureToolsAction")
@ActionRegistration(displayName = "Compressed Texture Editor")
@ActionReference(path = "Menu/Tools/Textures", position = 110)
public final class TextureToolsAction implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent event) {
        new Editor().setVisible(true);
    }

    /**
     * Opens a file from a project node or asset browser. May be called on any
     * thread; window creation is dispatched to EDT and file decoding to a worker.
     * <pre>
     * // 第一步：双击 DDS、ASTC 或包含 ASTC 的 KTX 文件。
     * // 第二步：打开器调用此入口；预览失败显示错误，不改写原文件。
     * TextureToolsAction.open(Path.of("textures/color.dds"));
     * </pre>
     */
    public static void open(Path file) {
        Runnable task = () -> {
            Editor editor = new Editor();
            editor.setTitle("Compressed Texture Editor — " + file.getFileName());
            editor.setVisible(true);
            editor.load(file);
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(task);
        }
    }

    private static final class Editor extends JFrame {
        private final JLabel image = new JLabel("Open a compressed texture", JLabel.CENTER);
        private final JScrollPane scroller = new JScrollPane(image);
        private boolean fitView = true;
        private double viewScale = 1;
        private boolean updatingZoom;
        private final JLabel status = new JLabel("CPU preview; DDS / ASTC / ASTC KTX1 (2D)");
        private final javax.swing.JProgressBar progress = new javax.swing.JProgressBar();
        private final JComboBox<Integer> mip = new JComboBox<>();
        private final JButton open = new JButton("Open");
        private final JButton encode = new JButton("Encode image");
        private final JButton replace = new JButton("Replace mip / Save as");
        private final JComboBox<String> channel = new JComboBox<>(new String[]{"RGBA", "RGB", "R", "G", "B", "A"});
        private final JComboBox<String> zoom = new JComboBox<>(new String[]{"25%", "50%", "100%", "200%", "400%"});
        private java.awt.image.BufferedImage decoded;
        private CompressedTexture texture;
        private Path current;
        // EDT-owned LRU, bounded to 128 MiB per editor; reopening clears it.
        private final java.util.LinkedHashMap<Integer, java.awt.image.BufferedImage> previews =
                new java.util.LinkedHashMap<>(16, 0.75f, true);
        private long previewBytes;
        private boolean busy;
        private SwingWorker<?, ?> activeWorker;

        Editor() {
            super("Compressed Texture Editor");
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent event) {
                    if (activeWorker != null) {
                        activeWorker.cancel(true);
                    }
                }
            });
            setLayout(new BorderLayout());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            buttons.add(open);
            buttons.add(encode);
            buttons.add(new JLabel("Mip:"));
            buttons.add(mip);
            buttons.add(replace);
            buttons.add(channel);
            buttons.add(zoom);
            JButton fit = new JButton("Fit");
            buttons.add(fit);
            fit.addActionListener(event -> {
                fitView = true;
                renderPreview();
            });
            channel.addActionListener(event -> renderPreview());
            zoom.addActionListener(event -> {
                if (updatingZoom) {
                    return;
                }
                fitView = "Fit".equals(zoom.getSelectedItem());
                if (!fitView) {
                    viewScale = Double.parseDouble(zoom.getSelectedItem().toString().replace("%", "")) / 100;
                }
                renderPreview();
            });
            add(buttons, BorderLayout.NORTH);
            add(scroller, BorderLayout.CENTER);
            scroller.setWheelScrollingEnabled(false);
            scroller.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (fitView) {
                        renderPreview();
                    }
                }
            });
            java.awt.event.MouseAdapter navigation = new java.awt.event.MouseAdapter() {
                private java.awt.Point last;

                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    last = event.getPoint();
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent event) {
                    last = null;
                }

                @Override
                public void mouseDragged(java.awt.event.MouseEvent event) {
                    if (last != null) {
                        java.awt.Point position = scroller.getViewport().getViewPosition();
                        position.translate(last.x - event.getX(), last.y - event.getY());
                        setViewPosition(position);
                    }
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                    fitView = false;
                    viewScale = Math.max(.01, Math.min(16, viewScale * Math.pow(1.15, -event.getPreciseWheelRotation())));
                    renderPreview();
                    event.consume();
                }
            };
            image.addMouseListener(navigation);
            image.addMouseMotionListener(navigation);
            scroller.addMouseWheelListener(navigation);
            image.setToolTipText("Wheel: zoom | Drag: pan | Fit: show entire texture");
            JPanel footer = new JPanel(new BorderLayout());
            footer.add(status, BorderLayout.CENTER);
            footer.add(progress, BorderLayout.SOUTH);
            progress.setVisible(false);
            add(footer, BorderLayout.SOUTH);
            setSize(new Dimension(960, 700));
            setLocationByPlatform(true);
            replace.setEnabled(false);
            open.addActionListener(event -> {
                Path file = choose(false);
                if (file != null) {
                    load(file);
                }
            });
            mip.addActionListener(event -> {
                if (!busy && texture != null && mip.getSelectedItem() != null) {
                    preview();
                }
            });
            encode.addActionListener(event -> encode());
            replace.addActionListener(event -> replace());
        }

        private Path choose(boolean save) {
            JFileChooser chooser = new JFileChooser();
            if (current != null) {
                chooser.setCurrentDirectory(current.getParent().toFile());
            }
            int result = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return null;
            }
            Path file = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (save && java.nio.file.Files.exists(file)) {
                JOptionPane.showMessageDialog(this, "Choose a new output file. Existing resources are preserved.");
                return null;
            }
            return file;
        }

        private void load(Path file) {
            run(() -> CompressedTexture.read(file), result -> {
                texture = result;
                previews.clear();
                previewBytes = 0;
                decoded = null;
                fitView = true;
                image.setIcon(null);
                current = file;
                busy = true;
                mip.removeAllItems();
                for (int i = 0; i < texture.levels(); i++) {
                    mip.addItem(i);
                }
                busy = false;
                preview();
            });
        }

        private void preview() {
            int level = (Integer) mip.getSelectedItem();
            java.awt.image.BufferedImage cached = previews.get(level);
            run(() -> cached != null ? cached : texture.preview(level, TextureToolchain.installed()), preview -> {
                if (cached == null) {
                    long bytes = 4L * preview.getWidth() * preview.getHeight();
                    while (!previews.isEmpty() && previewBytes + bytes > 128L * 1024 * 1024) {
                        Integer oldest = previews.keySet().iterator().next();
                        java.awt.image.BufferedImage removed = previews.remove(oldest);
                        previewBytes -= 4L * removed.getWidth() * removed.getHeight();
                    }
                    if (bytes <= 128L * 1024 * 1024) {
                        previews.put(level, preview);
                        previewBytes += bytes;
                    }
                }
                decoded = preview;
                renderPreview();
                status.setText(texture.format() + " | " + preview.getWidth() + " x " + preview.getHeight()
                        + " | mip " + level + " / " + (texture.levels() - 1)
                        + " | " + (texture.srgb() ? "sRGB" : "linear / unspecified") + " | CPU preview");
            });
        }

        private void renderPreview() {
            if (decoded == null) {
                return;
            }
            String selected = (String) channel.getSelectedItem();
            if (fitView) {
                Dimension extent = scroller.getViewport().getExtentSize();
                if (extent.width <= 0 || extent.height <= 0) {
                    return;
                }
                viewScale = Math.min((extent.width - 4.0) / decoded.getWidth(),
                        (extent.height - 4.0) / decoded.getHeight());
            }
            double scale = Math.max(.001, viewScale);
            updatingZoom = true;
            zoom.getModel().setSelectedItem(fitView ? "Fit" : String.format(java.util.Locale.ROOT, "%.1f%%", scale * 100));
            updatingZoom = false;
            int width = Math.max(1, (int) (decoded.getWidth() * scale));
            int height = Math.max(1, (int) (decoded.getHeight() * scale));
            // The icon paints channels and checkerboard without allocating a second full-size image.
            image.setText(null);
            image.setIcon(new javax.swing.Icon() {
                @Override
                public int getIconWidth() {
                    return width;
                }

                @Override
                public int getIconHeight() {
                    return height;
                }

                @Override
                public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int left, int top) {
                    java.awt.Rectangle clip = graphics.getClipBounds();
                    if (clip == null) {
                        clip = new java.awt.Rectangle(left, top, width, height);
                    }
                    int x0 = Math.max(0, clip.x - left);
                    int y0 = Math.max(0, clip.y - top);
                    int x1 = Math.min(width, clip.x + clip.width - left);
                    int y1 = Math.min(height, clip.y + clip.height - top);
                    if (x1 <= x0 || y1 <= y0) {
                        return;
                    }
                    // Batch the visible pixels into one raster instead of issuing millions
                    // of setColor/fillRect calls and allocating a Color per pixel.
                    java.awt.image.BufferedImage raster = new java.awt.image.BufferedImage(
                            x1 - x0, y1 - y0, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    int[] pixels = ((java.awt.image.DataBufferInt) raster.getRaster().getDataBuffer()).getData();
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            int pixel = decoded.getRGB(Math.min(decoded.getWidth() - 1, (int) (x / scale)),
                                    Math.min(decoded.getHeight() - 1, (int) (y / scale)));
                            if (selected.length() == 1) {
                                int shift = switch (selected) {
                                    case "R" -> 16;
                                    case "G" -> 8;
                                    case "A" -> 24;
                                    default -> 0;
                                };
                                int value = (pixel >>> shift) & 255;
                                pixel = 0xff000000 | (value << 16) | (value << 8) | value;
                            } else if (selected.equals("RGB")) {
                                pixel |= 0xff000000;
                            } else {
                                int alpha = pixel >>> 24;
                                int background = ((x / 12 + y / 12) % 2 == 0) ? 190 : 235;
                                int composed = 0xff000000;
                                for (int shift = 0; shift <= 16; shift += 8) {
                                    int value = (((pixel >>> shift) & 255) * alpha + background * (255 - alpha)) / 255;
                                    composed |= value << shift;
                                }
                                pixel = composed;
                            }
                            pixels[(y - y0) * (x1 - x0) + x - x0] = pixel;
                        }
                    }
                    graphics.drawImage(raster, left + x0, top + y0, null);
                }
            });
            image.revalidate();
            image.repaint();
        }

        private void setViewPosition(java.awt.Point position) {
            Dimension extent = scroller.getViewport().getExtentSize();
            Dimension size = scroller.getViewport().getViewSize();
            position.x = Math.max(0, Math.min(position.x, size.width - extent.width));
            position.y = Math.max(0, Math.min(position.y, size.height - extent.height));
            scroller.getViewport().setViewPosition(position);
        }

        private void encode() {
            Path source = choose(false);
            if (source == null) {
                return;
            }
            JComboBox<String> format = new JComboBox<>(new String[]{"BC1", "BC3", "BC5", "BC7", "ASTC KTX1"});
            format.setSelectedItem("BC3");
            JSpinner levels = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
            JCheckBox srgb = new JCheckBox("ASTC sRGB (color texture)", true);
            JComboBox<String> block = new JComboBox<>(new String[]{"4x4", "5x5", "6x6", "8x8", "10x10", "12x12"});
            block.setSelectedItem("6x6");
            Object[] options = {"Format", format, "Mip count (including base)", levels, srgb, "ASTC block", block,
                "BC mip filtering uses encoder defaults; normal maps need specialized filtering."};
            if (JOptionPane.showConfirmDialog(this, options, "Encode from source", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) {
                return;
            }
            Path output = choose(true);
            if (output == null) {
                return;
            }
            String selected = (String) format.getSelectedItem();
            boolean isAstc = selected.equals("ASTC KTX1");
            if (!output.toString().toLowerCase(java.util.Locale.ROOT).endsWith(isAstc ? ".ktx" : ".dds")) {
                JOptionPane.showMessageDialog(this, "Output extension must be " + (isAstc ? ".ktx" : ".dds"));
                return;
            }
            int count = (Integer) levels.getValue();
            boolean color = srgb.isSelected();
            String footprint = (String) block.getSelectedItem();
            run(() -> {
                TextureToolchain tools = TextureToolchain.installed();
                if (isAstc) {
                    CompressedTexture.encodeAstcKtx(source, output, color, footprint, count, tools);
                } else {
                    tools.dds(source, output, selected, count);
                }
                return output;
            }, this::load);
        }

        private void replace() {
            Path source = choose(false);
            if (source == null) {
                return;
            }
            Path output = choose(true);
            if (output == null) {
                return;
            }
            String extension = current.toString().substring(current.toString().lastIndexOf('.'));
            if (!output.toString().toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT))) {
                JOptionPane.showMessageDialog(this, "Keep the original container extension: " + extension);
                return;
            }
            int level = (Integer) mip.getSelectedItem();
            run(() -> {
                texture.replaceMip(level, source, output, TextureToolchain.installed());
                return output;
            }, this::load);
        }

        private <T> void run(Callable<T> work, java.util.function.Consumer<T> success) {
            setBusy(true);
            status.setText("Working...");
            SwingWorker<T, Void> worker = new SwingWorker<>() {
                @Override
                protected T doInBackground() throws Exception {
                    return work.call();
                }

                @Override
                protected void done() {
                    setBusy(false);
                    if (isCancelled()) {
                        return;
                    }
                    try {
                        success.accept(get());
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        status.setText("Failed: " + cause.getMessage());
                        JOptionPane.showMessageDialog(Editor.this, cause.getMessage(), "Texture tool error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            activeWorker = worker;
            worker.execute();
        }

        private void setBusy(boolean value) {
            busy = value;
            progress.setIndeterminate(value);
            progress.setVisible(value);
            open.setEnabled(!value);
            encode.setEnabled(!value);
            mip.setEnabled(!value && texture != null);
            replace.setEnabled(!value && texture != null);
        }
    }
}
