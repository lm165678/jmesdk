package com.jme3.gde.materialdefinition.editor;

import com.jme3.gde.core.editor.nodes.ConnectionEndpoint;
import com.jme3.gde.core.editor.nodes.NodeEditor;
import com.jme3.gde.core.editor.nodes.NodePanel;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/** Headless EDT regression: comment selection/deletion never implicitly selects its nodes. */
public final class MaterialCommentSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TestDiagram diagram = new TestDiagram();
            List<String> saved = new ArrayList<>();
            diagram.setEditorParent((NodeEditor) Proxy.newProxyInstance(
                    NodeEditor.class.getClassLoader(), new Class<?>[]{NodeEditor.class},
                    (proxy, method, values) -> {
                        if (method.getName().equals("savePositionToMetaData")) {
                            saved.add((String) values[0]);
                        }
                        return null;
                    }));
            diagram.setSize(1000, 800);
            TestNode node = new TestNode();
            node.setBounds(60, 70, 60, 40);
            diagram.addNode(node);
            ShaderCommentPanel comment = new ShaderCommentPanel("test", "备注",
                    new Rectangle(20, 20, 240, 180));
            diagram.loadComments(ShaderCommentPanel.encodeComments(List.of(comment)));
            comment = (ShaderCommentPanel) java.util.Arrays.stream(diagram.getComponents())
                    .filter(ShaderCommentPanel.class::isInstance).findFirst().orElseThrow();
            comment.mousePressed(mouse(comment, MouseEvent.MOUSE_PRESSED, 10, 10, 100, 100));
            require(diagram.getSelectedItems().equals(List.of(comment)), "Comment-only selection");
            comment.mouseDragged(mouse(comment, MouseEvent.MOUSE_DRAGGED, 40, 30, 130, 120));
            comment.mouseReleased(mouse(comment, MouseEvent.MOUSE_RELEASED, 40, 30, 130, 120));
            require(node.getX() == 90 && node.getY() == 90, "Contained node moves with comment");
            require(saved.contains("test-node"), "Moved node position is saved");
            require(!diagram.getSelectedItems().contains(node), "Drag must not select contained node");
            comment.keyPressed(new KeyEvent(comment, KeyEvent.KEY_PRESSED, 1, 0,
                    KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED));
            require(comment.getParent() == null && node.getParent() == diagram,
                    "Delete removes only the comment");
            System.out.println("MATERIAL_COMMENT_DELETE_DRAG_PASS");
        });
    }

    private static MouseEvent mouse(ShaderCommentPanel target, int id, int x, int y,
            int screenX, int screenY) {
        return new MouseEvent(target, id, 1, MouseEvent.BUTTON1_DOWN_MASK,
                x, y, screenX, screenY, 1, false, MouseEvent.BUTTON1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestDiagram extends ShaderNodeDiagram {
        @Override
        public void persistComments() {
        }

        @Override
        public void deleteSelectedItems() {
            // Bypass only the confirmation dialog; consume the actual deletion selection.
            for (var item : new ArrayList<>(getSelectedItems())) {
                require(item instanceof ShaderCommentPanel, "Node accidentally selected for deletion");
                removeCustomSelectable(item);
            }
            getSelectedItems().clear();
        }
    }

    private static final class TestNode extends NodePanel {
        @Override
        protected boolean canEdit() {
            return false;
        }

        @Override
        public String getKey() {
            return "test-node";
        }

        @Override
        public ConnectionEndpoint createConnectionEndpoint(String type,
                ConnectionEndpoint.ParamType paramType, String paramName) {
            return null;
        }
    }
}
