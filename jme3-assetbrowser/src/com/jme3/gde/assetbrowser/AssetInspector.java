package com.jme3.gde.assetbrowser;

import com.jme3.audio.AudioBuffer;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.SceneApplication;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.openide.filesystems.FileObject;
import org.openide.util.RequestProcessor;

/**
 * Preview one selected asset. Closing the pane stops audio and animation.
 * <pre>
 * 第一步：勾选 Preview，选择资源；文件大小和内存估算显示在名称下方。
 * 第二步：拖动模型旋转，滚轮缩放。选择动画片段后点 Play/Pause。
 * 第三步：贴图用滚轮缩放、拖动平移，Fit 恢复适合窗口的比例；音频用 Play/Stop。
 * </pre>
 */
final class AssetInspector extends JPanel {
    private record Selection(ProjectAssetManager manager, FileObject file, String path,
            AssetKind kind, long revision, long modified, long size) { }

    private final RequestProcessor reader = new RequestProcessor("Asset inspector", 1, true);
    private final PreviewSurface surface = new PreviewSurface();
    private final JLabel name = new JLabel(" ");
    private final JLabel info = new JLabel(" ");
    private final JLabel memory = new JLabel(" ");
    private final JLabel message = new JLabel();
    private final JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
    private final JButton fit = new JButton(AssetContentPanel.text("PreviewFit"));
    private final JButton play = new JButton(AssetContentPanel.text("Play"));
    private final JButton stop = new JButton(AssetContentPanel.text("Stop"));
    private final JComboBox<String> clips = new JComboBox<>();
    private final Timer animation = new Timer(50, event -> requestFrame());
    private final Runnable statusChanged;
    private Selection selected;
    private volatile long generation;
    private RequestProcessor.Task readTask;
    private boolean modelReady;
    private boolean framePending;
    private boolean frameDirty;
    private boolean playing;
    private BufferedImage picture;
    private AudioBuffer audio;
    private long memoryBytes = -1;
    private float yaw = .65f;
    private float pitch = .35f;
    private float distance = 4.9f;
    private double zoom = 1;
    private int panX;
    private int panY;
    private Point drag;
    // The render thread owns these resources, including release on selection changes.
    private SceneApplication app;
    private AssetModelPreview model;
    private final AssetAudioPlayer audioPlayer = new AssetAudioPlayer();

