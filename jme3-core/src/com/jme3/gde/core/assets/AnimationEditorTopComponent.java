package com.jme3.gde.core.assets;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.bounding.BoundingBox;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.undo.AbstractUndoableEdit;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.UndoRedo;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;

/**
 * J3O animation graph editor. Drafts and undo belong to EDT. Preview uses a private
 * model clone and the game's AnimationGraphPlayer in its own AWT rendering context.
 * Apply preview does not save or modify the original pose. Save J3O validates first
 * and stores graph definitions, comments and editor preference on the original model.
 * Preview clones the complete hierarchy on the source scene thread, including sibling
 * meshes, then installs it on the preview render thread with the selected composer.
 * Other composers are disabled in the clone. The SDK studio probe lights metallic
 * materials in preview only; original scene lighting and controls are unchanged.
 * Closing releases the preview context; unsaved drafts require explicit discard.
 * Multiple composers are selectable; save or discard before changing composer.
 * <pre>
 * 第一步：右键 j3o 或场景节点选择 Open in Animation Editor。
 * 第二步：Parameters 添加 FLOAT / BOOLEAN / TRIGGER 参数，默认值分别为数字 / 0或1 / 0。
 * 第三步：States 设置动画片段；1D 混合用 | 分隔片段和递增阈值，指定 FLOAT 参数。
 * 第四步：Transitions 按行优先级设置条件、过渡秒数和最早退出秒数（-1 不限制）。
 * 第五步：Apply preview，拖动预览旋转、滚轮缩放，修改 Live parameters 检查状态高亮。
 * 预览显示完整模型，播放 Composer 选中的控制器；环境光照仅作用于预览副本。
 * 第六步：Save J3O 保存；游戏中 AnimationGraphPlayer.attach(composerOwner) 启动。
 * 第七步：Double-click 选择 SCENE 并保存可恢复场景默认打开；右键始终可强制打开。
 * </pre>
 * Shift+wheel zooms around the cursor (25% to 300%); middle drag pans the view.
 * Navigation is EDT-only and does not alter saved positions or undo history.
 * V1 has non-interruptible transitions and no event tracks or layered graph editing.
 * Drag either of a node's blue ports onto another node to create an edge. Click an
 * arrow to select its transition row; each direction is a separate edge. New
 * edges default to ALWAYS with a 0.2-second fade and no exit-time restriction;
 * configure their condition before applying preview. Escape cancels a drag.
 * <pre>
 * 第一步：从节点左侧或右侧蓝色圆点拖到目标节点，松开后创建由起点到目标的转换箭头。
 * 第二步：Transitions 设置参数、条件和 Fade seconds；反向切换需要反向连线。
 * 第三步：点击已有箭头直接定位条件行；Ctrl+Z 撤销连线，Esc 取消未完成连线。
 * 第四步：空白处拖出选择框，Shift 增选；Delete 删除状态及其连线，Ctrl+Z 完整恢复。
 * 第五步：框选后按 C 输入备注，Ctrl+Enter 确认；双击备注编辑，拖标题移动框内状态。
 * 第六步：拖备注框右下角调整大小；删除备注只移除框，不删除其中状态。
 * 第七步：连线悬停高亮，右键 Delete transition 删除，Ctrl+Y 或 Ctrl+Shift+Z 重做。
 * 第八步：Shift+滚轮围绕鼠标缩放，按住中键拖动平移；视图导航不改动节点数据。
 * </pre>
 * Deleting the entry state selects the first remaining state as entry. An empty
 * draft is allowed, but add an entry state before preview/save. Source clips are
 * never deleted by graph edits. Comments are editor-only metadata persisted with
 * J3O. Each drag, deletion (including associated edges), or text commit is one undo
 * step. Comment title drags move states fully enclosed at drag start; resizing
 * changes only the comment rectangle. All interactions belong to EDT.
 */
@TopComponent.Description(preferredID = "AnimationEditor", persistenceType = TopComponent.PERSISTENCE_NEVER)
public final class AnimationEditorTopComponent extends TopComponent {
    private static final Map<BinaryModelDataObject, AnimationEditorTopComponent> OPEN = new WeakHashMap<>();
    private final BinaryModelDataObject data;
    private final Spatial root;
    private final List<Spatial> owners;
    private final JComboBox<String> ownerChoice = new JComboBox<>();
    private final JComboBox<AnimationEditorSupport.Preference> preference =
            new JComboBox<>(AnimationEditorSupport.Preference.values());
    private final JTextField entry = new JTextField(12);
    private final DefaultTableModel parameters = model("Name", "Type", "Default");
    private final DefaultTableModel states = model("Name", "Blend parameter", "Clips (| separated)", "Thresholds (|)", "Loop", "X", "Y");
    private final DefaultTableModel edges = model("From", "To", "Parameter", "Condition", "Value", "Fade seconds", "Exit seconds (-1=off)");
    private final List<JTable> tables = new ArrayList<>();
    private final List<AnimationGraphComments.Box> comments = new ArrayList<>();
    private final JPanel previewPanel = new JPanel(new BorderLayout());
    private final JPanel live = new JPanel();
    private final JLabel status = new JLabel("Loading preview...");
    private final GraphCanvas canvas = new GraphCanvas();
    private final JTabbedPane tabs = new JTabbedPane();
    private final UndoRedo.Manager undo = new UndoRedo.Manager();
    private final Timer timer;
    private Preview preview;
    private Spatial owner;
    private int ownerIndex;
    private boolean loading;
    private boolean dirty;
    private boolean saving;
    private boolean disposed;
    private int previewRevision;
    private Snapshot snapshot;
    private String savedGraph;
    private String savedComments;
    private JButton undoButton;
    private JButton redoButton;
    private String activeState = "";
    private JButton save;

    public static void open(BinaryModelDataObject data, Spatial root, List<Spatial> owners) {
        AnimationEditorTopComponent existing = OPEN.get(data);
        if (existing != null) {
            existing.open();
            existing.requestActive();
            return;
        }
        AnimationEditorTopComponent editor = new AnimationEditorTopComponent(data, root, owners);
        OPEN.put(data, editor);
        editor.open();
        editor.requestActive();
        editor.startPreview();
    }

