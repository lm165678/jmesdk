/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 */
package com.jme3.gde.materialdefinition.editor;

import com.jme3.gde.core.editor.nodes.DraggablePanel;
import com.jme3.gde.core.editor.nodes.NodePanel;
import com.jme3.gde.core.editor.nodes.Selectable;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import org.openide.util.NbBundle;

/**
 * A material-graph comment group. Usage:
 * 第一步：在空白处拖出框选，选中一组节点。
 * 第二步：按 C 创建备注框，双击备注框可编辑文字。
 * 第三步：拖动备注框标题，完全包含在框内的节点会一起移动。
 * 右下角只调整备注框大小，不改变节点位置。备注保存在材质编辑器元数据中，
 * 不写入 J3MD，也不影响运行时 Shader 生成。
 * 点击备注后按 Delete 只删除备注；框内节点仅参与拖动，不会被自动选中删除。
 */
public final class ShaderCommentPanel extends DraggablePanel implements Selectable, KeyListener {

    private static final int TITLE_HEIGHT = 26;
    private static final int RESIZE_SIZE = 14;
    private final String id;
    private String text;
    private boolean resizing;
    private int resizeScreenX;
    private int resizeScreenY;
    private int resizeWidth;
    private int resizeHeight;
    private final Map<NodePanel, Point> containedDragStarts = new LinkedHashMap<>();

    public ShaderCommentPanel(String id, String text, Rectangle bounds) {
        this.id = id;
        this.text = text;
        setBounds(bounds);
        setOpaque(false);
        setFocusable(true);
        addKeyListener(this);
    }

    @Override
    public String getKey() {
        return "Comment." + id;
    }