    AssetInspector(Runnable statusChanged) {
        super(new BorderLayout(2, 2));
        this.statusChanged = statusChanged;
        add(surface, BorderLayout.CENTER);
        JPanel footer = new JPanel();
        footer.setLayout(new javax.swing.BoxLayout(footer, javax.swing.BoxLayout.Y_AXIS));
        footer.add(name);
        footer.add(info);
        footer.add(memory);
        footer.add(message);
        message.setVisible(false);
        footer.add(controls);
        add(footer, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(0, 0));
        setPreferredSize(new Dimension(300, 160));
        clips.setPrototypeDisplayValue("Animation clip");
        fit.addActionListener(event -> resetView());
        play.addActionListener(event -> {
            if (selected == null) {
                return;
            }
            if (selected.kind() == AssetKind.AUDIO) {
                playAudio();
            } else {
                playing = !playing;
                play.setText(AssetContentPanel.text(playing ? "PreviewPause" : "Play"));
                if (playing) {
                    animation.start();
                } else {
                    animation.stop();
                }
                requestFrame();
            }
        });
        stop.addActionListener(event -> stopAudio());
        clips.addActionListener(event -> requestFrame());
        MouseAdapter gestures = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                drag = event.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                drag = null;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (selected == null || drag == null) {
                    return;
                }
                int dx = event.getX() - drag.x;
                int dy = event.getY() - drag.y;
                drag = event.getPoint();
                if (selected.kind() == AssetKind.MODEL) {
                    yaw -= dx * .012f;
                    pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dy * .012f));
                    requestFrame();
                } else if (selected.kind() == AssetKind.TEXTURE) {
                    panX += dx;
                    panY += dy;
                    surface.repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                if (selected == null) {
                    return;
                }
                double scale = Math.pow(1.15, event.getPreciseWheelRotation());
                if (selected.kind() == AssetKind.MODEL) {
                    distance = (float) Math.max(.25, Math.min(30, distance * scale));
                    requestFrame();
                } else if (selected.kind() == AssetKind.TEXTURE) {
                    zoom = Math.max(.1, Math.min(24, zoom / scale));
                    updateInfo();
                    surface.repaint();
                }
            }
        };
        surface.addMouseListener(gestures);
        surface.addMouseMotionListener(gestures);
        surface.addMouseWheelListener(gestures);
        surface.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (selected != null && selected.kind() == AssetKind.TEXTURE && picture != null) {
                    updateInfo();
                }
            }
        });
    }

    void select(ProjectAssetManager manager, FileObject file, String asset, AssetKind kind, long revision) {
        Selection next = file == null || manager == null ? null
                : new Selection(manager, file, asset, kind, revision, file.lastModified().getTime(), file.getSize());
        if (Objects.equals(next, selected)) {
            return;
        }
        clear();
        selected = next;
        if (next == null) {
            return;
        }
        name.setText(file.getNameExt());
        name.setToolTipText(asset);
        updateInfo();
        if (file.isFolder()) {
            return;
        }
        long ticket = generation;
        var convertAudio = com.jme3.gde.core.assets.ExternalAssetImport.audioAction(manager, file);
        if (convertAudio != null) {
            controls.add(new javax.swing.JButton(convertAudio));
            message.setText("Convert source audio to OGG to preview");
            message.setVisible(true);
            controls.revalidate();
            controls.repaint();
            return;
        }
        if (kind == AssetKind.MODEL) {
            controls.add(fit);
            surface.setToolTipText(AssetContentPanel.text("PreviewOrbitTip"));
            startModel(next, ticket);
        } else if (kind == AssetKind.TEXTURE || kind == AssetKind.AUDIO) {
            if (kind == AssetKind.TEXTURE) {
                controls.add(fit);
                surface.setToolTipText(AssetContentPanel.text("PreviewTextureTip"));
            }
            readTask = reader.post(() -> read(next, ticket));
        }
        controls.revalidate();
        controls.repaint();
    }

    private void read(Selection entry, long ticket) {
        try {
            if (entry.kind() == AssetKind.TEXTURE) {
                var manager = AssetModelPreview.isolated(entry.manager());
                BufferedImage image;
                try {
                    image = AssetPreviewTextures.read(manager, entry.file(), entry.path());
                } finally {
                    manager.clearCache();
                }
                onEdt(ticket, () -> {
                    picture = image;
                    memoryBytes = (long) image.getWidth() * image.getHeight() * 4;
                    updateInfo();
                    surface.repaint();
                });
            } else {
                var manager = AssetModelPreview.isolated(entry.manager());
                AudioBuffer buffer;
                try {
                    buffer = AssetThumbnails.loadAudio(manager, entry.file(), entry.path());
                } finally {
                    manager.clearCache();
                }
                onEdt(ticket, () -> {
                    audio = buffer;
                    memoryBytes = buffer.getData().remaining();
                    controls.add(play);
                    controls.add(stop);
                    controls.revalidate();
                    controls.repaint();
                    updateInfo();
                });
            }
        } catch (Exception | LinkageError error) {
            showError(ticket, error);
        }
    }

    private void startModel(Selection entry, long ticket) {
        enqueue(ticket, () -> {
            model = new AssetModelPreview(app, entry.manager(), entry.path());
            var names = model.clips;
            long size = model.memory;
            onEdt(ticket, () -> {
                memoryBytes = size;
                if (!names.isEmpty()) {
                    clips.setModel(new javax.swing.DefaultComboBoxModel<>(names.toArray(String[]::new)));
                    controls.add(clips);
                    controls.add(play);
                    controls.revalidate();
                    controls.repaint();
                }
                modelReady = true;
                updateInfo();
                requestFrame();
            });
        });
    }

    private void requestFrame() {
        if (!modelReady) {
            return;
        }
        if (framePending) {
            frameDirty = true;
            return;
        }
        framePending = true;
        frameDirty = false;
        long ticket = generation;
        float h = yaw;
        float v = pitch;
        float d = distance;
        String clip = (String) clips.getSelectedItem();
        boolean advance = playing;
        enqueue(ticket, () -> {
            var image = model.render(h, v, d, clip, advance);
            onEdt(ticket, () -> {
                picture = image;
                surface.repaint();
                framePending = false;
                if (frameDirty) {
                    requestFrame();
                }
            });
        });
    }

    private void playAudio() {
        long ticket = generation;
        if (audio != null) {
            audioPlayer.play(audio, error -> showError(ticket, error));
        }
    }

    private void stopAudio() {
        audioPlayer.stop();
    }

    private void enqueue(long ticket, Runnable action) {
        try {
            if (app == null) {
                app = SceneApplication.getApplication();
            }
            app.enqueue(() -> {
                if (generation == ticket) {
                    try {
                        action.run();
                    } catch (Exception | LinkageError error) {
                        showError(ticket, error);
                    }
                }
                return null;
            });
        } catch (Exception | LinkageError error) {
            showError(ticket, error);
        }
    }

    private void showError(long ticket, Throwable error) {
        onEdt(ticket, () -> {
            animation.stop();
            playing = false;
            play.setText(AssetContentPanel.text("Play"));
            framePending = false;
            modelReady = false;
            message.setText(AssetContentPanel.text("PreviewFailed"));
            message.setToolTipText(error.toString());
            message.setVisible(true);
        });
    }

    private void onEdt(long ticket, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (generation == ticket) {
                action.run();
            }
        });
    }

    void clear() {
        if (selected == null) {
            return;
        }
        generation++;
        selected = null;
        animation.stop();
        playing = false;
        framePending = false;
        frameDirty = false;
        modelReady = false;
        if (readTask != null) {
            readTask.cancel();
            readTask = null;
        }
        audioPlayer.stop();
        audio = null;
        picture = null;
        memoryBytes = -1;
        name.setText(" ");
        name.setToolTipText(null);
        surface.setToolTipText(null);
        info.setText(" ");
        message.setVisible(false);
        message.setToolTipText(null);
        memory.setText(" ");
        controls.removeAll();
        controls.revalidate();
        controls.repaint();
        play.setText(AssetContentPanel.text("Play"));
        clips.removeAllItems();
        resetView();
        if (app != null) {
            app.enqueue(() -> {
                if (model != null) {
                    model.close();
                    model = null;
                }
                return null;
            });
        }
    }

    private void resetView() {
        yaw = .65f;
        pitch = .35f;
        distance = 4.9f;
        zoom = 1;
        panX = 0;
        panY = 0;
        drag = null;
        updateInfo();
        requestFrame();
        surface.repaint();
    }

    void poster(BufferedImage image) {
        if (selected != null && selected.kind() != AssetKind.TEXTURE
                && (selected.kind() != AssetKind.MODEL || !modelReady)) {
            picture = image;
            surface.repaint();
        }
    }

    long memoryBytes() {
        return memoryBytes;
    }

    private void updateInfo() {
        if (selected != null) {
            String file = selected.file().isFolder() ? AssetContentPanel.text("Folder")
                    : AssetContentPanel.text("PreviewFile") + " " + units(selected.size());
            if (selected.kind() == AssetKind.TEXTURE && picture != null) {
                file += "  ·  " + picture.getWidth() + " × " + picture.getHeight()
                        + "  ·  " + Math.max(1, Math.round(surface.imageScale() * 100)) + "%";
            } else if (audio != null) {
                file += "  ·  " + String.format(Locale.ROOT, "%.1f s", audio.getDuration());
            }
            info.setText(file);
            memory.setText(AssetContentPanel.text("PreviewMemory") + " "
                    + (memoryBytes < 0 ? "—" : units(memoryBytes)));
        }
        statusChanged.run();
    }

    static String units(long size) {
        double value = size / 1024.0;
        String label = "KB";
        if (value >= 1024) {
            value /= 1024;
            label = "MB";
        }
        if (value >= 1024) {
            value /= 1024;
            label = "GB";
        }
        return String.format(Locale.ROOT, "%.2f %s", value, label);
    }

    private final class PreviewSurface extends JComponent {
        double imageScale() {
            return Math.min((double) getWidth() / picture.getWidth(),
                    (double) getHeight() / picture.getHeight()) * zoom;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (picture == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        zoom > 2 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = imageScale();
                int w = Math.max(1, (int) Math.round(scale * picture.getWidth()));
                int h = Math.max(1, (int) Math.round(scale * picture.getHeight()));
                g.drawImage(picture, (getWidth() - w) / 2 + panX, (getHeight() - h) / 2 + panY, w, h, null);
            } finally {
                g.dispose();
            }
        }
    }
}
