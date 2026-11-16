package com.jme3.gde.core.assets;

import com.jme3.anim.*;
import com.jme3.anim.graph.*;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.MultiDataObject;
import org.openide.loaders.UniFileLoader;

/** Run tools/verify-animation-editor.ps1; verifies actual Swing models and J3O persistence. */
public final class AnimationEditorSmoke {
    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    public static Node model() {
        Node root = new Node("Character");
        AnimComposer composer = new AnimComposer();
        root.addControl(composer);
        for (int i = 0; i < 3; i++) {
            AnimClip clip = new AnimClip(new String[]{"Idle", "Walk", "Run"}[i]);
            clip.setTracks(new AnimTrack[]{new TransformTrack(root, new float[]{0, 1},
                    new Vector3f[]{new Vector3f(i, 0, 0), new Vector3f(i, 0, 0)}, null, null)});
            composer.addAnimClip(clip);
        }
        return root;
    }

    public static void main(String[] args) throws Exception {
        Node root = model();
        require(!AnimationEditorSupport.prefersAnimation(root), "Old J3O defaults to scene");
        root.setUserData(AnimationEditorSupport.PREFERENCE, "AUTO");
        require(AnimationEditorSupport.prefersAnimation(root), "AUTO detects clips");
        Node staticModel = new Node("Static");
        staticModel.setUserData(AnimationEditorSupport.PREFERENCE, "AUTO");
        require(!AnimationEditorSupport.prefersAnimation(staticModel), "Static model opens scene");
        root.setUserData(AnimationEditorSupport.PREFERENCE, "SCENE");
        require(!AnimationEditorSupport.prefersAnimation(root), "Explicit scene wins");
        root.setUserData(AnimationEditorSupport.PREFERENCE, "ANIMATION");
        require(AnimationEditorSupport.prefersAnimation(root), "Explicit animation wins");
        File file = new File("build/animation-smoke/Character.j3o").getAbsoluteFile();
        file.getParentFile().mkdirs();
        FaultFileSystem filesystem = new FaultFileSystem();
        filesystem.setRootDirectory(file.getParentFile());
        FileObject fileObject = filesystem.getRoot().getFileObject(file.getName());
        if (fileObject == null) {
            fileObject = filesystem.getRoot().createData(file.getName());
        }
        TestData data = (TestData) new Loader().findDataObject(fileObject, new java.util.HashSet<>());
        data.model(root);
        SwingUtilities.invokeAndWait(() -> {
            try {
                AnimationEditorTopComponent editor = new AnimationEditorTopComponent(data, root, List.of(root));
                DefaultTableModel parameters = (DefaultTableModel) field(editor, "parameters");
                DefaultTableModel states = (DefaultTableModel) field(editor, "states");
                DefaultTableModel edges = (DefaultTableModel) field(editor, "edges");
                require(states.getRowCount() == 3, "Discovered all imported clips");
                JComponent canvas = (JComponent) field(editor, "canvas");
                drag(canvas, 205, 72, 250, 72);
                require(edges.getRowCount() == 1 && "Idle".equals(edges.getValueAt(0, 0))
                        && "Run".equals(edges.getValueAt(0, 1)), "Port drag creates directed transition");
                editor.getUndoRedo().undo();
                require(edges.getRowCount() == 0, "Undo drawn transition");
                editor.getUndoRedo().redo();
                require(edges.getRowCount() == 1, "Redo drawn transition");
                drag(canvas, 240, 72, 50, 72);
                require(edges.getRowCount() == 2 && "Run".equals(edges.getValueAt(1, 0))
                        && "Idle".equals(edges.getValueAt(1, 1)), "Reverse direction is a separate edge");
                JTabbedPane tabs = (JTabbedPane) field(editor, "tabs");
                tabs.setSelectedIndex(0);
                mouse(canvas, java.awt.event.MouseEvent.MOUSE_CLICKED, 223, 50, 1);
                require(tabs.getSelectedIndex() == 3, "Click arrow opens transition properties");
                drag(canvas, 205, 72, 900, 700);
                require(edges.getRowCount() == 2, "Dropping on empty space does not create an edge");
                mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, 205, 72, 1);
                canvas.getActionMap().get("cancelLink").actionPerformed(new java.awt.event.ActionEvent(canvas, 0, "escape"));
                mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, 250, 72, 1);
                require(edges.getRowCount() == 2, "Escape cancels pending transition");
                edges.setRowCount(0);
                System.out.println("ANIMATION_GRAPH_DRAG_CONNECT_SELECT_UNDO_PASS");
                verifyNavigation(editor);
                verifyGraphEdits(editor, root);
                parameters.addRow(new Object[]{"Speed", "FLOAT", "0"});
                parameters.addRow(new Object[]{"Attack", "TRIGGER", "0"});
                states.addRow(new Object[]{"Locomotion", "Speed", "Idle|Walk|Run", "0|1|2", "true", "40", "40"});
                ((JTextField) field(editor, "entry")).setText("Locomotion");
                edges.addRow(new Object[]{"Locomotion", "Run", "Attack", "TRIGGER", "0", "0.3", "-1"});
                AnimationGraph graph = (AnimationGraph) call(editor, "graph");
                require(graph.states.size() == 4 && graph.transitions.size() == 1, "UI compiles graph");
                parameters.setValueAt("2", 0, 2);
                editor.getUndoRedo().undo();
                require("0".equals(parameters.getValueAt(0, 2).toString()), "Undo parameter edit");
                editor.getUndoRedo().redo();
                require("2".equals(parameters.getValueAt(0, 2).toString()), "Redo parameter edit");
                root.setUserData(AnimationGraph.USER_DATA, graph.encode());
                @SuppressWarnings("unchecked")
                List<AnimationGraphComments.Box> boxes = (List<AnimationGraphComments.Box>) field(editor, "comments");
                root.setUserData(AnimationGraphComments.USER_DATA, AnimationGraphComments.encode(boxes));
                AnimationEditorSupport.saveSnapshot(data, root);
                if (System.getProperty("os.name").startsWith("Windows")) {
                    try (var reader = java.nio.channels.FileChannel.open(file.toPath(),
                            java.nio.file.StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                        root.setUserData("saveRegression", "reader-open");
                        AnimationEditorSupport.saveSnapshot(data, root);
                    }
                    Node check = (Node) new BinaryImporter().load(file);
                    require("reader-open".equals(check.getUserData("saveRegression")), "Save with Windows rename-denying reader");
                    byte[] saved = java.nio.file.Files.readAllBytes(file.toPath());
                    data.setModified(true);
                    filesystem.failNextWrite = true;
                    try (var reader = java.nio.channels.FileChannel.open(file.toPath(),
                            java.nio.file.StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                        try {
                            AnimationEditorSupport.saveSnapshot(data, root);
                            throw new AssertionError("Injected write error must fail save");
                        } catch (java.io.IOException expected) {
                            require(java.util.Arrays.equals(saved, java.nio.file.Files.readAllBytes(file.toPath())),
                                    "Failed stream save restores original bytes");
                            require(data.isModified(), "Failed save preserves dirty state");
                        }
                    }
                    data.setModified(false);
                    System.out.println("ANIMATION_WINDOWS_LOCKED_REPLACE_AND_ROLLBACK_PASS");
                }
                Node loaded = (Node) new BinaryImporter().load(file);
                AnimationGraph restored = AnimationGraph.decode(loaded.getUserData(AnimationGraph.USER_DATA));
                require(restored.encode().equals(graph.encode()), "Save/reload graph");
                require(AnimationGraphComments.decode(loaded.getUserData(AnimationGraphComments.USER_DATA)).equals(boxes),
                        "J3O restores comment text and geometry");
                AnimationEditorTopComponent reopened = new AnimationEditorTopComponent(data, loaded, List.of(loaded));
                require(field(reopened, "comments").equals(boxes), "Reopening editor restores comments");
                require(AnimationEditorSupport.prefersAnimation(loaded), "Save/reload editor preference");
                AnimationGraphPlayer player = AnimationGraphPlayer.attach(loaded);
                player.setFloat("Speed", 0.5f);
                loaded.updateLogicalState(0.1f);
                require(Math.abs(loaded.getLocalTranslation().x - 0.5f) < 0.001, "Editor graph runs in game");
                player.trigger("Attack");
                loaded.updateLogicalState(0.1f);
                require(player.isTransitioning(), "Editor transition runs");
                player.close();
                System.out.println("ANIMATION_EDITOR_SWING_SAVE_RUNTIME_PASS");
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        System.exit(0);
    }

    private static final class FaultFileSystem extends org.openide.filesystems.LocalFileSystem {
        boolean failNextWrite;

        @Override
        protected java.io.OutputStream outputStream(String name) throws java.io.IOException {
            java.io.OutputStream output = super.outputStream(name);
            if (!failNextWrite) {
                return output;
            }
            failNextWrite = false;
            return new java.io.FilterOutputStream(output) {
                @Override
                public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                    out.write(bytes, offset, Math.min(8, length));
                    throw new java.io.IOException("Injected partial write");
                }
            };
        }
    }

    private static final class Loader extends UniFileLoader {
        Loader() {
            super(BinaryModelDataObject.class.getName());
        }
        protected FileObject findPrimaryFile(FileObject file) {
            return file;
        }
        protected MultiDataObject createMultiObject(FileObject file) throws java.io.IOException {
            return new TestData(file, this);
        }
    }

    private static void verifyNavigation(AnimationEditorTopComponent editor) throws Exception {
        JComponent canvas = (JComponent) field(editor, "canvas");
        DefaultTableModel states = (DefaultTableModel) field(editor, "states");
        String before = ((AnimationGraph) call(editor, "graph")).encode();
        canvas.dispatchEvent(new java.awt.event.MouseWheelEvent(canvas, java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), java.awt.event.InputEvent.SHIFT_DOWN_MASK, 100, 100, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2));
        double zoom = (Double) field(canvas, "zoom");
        require(zoom > 1 && Math.abs((Double) field(canvas, "panX") - (100 - 100 * zoom)) < 0.001,
                "Shift wheel zoom is cursor anchored");
        for (int id : new int[]{java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                java.awt.event.MouseEvent.MOUSE_RELEASED}) {
            int delta = id == java.awt.event.MouseEvent.MOUSE_PRESSED ? 0 : 50;
            canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, id, System.currentTimeMillis(),
                    java.awt.event.InputEvent.BUTTON2_DOWN_MASK, 100 + delta, 100 + delta, 1, false,
                    java.awt.event.MouseEvent.BUTTON2));
        }
        require(before.equals(((AnimationGraph) call(editor, "graph")).encode()), "Navigation leaves graph data unchanged");
        double x = (Double) field(canvas, "panX");
        double y = (Double) field(canvas, "panY");
        drag(canvas, (int) Math.round(x + 60 * zoom), (int) Math.round(y + 60 * zoom),
                (int) Math.round(x + 90 * zoom), (int) Math.round(y + 90 * zoom));
        require(((Number) states.getValueAt(0, 5)).intValue() == 70, "Node dragging respects view transform");
        shortcut(canvas, "control Z");
        for (String name : new String[]{"zoom", "panX", "panY"}) {
            Field value = canvas.getClass().getDeclaredField(name);
            value.setAccessible(true);
            value.setDouble(canvas, name.equals("zoom") ? 1 : 0);
        }
        System.out.println("ANIMATION_GRAPH_ZOOM_PAN_HIT_TEST_PASS");
    }

    @SuppressWarnings("unchecked")
    private static void verifyGraphEdits(AnimationEditorTopComponent editor, Node source) throws Exception {
        JComponent canvas = (JComponent) field(editor, "canvas");
        DefaultTableModel states = (DefaultTableModel) field(editor, "states");
        DefaultTableModel edges = (DefaultTableModel) field(editor, "edges");
        JTextField entry = (JTextField) field(editor, "entry");
        List<AnimationGraphComments.Box> comments = (List<AnimationGraphComments.Box>) field(editor, "comments");
        edges.addRow(new Object[]{"Idle", "Run", "", "ALWAYS", "0", "0.2", "-1"});
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_MOVED, 223, 50, 0);
        require(((Integer) field(canvas, "hoveredEdge")) == 0, "Hover highlights the actual edge");
        Method popup = canvas.getClass().getDeclaredMethod("createPopup", java.awt.Point.class);
        popup.setAccessible(true);
        JPopupMenu menu = (JPopupMenu) popup.invoke(canvas, new java.awt.Point(223, 50));
        clickMenu(menu, "Delete transition");
        require(edges.getRowCount() == 0, "Context menu deletes hovered edge");
        shortcut(canvas, "control Z");
        require(edges.getRowCount() == 1, "Shortcut undoes edge deletion");
        shortcut(canvas, "control Y");
        require(edges.getRowCount() == 0, "Shortcut redoes edge deletion");
        shortcut(canvas, "control Z");
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, 60, 60, 1);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, 60, 60, 1);
        shortcut(canvas, "DELETE");
        require(states.getRowCount() == 2 && edges.getRowCount() == 0 && entry.getText().equals("Run"),
                "Deleting entry atomically removes edges and selects a replacement");
        require(source.getControl(AnimComposer.class).getAnimClips().size() == 3, "Deleting states preserves imported clips");
        shortcut(canvas, "control Z");
        require(states.getRowCount() == 3 && edges.getRowCount() == 1 && entry.getText().equals("Idle"),
                "One undo restores state, entry and associated edge");
        shortcut(canvas, "control shift Z");
        require(states.getRowCount() == 2, "Ctrl+Shift+Z redoes state deletion");
        shortcut(canvas, "control Z");
        edges.setRowCount(0);
        states.addRow(new Object[]{"Temporary", "", "Idle", "0", "true", "700", "200"});
        shortcut(canvas, "control Z");
        require(states.getRowCount() == 3, "Undo creating a clip state");
        shortcut(canvas, "control Y");
        require(states.getRowCount() == 4, "Redo creating a clip state");
        shortcut(canvas, "control Z");
        drag(canvas, 20, 20, 420, 120);
        require(((java.util.Set<?>) field(canvas, "selectedStates")).size() == 2, "Marquee selects two states");
        drag(canvas, 60, 60, 90, 90);
        require(((Number) states.getValueAt(0, 5)).intValue() == 70
                && ((Number) states.getValueAt(1, 5)).intValue() == 270, "Dragging selected state moves the selection");
        shortcut(canvas, "control Z");
        require(((Number) states.getValueAt(0, 5)).intValue() == 40, "One undo restores the whole selection move");
        drag(canvas, 20, 20, 420, 120);
        shortcut(canvas, "C");
        require(comments.size() == 1, "C wraps selected states in a comment");
        JTextArea text = (JTextArea) field(canvas, "commentEditor");
        require(text != null, "C opens an inline comment editor");
        text.setText("Locomotion\n移动状态备注");
        text.getActionMap().get("applyComment").actionPerformed(new java.awt.event.ActionEvent(text, 0, "apply"));
        require(comments.get(0).text().contains("移动"), "Comment accepts Unicode and newlines");
        shortcut(canvas, "control Z");
        require(comments.get(0).text().equals("Comment"), "Undo text edit");
        shortcut(canvas, "control Y");
        require(comments.get(0).text().contains("移动"), "Redo text edit");
        AnimationGraphComments.Box before = comments.get(0);
        require(before.bounds().contains(new java.awt.Rectangle(40, 40, 165, 64))
                && before.bounds().contains(new java.awt.Rectangle(240, 40, 165, 64)), "Comment encloses selected states");
        drag(canvas, before.x() + 10, before.y() + 10, before.x() + 60, before.y() + 60);
        require(comments.get(0).x() == before.x() + 50 && ((Number) states.getValueAt(0, 5)).intValue() == 90,
                "Comment header moves enclosed states as a group");
        shortcut(canvas, "control Z");
        require(comments.get(0).equals(before) && ((Number) states.getValueAt(0, 5)).intValue() == 40, "Undo comment group move");
        drag(canvas, before.x() + before.width() - 5, before.y() + before.height() - 5,
                before.x() + before.width() + 45, before.y() + before.height() + 25);
        require(comments.get(0).width() == before.width() + 50
                && ((Number) states.getValueAt(0, 5)).intValue() == 40, "Resizing does not move states");
        shortcut(canvas, "control Z");
        require(comments.get(0).equals(before), "Undo comment resize");
        menu = (JPopupMenu) popup.invoke(canvas, new java.awt.Point(before.x() + 5, before.y() + 5));
        clickMenu(menu, "Delete comment (keep states)");
        require(comments.isEmpty() && states.getRowCount() == 3, "Deleting comment preserves all states");
        shortcut(canvas, "control Z");
        require(comments.size() == 1, "Undo comment deletion");
        require(AnimationGraphComments.decode(AnimationGraphComments.encode(comments)).equals(comments), "Comment codec roundtrip");
        require(AnimationGraphComments.decode(null).isEmpty(), "Older J3O has no comments");
        System.out.println("ANIMATION_SELECTION_DELETE_COMMENT_HISTORY_PASS");
    }

    private static void clickMenu(JPopupMenu menu, String label) {
        for (java.awt.Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getText().equals(label)) {
                item.doClick();
                return;
            }
        }
        throw new AssertionError("Missing menu: " + label);
    }

    private static void shortcut(JComponent component, String stroke) {
        Object key = component.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
        require(key != null, "Key binding exists: " + stroke);
        Action action = component.getActionMap().get(key);
        require(action != null, "Action exists: " + stroke);
        action.actionPerformed(new java.awt.event.ActionEvent(component, 0, stroke));
    }

    private static void mouse(JComponent canvas, int id, int x, int y, int clicks) {
        canvas.dispatchEvent(new java.awt.event.MouseEvent(canvas, id, System.currentTimeMillis(),
                id == java.awt.event.MouseEvent.MOUSE_DRAGGED ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : 0,
                x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1));
    }

    private static void drag(JComponent canvas, int x1, int y1, int x2, int y2) {
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, x1, y1, 1);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, x2, y2, 0);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, x2, y2, 1);
    }

    private static final class TestData extends BinaryModelDataObject {
        TestData(FileObject file, Loader loader) throws java.io.IOException {
            super(file, loader);
        }
        void model(Node root) {
            savable = root;
        }
    }
}