    public String getText() {
        return text;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        containedDragStarts.clear();
        if (isResizeHandle(e)) {
            resizing = true;
            resizeScreenX = e.getXOnScreen();
            resizeScreenY = e.getYOnScreen();
            resizeWidth = getWidth();
            resizeHeight = getHeight();
            ((ShaderNodeDiagram) diagram).selectComment(this, e.isShiftDown() || e.isControlDown());
        } else {
            ((ShaderNodeDiagram) diagram).selectComment(this,
                    e.isShiftDown() || e.isControlDown());
            super.mousePressed(e);
            for (java.awt.Component component : diagram.getComponents()) {
                if (component instanceof NodePanel node && getBounds().contains(node.getBounds())
                        && !diagram.getSelectedItems().contains(node)) {
                    containedDragStarts.put(node, node.getLocation());
                }
            }
        }
        requestFocusInWindow();
        e.consume();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (resizing) {
            double zoom = diagram.getZoom();
            int width = resizeWidth + (int) Math.round((e.getXOnScreen() - resizeScreenX) / zoom);
            int height = resizeHeight + (int) Math.round((e.getYOnScreen() - resizeScreenY) / zoom);
            setSize(Math.max(140, width), Math.max(70, height));
            revalidate();
            repaint();
            e.consume();
        } else {
            super.mouseDragged(e);
            int dx = getX() - getDragStartLocation().x;
            int dy = getY() - getDragStartLocation().y;
            for (Map.Entry<NodePanel, Point> entry : containedDragStarts.entrySet()) {
                Point start = entry.getValue();
                entry.getKey().setLocation(start.x + dx, start.y + dy);
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        resizing = false;
        super.mouseReleased(e);
        diagram.fixSize();
        for (Selectable selected : diagram.getSelectedItems()) {
            if (selected instanceof NodePanel) {
                NodePanel node = (NodePanel) selected;
                diagram.getEditorParent().savePositionToMetaData(
                        node.getKey(), node.getX(), node.getY());
                if (node instanceof ShaderNodePanel) {
                    ((ShaderNodePanel) node).notifyPositionChanged(
                            node.getDragStartLocation().x, node.getX());
                }
            }
        }
        for (Map.Entry<NodePanel, Point> entry : containedDragStarts.entrySet()) {
            NodePanel node = entry.getKey();
            if (!node.getLocation().equals(entry.getValue())) {
                diagram.getEditorParent().savePositionToMetaData(node.getKey(), node.getX(), node.getY());
                if (node instanceof ShaderNodePanel shaderNode) {
                    shaderNode.notifyPositionChanged(entry.getValue().x, node.getX());
                }
            }
        }
        containedDragStarts.clear();
        ((ShaderNodeDiagram) diagram).persistComments();
        e.consume();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
            editText();
            e.consume();
        }
    }

    private boolean isResizeHandle(MouseEvent e) {
        return e.getX() >= getWidth() - RESIZE_SIZE && e.getY() >= getHeight() - RESIZE_SIZE;
    }

    private void editText() {
        JTextArea textArea = new JTextArea(text, 6, 32);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        int result = JOptionPane.showConfirmDialog(this, new JScrollPane(textArea),
                NbBundle.getMessage(ShaderCommentPanel.class, "ShaderNodeDiagram.comment.editTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            text = textArea.getText();
            repaint();
            ((ShaderNodeDiagram) diagram).persistComments();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean selected = diagram.getSelectedItems().contains(this);
        g2.setColor(new Color(70, 90, 115, 95));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.setColor(new Color(85, 110, 145, 210));
        g2.fillRoundRect(0, 0, getWidth() - 1, TITLE_HEIGHT, 12, 12);
        g2.setColor(selected ? new Color(180, 215, 255) : new Color(125, 155, 190));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        Font font = UIManager.getFont("Label.font");
        if (font == null) {
            font = getFont();
        }
        String[] lines = text.split("\\R", -1);
        g2.setFont(font.deriveFont(Font.BOLD, 12.0f));
        g2.setColor(Color.WHITE);
        g2.setClip(8, 3, Math.max(0, getWidth() - 16), TITLE_HEIGHT - 4);
        g2.drawString(lines.length == 0 ? "" : lines[0], 8, 18);
        g2.setFont(font.deriveFont(Font.PLAIN, 12.0f));
        g2.setClip(8, TITLE_HEIGHT + 2, Math.max(0, getWidth() - 16),
                Math.max(0, getHeight() - TITLE_HEIGHT - 8));
        int lineHeight = g2.getFontMetrics().getHeight();
        int lineY = TITLE_HEIGHT + 5 + g2.getFontMetrics().getAscent();
        for (int i = 1; i < lines.length && lineY < getHeight() - 8; i++) {
            g2.drawString(lines[i], 8, lineY);
            lineY += lineHeight;
        }
        g2.setClip(null);
        g2.setColor(new Color(210, 225, 240));
        g2.drawLine(getWidth() - 10, getHeight() - 3, getWidth() - 3, getHeight() - 10);
        g2.drawLine(getWidth() - 7, getHeight() - 3, getWidth() - 3, getHeight() - 7);
        g2.dispose();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_DELETE) {
            ((ShaderNodeDiagram) diagram).deleteSelectedItems();
            e.consume();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    public static String encodeComments(List<ShaderCommentPanel> comments) {
        try {
            Properties properties = new Properties();
            properties.setProperty("count", Integer.toString(comments.size()));
            for (int i = 0; i < comments.size(); i++) {
                ShaderCommentPanel comment = comments.get(i);
                String prefix = "comment." + i + ".";
                properties.setProperty(prefix + "id", comment.id);
                properties.setProperty(prefix + "text", comment.text);
                properties.setProperty(prefix + "x", Integer.toString(comment.getX()));
                properties.setProperty(prefix + "y", Integer.toString(comment.getY()));
                properties.setProperty(prefix + "width", Integer.toString(comment.getWidth()));
                properties.setProperty(prefix + "height", Integer.toString(comment.getHeight()));
            }
            StringWriter writer = new StringWriter();
            properties.store(writer, null);
            return Base64.getEncoder().encodeToString(
                    writer.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot encode material graph comments", ex);
        }
    }

    public static List<ShaderCommentPanel> decodeComments(String encoded) {
        List<ShaderCommentPanel> comments = new ArrayList<ShaderCommentPanel>();
        if (encoded == null || encoded.trim().isEmpty()) {
            return comments;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            Properties properties = new Properties();
            properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            int count = Math.max(0, Math.min(1000, Integer.parseInt(properties.getProperty("count", "0"))));
            for (int i = 0; i < count; i++) {
                String prefix = "comment." + i + ".";
                String id = properties.getProperty(prefix + "id");
                if (id == null) {
                    continue;
                }
                String text = properties.getProperty(prefix + "text", "");
                int x = Integer.parseInt(properties.getProperty(prefix + "x", "0"));
                int y = Integer.parseInt(properties.getProperty(prefix + "y", "0"));
                int width = Math.max(140, Integer.parseInt(properties.getProperty(prefix + "width", "240")));
                int height = Math.max(70, Integer.parseInt(properties.getProperty(prefix + "height", "140")));
                comments.add(new ShaderCommentPanel(id, text, new Rectangle(x, y, width, height)));
            }
        } catch (IOException | IllegalArgumentException ex) {
            comments.clear();
        }
        return comments;
    }
}