    AnimationEditorTopComponent(BinaryModelDataObject data, Spatial root, List<Spatial> owners) {
        this.data = data;
        this.root = root;
        this.owners = new ArrayList<>(owners);
        owner = owners.get(0);
        setName("Animation: " + data.getPrimaryFile().getNameExt());
        setLayout(new BorderLayout(6, 6));
        setActivatedNodes(new org.openide.nodes.Node[]{data.getNodeDelegate()});
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (int i = 0; i < owners.size(); i++) {
            ownerChoice.addItem(i + ": " + owners.get(i).getName() + " ("
                    + owners.get(i).getControl(AnimComposer.class).getAnimClipsNames().size() + " clips)");
        }
        toolbar.add(new JLabel("Composer"));
        toolbar.add(ownerChoice);
        toolbar.add(new JLabel("Entry"));
        toolbar.add(entry);
        toolbar.add(new JLabel("Double-click"));
        toolbar.add(preference);
        JPanel playback = new JPanel(new FlowLayout(FlowLayout.LEFT));
        playback.add(button("Apply preview", () -> applyPreview()));
        playback.add(button("Pause", () -> enqueuePlayer(player -> player.setPaused(true))));
        playback.add(button("Play", () -> enqueuePlayer(player -> player.setPaused(false))));
        playback.add(button("Reset", () -> applyPreview()));
        save = button("Save J3O", this::save);
        playback.add(save);
        undoButton = button("Undo", () -> undoEdit(false));
        redoButton = button("Redo", () -> undoEdit(true));
        playback.add(undoButton);
        playback.add(redoButton);
        undo.addChangeListener(event -> updateHistoryButtons());
        playback.add(new JLabel("Box-select + C: comment | Delete: remove | Drag blue port: connect"));
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(toolbar);
        top.add(playback);
        add(top, BorderLayout.NORTH);
        tabs.addTab("State graph", new JScrollPane(canvas));
        tabs.addTab("Parameters", tablePanel(parameters, new Object[]{"Parameter", "FLOAT", "0"}));
        tabs.addTab("States / 1D blend", tablePanel(states, new Object[]{"State", "", firstClip(), "0", "true", "40", "40"}));
        tabs.addTab("Transitions", tablePanel(edges, new Object[]{"", "", "", "ALWAYS", "0", "0.2", "-1"}));
        JPanel previewContainer = new JPanel(new BorderLayout());
        previewPanel.setPreferredSize(new Dimension(420, 380));
        previewContainer.add(previewPanel, BorderLayout.CENTER);
        live.setLayout(new BoxLayout(live, BoxLayout.Y_AXIS));
        JScrollPane controls = new JScrollPane(live);
        controls.setPreferredSize(new Dimension(420, 170));
        previewContainer.add(controls, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, previewContainer);
        split.setResizeWeight(0.60);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        String storedPreference = root.getUserData(AnimationEditorSupport.PREFERENCE);
        preference.setSelectedItem(storedPreference == null ? AnimationEditorSupport.Preference.SCENE
                : parsePreference(storedPreference));
        loadOwner();
        ownerChoice.addActionListener(event -> changeOwner());
        for (DefaultTableModel model : new DefaultTableModel[]{parameters, states, edges}) {
            model.addTableModelListener(event -> changed());
        }
        entry.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                changed();
            }
        });
        preference.addActionListener(event -> changed());
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"), "saveAnimation");
        getActionMap().put("saveAnimation", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                save();
            }
        });
        bindHistory("control Z", false);
        bindHistory("control Y", true);
        bindHistory("control shift Z", true);
        updateHistoryButtons();
        timer = new Timer(100, event -> {
            if (preview != null) {
                activeState = preview.stateName;
                if (!saving) {
                    status.setText(preview.message);
                }
                canvas.repaint();
            }
        });
    }

    private String firstClip() {
        return owner.getControl(AnimComposer.class).getAnimClipsNames().iterator().next();
    }

    private static AnimationEditorSupport.Preference parsePreference(String value) {
        try {
            return AnimationEditorSupport.Preference.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AnimationEditorSupport.Preference.SCENE;
        }
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0);
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JPanel tablePanel(DefaultTableModel model, Object[] defaults) {
        JTable table = new JTable(model);
        tables.add(table);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 2 && model == states ? 240 : 120);
        }
        if (model == parameters) {
            table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(Type.values())));
        } else if (model == edges) {
            table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(Operator.values())));
        } else {
            table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"true", "false"})));
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Add", () -> {
            Object[] row = defaults.clone();
            if (model == states) {
                row[0] = "State " + (states.getRowCount() + 1);
                row[2] = firstClip();
            } else if (model == parameters) {
                row[0] = "Parameter" + (parameters.getRowCount() + 1);
            }
            model.addRow(row);
        }));
        buttons.add(button("Remove", () -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                if (model == states) {
                    deleteStates(java.util.Arrays.stream(table.getSelectedRows()).boxed()
                            .collect(java.util.stream.Collectors.toSet()));
                } else {
                    model.removeRow(row);
                }
            }
        }));
        buttons.add(button("Move up", () -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveRow(row, row, row - 1);
                table.setRowSelectionInterval(row - 1, row - 1);
            }
        }));
        panel.add(buttons, BorderLayout.SOUTH);
        if (model == states) {
            JLabel help = new JLabel("1D blend: clips Idle|Walk|Run, thresholds 0|1|2, FLOAT parameter Speed. Single clip: threshold 0.");
            panel.add(help, BorderLayout.NORTH);
        } else if (model == edges) {
            panel.add(new JLabel("Top row wins. Exit is elapsed seconds; -1 disables it. Active fades cannot be interrupted."), BorderLayout.NORTH);
        }
        return panel;
    }

    private void loadOwner() {
        loading = true;
        try {
            parameters.setRowCount(0);
            states.setRowCount(0);
            edges.setRowCount(0);
            comments.clear();
            canvas.clearSelection();
            savedGraph = owner.getUserData(AnimationGraph.USER_DATA);
            savedComments = owner.getUserData(AnimationGraphComments.USER_DATA);
            comments.addAll(AnimationGraphComments.decode(savedComments));
            AnimationGraph graph;
            if (savedGraph == null) {
                List<State> initial = new ArrayList<>();
                List<String> clips = new ArrayList<>(owner.getControl(AnimComposer.class).getAnimClipsNames());
                java.util.Collections.sort(clips);
                for (int i = 0; i < clips.size(); i++) {
                    initial.add(new State(clips.get(i), "", List.of(clips.get(i)), new float[]{0}, true,
                            40 + (i % 3) * 200, 40 + (i / 3) * 110));
                }
                graph = new AnimationGraph(clips.get(0), List.of(), initial, List.of());
            } else {
                graph = AnimationGraph.decode(savedGraph);
            }
            entry.setText(graph.entry);
            for (Parameter p : graph.parameters) {
                parameters.addRow(new Object[]{p.name, p.type.name(), p.initial});
            }
            for (State s : graph.states) {
                List<String> thresholds = new ArrayList<>();
                for (int i = 0; i < s.clips.size(); i++) {
                    thresholds.add(Float.toString(s.threshold(i)));
                }
                states.addRow(new Object[]{s.name, s.parameter, String.join("|", s.clips), String.join("|", thresholds), s.loop, s.x, s.y});
            }
            for (Transition t : graph.transitions) {
                edges.addRow(new Object[]{t.from, t.to, t.parameter, t.operator.name(), t.value, t.duration, t.exitTime});
            }
            dirty = false;
            undo.discardAllEdits();
            snapshot = new Snapshot();
            status.setText("Available clips: " + String.join(" | ", owner.getControl(AnimComposer.class).getAnimClipsNames()));
            canvas.repaint();
        } catch (Exception exception) {
            AnimationEditorSupport.error(exception);
            save.setEnabled(false);
        } finally {
            loading = false;
        }
    }

    private void changeOwner() {
        if (loading || saving || ownerChoice.getSelectedIndex() == ownerIndex) {
            return;
        }
        canvas.finishComment(true);
        if (dirty && !discard()) {
            loading = true;
            ownerChoice.setSelectedIndex(ownerIndex);
            loading = false;
            return;
        }
        ownerIndex = ownerChoice.getSelectedIndex();
        owner = owners.get(ownerIndex);
        loadOwner();
        applyPreview();
    }

    private AnimationGraph graph() {
        canvas.finishComment(true);
        for (JTable table : tables) {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
                throw new IllegalArgumentException("Finish editing the current cell");
            }
        }
        List<Parameter> ps = new ArrayList<>();
        List<State> ss = new ArrayList<>();
        List<Transition> ts = new ArrayList<>();
        for (int i = 0; i < parameters.getRowCount(); i++) {
            ps.add(new Parameter(cell(parameters, i, 0), Type.valueOf(cell(parameters, i, 1)), number(parameters, i, 2)));
        }
        for (int i = 0; i < states.getRowCount(); i++) {
            String[] samples = cell(states, i, 2).split("\\|", -1);
            String[] thresholdText = cell(states, i, 3).split("\\|", -1);
            float[] thresholds = new float[thresholdText.length];
            for (int j = 0; j < thresholds.length; j++) {
                thresholds[j] = Float.parseFloat(thresholdText[j].trim());
            }
            String loop = cell(states, i, 4);
            if (!loop.equals("true") && !loop.equals("false")) {
                throw new IllegalArgumentException("Loop must be true or false");
            }
            ss.add(new State(cell(states, i, 0), cell(states, i, 1), Arrays.asList(samples), thresholds,
                    Boolean.parseBoolean(loop), number(states, i, 5), number(states, i, 6)));
        }
        for (int i = 0; i < edges.getRowCount(); i++) {
            ts.add(new Transition(cell(edges, i, 0), cell(edges, i, 1), cell(edges, i, 2),
                    Operator.valueOf(cell(edges, i, 3)), number(edges, i, 4), number(edges, i, 5), number(edges, i, 6)));
        }
        AnimationGraph result = new AnimationGraph(entry.getText().trim(), ps, ss, ts);
        result.validate(owner.getControl(AnimComposer.class));
        return result;
    }

    private static String cell(DefaultTableModel model, int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString().trim();
    }

    private static float number(DefaultTableModel model, int row, int column) {
        return Float.parseFloat(cell(model, row, column));
    }

    private void changed() {
        if (loading || saving) {
            return;
        }
        Snapshot before = snapshot;
        Snapshot after = new Snapshot();
        if (before != null && before.sameContent(after)) {
            return;
        }
        snapshot = after;
        dirty = true;
        canvas.repaint();
        if (before != null) {
            undo.addEdit(new AbstractUndoableEdit() {
                public void undo() {
                    super.undo();
                    before.restore();
                }
                public void redo() {
                    super.redo();
                    after.restore();
                }
                public String getPresentationName() {
                    return "Animation graph";
                }
            });
        }
    }

    @Override
    public UndoRedo getUndoRedo() {
        return undo;
    }

    private void bindHistory(String stroke, boolean redo) {
        String key = "animation." + stroke;
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(stroke), key);
        getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                undoEdit(redo);
            }
        });
        canvas.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), key);
        canvas.getActionMap().put(key, getActionMap().get(key));
    }

    private void undoEdit(boolean redo) {
        if (saving) {
            return;
        }
        canvas.finishComment(true);
        for (JTable table : tables) {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }
        if (redo && undo.canRedo()) {
            undo.redo();
        } else if (!redo && undo.canUndo()) {
            undo.undo();
        }
    }

    private void updateHistoryButtons() {
        if (undoButton != null) {
            undoButton.setEnabled(!saving && undo.canUndo());
            redoButton.setEnabled(!saving && undo.canRedo());
        }
    }

    /** Batch structural edits into one history entry, including dependent edges/entry. */
    private void edit(Runnable action) {
        if (saving) {
            return;
        }
        loading = true;
        try {
            action.run();
        } finally {
            loading = false;
        }
        changed();
    }

    private void deleteStates(java.util.Set<Integer> selected) {
        if (selected.isEmpty()) {
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int row : selected) {
            if (row >= 0 && row < states.getRowCount()) {
                names.add(cell(states, row, 0));
            }
        }
        edit(() -> {
            for (int i = edges.getRowCount() - 1; i >= 0; i--) {
                if (names.contains(cell(edges, i, 0)) || names.contains(cell(edges, i, 1))) {
                    edges.removeRow(i);
                }
            }
            for (int i = states.getRowCount() - 1; i >= 0; i--) {
                if (selected.contains(i)) {
                    states.removeRow(i);
                }
            }
            if (names.contains(entry.getText())) {
                entry.setText(states.getRowCount() == 0 ? "" : cell(states, 0, 0));
            }
            canvas.clearSelection();
        });
        if (states.getRowCount() == 0) {
            status.setText("Empty graph: add an entry state before previewing or saving. Imported animation clips are unchanged.");
        }
    }

    private final class Snapshot {
        private final List<AnimationGraphComments.Box> boxes = List.copyOf(comments);
        private final Object[][][] rows = new Object[3][][];
        private final String entryText = entry.getText();
        private final Object preferred = preference.getSelectedItem();

        Snapshot() {
            DefaultTableModel[] models = {parameters, states, edges};
            for (int m = 0; m < models.length; m++) {
                rows[m] = new Object[models[m].getRowCount()][models[m].getColumnCount()];
                for (int r = 0; r < rows[m].length; r++) {
                    for (int c = 0; c < rows[m][r].length; c++) {
                        rows[m][r][c] = models[m].getValueAt(r, c);
                    }
                }
            }
        }

        void restore() {
            canvas.finishComment(false);
            loading = true;
            DefaultTableModel[] models = {parameters, states, edges};
            for (int m = 0; m < models.length; m++) {
                models[m].setRowCount(0);
                for (Object[] row : rows[m]) {
                    models[m].addRow(row);
                }
            }
            entry.setText(entryText);
            preference.setSelectedItem(preferred);
            comments.clear();
            comments.addAll(boxes);
            canvas.clearSelection();
            snapshot = this;
            dirty = true;
            loading = false;
            canvas.repaint();
        }

        boolean sameContent(Snapshot other) {
            return Arrays.deepEquals(rows, other.rows) && entryText.equals(other.entryText)
                    && java.util.Objects.equals(preferred, other.preferred) && boxes.equals(other.boxes);
        }
    }

    private void startPreview() {
        preview = new Preview();
        Preview app = preview;
        RequestProcessor.getDefault().post(() -> {
            try {
                app.start();
                app.enqueue(() -> {
                    EventQueue.invokeLater(() -> {
                        if (!disposed) {
                            var panel = app.getPanel();
                            MouseAdapter orbit = new MouseAdapter() {
                                private int lastX;
                                private int lastY;
                                public void mousePressed(MouseEvent event) {
                                    lastX = event.getX();
                                    lastY = event.getY();
                                }
                                public void mouseDragged(MouseEvent event) {
                                    float dx = (event.getX() - lastX) * 0.01f;
                                    float dy = (event.getY() - lastY) * 0.01f;
                                    lastX = event.getX();
                                    lastY = event.getY();
                                    app.enqueue(() -> app.orbit(dx, dy, 1));
                                }
                                public void mouseWheelMoved(MouseWheelEvent event) {
                                    float scale = (float) Math.pow(1.1, event.getWheelRotation());
                                    app.enqueue(() -> app.orbit(0, 0, scale));
                                }
                            };
                            panel.addMouseListener(orbit);
                            panel.addMouseMotionListener(orbit);
                            panel.addMouseWheelListener(orbit);
                            previewPanel.add(panel, BorderLayout.CENTER);
                            previewPanel.revalidate();
                            previewPanel.repaint();
                            applyPreview();
                            timer.start();
                        }
                    });
                });
            } catch (Exception exception) {
                AnimationEditorSupport.error(exception);
            }
        });
    }

    private void applyPreview() {
        if (preview == null || disposed) {
            return;
        }
        try {
            AnimationGraph definition = graph();
            Spatial selected = owner;
            Preview app = preview;
            int revision = ++previewRevision;
            // Clone on the main scene thread, where the original may be animated.
            RequestProcessor.getDefault().post(() -> {
                try {
                    AnimationEditorSupport.PreviewModel clone = com.jme3.gde.core.scene.SceneApplication.getApplication()
                            .enqueue(() -> AnimationEditorSupport.cloneForPreview(root, selected)).get();
                    EventQueue.invokeLater(() -> {
                        if (!disposed && revision == previewRevision) {
                            app.enqueue(() -> {
                                try {
                                    app.install(clone.model(), clone.owner(), definition);
                                } catch (Exception exception) {
                                    app.message = exception.getMessage();
                                    AnimationEditorSupport.error(exception);
                                }
                            });
                        }
                    });
                } catch (Exception exception) {
                    AnimationEditorSupport.error(exception);
                }
            });
            live.removeAll();
            live.add(new JLabel("Live parameters (preview only)"));
            for (Parameter parameter : definition.parameters) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                row.add(new JLabel(parameter.name));
                if (parameter.type == Type.TRIGGER) {
                    row.add(button("Trigger", () -> enqueuePlayer(p -> p.trigger(parameter.name))));
                } else if (parameter.type == Type.BOOLEAN) {
                    JCheckBox value = new JCheckBox("true", parameter.initial != 0);
                    value.addActionListener(e -> {
                        boolean checked = value.isSelected();
                        enqueuePlayer(p -> p.setBoolean(parameter.name, checked));
                    });
                    row.add(value);
                } else {
                    JSpinner value = new JSpinner(new SpinnerNumberModel((double) parameter.initial, -1e9, 1e9, 0.1));
                    value.addChangeListener(e -> {
                        float number = ((Number) value.getValue()).floatValue();
                        enqueuePlayer(p -> p.setFloat(parameter.name, number));
                    });
                    row.add(value);
                }
                live.add(row);
            }
            live.revalidate();
            live.repaint();
        } catch (Exception exception) {
            AnimationEditorSupport.error(exception);
        }
    }

    private void enqueuePlayer(java.util.function.Consumer<AnimationGraphPlayer> operation) {
        if (preview != null) {
            Preview app = preview;
            app.enqueue(() -> {
                if (app.player != null) {
                    operation.accept(app.player);
                }
            });
        }
    }

    private void save() {
        if (saving) {
            return;
        }
        try {
            String text = graph().encode();
            String commentText = AnimationGraphComments.encode(comments);
            String expectedComments = savedComments;
            String expected = savedGraph;
            String preferred = preference.getSelectedItem().toString();
            Spatial target = owner;
            saving = true;
            setInputsEnabled(this, false);
            status.setText("Saving J3O...");
            RequestProcessor.getDefault().post(() -> {
                try {
                    if (data.loadAsset() != root) {
                        throw new IllegalStateException("Model was reloaded; reopen the animation editor before saving");
                    }
                    com.jme3.gde.core.scene.SceneApplication.getApplication().enqueue(() -> {
                        if (!java.util.Objects.equals(expected, target.getUserData(AnimationGraph.USER_DATA))
                                || !java.util.Objects.equals(expectedComments, target.getUserData(AnimationGraphComments.USER_DATA))) {
                            throw new IllegalStateException("Animation definition changed elsewhere; reopen the editor");
                        }
                        String previousPreference = root.getUserData(AnimationEditorSupport.PREFERENCE);
                        try {
                            target.setUserData(AnimationGraph.USER_DATA, text);
                            target.setUserData(AnimationGraphComments.USER_DATA, commentText);
                            root.setUserData(AnimationEditorSupport.PREFERENCE, preferred);
                            data.setModified(true);
                            AnimationEditorSupport.saveSnapshot(data, root);
                        } catch (Exception exception) {
                            target.setUserData(AnimationGraph.USER_DATA, expected);
                            target.setUserData(AnimationGraphComments.USER_DATA, expectedComments);
                            root.setUserData(AnimationEditorSupport.PREFERENCE, previousPreference);
                            throw exception;
                        }
                        return null;
                    }).get();
                    EventQueue.invokeLater(() -> {
                        savedGraph = text;
                        savedComments = commentText;
                        dirty = false;
                        status.setText("Saved " + data.getPrimaryFile().getNameExt());
                    });
                } catch (Exception exception) {
                    data.setModified(true);
                    AnimationEditorSupport.error(exception);
                } finally {
                    EventQueue.invokeLater(() -> {
                        saving = false;
                        setInputsEnabled(this, true);
                        updateHistoryButtons();
                    });
                }
            });
        } catch (Exception exception) {
            AnimationEditorSupport.error(exception);
        }
    }

    private boolean discard() {
        return DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(
                "Discard unsaved animation changes? Use Save J3O to keep them.",
                NotifyDescriptor.YES_NO_OPTION)) == NotifyDescriptor.YES_OPTION;
    }

    private static void setInputsEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setInputsEnabled(child, enabled);
            }
        }
    }

    @Override
    public boolean canClose() {
        canvas.finishComment(true);
        return !saving && (!dirty || discard());
    }

    @Override
    protected void componentClosed() {
        disposed = true;
        timer.stop();
        OPEN.remove(data);
        if (preview != null) {
            Preview app = preview;
            preview = null;
            app.stop(false);
        }
        previewPanel.removeAll();
    }

    private final class GraphCanvas extends JPanel {
        private final java.util.Set<Integer> selectedStates = new java.util.LinkedHashSet<>();
        private final Map<Integer, Point> dragLocations = new java.util.LinkedHashMap<>();
        private int selectedComment = -1;
        private int hoveredEdge = -1;
        private Point dragStart;
        private Rectangle originalComment;
        private boolean resizingComment;
        private Point marqueeStart;
        private Rectangle marquee;
        private java.util.Set<Integer> marqueeBase = new java.util.LinkedHashSet<>();
        private JTextArea commentEditor;
        private int editedComment = -1;
        private int dragged = -1;
        private int offsetX;
        private int offsetY;
        private int linking = -1;
        private Point linkEnd;
        private boolean linkLeft;
        private double zoom = 1;
        private double panX;
        private double panY;
        private Point panStart;

        GraphCanvas() {
            setLayout(null);
            setPreferredSize(new Dimension(1600, 1200));
            setFocusable(true);
            setToolTipText("Shift+wheel: zoom; middle drag: pan. Drag empty space to select states; C creates a comment. Delete removes selection. Double-click a comment to edit.");
            graphAction("DELETE", "deleteSelection", this::deleteSelection);
            graphAction("C", "createComment", () -> {
                if (!selectedStates.isEmpty()) {
                    addComment("Comment");
                    editComment(comments.size() - 1);
                }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "cancelLink");
            getActionMap().put("cancelLink", new AbstractAction() {
                public void actionPerformed(ActionEvent event) {
                    linking = -1;
                    linkEnd = null;
                    if (dragStart != null) {
                        snapshot.restore();
                    }
                    dragStart = null;
                    marqueeStart = null;
                    marquee = null;
                    repaint();
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                public void mousePressed(MouseEvent event) {
                    if (!isEnabled()) {
                        return;
                    }
                    requestFocusInWindow();
                    finishComment(true);
                    if (SwingUtilities.isMiddleMouseButton(event)) {
                        panStart = event.getPoint();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        return;
                    }
                    event = modelEvent(event);
                    if (event.isPopupTrigger()) {
                        popup(event);
                        return;
                    }
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        return;
                    }
                    for (int i = states.getRowCount() - 1; i >= 0; i--) {
                        Rectangle b = bounds(i);
                        if (event.getPoint().distance(b.x + b.width, b.y + 32) <= 10
                                || event.getPoint().distance(b.x, b.y + 32) <= 10) {
                            linkLeft = event.getX() < b.getCenterX();
                            linking = i;
                            linkEnd = event.getPoint();
                            repaint();
                            return;
                        }
                    }
                    for (int i = states.getRowCount() - 1; i >= 0; i--) {
                        Rectangle bounds = bounds(i);
                        if (bounds.contains(event.getPoint())) {
                            if (event.isShiftDown()) {
                                if (!selectedStates.add(i)) {
                                    selectedStates.remove(i);
                                }
                            } else if (!selectedStates.contains(i)) {
                                selectedStates.clear();
                                selectedStates.add(i);
                            }
                            selectedComment = -1;
                            dragged = i;
                            dragStart = event.getPoint();
                            captureLocations();
                            repaint();
                            return;
                        }
                    }
                    if (edgeAt(event.getPoint()) >= 0) {
                        return;
                    }
                    int boxIndex = commentAt(event.getPoint());
                    if (boxIndex >= 0) {
                        Rectangle box = comments.get(boxIndex).bounds();
                        boolean grip = new Rectangle(box.x + box.width - 14, box.y + box.height - 14, 14, 14).contains(event.getPoint());
                        if (grip || event.getY() < box.y + 36) {
                            selectedComment = boxIndex;
                            selectedStates.clear();
                            resizingComment = grip;
                            originalComment = box;
                            dragStart = event.getPoint();
                            if (!grip) {
                                for (int i = 0; i < states.getRowCount(); i++) {
                                    if (box.contains(bounds(i))) {
                                        selectedStates.add(i);
                                    }
                                }
                            }
                            captureLocations();
                            repaint();
                            return;
                        }
                    }
                    selectedComment = -1;
                    marqueeStart = event.getPoint();
                    if (!event.isShiftDown()) {
                        selectedStates.clear();
                    }
                    marqueeBase = new java.util.LinkedHashSet<>(selectedStates);
                    repaint();
                }
                public void mouseClicked(MouseEvent event) {
                    event = modelEvent(event);
                    if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event) && isEnabled()) {
                        for (int i = 0; i < states.getRowCount(); i++) {
                            if (bounds(i).contains(event.getPoint())) {
                                tabs.setSelectedIndex(2);
                                tables.get(1).setRowSelectionInterval(i, i);
                                return;
                            }
                        }
                        int box = commentAt(event.getPoint());
                        if (box >= 0) {
                            editComment(box);
                            return;
                        }
                    }
                    if (SwingUtilities.isLeftMouseButton(event) && isEnabled() && hitState(event.getPoint()) < 0) {
                        for (int i = edges.getRowCount() - 1; i >= 0; i--) {
                            java.awt.geom.CubicCurve2D curve = edgeCurve(i);
                            if (curve != null && nearCurve(curve, event.getPoint())) {
                                editTransition(i);
                                return;
                            }
                        }
                    }
                }
                public void mouseDragged(MouseEvent event) {
                    if (panStart != null) {
                        panX += event.getX() - panStart.x;
                        panY += event.getY() - panStart.y;
                        panStart = event.getPoint();
                        repaint();
                        return;
                    }
                    event = modelEvent(event);
                    if (isEnabled() && linking >= 0) {
                        linkEnd = event.getPoint();
                        repaint();
                        return;
                    }
                    if (isEnabled() && marqueeStart != null) {
                        marquee = new Rectangle(Math.min(marqueeStart.x, event.getX()), Math.min(marqueeStart.y, event.getY()),
                                Math.abs(event.getX() - marqueeStart.x), Math.abs(event.getY() - marqueeStart.y));
                        selectedStates.clear();
                        selectedStates.addAll(marqueeBase);
                        for (int i = 0; i < states.getRowCount(); i++) {
                            if (marquee.intersects(bounds(i))) {
                                selectedStates.add(i);
                            }
                        }
                        repaint();
                        return;
                    }
                    if (isEnabled() && dragStart != null) {
                        int dx = event.getX() - dragStart.x;
                        int dy = event.getY() - dragStart.y;
                        for (Point point : dragLocations.values()) {
                            dx = Math.max(dx, -point.x);
                            dy = Math.max(dy, -point.y);
                        }
                        if (selectedComment >= 0) {
                            if (!resizingComment) {
                                dx = Math.max(dx, -originalComment.x);
                                dy = Math.max(dy, -originalComment.y);
                            }
                            AnimationGraphComments.Box box = comments.get(selectedComment);
                            comments.set(selectedComment, resizingComment
                                    ? new AnimationGraphComments.Box(box.text(), originalComment.x, originalComment.y,
                                            Math.max(100, originalComment.width + dx), Math.max(80, originalComment.height + dy))
                                    : new AnimationGraphComments.Box(box.text(), originalComment.x + dx, originalComment.y + dy,
                                            originalComment.width, originalComment.height));
                        }
                        loading = true;
                        for (Map.Entry<Integer, Point> location : dragLocations.entrySet()) {
                            states.setValueAt(location.getValue().x + dx, location.getKey(), 5);
                            states.setValueAt(location.getValue().y + dy, location.getKey(), 6);
                        }
                        loading = false;
                        repaint();
                    }
                }
                public void mouseReleased(MouseEvent event) {
                    if (SwingUtilities.isMiddleMouseButton(event)) {
                        panStart = null;
                        setCursor(Cursor.getDefaultCursor());
                        return;
                    }
                    event = modelEvent(event);
                    if (event.isPopupTrigger()) {
                        popup(event);
                    }
                    if (linking >= 0) {
                        int source = linking;
                        linking = -1;
                        linkEnd = null;
                        int target = hitState(event.getPoint());
                        if (isEnabled() && target >= 0) {
                            addTransition(cell(states, source, 0), cell(states, target, 0));
                        }
                        repaint();
                        return;
                    }
                    if (dragStart != null) {
                        dragged = -1;
                        dragStart = null;
                        changed();
                    }
                    marqueeStart = null;
                    marquee = null;
                    repaint();
                }
                public void mouseMoved(MouseEvent event) {
                    event = modelEvent(event);
                    int edge = hitState(event.getPoint()) < 0 ? edgeAt(event.getPoint()) : -1;
                    if (hoveredEdge != edge) {
                        hoveredEdge = edge;
                        setCursor(Cursor.getPredefinedCursor(edge >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }
                public void mouseExited(MouseEvent event) {
                    hoveredEdge = -1;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(event -> {
                if (event.isShiftDown() && isEnabled()) {
                    finishComment(true);
                    double next = Math.max(0.25, Math.min(3, zoom * Math.pow(1.12, -event.getPreciseWheelRotation())));
                    panX = event.getX() - (event.getX() - panX) * next / zoom;
                    panY = event.getY() - (event.getY() - panY) * next / zoom;
                    zoom = next;
                    event.consume();
                    repaint();
                } else if (getParent() != null) {
                    getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, event, getParent()));
                }
            });
        }

        private MouseEvent modelEvent(MouseEvent event) {
            return new MouseEvent(this, event.getID(), event.getWhen(), event.getModifiersEx(),
                    (int) Math.round((event.getX() - panX) / zoom),
                    (int) Math.round((event.getY() - panY) / zoom),
                    event.getClickCount(), event.isPopupTrigger(), event.getButton());
        }

        private void graphAction(String stroke, String name, Runnable action) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), name);
            getActionMap().put(name, new AbstractAction() {
                public void actionPerformed(ActionEvent event) {
                    if (isEnabled() && !saving) {
                        action.run();
                    }
                }
            });
        }

        private void clearSelection() {
            selectedStates.clear();
            selectedComment = -1;
            hoveredEdge = -1;
            dragged = -1;
            dragStart = null;
            marquee = null;
            marqueeStart = null;
            linking = -1;
            linkEnd = null;
        }

        private void captureLocations() {
            dragLocations.clear();
            for (int row : selectedStates) {
                dragLocations.put(row, bounds(row).getLocation());
            }
        }

        private void deleteSelection() {
            finishComment(true);
            if (selectedComment >= 0) {
                int index = selectedComment;
                edit(() -> comments.remove(index));
                clearSelection();
                repaint();
            } else {
                deleteStates(new java.util.HashSet<>(selectedStates));
            }
        }

        private void addComment(String text) {
            Rectangle area = null;
            for (int index : selectedStates) {
                area = area == null ? bounds(index) : area.union(bounds(index));
            }
            if (area == null) {
                return;
            }
            int x = Math.max(0, area.x - 24);
            int y = Math.max(0, area.y - 60);
            AnimationGraphComments.Box box = new AnimationGraphComments.Box(text, x, y,
                    Math.max(100, area.x + area.width + 24 - x), Math.max(80, area.y + area.height + 24 - y));
            edit(() -> comments.add(box));
            selectedComment = comments.size() - 1;
            selectedStates.clear();
        }

        private int commentAt(Point point) {
            for (int i = comments.size() - 1; i >= 0; i--) {
                if (comments.get(i).bounds().contains(point)) {
                    return i;
                }
            }
            return -1;
        }

        private void editComment(int index) {
            finishComment(true);
            selectedComment = index;
            editedComment = index;
            AnimationGraphComments.Box box = comments.get(index);
            JTextArea input = new JTextArea(box.text());
            input.setLineWrap(true);
            input.setWrapStyleWord(true);
            input.setBorder(BorderFactory.createLineBorder(Color.CYAN));
            input.setToolTipText("Ctrl+Enter: apply comment; Escape: cancel text edit");
            input.setBounds((int) (panX + (box.x() + 6) * zoom), (int) (panY + (box.y() + 4) * zoom),
                    (int) ((box.width() - 12) * zoom), Math.max(32, (int) (Math.min(56, box.height() - 12) * zoom)));
            commentEditor = input;
            add(input);
            input.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "applyComment");
            input.getActionMap().put("applyComment", new AbstractAction() {
                public void actionPerformed(ActionEvent event) {
                    finishComment(true);
                    requestFocusInWindow();
                }
            });
            input.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancelComment");
            input.getActionMap().put("cancelComment", new AbstractAction() {
                public void actionPerformed(ActionEvent event) {
                    finishComment(false);
                    requestFocusInWindow();
                }
            });
            input.addFocusListener(new FocusAdapter() {
                public void focusLost(FocusEvent event) {
                    if (commentEditor == input) {
                        finishComment(true);
                    }
                }
            });
            input.selectAll();
            input.requestFocusInWindow();
            revalidate();
            repaint();
        }

        private void finishComment(boolean commit) {
            if (commentEditor == null) {
                return;
            }
            JTextArea input = commentEditor;
            int index = editedComment;
            commentEditor = null;
            editedComment = -1;
            remove(input);
            if (commit && index >= 0 && index < comments.size()) {
                AnimationGraphComments.Box box = comments.get(index);
                edit(() -> comments.set(index, new AnimationGraphComments.Box(input.getText(), box.x(), box.y(), box.width(), box.height())));
            }
            revalidate();
            repaint();
        }

        private int edgeAt(Point point) {
            for (int i = edges.getRowCount() - 1; i >= 0; i--) {
                java.awt.geom.CubicCurve2D curve = edgeCurve(i);
                if (curve != null && nearCurve(curve, point)) {
                    return i;
                }
            }
            return -1;
        }

        private int hitState(Point point) {
            for (int i = states.getRowCount() - 1; i >= 0; i--) {
                Rectangle b = bounds(i);
                b.grow(8, 0);
                if (b.contains(point)) {
                    return i;
                }
            }
            return -1;
        }

        private void addTransition(String from, String to) {
            edges.addRow(new Object[]{from, to, "", "ALWAYS", "0", "0.2", "-1"});
            editTransition(edges.getRowCount() - 1);
        }

        private void editTransition(int row) {
            tabs.setSelectedIndex(3);
            JTable table = tables.get(2);
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 3, true));
        }

        private java.awt.geom.CubicCurve2D edgeCurve(int row) {
            int from = find(cell(edges, row, 0));
            int to = find(cell(edges, row, 1));
            if (from < 0 || to < 0) {
                return null;
            }
            Rectangle a = bounds(from);
            Rectangle b = bounds(to);
            double lane = 0;
            for (int i = 0; i < row; i++) {
                if (cell(edges, i, 0).equals(cell(edges, row, 0)) && cell(edges, i, 1).equals(cell(edges, row, 1))) {
                    lane -= 24;
                }
            }
            if (from == to) {
                return new java.awt.geom.CubicCurve2D.Double(a.x + a.width, a.y + 32,
                        a.x + a.width + 85, a.y - 90 + lane, a.x - 85, a.y - 90 + lane, a.x, a.y + 32);
            }
            boolean right = a.getCenterX() <= b.getCenterX();
            double start = right ? a.getMaxX() : a.x;
            double end = right ? b.x : b.getMaxX();
            double handle = Math.min(80, Math.abs(end - start) / 3);
            double sign = right ? 1 : -1;
            double bend = sign * (-30 + lane);
            return new java.awt.geom.CubicCurve2D.Double(start, a.y + 32,
                    start + sign * handle, a.y + 32 + bend,
                    end - sign * handle, b.y + 32 + bend, end, b.y + 32);
        }

        private boolean nearCurve(java.awt.geom.CubicCurve2D curve, Point point) {
            java.awt.geom.PathIterator path = curve.getPathIterator(null, 2);
            double[] coordinates = new double[6];
            double x = 0;
            double y = 0;
            while (!path.isDone()) {
                int type = path.currentSegment(coordinates);
                if (type == java.awt.geom.PathIterator.SEG_LINETO
                        && java.awt.geom.Line2D.ptSegDist(x, y, coordinates[0], coordinates[1], point.x, point.y) <= 7) {
                    return true;
                }
                x = coordinates[0];
                y = coordinates[1];
                path.next();
            }
            return false;
        }

        private void popup(MouseEvent event) {
            if (!isEnabled()) {
                return;
            }
            createPopup(event.getPoint()).show(this, (int) (event.getX() * zoom + panX), (int) (event.getY() * zoom + panY));
        }

        private JPopupMenu createPopup(Point point) {
            int selected = -1;
            for (int i = 0; i < states.getRowCount(); i++) {
                if (bounds(i).contains(point)) {
                    selected = i;
                }
            }
            JPopupMenu menu = new JPopupMenu();
            int edge = selected < 0 ? edgeAt(point) : -1;
            if (edge >= 0) {
                JMenuItem inspect = new JMenuItem("Edit transition");
                inspect.addActionListener(action -> editTransition(edge));
                menu.add(inspect);
                JMenuItem remove = new JMenuItem("Delete transition");
                remove.addActionListener(action -> {
                    edges.removeRow(edge);
                    hoveredEdge = -1;
                    repaint();
                });
                menu.add(remove);
                return menu;
            }
            int boxIndex = selected < 0 ? commentAt(point) : -1;
            if (boxIndex >= 0) {
                JMenuItem editText = new JMenuItem("Edit comment");
                editText.addActionListener(action -> editComment(boxIndex));
                menu.add(editText);
                JMenuItem remove = new JMenuItem("Delete comment (keep states)");
                remove.addActionListener(action -> {
                    selectedComment = boxIndex;
                    deleteSelection();
                });
                menu.add(remove);
                menu.addSeparator();
            }
            if (selected >= 0) {
                if (!selectedStates.contains(selected)) {
                    selectedStates.clear();
                    selectedStates.add(selected);
                }
                selectedComment = -1;
                JMenuItem remove = new JMenuItem("Delete selected states");
                remove.addActionListener(action -> deleteSelection());
                menu.add(remove);
            }
            if (!selectedStates.isEmpty()) {
                JMenuItem comment = new JMenuItem("Create comment from selection (C)");
                comment.addActionListener(action -> {
                    addComment("Comment");
                    editComment(comments.size() - 1);
                });
                menu.add(comment);
            }
            JMenuItem add = new JMenuItem("Add clip state...");
            add.addActionListener(action -> {
                Object clip = JOptionPane.showInputDialog(AnimationEditorTopComponent.this, "Animation clip",
                        "New state", JOptionPane.PLAIN_MESSAGE, null,
                        owner.getControl(AnimComposer.class).getAnimClipsNames().toArray(), firstClip());
                if (clip != null) {
                    String name = clip.toString();
                    int suffix = 2;
                    while (find(name) >= 0) {
                        name = clip + " " + suffix++;
                    }
                    states.addRow(new Object[]{name, "", clip, "0", "true", point.x, point.y});
                }
            });
            menu.add(add);
            if (selected >= 0) {
                String source = cell(states, selected, 0);
                JMenuItem start = new JMenuItem("Set as entry state");
                start.addActionListener(action -> edit(() -> entry.setText(source)));
                menu.add(start);
                JMenuItem connect = new JMenuItem("Add transition to...");
                connect.addActionListener(action -> {
                    String[] names = new String[states.getRowCount()];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = cell(states, i, 0);
                    }
                    Object target = JOptionPane.showInputDialog(AnimationEditorTopComponent.this, "Target state",
                            "New transition", JOptionPane.PLAIN_MESSAGE, null, names, source);
                    if (target != null) {
                        addTransition(source, target.toString());
                    }
                });
                menu.add(connect);
            }
            repaint();
            return menu;
        }

        private Rectangle bounds(int row) {
            try {
                return new Rectangle((int) number(states, row, 5), (int) number(states, row, 6), 165, 64);
            } catch (RuntimeException exception) {
                return new Rectangle(40, 40 + row * 100, 165, 64);
            }
        }

        private int find(String name) {
            for (int i = 0; i < states.getRowCount(); i++) {
                if (name.equals(cell(states, i, 0))) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(panX, panY);
            g.scale(zoom, zoom);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < comments.size(); i++) {
                AnimationGraphComments.Box box = comments.get(i);
                g.setColor(new Color(95, 130, 170, 45));
                g.fillRoundRect(box.x(), box.y(), box.width(), box.height(), 12, 12);
                g.setColor(i == selectedComment ? Color.CYAN : new Color(130, 155, 185));
                g.setStroke(new BasicStroke(i == selectedComment ? 2 : 1));
                g.drawRoundRect(box.x(), box.y(), box.width(), box.height(), 12, 12);
                Graphics2D textGraphics = (Graphics2D) g.create();
                textGraphics.clipRect(box.x() + 8, box.y() + 4, box.width() - 16, 32);
                textGraphics.setColor(getForeground());
                int lineY = box.y() + 17;
                for (String line : box.text().split("\\R", -1)) {
                    textGraphics.drawString(line, box.x() + 10, lineY);
                    lineY += 15;
                }
                textGraphics.dispose();
                g.drawLine(box.x() + box.width() - 12, box.y() + box.height() - 4,
                        box.x() + box.width() - 4, box.y() + box.height() - 12);
            }
            for (int i = 0; i < edges.getRowCount(); i++) {
                java.awt.geom.CubicCurve2D curve = edgeCurve(i);
                if (curve == null) {
                    continue;
                }
                int x2 = (int) curve.getX2();
                int y2 = (int) curve.getY2();
                g.setColor(i == hoveredEdge ? Color.CYAN : getForeground());
                g.setStroke(new BasicStroke(i == hoveredEdge ? 4 : 2));
                g.draw(curve);
                double angle = Math.atan2(y2 - curve.getCtrlY2(), x2 - curve.getCtrlX2());
                for (double direction : new double[]{angle - 0.45, angle + 0.45}) {
                    g.drawLine(x2, y2, x2 - (int) (12 * Math.cos(direction)), y2 - (int) (12 * Math.sin(direction)));
                }
                int labelX = (int) ((curve.getX1() + 3 * curve.getCtrlX1() + 3 * curve.getCtrlX2() + curve.getX2()) / 8);
                int labelY = (int) ((curve.getY1() + 3 * curve.getCtrlY1() + 3 * curve.getCtrlY2() + curve.getY2()) / 8);
                g.drawString(Integer.toString(i + 1), labelX, labelY - 5);
            }
            if (linking >= 0 && linking < states.getRowCount() && linkEnd != null) {
                Rectangle source = bounds(linking);
                g.setColor(Color.CYAN);
                g.drawLine(linkLeft ? source.x : source.x + source.width, source.y + 32, linkEnd.x, linkEnd.y);
            }
            for (int i = 0; i < states.getRowCount(); i++) {
                Rectangle b = bounds(i);
                String name = cell(states, i, 0);
                g.setColor(name.equals(activeState) ? new Color(52, 132, 92) : new Color(55, 72, 96));
                g.fillRoundRect(b.x, b.y, b.width, b.height, 14, 14);
                g.setStroke(new BasicStroke(selectedStates.contains(i) ? 3 : 1));
                g.setColor(selectedStates.contains(i) ? Color.CYAN : name.equals(entry.getText()) ? Color.ORANGE : Color.LIGHT_GRAY);
                g.drawRoundRect(b.x, b.y, b.width, b.height, 14, 14);
                g.setColor(Color.WHITE);
                g.drawString(name, b.x + 10, b.y + 23);
                g.drawString(cell(states, i, 1).isEmpty() ? "Clip" : "1D: " + cell(states, i, 1), b.x + 10, b.y + 45);
                g.setColor(new Color(75, 195, 240));
                g.fillOval(b.x + b.width - 6, b.y + 26, 12, 12);
                g.fillOval(b.x - 6, b.y + 26, 12, 12);
                g.setColor(Color.WHITE);
                g.drawOval(b.x + b.width - 6, b.y + 26, 12, 12);
                g.drawOval(b.x - 6, b.y + 26, 12, 12);
            }
            if (marquee != null) {
                g.setColor(new Color(70, 190, 255, 40));
                g.fill(marquee);
                g.setColor(Color.CYAN);
                g.setStroke(new BasicStroke(1));
                g.draw(marquee);
            }
            g.dispose();
        }
    }

    private static final class Preview extends com.jme3.gde.core.editor.SceneApplication {
        private AnimationGraphPlayer player;
        private Vector3f center = new Vector3f();
        private float radius = 4;
        private float yaw;
        private float pitch = 0.15f;
        private com.jme3.light.LightProbe studioProbe;
        volatile String stateName = "";
        volatile String message = "Preview ready";

        @Override
        public void simpleInitApp() {
            super.simpleInitApp();
            rootNode.addLight(new AmbientLight(ColorRGBA.White.mult(0.6f)));
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-1, -2, -1).normalizeLocal());
            rootNode.addLight(sun);
            // Reuse the SDK studio environment so metallic GLTF materials remain visible.
            Spatial environment = assetManager.loadModel("com/jme3/gde/core/sceneviewer/pbrenv.j3o");
            studioProbe = (com.jme3.light.LightProbe) environment.getLocalLightList().get(0).clone();
            rootNode.addLight(studioProbe);
            viewPort.setBackgroundColor(new ColorRGBA(0.12f, 0.14f, 0.18f, 1));
            setDisplayStatView(false);
            setDisplayFps(false);
        }

        void install(Spatial model, AnimationGraph graph) {
            install(model, model, graph);
        }

        void install(Spatial model, Spatial selectedOwner, AnimationGraph graph) {
            if (player != null) {
                player.close();
            }
            rootNode.detachAllChildren();
            // Do not run arbitrary gameplay, physics or environment controls in preview.
            model.depthFirstTraversal(spatial -> {
                for (int i = spatial.getNumControls() - 1; i >= 0; i--) {
                    var control = spatial.getControl(i);
                    if (!(control instanceof AnimComposer)
                            && !(control instanceof com.jme3.anim.SkinningControl)
                            && !(control instanceof com.jme3.anim.MorphControl)) {
                        spatial.removeControl(control);
                    }
                }
            });
            model.depthFirstTraversal(spatial -> {
                AnimComposer composer = spatial.getControl(AnimComposer.class);
                if (composer != null && spatial != selectedOwner) {
                    composer.setEnabled(false);
                }
            });
            rootNode.attachChild(model);
            AnimComposer composer = selectedOwner.getControl(AnimComposer.class);
            composer.setEnabled(true);
            player = new AnimationGraphPlayer(graph, composer);
            center.set(0, 0, 0);
            radius = 4;
            rootNode.updateGeometricState();
            if (model.getWorldBound() != null) {
                center = model.getWorldBound().getCenter().clone();
                if (model.getWorldBound() instanceof BoundingBox box) {
                    radius = Math.max(0.2f, Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()))) * 3.5f;
                }
            }
            studioProbe.setPosition(center);
            studioProbe.getArea().setRadius(Math.max(100, radius * 10));
            orbit(0, 0, 1);
        }

        void orbit(float dx, float dy, float scale) {
            yaw += dx;
            pitch = Math.max(-1.4f, Math.min(1.4f, pitch + dy));
            radius = Math.max(0.01f, Math.min(1e6f, radius * scale));
            cam.setLocation(center.add(new Vector3f((float) (Math.sin(yaw) * Math.cos(pitch)),
                    (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch))).multLocal(radius)));
            cam.lookAt(center, Vector3f.UNIT_Y);
            cam.setFrustumPerspective(45, Math.max(0.01f, (float) cam.getWidth() / Math.max(1, cam.getHeight())),
                    Math.max(0.001f, radius / 1000), Math.max(100, radius * 10));
        }

        @Override
        public void simpleUpdate(float tpf) {
            if (player != null) {
                stateName = player.getStateName();
                message = String.format("%s  %.2fs%s | Drag: orbit / Wheel: zoom", stateName,
                        player.getStateTime(), player.isTransitioning() ? "  (transition)" : "");
            }
        }
    }
}
