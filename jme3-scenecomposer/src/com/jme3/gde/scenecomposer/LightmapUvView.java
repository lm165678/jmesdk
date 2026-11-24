package com.jme3.gde.scenecomposer;

import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.nio.FloatBuffer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/** Independent CPU-only UV2 view. Capture on render thread; display on EDT. */
final class LightmapUvView extends TopComponent {
    record Snapshot(String name, float[] uv, int[] indices, int resolution, String message) {
    }

    private final JLabel summary = new JLabel("No UV2 selected");
    private final UvCanvas canvas = new UvCanvas();
    private final JComboBox<String> zoom = new JComboBox<>(new String[]{"50%", "100%", "200%", "400%", "800%"});

    LightmapUvView() {
        setName("Lightmap UV2");
        setLayout(new BorderLayout(4, 4));
        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        toolbar.add(summary, BorderLayout.CENTER);
        toolbar.add(zoom, BorderLayout.EAST);
        zoom.setSelectedIndex(1);
        zoom.addActionListener(event -> {
            int edge = 256 << zoom.getSelectedIndex();
            canvas.setPreferredSize(new Dimension(edge + 48, edge + 48));
            canvas.revalidate();
            canvas.repaint();
        });
        canvas.setPreferredSize(new Dimension(560, 560));
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        add(toolbar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        canvas.addMouseWheelListener(event -> {
            if (event.isControlDown()) {
                int next = Math.max(0, Math.min(zoom.getItemCount() - 1,
                        zoom.getSelectedIndex() - event.getWheelRotation()));
                zoom.setSelectedIndex(next);
                event.consume();
            }
        });
        setToolTipText("UV2 only. U right, V up; Ctrl+wheel zoom. Scroll to inspect. No scene or UV edits.");
    }

    static Snapshot capture(Geometry geometry) {
        Mesh mesh = geometry.getMesh();
        Integer size = geometry.getUserData(com.jme3.gde.core.assets.LightmapUvGenerator.RESOLUTION_KEY);
        int resolution = size == null ? 256 : size;
        VertexBuffer buffer = mesh.getBuffer(VertexBuffer.Type.TexCoord2);
        if (buffer == null) {
            return new Snapshot(geometry.getName(), new float[0], new int[0], resolution,
                    "No UV2. Use Regenerate UV2 to create a layout.");
        }
        if (buffer.getNumComponents() != 2 || buffer.getStride() != 0
                || !(buffer.getDataReadOnly() instanceof FloatBuffer coordinates)) {
            return new Snapshot(geometry.getName(), new float[0], new int[0], resolution,
                    "Preview requires noninterleaved float2 UV2.");
        }
        float[] uv = new float[coordinates.remaining()];
        coordinates.get(uv);
        int[] indices = new int[mesh.getTriangleCount() * 3];
        int[] triangle = new int[3];
        for (int i = 0; i < mesh.getTriangleCount(); i++) {
            mesh.getTriangle(i, triangle);
            System.arraycopy(triangle, 0, indices, i * 3, 3);
        }
        return new Snapshot(geometry.getName(), uv, indices, resolution, "UV2 / LOD 0");
    }

    void showSnapshot(Snapshot snapshot, boolean activate) {
        setName("Lightmap UV2 - " + snapshot.name());
        summary.setText(snapshot.message() + " | " + snapshot.indices().length / 3
                + " triangles | " + snapshot.resolution() + " px");
        summary.setToolTipText(summary.getText());
        canvas.setSnapshot(snapshot);
        if (activate) {
            var mode = WindowManager.getDefault().findMode("editor");
            if (mode != null && !isOpened()) {
                mode.dockInto(this);
            }
            open();
            requestActive();
        }
    }

    @Override
    public int getPersistenceType() {
        return PERSISTENCE_NEVER;
    }

    static final class UvCanvas extends JPanel {
        private Path2D.Float triangles = new Path2D.Float();

        void setSnapshot(Snapshot snapshot) {
            Path2D.Float path = new Path2D.Float();
            for (int i = 0; i < snapshot.indices().length; i += 3) {
                boolean valid = true;
                for (int corner = 0; corner < 3; corner++) {
                    int offset = snapshot.indices()[i + corner] * 2;
                    valid &= offset >= 0 && offset + 1 < snapshot.uv().length
                            && Float.isFinite(snapshot.uv()[offset]) && Float.isFinite(snapshot.uv()[offset + 1]);
                }
                if (!valid) {
                    continue;
                }
                for (int corner = 0; corner < 3; corner++) {
                    int offset = snapshot.indices()[i + corner] * 2;
                    if (corner == 0) {
                        path.moveTo(snapshot.uv()[offset], 1 - snapshot.uv()[offset + 1]);
                    } else {
                        path.lineTo(snapshot.uv()[offset], 1 - snapshot.uv()[offset + 1]);
                    }
                }
                path.closePath();
            }
            triangles = path;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int edge = Math.min(getPreferredSize().width, getPreferredSize().height) - 48;
                g.translate(24, 24);
                g.setColor(new Color(25, 28, 32));
                g.fillRect(0, 0, edge, edge);
                g.setColor(new Color(58, 62, 68));
                for (int i = 0; i <= 8; i++) {
                    int pixel = i * edge / 8;
                    g.drawLine(pixel, 0, pixel, edge);
                    g.drawLine(0, pixel, edge, pixel);
                }
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("(0,1)", 0, -6);
                g.drawString("(0,0)", 0, edge + 16);
                g.drawString("(1,0)", edge - 28, edge + 16);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.scale(edge, edge);
                g.setColor(new Color(72, 145, 205, 70));
                g.fill(triangles);
                g.setStroke(new java.awt.BasicStroke(1f / edge));
                g.setColor(new Color(135, 208, 240));
                g.draw(triangles);
            } finally {
                g.dispose();
            }
        }
    }
}
