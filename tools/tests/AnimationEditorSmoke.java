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
        Class<?> rootTrackType = Class.forName("com.jme3.gde.core.assets.AnimationEditorTopComponent$RootTrack");
        var rootTrackConstructor = rootTrackType.getDeclaredConstructor(String.class, boolean.class);
        rootTrackConstructor.setAccessible(true);
        String longPath = "Armature/".repeat(100) + "Hips";
        Object rootTrack = rootTrackConstructor.newInstance(longPath, true);
        require(rootTrack.toString().endsWith("Hips") && rootTrack.toString().length() < 60,
                "Root motion choice must not display the full hierarchy inline");
        require(((String) call(rootTrack, "hint")).endsWith(longPath),
                "Root motion tooltip must retain the complete hierarchy");
        String idleId = "Models%2FPaladin%2FIdle.j3o##mixamo.com";
        var idleChoice = AnimationEditorTopComponent.linkedClipChoices(List.of(idleId)).getFirst();
        require("Idle".equals(idleChoice.toString()) && idleId.equals(idleChoice.id()),
                "A single linked clip shows the J3O name and keeps its resolver ID");
        var library = AnimationEditorTopComponent.linkedClipChoices(List.of(idleId, "Models%2FPaladin%2FIdle.j3o##Walk"));
        require("Idle — mixamo.com".equals(library.get(0).label()) && "Idle — Walk".equals(library.get(1).label()),
                "Multiple clips in the same J3O have distinct picker labels");
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
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
                java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
                AnimationEditorTopComponent localized = new AnimationEditorTopComponent(data, root, List.of(root));
                require("Parameters".equals(((JTabbedPane) field(localized, "tabs")).getTitleAt(1)),
                        "English-only SDK remains English on Chinese Windows");
                require(!AnimationEditorSupport.guide(root).contains("第一步"), "Tutorial follows English SDK, not OS locale");
                require("Boolean".equals(AnimationEditorMessages.value(AnimationGraph.Type.BOOLEAN)), "Enums follow SDK language");
                require(AnimationEditorMessages.uiLocale(java.util.Locale.SIMPLIFIED_CHINESE, null, path -> false)
                        .equals(java.util.Locale.ENGLISH), "Missing SDK language pack falls back to English");
                require(AnimationEditorMessages.uiLocale(java.util.Locale.SIMPLIFIED_CHINESE, null,
                        path -> path.endsWith("Bundle_zh_CN.properties")).equals(java.util.Locale.SIMPLIFIED_CHINESE),
                        "Installed SDK Chinese pack enables Chinese editor");
                require(AnimationEditorMessages.uiLocale(java.util.Locale.SIMPLIFIED_CHINESE, "sdk_test",
                        path -> path.endsWith("Bundle_sdk_zh.properties")).equals(java.util.Locale.SIMPLIFIED_CHINESE),
                        "Branded language packs and parent language are supported");
                require("参数".equals(AnimationEditorMessages.messages(java.util.Locale.SIMPLIFIED_CHINESE)
                        .getString("Animation.Parameters")), "Chinese resources remain available");
                require("Parameters".equals(AnimationEditorMessages.messages(java.util.Locale.ENGLISH)
                        .getString("Animation.Parameters")), "Explicit English must never fall back to Chinese OS locale");
                String localizedGraph = ((AnimationGraph) call(localized, "graph")).encode();
                java.util.Locale.setDefault(java.util.Locale.ENGLISH);
                require(localizedGraph.equals(((AnimationGraph) call(localized, "graph")).encode()), "Locale never changes graph identifiers");
                require("Boolean".equals(AnimationEditorMessages.value(AnimationGraph.Type.BOOLEAN)), "English enum display");
                require(!AnimationEditorSupport.guide(root).contains("第一步"), "English tutorial has no Chinese text");
                System.out.println("ANIMATION_LOCALIZATION_LOCALE_PERSISTENCE_PASS");

                Node linkTarget = model();
                AnimationEditorTopComponent linkEditor = new AnimationEditorTopComponent(data, linkTarget, List.of(linkTarget));
                var linkStates = (DefaultTableModel) field(linkEditor, "states");
                linkStates.setValueAt("Edited", 0, 0);
                AnimClip linked = new AnimClip(idleId);
                linked.setTracks(new AnimTrack[]{new TransformTrack(linkTarget, new float[]{0, 1},
                        new Vector3f[]{Vector3f.ZERO, Vector3f.UNIT_Y}, null, null)});
                linkTarget.getControl(AnimComposer.class).setLinkedAnimClips(List.of(linked));
                Node singleClipTarget = new Node("Paladin");
                AnimComposer singleComposer = new AnimComposer();
                singleClipTarget.addControl(singleComposer);
                AnimClip pose = new AnimClip("mixamo.com");
                pose.setTracks(new AnimTrack[]{new TransformTrack(singleClipTarget, new float[]{0, 1},
                        new Vector3f[]{Vector3f.ZERO, Vector3f.ZERO}, null, null)});
                singleComposer.addAnimClip(pose);
                singleComposer.setLinkedAnimClips(List.of(linked));
                var named = new AnimationEditorTopComponent(data, singleClipTarget, List.of(singleClipTarget));
                AnimationGraph namedGraph = (AnimationGraph) call(named, "graph");
                require(namedGraph.states.stream().anyMatch(state -> state.name.equals("Character")
                        && state.clips.equals(List.of("mixamo.com"))), "Single local pose uses its J3O filename");
                require(namedGraph.states.stream().anyMatch(state -> state.name.equals("Idle")
                        && state.clips.equals(List.of(idleId))), "Linked state uses its source filename but plays by ID");
                var namedStates = (DefaultTableModel) field(named, "states");
                int idleRow = namedGraph.states.get(0).clips.contains(idleId) ? 0 : 1;
                namedStates.setValueAt(idleId, idleRow, 0);
                var title = AnimationEditorTopComponent.class.getDeclaredMethod("stateTitle", int.class);
                title.setAccessible(true);
                require("Idle".equals(title.invoke(named, idleRow))
                        && idleId.equals(namedStates.getValueAt(idleRow, 0)), "Old saved title is shortened only visually");
                var refreshLinks = AnimationEditorTopComponent.class.getDeclaredMethod("refreshLinks", String.class);
                refreshLinks.setAccessible(true);
                call(linkEditor, "beginLinkWork");
                refreshLinks.invoke(linkEditor, idleId);
                require("Edited".equals(linkStates.getValueAt(0, 0)) && (boolean) field(linkEditor, "dirty"),
                        "Updating links preserves unsaved graph edits");
                require(findButton(linkEditor, "Animation Links").isEnabled()
                        && !(boolean) field(linkEditor, "saving"), "Link button re-enables after the update");
                require(((JComboBox<?>) field(linkEditor, "ownerChoice")).getItemAt(0).toString().contains("4 clips"),
                        "Composer badge counts the added clip immediately");
                System.out.println("ANIMATION_LINK_REFRESH_PRESERVES_DRAFT_PASS");

                AnimationEditorTopComponent editor = new AnimationEditorTopComponent(data, root, List.of(root));
                AnimationEditorTopComponent emptyEditor = new AnimationEditorTopComponent(data, root, List.of(root));
                DefaultTableModel emptyStates = (DefaultTableModel) field(emptyEditor, "states");
                emptyStates.setRowCount(0);
                ((JTextField) field(emptyEditor, "entry")).setText("");
                call(emptyEditor, "applyPreview");
                call(emptyEditor, "save");
                JTabbedPane emptyTabs = (JTabbedPane) field(emptyEditor, "tabs");
                findButton((java.awt.Container) emptyTabs.getComponentAt(2), "Add").doClick();
                require(emptyStates.getRowCount() == 1 && ((JTextField) field(emptyEditor, "entry")).getText()
                        .equals(emptyStates.getValueAt(0, 0)), "First state after empty graph becomes entry");
                emptyEditor.getUndoRedo().undo();
                require(emptyStates.getRowCount() == 0 && ((JTextField) field(emptyEditor, "entry")).getText().isEmpty(),
                        "Undo first state restores empty graph atomically");
                require(AnimationEditorSupport.guide(root).contains("Parameters"), "Embedded beginner guide is available");
                System.out.println("ANIMATION_EMPTY_DRAFT_ENTRY_GUIDE_PASS");
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
                drag(canvas, 40, 72, 240, 72);
                drag(canvas, 205, 72, 405, 72);
                require(edges.getRowCount() == 1, "Input-to-input and output-to-output are rejected");
                drag(canvas, 405, 72, 40, 72);
                drag(canvas, 40, 72, 405, 72);
                require(edges.getRowCount() == 1, "Outer ports cannot create a wraparound transition");
                drag(canvas, 240, 72, 205, 87);
                require(edges.getRowCount() == 2 && "Run".equals(edges.getValueAt(1, 0))
                        && "Idle".equals(edges.getValueAt(1, 1)), "Facing ports create the return edge");
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
                verifyFixedPorts(canvas, states);
                edges.removeRow(0);
                var routeMethod = canvas.getClass().getDeclaredMethod("edgeRoute", int.class);
                routeMethod.setAccessible(true);
                Object loneReturn = routeMethod.invoke(canvas, 0);
                require((double) field(loneReturn, "endX") == 205,
                        "A return transition keeps the facing destination without a forward edge");
                edges.setRowCount(0);
                drag(canvas, 240, 72, 205, 72);
                require(edges.getRowCount() == 1 && "Run".equals(edges.getValueAt(0, 0))
                        && "Idle".equals(edges.getValueAt(0, 1)), "Return can be created first");
                editor.getUndoRedo().undo();
                require(edges.getRowCount() == 0, "Undo return created first");
                editor.getUndoRedo().redo();
                require(edges.getRowCount() == 1, "Redo return created first");
                edges.setRowCount(0);
                System.out.println("ANIMATION_GRAPH_DRAG_CONNECT_SELECT_UNDO_PASS");
                verifyNavigation(editor);
                verifyGraphEdits(editor, root);
                parameters.addRow(new Object[]{"Speed", "FLOAT", "0"});
                parameters.addRow(new Object[]{"Attack", "TRIGGER", "0"});
                parameters.addRow(new Object[]{"Grounded", "BOOLEAN", "true"});
                require(((AnimationGraph) call(editor, "graph")).parameters.get(2).initial == 1,
                        "Boolean true compiles to runtime value");
                parameters.setValueAt("false", 2, 2);
                require(((AnimationGraph) call(editor, "graph")).parameters.get(2).initial == 0,
                        "Boolean false compiles to runtime value");
                parameters.setValueAt("1.0", 2, 2);
                AnimationGraph authored = (AnimationGraph) call(editor, "graph");
                require(authored.parameters.get(2).initial == 1,
                        "Legacy numeric boolean defaults remain readable");
                require(AnimationEditorTopComponent.previewDefaults(authored).parameters.get(2).initial == 0
                        && authored.parameters.get(2).initial == 1, "Live switches start cleared without editing defaults");
                parameters.removeRow(2);
                parameters.addRow(new Object[]{"End", "BOOLEAN", "false"});
                AnimationGraph endGraph = (AnimationGraph) call(editor, "graph");
                require(endGraph.parameters.get(2).name.equals("End") && endGraph.parameters.get(2).initial == 0,
                        "Adding End without a transition reference is valid");
                verifyBlendEditors(editor, states, edges);
                edges.addRow(new Object[]{"Idle", "Run", "", "TRUE", "0", "0.2", "-1"});
                try {
                    call(editor, "graph");
                    throw new AssertionError("Missing transition reference must fail validation");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    require(expected.getCause().getMessage().contains("Transition row 1 (Idle -> Run)"),
                            "Missing parameter error identifies the exact transition");
                    require(tabs.getSelectedIndex() == 3, "Invalid transition is revealed");
                }
                edges.setValueAt("End", 0, 2);
                @SuppressWarnings("unchecked")
                List<JTable> editorTables = (List<JTable>) field(editor, "tables");
                JTable transitionTable = editorTables.get(2);
                JComboBox<?> parameterChoices = (JComboBox<?>) transitionTable.getCellEditor(0, 2)
                        .getTableCellEditorComponent(transitionTable, "End", true, 0, 2);
                require(!parameterChoices.isEditable() && parameterChoices.getItemCount() == 2
                        && "End".equals(parameterChoices.getItemAt(1)), "Boolean condition offers existing boolean parameters only");
                for (int column = 0; column < 2; column++) {
                    JComboBox<?> stateChoices = (JComboBox<?>) transitionTable.getCellEditor(0, column)
                            .getTableCellEditorComponent(transitionTable, "Idle", true, 0, column);
                    require(!stateChoices.isEditable() && stateChoices.getItemCount() == states.getRowCount() + 1,
                            "From and To use noneditable current-state choices");
                }
                JPanel livePanel = (JPanel) field(editor, "live");
                JPanel sampleLiveRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
                sampleLiveRow.add(new JLabel("End"));
                sampleLiveRow.add(new JCheckBox());
                livePanel.add(sampleLiveRow);
                JPanel longLiveRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
                longLiveRow.add(new JLabel("Long parameter name"));
                longLiveRow.add(new JCheckBox());
                livePanel.add(longLiveRow);
                call(editor, "compactLiveRows");
                livePanel.setSize(420, 400);
                livePanel.doLayout();
                sampleLiveRow.doLayout();
                longLiveRow.doLayout();
                require(sampleLiveRow.getComponent(1).getX() == longLiveRow.getComponent(1).getX(),
                        "Live controls align despite different parameter name lengths");
                require(sampleLiveRow.getHeight() == sampleLiveRow.getPreferredSize().height,
                        "Extra viewport height does not stretch live parameter rows");
                livePanel.remove(sampleLiveRow);
                livePanel.remove(longLiveRow);
                JScrollPane liveScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, livePanel);
                require(liveScroll != null && liveScroll.getVerticalScrollBarPolicy() == JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        "Live parameters retain vertical scrolling");
                System.out.println("ANIMATION_LIVE_PARAMETER_COMPACT_LAYOUT_SCROLL_PASS");
                require(java.util.Arrays.stream(livePanel.getComponents()).anyMatch(component -> component instanceof JLabel label
                        && label.getText().startsWith("End (")), "New parameter appears immediately in pending preview list");
                edges.setValueAt("Typo", 0, 2);
                try {
                    call(editor, "graph");
                    throw new AssertionError("Stale parameter reference must be rejected");
                } catch (java.lang.reflect.InvocationTargetException expected) {
                    require(expected.getCause().getMessage().contains("parameter \"Typo\" was not found"),
                            "Invalid existing parameter name is distinguished from empty reference");
                }
                edges.setValueAt("End", 0, 2);
                require(((AnimationGraph) call(editor, "graph")).transitions.get(0).parameter.equals("End"),
                        "Binding End makes the graph valid");
                edges.setRowCount(0);
                parameters.removeRow(2);
                System.out.println("ANIMATION_END_PARAMETER_TRANSITION_VALIDATION_PASS");
                System.out.println("ANIMATION_REFERENCE_DROPDOWNS_PENDING_PARAMETERS_PASS");

                System.out.println("ANIMATION_BOOLEAN_DEFAULT_COMPATIBILITY_PASS");
                states.addRow(new Object[]{"Locomotion", "Speed", "Idle|Walk|Run", "0|1|2", "true", "40", "40"});
                ((JTextField) field(editor, "entry")).setText("Locomotion");
                edges.addRow(new Object[]{"Locomotion", "Run", "Attack", "TRIGGER", "0", "0.3", "-1"});
                edges.setValueAt(true, 0, 7);
                AnimationGraph graph = (AnimationGraph) call(editor, "graph");
                require(graph.states.size() == 4 && graph.transitions.size() == 1, "UI compiles graph");
                parameters.setValueAt("2", 0, 2);
                editor.getUndoRedo().undo();
                require("0".equals(parameters.getValueAt(0, 2).toString()), "Undo parameter edit");
                editor.getUndoRedo().redo();
                require("2".equals(parameters.getValueAt(0, 2).toString()), "Redo parameter edit");
                root.setUserData(AnimationGraph.USER_DATA, graph.encode());
                String ports = (String) call(editor, "encodePorts");
                root.setUserData("jme.animationGraph.ports.v1", ports);
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
                require(ports.equals(call(reopened, "encodePorts")), "J3O restores fixed edge ports");
                require(Boolean.TRUE.equals(((DefaultTableModel) field(reopened, "edges")).getValueAt(0, 7)),
                        "Saved left source port must override current node layout");
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

    @SuppressWarnings("unchecked")
    private static void verifyBlendEditors(AnimationEditorTopComponent editor, DefaultTableModel states,
            DefaultTableModel edges) throws Exception {
        JTable table = ((List<JTable>) field(editor, "tables")).get(1);
        require(table.editCellAt(0, 1), "Open blend parameter editor");
        var parameter = (JComboBox<String>) table.getEditorComponent();
        require(!parameter.isEditable() && parameter.getItemCount() == 3 && "Speed".equals(parameter.getItemAt(1))
                && "End".equals(parameter.getItemAt(2)), "Float and Boolean parameters are available");
        parameter.setSelectedItem("Speed");
        require("Speed".equals(states.getValueAt(0, 1)), "Blend parameter selection writes the state");

        require(table.editCellAt(0, 2), "Open clip editor");
        require(table.getEditorComponent() instanceof JButton, "Clips opens the paired sample editor");
        table.getCellEditor().cancelCellEditing();
        var samples = new AnimationEditorTopComponent.StateSamplesPanel(List.of("Idle", "Run"), true, "Idle", "0");
        ((JButton) field(samples, "add")).doClick(0);
        JPanel rows = (JPanel) field(samples, "rows");
        var choices = java.util.Arrays.stream(rows.getComponents()).filter(JComboBox.class::isInstance).toList();
        ((JComboBox<?>) choices.get(1)).setSelectedItem("Run");
        require(samples.samples().equals(new AnimationEditorTopComponent.Samples("Idle|Run", "0.0|1.0")),
                "Adding a clip automatically adds a corresponding threshold");
        var values = java.util.Arrays.stream(rows.getComponents()).filter(JTextField.class::isInstance).toList();
        var secondThreshold = (JTextField) values.get(1);
        secondThreshold.setText("0");
        try {
            samples.samples();
            throw new AssertionError("Equal thresholds should fail");
        } catch (IllegalArgumentException expected) {
            secondThreshold.setText("1");
        }
        var apply = AnimationEditorTopComponent.class.getDeclaredMethod("applySamples",
                int.class, AnimationEditorTopComponent.Samples.class);
        apply.setAccessible(true);
        apply.invoke(editor, 0, samples.samples());
        require(((AnimationGraph) call(editor, "graph")).states.get(0).clips.equals(List.of("Idle", "Run")),
                "Dropdown selections compile to a 1D blend");
        editor.getUndoRedo().undo();
        editor.getUndoRedo().undo();
        var single = new AnimationEditorTopComponent.StateSamplesPanel(List.of("Idle", "Run"), false, "Run", "0");
        require(!((JButton) field(single, "add")).isEnabled()
                && single.samples().equals(new AnimationEditorTopComponent.Samples("Run", "0.0")),
                "Boolean or empty parameter provides one clip at threshold zero");
        table.setValueAt("End", 0, 1);
        require("End".equals(states.getValueAt(0, 1)), "Boolean parameter accepted for a single clip");
        editor.getUndoRedo().undo();

        states.setValueAt("Running", 1, 0);
        edges.addRow(new Object[]{"Idle", "Running", "", "ALWAYS", "0", "0.2", "-1"});
        require(table.editCellAt(0, 0), "Open name editor");
        var name = (JTextField) table.getEditorComponent();
        require("StateName".equals(table.getColumnName(0)), "State name heading");
        name.setText(" ");
        require(!table.getCellEditor().stopCellEditing(), "Empty state name rejected");
        name.setText("Running");
        require(!table.getCellEditor().stopCellEditing(), "Duplicate state name rejected");
        name.setText("Locomotion");
        require(table.getCellEditor().stopCellEditing(), "Custom state name accepted");
        require("Locomotion".equals(states.getValueAt(0, 0)) && "Locomotion".equals(edges.getValueAt(0, 0))
                && "Locomotion".equals(((JTextField) field(editor, "entry")).getText()),
                "Rename updates transitions and entry");
        require("Idle".equals(states.getValueAt(0, 2)), "Renaming preserves clip binding");
        editor.getUndoRedo().undo();
        require("Idle".equals(states.getValueAt(0, 0)) && "Idle".equals(edges.getValueAt(0, 0))
                && "Idle".equals(states.getValueAt(0, 2)), "Undo restores name, clip and references together");
        edges.removeRow(0);
        states.setValueAt("Run", 1, 0);
    }

    private static void verifyFixedPorts(JComponent canvas, DefaultTableModel states) throws Exception {
        var curve = canvas.getClass().getDeclaredMethod("edgeRoute", int.class);
        curve.setAccessible(true);
        var bounds = canvas.getClass().getDeclaredMethod("bounds", int.class);
        bounds.setAccessible(true);
        Object oldX = states.getValueAt(1, 5);
        Object oldY = states.getValueAt(1, 6);
        Object oldSourceY = states.getValueAt(0, 6);
        var originalSource = (java.awt.Rectangle) bounds.invoke(canvas, 0);
        var originalTarget = (java.awt.Rectangle) bounds.invoke(canvas, 1);
        verifyEdgePorts(curve.invoke(canvas, 0), originalSource, originalTarget);
        Object returning = curve.invoke(canvas, 1);
        var returnPath = (java.awt.Shape) field(returning, "path");
        var returnBounds = returnPath.getBounds2D();
        require(returnBounds.getMinX() == originalSource.getMaxX()
                && returnBounds.getMaxX() == originalTarget.getMinX(),
                "The return arc stays between the facing ports");
        require((double) field(returning, "endX") == originalSource.getMaxX()
                && (double) field(returning, "labelY") > originalSource.getCenterY(),
                "The lower arc points back to the source of the forward transition");
        states.setValueAt(14, 1, 5);
        states.setValueAt(160, 1, 6);
        try {
            var source = (java.awt.Rectangle) bounds.invoke(canvas, 0);
            var target = (java.awt.Rectangle) bounds.invoke(canvas, 1);
            Object route = curve.invoke(canvas, 0);
            verifyEdgePorts(route, source, target);
            verifyEdgePorts(curve.invoke(canvas, 1), target, source, true);
            require((double) field(curve.invoke(canvas, 1), "labelY")
                    != (double) field(route, "labelY"), "Reciprocal labels must use separate lanes");
            double lane = (double) field(route, "labelY");
            require(lane > source.getMaxY() && lane < target.getMinY(),
                    "Backwards link should use the gap between vertically separated nodes");
            String capture = System.getProperty("jme.sdk.animationRouteSnapshot");
            if (capture != null) {
                canvas.setSize(700, 400);
                var image = new java.awt.image.BufferedImage(700, 400, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    canvas.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                javax.imageio.ImageIO.write(image, "png", new File(capture));
            }
            states.setValueAt(-200, 1, 5);
            target = (java.awt.Rectangle) bounds.invoke(canvas, 1);
            verifyEdgePorts(curve.invoke(canvas, 0), source, target);
            verifyEdgePorts(curve.invoke(canvas, 1), target, source, true);
            states.setValueAt(14, 1, 5);
            states.setValueAt(0, 1, 6);
            states.setValueAt(160, 0, 6);
            source = (java.awt.Rectangle) bounds.invoke(canvas, 0);
            target = (java.awt.Rectangle) bounds.invoke(canvas, 1);
            verifyEdgePorts(curve.invoke(canvas, 0), source, target);
            lane = (double) field(curve.invoke(canvas, 0), "labelY");
            require(lane > target.getMaxY() && lane < source.getMinY(),
                    "Link should take the gap when the target is above the source");
        } finally {
            states.setValueAt(oldSourceY, 0, 6);
            states.setValueAt(oldX, 1, 5);
            states.setValueAt(oldY, 1, 6);
        }
    }

    private static void verifyEdgePorts(Object route, java.awt.Rectangle source, java.awt.Rectangle target)
            throws Exception {
        verifyEdgePorts(route, source, target, false);
    }

    private static void verifyEdgePorts(Object route, java.awt.Rectangle source, java.awt.Rectangle target,
            boolean sourceLeft) throws Exception {
        var path = (java.awt.Shape) field(route, "path");
        var iterator = path.getPathIterator(null);
        double[] point = new double[6];
        require(iterator.currentSegment(point) == java.awt.geom.PathIterator.SEG_MOVETO
                && point[0] == (sourceLeft ? source.getMinX() : source.getMaxX())
                && point[1] == source.getCenterY(),
                "Transition must start at the source output");
        require((double) field(route, "endX") == (sourceLeft ? target.getMaxX() : target.getMinX())
                && (double) field(route, "endY") == target.getCenterY(),
                "Transition must finish at the destination input");
    }

    private static JButton findButton(java.awt.Container root, String label) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JButton button && label.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container container) {
                JButton found = findButton(container, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
