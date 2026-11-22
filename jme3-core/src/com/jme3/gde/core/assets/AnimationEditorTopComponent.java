package com.jme3.gde.core.assets;

import static com.jme3.gde.core.assets.AnimationEditorMessages.text;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.Joint;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.export.binary.AnimationLinkResolver;
import com.jme3.export.binary.AnimationLinkResolver.Link;
import com.jme3.bounding.BoundingBox;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
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
 * 第二步：Parameters 添加 FLOAT / BOOLEAN / TRIGGER 参数，默认值分别为数字 / true或false / 0。
 * 修改定义后重新 Apply preview；BOOLEAN 转换使用 TRUE / FALSE，实时参数只影响预览。
 * 第三步：在 StateName 输入状态名，选择混合参数。打开 Clips 或 Thresholds 逐行选择片段和阈值。
 * FLOAT 支持多行；BOOLEAN 或空参数只使用一个片段，阈值为 0。
 * 第四步：Transitions 按行优先级设置条件、过渡秒数和最早退出秒数（-1 不限制）。
 * 第五步：Apply preview，拖动预览旋转、滚轮缩放，修改 Live parameters 检查状态高亮。
 * 预览显示完整模型，播放 Composer 选中的控制器；环境光照仅作用于预览副本。
 * 第六步：Save J3O 保存；游戏中 AnimationGraphPlayer.attach(composerOwner) 启动。
 * 第七步：Double-click 选择 SCENE 并保存可恢复场景默认打开；右键始终可强制打开。
 * 第八步：骨架模型即使没有动作也能打开。Animation Links 添加选定片段或整个库。
 * 关联保存到当前目标 J3O。删除关联可恢复原列表，循环与重复条目不会保存。
 * 动作资源可用 Preview on Model 选择兼容角色，预览不会写入任一文件。
 * 第九步：需要单文件交付时使用 Export embedded J3O，原文件仍保留资源引用。
 * </pre>
 * Shift+wheel zooms around the cursor (25% to 300%); middle drag pans the view.
 * Navigation is EDT-only and does not alter saved positions or undo history.
 * V1 has non-interruptible transitions and a single masked override.
 * <pre>
 * 第一步：声明 MoveX、MoveY 两个 Float，在 States 中选为 X/Y 混合参数。
 * 第二步：双击剪辑或阈值列，为每个 2D 样本填写 X,Y 坐标。
 * 第三步：声明 Armed Boolean，打开 Masked Override 选择 HoldGun 和胸椎分支。
 * 第四步：Apply preview，调节两个 Float 并切换 Armed；Save J3O 保存。
 * 第五步：在 Masked Override 中移除覆盖，或清空 Y 轴恢复 1D 混合。
 * 第六步：Root Motion 选择移动根轨道及运行模式；保存 J3O 后在游戏加载自动启动。
 * 轨道列表显示短名称，悬停查看完整路径；选择后预览以黄色标出目标及子级。
 * 关闭选择器恢复原骨架显示，临时高亮不会写入模型。
 * 预览用 Apply preview 后勾选 Preview root movement；关闭复选框只看原地姿势。
 * 物理角色选择 Extract Only，在 Java 角色控制器中消费每帧位移。
 * </pre>
 * Drag between facing sides of horizontally separated states. The direction follows
 * the drag; the two outer ports cannot be connected. Moving a state reroutes its edges.
 * 第一步：从 Idle 右端拖到 Walk 左端。第二步：从 Walk 左端拖回 Idle 右端建立返回过渡。
 * Click an arrow to select its transition row; each direction is a separate edge. New
 * edges default to ALWAYS with a 0.2-second fade and no exit-time restriction;
 * configure their condition before applying preview. Escape cancels a drag.
 * <pre>
 * 第一步：从蓝色圆点拖到另一节点异侧的圆点，松开后创建由起点到目标的过渡。
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
    private final JLabel guidance = new JLabel();
    private final JTextField entry = new JTextField(12);
    private final DefaultTableModel parameters = model(text("Animation.Name"), text("Animation.Type"), text("Animation.Default"));
    private final DefaultTableModel states = model("StateName", text("Animation.Blend_parameter"), text("Animation.Clips_separated"), text("Animation.Thresholds"), text("Animation.Loop"), "X", "Y", text("Animation.Blend_parameter_y"));
    private Overlay overlay;
    private RootMotion rootMotion;
    private static final String PORTS_DATA = "jme.animationGraph.ports.v1";
    private final DefaultTableModel edges = edgeModel();
    private final List<JTable> tables = new ArrayList<>();
    private final List<AnimationGraphComments.Box> comments = new ArrayList<>();
    private final JPanel previewPanel = new JPanel(new BorderLayout());
    private final JPanel live = new JPanel();
    private final JLabel status = new JLabel(text("Animation.Loading_preview"));
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
    private String savedPorts;
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
        setName(text("Animation.Animation") + data.getPrimaryFile().getNameExt());
        setLayout(new BorderLayout(6, 6));
        setActivatedNodes(new org.openide.nodes.Node[]{data.getNodeDelegate()});
        JPanel toolbar = new AnimationToolbar();
        refreshOwnerChoices();
        toolbar.add(new JLabel(text("Animation.Composer")));
        toolbar.add(ownerChoice);
        ownerChoice.setToolTipText(text("Animation.More_tracks_may_cover_more_parts_See_Guide_for_track_targets_node"));
        toolbar.add(new JLabel(text("Animation.Entry")));
        toolbar.add(entry);
        toolbar.add(new JLabel(text("Animation.Double_click")));
        toolbar.add(preference);
        preference.setRenderer(AnimationEditorMessages.valueRenderer());
        JPanel playback = new AnimationToolbar();
        playback.add(button(text("Animation.Guide"), this::showGuide));
        toolbar.add(button(text("Animation.External_links"), this::showAnimationLinks));
        toolbar.add(button(text("Animation.Export_embedded"), this::exportEmbedded));
        playback.add(button(text("Animation.Masked_override"), this::editOverlay));
        toolbar.add(button(text("Animation.Root_motion"), this::editRootMotion));
        playback.add(button(text("Animation.Preview_source_clip"), this::previewSourceClip));
        playback.add(button(text("Animation.Apply_preview"), () -> applyPreview()));
        playback.add(button(text("Animation.Pause"), () -> enqueuePlayer(player -> player.setPaused(true))));
        playback.add(button(text("Animation.Play"), () -> enqueuePlayer(player -> player.setPaused(false))));
        playback.add(button(text("Animation.Reset"), () -> applyPreview()));
        save = button(text("Animation.Save_J3O"), this::save);
        playback.add(save);
        undoButton = button(text("Animation.Undo"), () -> undoEdit(false));
        redoButton = button(text("Animation.Redo"), () -> undoEdit(true));
        playback.add(undoButton);
        playback.add(redoButton);
        undo.addChangeListener(event -> updateHistoryButtons());
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(toolbar);
        top.add(playback);
        add(top, BorderLayout.NORTH);
        tabs.addTab(text("Animation.State_graph"), new JScrollPane(canvas));
        tabs.addTab(text("Animation.Parameters"), tablePanel(parameters, new Object[]{text("Animation.Parameter"), "FLOAT", "0"}));
        tabs.addTab(text("Animation.States_1D_2D_blend"), tablePanel(states,
                new Object[]{text("Animation.State"), "", firstClip(), "0", "true", "40", "40", ""}));
        tabs.addTab(text("Animation.Transitions"), tablePanel(edges, new Object[]{"", "", "", "ALWAYS", "0", "0.2", "-1"}));
        JPanel previewContainer = new JPanel(new BorderLayout());
        previewPanel.setPreferredSize(new Dimension(420, 380));
        previewContainer.add(previewPanel, BorderLayout.CENTER);
        JCheckBox skeleton = new JCheckBox(text("Animation.Show_skeleton_targets_cyan_skeleton_orange_selected_joints_purple"));
        skeleton.setToolTipText(text("Animation.This_graph_uses_the_selected_Composer_Highlights_show_direct_targ"));
        skeleton.addActionListener(event -> {
            boolean visible = skeleton.isSelected();
            if (preview != null) {
                Preview app = preview;
                app.enqueue(() -> app.showTargets(visible));
            }
        });
        JPanel previewOptions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        previewOptions.add(skeleton);
        JCheckBox previewMotion = new JCheckBox(text("Animation.Preview_root_motion"));
        previewMotion.addActionListener(event -> {
            boolean enabled = previewMotion.isSelected();
            if (preview != null) {
                Preview app = preview;
                app.enqueue(() -> app.rootMotionVisible = enabled);
            }
        });
        previewOptions.add(previewMotion);
        previewContainer.add(previewOptions, BorderLayout.NORTH);
        live.setLayout(new BoxLayout(live, BoxLayout.Y_AXIS));
        JScrollPane controls = new JScrollPane(live);
        controls.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        controls.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        controls.getVerticalScrollBar().setUnitIncrement(24);
        controls.setPreferredSize(new Dimension(420, 170));
        previewContainer.add(controls, BorderLayout.SOUTH);
        // Divider limits must not depend on translated help text or native panel size.
        tabs.setMinimumSize(new Dimension(0, 0));
        previewContainer.setMinimumSize(new Dimension(0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, previewContainer);
        split.setResizeWeight(0.60);
        add(split, BorderLayout.CENTER);
        // Leave the graph its full height by putting diagnostics in the bottom row.
        JPanel footer = new JPanel(new GridLayout(1, 2, 12, 0)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                // Diagnostics use available width; JLabel clips with an ellipsis.
                // Put the full text in tooltips so the labels can fit in the panel.
                return new Dimension(0, size.height);
            }
        };
        guidance.setHorizontalAlignment(SwingConstants.RIGHT);
        for (JLabel label : new JLabel[]{status, guidance}) {
            label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
            label.addPropertyChangeListener("text", event -> label.setToolTipText(label.getText()));
            label.setToolTipText(label.getText());
            footer.add(label);
        }
        add(footer, BorderLayout.SOUTH);
        String storedPreference = root.getUserData(AnimationEditorSupport.PREFERENCE);
        preference.setSelectedItem(storedPreference == null ? AnimationEditorSupport.Preference.SCENE
                : parsePreference(storedPreference));
        loadOwner();
        refreshGuidance();
        ownerChoice.addActionListener(event -> changeOwner());
        for (DefaultTableModel model : new DefaultTableModel[]{parameters, states, edges}) {
            model.addTableModelListener(event -> {
                changed();
                if (!loading && (model != states || event.getColumn() < 5)) {
                    showPendingParameters();
                }
            });
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
            refreshGuidance();
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
        AnimComposer composer = owner.getControl(AnimComposer.class);
        return composer.getAnimClips().stream()
                .filter(clip -> !AnimationEditorSupport.constantTransformClip(clip))
                .map(com.jme3.anim.AnimClip::getName).sorted().findFirst()
                .orElseGet(() -> composer.getAnimClipsNames().stream().sorted().findFirst().orElse(""));
    }

    private void refreshOwnerChoices() {
        var items = new javax.swing.DefaultComboBoxModel<String>();
        for (int i = 0; i < owners.size(); i++) {
            Spatial model = owners.get(i);
            AnimComposer composer = model.getControl(AnimComposer.class);
            int tracks = composer.getAnimClips().stream().mapToInt(clip -> clip.getTracks().length).max().orElse(0);
            items.addElement(i + ": " + model.getName() + " (" + composer.getAnimClipsNames().size()
                    + text("Animation.clips") + tracks + text("Animation.tracks_max"));
        }
        items.setSelectedItem(items.getElementAt(ownerIndex));
        ownerChoice.setModel(items);
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

    private DefaultTableModel edgeModel() {
        return new DefaultTableModel(new String[]{text("Animation.From"), text("Animation.To"),
            text("Animation.Parameter"), text("Animation.Condition"), text("Animation.Value"),
            text("Animation.Fade_seconds"), text("Animation.Exit_seconds_1_off"), "SourceLeft"}, 0) {
            @Override
            public void addRow(Object[] values) {
                Object[] row = Arrays.copyOf(values, 8);
                if (row[7] == null) {
                    int from = canvas.find(String.valueOf(row[0]));
                    int to = canvas.find(String.valueOf(row[1]));
                    row[7] = from >= 0 && to >= 0 && from != to
                            && canvas.bounds(from).getCenterX() > canvas.bounds(to).getCenterX();
                }
                super.addRow(row);
            }
        };
    }

    /**
     * Editor-only sides follow transition rows through history and J3O saves.
     * Older graphs infer their sides once from the loaded positions.
     * 第一步：从需要的左/右端口创建连线；第二步：移动节点只改变路径。
     * 第三步：Save J3O 保存端口，重新打开、撤销及重做均保留连接侧。
     */
    private String encodePorts() {
        StringBuilder result = new StringBuilder("1:");
        for (int i = 0; i < edges.getRowCount(); i++) {
            result.append(Boolean.TRUE.equals(edges.getValueAt(i, 7)) ? 'L' : 'R');
        }
        return result.toString();
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JPanel tablePanel(DefaultTableModel model, Object[] defaults) {
        JTable table = new JTable(model) {
            @Override
            public javax.swing.table.TableCellEditor getCellEditor(int row, int column) {
                if (model == states) {
                    int stateRow = convertRowIndexToModel(row);
                    return switch (convertColumnIndexToModel(column)) {
                        case 0 -> stateNameEditor(stateRow);
                        case 1 -> blendParameterEditor();
                        case 2, 3 -> new SamplesEditor(stateRow);
                        default -> super.getCellEditor(row, column);
                    };
                }
                if (model == parameters && column == 2
                        && "BOOLEAN".equals(cell(model, row, 1))) {
                    return valueEditor(new String[]{"true", "false"});
                }
                if (model == states && convertColumnIndexToModel(column) == 7) {
                    List<String> choices = new ArrayList<>();
                    choices.add("");
                    for (int i = 0; i < parameters.getRowCount(); ++i) {
                        if ("FLOAT".equals(cell(parameters, i, 1))) {
                            choices.add(cell(parameters, i, 0));
                        }
                    }
                    return new DefaultCellEditor(new JComboBox<>(choices.toArray(String[]::new)));
                }
                if (model == edges && column == 2) {
                    List<String> names = new ArrayList<>();
                    names.add("");
                    Operator op = Operator.valueOf(cell(edges, row, 3));
                    Type expected = op == Operator.TRIGGER ? Type.TRIGGER
                            : op == Operator.TRUE || op == Operator.FALSE ? Type.BOOLEAN : Type.FLOAT;
                    for (int i = 0; i < parameters.getRowCount(); i++) {
                        if (op == Operator.ALWAYS || expected.name().equals(cell(parameters, i, 1))) {
                            names.add(cell(parameters, i, 0));
                        }
                    }
                    return new DefaultCellEditor(new JComboBox<>(names.toArray(String[]::new)));
                }
                if (model == edges && (column == 0 || column == 1)) {
                    List<String> names = new ArrayList<>();
                    names.add("");
                    for (int i = 0; i < states.getRowCount(); i++) {
                        names.add(cell(states, i, 0));
                    }
                    return new DefaultCellEditor(new JComboBox<>(names.toArray(String[]::new)));
                }
                return super.getCellEditor(row, column);
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                int field = convertColumnIndexToModel(column);
                if (model == states && field == 0) {
                    renameState(convertRowIndexToModel(row), value.toString());
                } else if (model == states && field == 1) {
                    changeBlendParameter(convertRowIndexToModel(row), value.toString());
                } else if (model == states && field == 7) {
                    changeBlendY(convertRowIndexToModel(row), value.toString());
                } else {
                    super.setValueAt(value, row, column);
                }
            }

            @Override
            public Object getValueAt(int row, int column) {
                Object value = super.getValueAt(row, column);
                if (model == parameters && column == 2
                        && "BOOLEAN".equals(cell(model, row, 1))) {
                    return Boolean.toString(booleanDefault(value.toString()) != 0);
                }
                return value;
            }
        };
        if (model == edges) {
            table.removeColumn(table.getColumnModel().getColumn(7));
        }
        tables.add(table);
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                    boolean focused, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focused, row, column);
                if ((model == parameters && (column == 1 || (column == 2
                        && "BOOLEAN".equals(cell(model, row, 1)))))
                        || (model == edges && column == 3) || (model == states && column == 4)) {
                    setText(AnimationEditorMessages.value(value));
                }
                int field = source.convertColumnIndexToModel(column);
                if (model == states && field == 0) {
                    setText(stateTitle(source.convertRowIndexToModel(row)));
                }
                if (model == states && (field == 2 || field == 3)) {
                    String label = field == 2 ? Arrays.stream(value.toString().split("\\|"))
                            .map(clip -> editorClipLabel(clip, clipNames())).collect(java.util.stream.Collectors.joining(" | "))
                            : value.toString();
                    setText(label + "  …");
                    setToolTipText(text("Animation.Edit_samples"));
                } else {
                    setToolTipText(null);
                }
                return this;
            }
        });
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 2 && model == states ? 240 : 120);
        }
        if (model == parameters) {
            table.getColumnModel().getColumn(1).setCellEditor(valueEditor(Type.values()));
        } else if (model == edges) {
            table.getColumnModel().getColumn(3).setCellEditor(valueEditor(Operator.values()));
        } else {
            table.getColumnModel().getColumn(4).setCellEditor(valueEditor(new String[]{"true", "false"}));
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button(text("Animation.Add"), () -> {
            Object[] row = defaults.clone();
            if (model == states) {
                row[0] = text("Animation.State_40") + (states.getRowCount() + 1);
                row[2] = firstClip();
            } else if (model == parameters) {
                row[0] = text("Animation.Parameter") + (parameters.getRowCount() + 1);
            }
            edit(() -> {
                model.addRow(row);
                if (model == states && states.getRowCount() == 1) {
                    entry.setText(row[0].toString());
                }
            });
        }));
        buttons.add(button(text("Animation.Remove"), () -> {
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
        buttons.add(button(text("Animation.Move_up"), () -> {
            int row = table.getSelectedRow();
            if (row > 0) {
                model.moveRow(row, row, row - 1);
                table.setRowSelectionInterval(row - 1, row - 1);
            }
        }));
        if (model == states) {
            buttons.add(button(text("Animation.Edit_blend_y"), () -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    int column = table.convertColumnIndexToView(7);
                    table.scrollRectToVisible(table.getCellRect(row, column, true));
                    if (table.editCellAt(row, column) && table.getEditorComponent() instanceof JComboBox<?> choice) {
                        choice.requestFocusInWindow();
                        choice.showPopup();
                    }
                }
            }));
        }
        panel.add(buttons, BorderLayout.SOUTH);
        if (model == states) {
            JLabel help = new JLabel(text("Animation.Blend_axes_help"));
            panel.add(help, BorderLayout.NORTH);
        } else if (model == edges) {
            panel.add(new JLabel(text("Animation.Top_row_wins_Exit_is_elapsed_seconds_1_disables_it_Active_fades_c")), BorderLayout.NORTH);
        }
        return panel;
    }

    private List<String> clipNames() {
        return owner.getControl(AnimComposer.class).getAnimClipsNames().stream().sorted().toList();
    }

    private DefaultCellEditor stateNameEditor(int row) {
        JTextField name = new JTextField();
        return new DefaultCellEditor(name) {
            @Override
            public boolean stopCellEditing() {
                if (!canNameState(row, name.getText().trim())) {
                    name.setBackground(new Color(255, 210, 210));
                    return false;
                }
                name.setText(name.getText().trim());
                return super.stopCellEditing();
            }
        };
    }

    private boolean canNameState(int row, String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < states.getRowCount(); i++) {
            if (i != row && cell(states, i, 0).equals(name)) {
                return false;
            }
        }
        return true;
    }

    private void renameState(int row, String name) {
        String previous = cell(states, row, 0);
        if (previous.equals(name) || !canNameState(row, name)) {
            return;
        }
        edit(() -> {
            states.setValueAt(name, row, 0);
            for (int i = 0; i < edges.getRowCount(); i++) {
                for (int endpoint = 0; endpoint < 2; endpoint++) {
                    if (cell(edges, i, endpoint).equals(previous)) {
                        edges.setValueAt(name, i, endpoint);
                    }
                }
            }
            if (entry.getText().trim().equals(previous)) {
                entry.setText(name);
            }
        });
    }

    private DefaultCellEditor blendParameterEditor() {
        var choice = new JComboBox<String>();
        choice.addItem("");
        for (int i = 0; i < parameters.getRowCount(); i++) {
            String type = cell(parameters, i, 1);
            if (Type.FLOAT.name().equals(type) || Type.BOOLEAN.name().equals(type)) {
                choice.addItem(cell(parameters, i, 0));
            }
        }
        return new DefaultCellEditor(choice);
    }

    private boolean canBlend(String parameter) {
        for (int i = 0; i < parameters.getRowCount(); i++) {
            if (parameter.equals(cell(parameters, i, 0))) {
                return Type.FLOAT.name().equals(cell(parameters, i, 1));
            }
        }
        return false;
    }

    private void changeBlendParameter(int row, String parameter) {
        if (parameter.equals(cell(states, row, 1))) {
            return;
        }
        boolean blend = canBlend(parameter);
        if (blend && parameter.equals(cell(states, row, 7))) {
            status.setText(text("Animation.Two_float_axes"));
            return;
        }
        if (!blend && cell(states, row, 2).contains("|")) {
            EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    text("Animation.Remove_blend_samples_first")));
            return;
        }
        edit(() -> {
            states.setValueAt(parameter, row, 1);
            if (!blend) {
                states.setValueAt("", row, 7);
                states.setValueAt("0", row, 3);
            }
        });
        showPendingParameters();
    }

    private void changeBlendY(int row, String parameter) {
        String previous = cell(states, row, 7);
        if (previous.equals(parameter)) {
            return;
        }
        String x = cell(states, row, 1);
        if (!parameter.isEmpty() && (!canBlend(x) || !canBlend(parameter) || parameter.equals(x))) {
            status.setText(text("Animation.Two_float_axes"));
            return;
        }
        edit(() -> {
            states.setValueAt(parameter, row, 7);
            if (previous.isEmpty() != parameter.isEmpty()) {
                String[] samples = cell(states, row, 3).split("\\|", -1);
                for (int i = 0; i < samples.length; ++i) {
                    samples[i] = parameter.isEmpty() ? Integer.toString(i) : samples[i] + ",0";
                }
                states.setValueAt(String.join("|", samples), row, 3);
            }
        });
        showPendingParameters();
    }

    private void editOverlay() {
        List<String> switches = new ArrayList<>();
        for (int i = 0; i < parameters.getRowCount(); ++i) {
            if ("BOOLEAN".equals(cell(parameters, i, 1))) {
                switches.add(cell(parameters, i, 0));
            }
        }
        var composer = owner.getControl(AnimComposer.class);
        List<String> clips = clipNames().stream().filter(name ->
                Arrays.stream(composer.getAnimClip(name).getTracks()).anyMatch(track ->
                        track instanceof TransformTrack transform && transform.getTarget() instanceof Joint)).toList();
        if ((switches.isEmpty() || clips.isEmpty()) && overlay == null) {
            status.setText(text("Animation.Overlay_requires_inputs"));
            return;
        }
        var selectedSwitch = new JComboBox<>(switches.toArray(String[]::new));
        var selectedClip = new JComboBox<>(clips.toArray(String[]::new));
        var fade = new JTextField(overlay == null ? "0.15" : Float.toString(overlay.fade));
        if (overlay != null) {
            selectedSwitch.setSelectedItem(overlay.parameter);
            selectedClip.setSelectedItem(overlay.clip);
        }
        var skeleton = new javax.swing.tree.DefaultMutableTreeNode(text("Animation.Mask_bones"));
        var tree = new JTree(skeleton);
        tree.setRootVisible(false);
        var selectedRoots = new ArrayList<javax.swing.tree.TreePath>();
        var nodes = new java.util.LinkedHashMap<String, javax.swing.tree.DefaultMutableTreeNode>();
        Runnable refresh = () -> {
            selectedRoots.clear();
            nodes.clear();
            skeleton.removeAllChildren();
            var clip = composer.getAnimClip((String) selectedClip.getSelectedItem());
            if (clip != null) {
                for (var track : clip.getTracks()) {
                    if (track instanceof TransformTrack transform && transform.getTarget() instanceof Joint joint) {
                        addMaskJoint(joint, skeleton, nodes);
                    }
                }
            }
            ((javax.swing.tree.DefaultTreeModel) tree.getModel()).reload();
            for (var item : nodes.entrySet()) {
                var path = new javax.swing.tree.TreePath(item.getValue().getPath());
                tree.expandPath(path);
                if (overlay != null && overlay.joints.contains(item.getKey())) {
                    selectedRoots.add(path);
                }
            }
            tree.setSelectionPaths(selectedRoots.toArray(javax.swing.tree.TreePath[]::new));
        };
        selectedClip.addActionListener(event -> refresh.run());
        refresh.run();
        var form = new JPanel(new GridLayout(3, 2, 6, 4));
        form.add(new JLabel(text("Animation.Overlay_boolean")));
        form.add(selectedSwitch);
        form.add(new JLabel(text("Animation.Overlay_clip")));
        form.add(selectedClip);
        form.add(new JLabel(text("Animation.Overlay_fade_seconds")));
        form.add(fade);
        var panel = new JPanel(new BorderLayout(6, 6));
        panel.add(form, BorderLayout.NORTH);
        var scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(460, 260));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(new JLabel(text("Animation.Mask_subtrees_hint")), BorderLayout.SOUTH);
        String[] options = {text("Animation.Apply_mask_override"), text("Animation.Remove"), text("Animation.Close_external_links")};
        while (true) {
            int choice = JOptionPane.showOptionDialog(this, panel, text("Animation.Masked_override"),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 1) {
                edit(() -> overlay = null);
                showPendingParameters();
                return;
            }
            if (choice != 0) {
                return;
            }
            try {
                var paths = tree.getSelectionPaths();
                float seconds = Float.parseFloat(fade.getText().trim());
                if (paths == null || selectedSwitch.getSelectedItem() == null
                        || selectedClip.getSelectedItem() == null || !Float.isFinite(seconds) || seconds < 0) {
                    throw new IllegalArgumentException(text("Animation.Overlay_requires_inputs"));
                }
                List<String> masks = Arrays.stream(paths)
                        .map(path -> (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent())
                        .map(node -> ((MaskJoint) node.getUserObject()).path()).toList();
                edit(() -> overlay = new Overlay((String) selectedSwitch.getSelectedItem(),
                        (String) selectedClip.getSelectedItem(), seconds, masks));
                showPendingParameters();
                return;
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(this, invalid.getMessage());
            }
        }
    }

    private void editRootMotion() {
        var candidates = new java.util.LinkedHashMap<String, RootTrack>();
        var composer = owner.getControl(AnimComposer.class);
        for (int row = 0; row < states.getRowCount(); ++row) {
            for (String name : cell(states, row, 2).split("\\|")) {
                var clip = composer.getAnimClip(name.trim());
                if (clip == null) {
                    continue;
                }
                for (var track : clip.getTracks()) {
                    if (track instanceof TransformTrack transform) {
                        if (transform.getTarget() instanceof Joint joint) {
                            String path = AnimationGraph.jointPath(joint);
                            candidates.putIfAbsent("joint:" + path, new RootTrack(path, true));
                        } else if (transform.getTarget() instanceof Spatial spatial) {
                            String path = AnimationGraph.nodePath(owner, spatial);
                            if (path != null) {
                                candidates.putIfAbsent("node:" + path, new RootTrack(path, false));
                            }
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty() && rootMotion == null) {
            status.setText(text("Animation.Root_motion_requires_track"));
            return;
        }
        var source = new JComboBox<>(candidates.values().toArray(RootTrack[]::new));
        source.setPrototypeDisplayValue(new RootTrack("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM", true));
        source.setMaximumRowCount(12);
        source.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setToolTipText(value instanceof RootTrack track ? track.hint() : null);
                return this;
            }
        });
        var automatic = new JCheckBox(text("Animation.Root_motion_auto"), rootMotion == null || rootMotion.autoApply);
        if (rootMotion != null) {
            source.setSelectedItem(new RootTrack(rootMotion.path, rootMotion.joint));
        }
        Runnable highlight = () -> {
            RootTrack track = (RootTrack) source.getSelectedItem();
            source.setToolTipText(track == null ? null : track.hint());
            Preview app = preview;
            if (app != null) {
                app.enqueue(() -> app.focusRootTrack(track));
            }
        };
        source.addActionListener(event -> highlight.run());
        highlight.run();
        var form = new JPanel(new GridLayout(0, 1, 0, 5));
        form.add(new JLabel(text("Animation.Root_motion_track")));
        form.add(source);
        form.add(automatic);
        form.add(new JLabel(text("Animation.Root_motion_consumer_hint")));
        String[] options = {text("Animation.Apply_mask_override"), text("Animation.Disable_root_motion"),
            text("Animation.Close_external_links")};
        try {
            while (true) {
                int choice = JOptionPane.showOptionDialog(this, form, text("Animation.Root_motion"),
                        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
                if (choice == 1) {
                    edit(() -> rootMotion = null);
                    showPendingParameters();
                    return;
                }
                if (choice != 0) {
                    return;
                }
                if (source.getSelectedItem() instanceof RootTrack track) {
                    if (!track.joint() && ".".equals(track.path()) && automatic.isSelected()) {
                        JOptionPane.showMessageDialog(this, text("Animation.Root_motion_needs_child"));
                        continue;
                    }
                    edit(() -> rootMotion = new RootMotion(track.path(), track.joint(), automatic.isSelected()));
                    showPendingParameters();
                    return;
                }
                JOptionPane.showMessageDialog(this, text("Animation.Root_motion_requires_track"));
            }
        } finally {
            Preview app = preview;
            if (app != null) {
                app.enqueue(() -> app.focusRootTrack(null));
            }
        }
    }

    private record RootTrack(String path, boolean joint) {
        @Override
        public String toString() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.length() > 45) {
                name = name.substring(0, 42) + "...";
            }
            return text(joint ? "Animation.Root_motion_joint" : "Animation.Root_motion_node") + " " + name;
        }

        String hint() {
            return text(joint ? "Animation.Root_motion_joint" : "Animation.Root_motion_node") + " " + path;
        }
    }

    private static javax.swing.tree.DefaultMutableTreeNode addMaskJoint(Joint joint,
            javax.swing.tree.DefaultMutableTreeNode root,
            Map<String, javax.swing.tree.DefaultMutableTreeNode> nodes) {
        String path = AnimationGraph.jointPath(joint);
        var found = nodes.get(path);
        if (found != null) {
            return found;
        }
        var parent = joint.getParent() == null ? root : addMaskJoint(joint.getParent(), root, nodes);
        var node = new javax.swing.tree.DefaultMutableTreeNode(new MaskJoint(joint.getName(), path));
        parent.add(node);
        nodes.put(path, node);
        return node;
    }

    private record MaskJoint(String name, String path) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? "(root)" : name;
        }
    }

    private void applySamples(int row, Samples samples) {
        edit(() -> {
            states.setValueAt(samples.clips(), row, 2);
            states.setValueAt(samples.thresholds(), row, 3);
        });
        showPendingParameters();
    }

    private final class SamplesEditor extends AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final int row;

        private SamplesEditor(int row) {
            this.row = row;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int viewRow, int column) {
            JButton open = button(text("Animation.Edit_samples"), () -> {
                cancelCellEditing();
                var panel = new StateSamplesPanel(clipNames(), canBlend(cell(states, row, 1)),
                        cell(states, row, 2), cell(states, row, 3), !cell(states, row, 7).isEmpty(),
                        data.getPrimaryFile().getName());
                while (JOptionPane.showConfirmDialog(table, panel, text("Animation.Edit_samples"),
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    try {
                        applySamples(row, panel.samples());
                        break;
                    } catch (IllegalArgumentException invalid) {
                        JOptionPane.showMessageDialog(table, invalid.getMessage());
                    }
                }
            });
            EventQueue.invokeLater(() -> {
                if (open.isShowing() && table.getEditorComponent() == open) {
                    open.doClick(0);
                }
            });
            return open;
        }

        @Override
        public Object getCellEditorValue() {
            return null;
        }

        @Override
        public boolean stopCellEditing() {
            cancelCellEditing();
            return true;
        }
    }

    record Samples(String clips, String thresholds) {
    }

    static final class StateSamplesPanel extends JPanel {
        private final List<String> available;
        private final java.util.Map<String, String> labels;
        private final boolean blend;
        private final boolean twoDimensional;
        private final List<SampleRow> samples = new ArrayList<>();
        private final JPanel rows = new JPanel(new GridBagLayout());
        private final JButton add;

        StateSamplesPanel(List<String> available, boolean blend, String clips, String thresholds) {
            this(available, blend, clips, thresholds, false);
        }

        StateSamplesPanel(List<String> available, boolean blend, String clips, String thresholds, boolean twoDimensional) {
            this(available, blend, clips, thresholds, twoDimensional, null);
        }

        StateSamplesPanel(List<String> available, boolean blend, String clips, String thresholds, boolean twoDimensional,
                String localAssetName) {
            super(new BorderLayout(0, 6));
            this.available = available;
            labels = linkedClipChoices(available, localAssetName).stream().collect(java.util.stream.Collectors.toMap(
                    LinkedClipChoice::id, LinkedClipChoice::label));
            this.blend = blend;
            this.twoDimensional = twoDimensional;
            add = button(text("Animation.Add_clip_sample"), () -> addSample(null, nextThreshold()));
            var scroll = new JScrollPane(rows);
            scroll.setPreferredSize(new Dimension(460, 200));
            add(scroll, BorderLayout.CENTER);
            add(add, BorderLayout.SOUTH);
            add(new JLabel(text(twoDimensional ? "Animation.Blend_sample_xy"
                    : blend ? "Animation.Blend_sample_columns" : "Animation.Single_clip_sample")),
                    BorderLayout.NORTH);
            String[] names = clips.split("\\|", -1);
            String[] values = thresholds.split("\\|", -1);
            for (int i = 0; i < names.length; i++) {
                addSample(names[i].isEmpty() ? null : names[i], i < values.length ? values[i] : "");
            }
        }

        private void addSample(String clip, String threshold) {
            var choice = new JComboBox<>(available.toArray(String[]::new));
            var renderer = new DefaultListCellRenderer();
            choice.setRenderer((list, value, index, selected, focused) -> renderer
                    .getListCellRendererComponent(list, labels.getOrDefault(value, value), index, selected, focused));
            if (clip != null && !available.contains(clip)) {
                choice.addItem(clip);
            }
            if (clip != null) {
                choice.setSelectedItem(clip);
            }
            var value = new JTextField(threshold, 8);
            value.setToolTipText(text("Animation.Thresholds"));
            var sample = new SampleRow(choice, value);
            samples.add(sample);
            rebuildRows();
        }

        private String nextThreshold() {
            if (twoDimensional) {
                return "0,0";
            }
            try {
                double last = Float.parseFloat(samples.getLast().threshold().getText().trim());
                return Float.toString((float) (last + 1d));
            } catch (NumberFormatException invalid) {
                return "";
            }
        }

        private void rebuildRows() {
            rows.removeAll();
            for (int i = 0; i < samples.size(); i++) {
                SampleRow sample = samples.get(i);
                var position = new GridBagConstraints();
                position.gridy = i;
                position.weightx = 1;
                position.fill = GridBagConstraints.HORIZONTAL;
                rows.add(sample.clip(), position);
                position.weightx = 0;
                if (blend) {
                    rows.add(sample.threshold(), position);
                }
                var remove = button(text("Animation.Remove"), () -> {
                    samples.remove(sample);
                    rebuildRows();
                });
                remove.setEnabled(samples.size() > 1);
                rows.add(remove, position);
            }
            var remaining = new GridBagConstraints();
            remaining.gridy = samples.size();
            remaining.weighty = 1;
            rows.add(Box.createGlue(), remaining);
            add.setEnabled(blend && !available.isEmpty());
            rows.revalidate();
            rows.repaint();
        }

        Samples samples() {
            if (!blend && samples.size() > 1) {
                throw new IllegalArgumentException(text("Animation.Remove_blend_samples_first"));
            }
            var clips = new ArrayList<String>();
            var thresholds = new ArrayList<String>();
            float previous = Float.NEGATIVE_INFINITY;
            var coordinates = new java.util.HashSet<String>();
            for (SampleRow row : samples) {
                String clip = (String) row.clip().getSelectedItem();
                if (clip == null || !available.contains(clip)) {
                    throw new IllegalArgumentException(text("Animation.Select_available_clip"));
                }
                clips.add(clip);
                if (twoDimensional) {
                    String[] pair = row.threshold().getText().split(",", -1);
                    if (pair.length != 2) {
                        throw new IllegalArgumentException(text("Animation.Unique_sample_xy"));
                    }
                    float x = Float.parseFloat(pair[0].trim());
                    float y = Float.parseFloat(pair[1].trim());
                    String xy = (x == 0 ? 0f : x) + "," + (y == 0 ? 0f : y);
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !coordinates.add(xy)) {
                        throw new IllegalArgumentException(text("Animation.Unique_sample_xy"));
                    }
                    thresholds.add(xy);
                    continue;
                }
                float value = 0;
                if (blend) {
                    try {
                        value = Float.parseFloat(row.threshold().getText().trim());
                    } catch (NumberFormatException invalid) {
                        value = Float.NaN;
                    }
                    if (!Float.isFinite(value) || value <= previous) {
                        throw new IllegalArgumentException(text("Animation.Increasing_sample_thresholds"));
                    }
                }
                thresholds.add(Float.toString(value));
                previous = value;
            }
            return new Samples(String.join("|", clips), String.join("|", thresholds));
        }

        private record SampleRow(JComboBox<String> clip, JTextField threshold) {
        }
    }

    private static DefaultCellEditor valueEditor(Object[] values) {
        JComboBox<Object> choice = new JComboBox<>(values);
        choice.setRenderer(AnimationEditorMessages.valueRenderer());
        return new DefaultCellEditor(choice);
    }

    /**
     * 第一步：编辑参数或转换；第二步：检查实时区待应用列表；第三步：应用预览。
     * Editing definitions never silently restarts playback; stale controls cannot mutate the old player.
     */
    private void showPendingParameters() {
        live.removeAll();
        live.add(new JLabel(text("Animation.PendingPreview")));
        for (int i = 0; i < parameters.getRowCount(); i++) {
            live.add(new JLabel(cell(parameters, i, 0) + " ("
                    + AnimationEditorMessages.value(cell(parameters, i, 1)) + ")"));
        }
        live.add(button(text("Animation.Apply_preview"), this::applyPreview));
        compactLiveRows();
        live.revalidate();
        live.repaint();
    }

    /** Leaves extra space below the preview controls so the parameter rows keep their height. */
    private void compactLiveRows() {
        int nameWidth = 0;
        for (Component component : live.getComponents()) {
            if (component instanceof JPanel row && row.getComponentCount() > 0
                    && row.getComponent(0) instanceof JLabel name) {
                nameWidth = Math.max(nameWidth, name.getFontMetrics(name.getFont()).stringWidth(name.getText()));
            }
        }
        for (Component component : live.getComponents()) {
            if (component instanceof JPanel row && row.getComponentCount() > 0
                    && row.getComponent(0) instanceof JLabel name) {
                name.setPreferredSize(new Dimension(nameWidth + 8, name.getPreferredSize().height));
            }
        }
        for (Component component : live.getComponents()) {
            if (component instanceof JComponent child) {
                child.setAlignmentX(Component.LEFT_ALIGNMENT);
                child.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
            }
        }
    }

    private void loadOwner() {
        loading = true;
        try {
            parameters.setRowCount(0);
            states.setRowCount(0);
            edges.setRowCount(0);
            comments.clear();
            overlay = null;
            rootMotion = null;
            canvas.clearSelection();
            savedGraph = owner.getUserData(AnimationGraph.USER_DATA);
            savedComments = owner.getUserData(AnimationGraphComments.USER_DATA);
            savedPorts = owner.getUserData(PORTS_DATA);
            comments.addAll(AnimationGraphComments.decode(savedComments));
            AnimationGraph graph;
            if (savedGraph == null) {
                List<State> initial = new ArrayList<>();
                List<String> clips = new ArrayList<>(owner.getControl(AnimComposer.class).getAnimClipsNames());
                // Give existing local clips first choice of the short state names.
                clips.sort(java.util.Comparator.comparing((String clip) -> linkedAsset(clip) != null)
                        .thenComparing(java.util.Comparator.naturalOrder()));
                Set<String> names = new java.util.HashSet<>();
                String initialEntry = "";
                String entryClip = firstClip();
                for (int i = 0; i < clips.size(); i++) {
                    String clip = clips.get(i);
                    String name = uniqueStateName(editorClipLabel(clip, clips), names);
                    names.add(name);
                    if (clip.equals(entryClip)) {
                        initialEntry = name;
                    }
                    initial.add(new State(name, "", List.of(clip), new float[]{0}, true,
                            40 + (i % 3) * 200, 40 + (i / 3) * 110));
                }
                graph = new AnimationGraph(initialEntry, List.of(), initial, List.of());
            } else {
                graph = AnimationGraph.decode(savedGraph);
            }
            overlay = graph.overlay;
            rootMotion = graph.rootMotion;
            entry.setText(graph.entry);
            for (Parameter p : graph.parameters) {
                parameters.addRow(new Object[]{p.name, p.type.name(), p.initial});
            }
            for (State s : graph.states) {
                List<String> thresholds = new ArrayList<>();
                for (int i = 0; i < s.clips.size(); i++) {
                    thresholds.add(s.isTwoDimensional() ? s.threshold(i) + "," + s.sampleY(i)
                            : Float.toString(s.threshold(i)));
                }
                states.addRow(new Object[]{s.name, s.parameter, String.join("|", s.clips),
                    String.join("|", thresholds), s.loop, s.x, s.y, s.parameterY});
            }
            for (Transition t : graph.transitions) {
                edges.addRow(new Object[]{t.from, t.to, t.parameter, t.operator.name(), t.value, t.duration, t.exitTime});
            }
            if (savedPorts != null && savedPorts.startsWith("1:")
                    && savedPorts.length() == edges.getRowCount() + 2) {
                for (int i = 0; i < edges.getRowCount(); i++) {
                    char side = savedPorts.charAt(i + 2);
                    if (side == 'L' || side == 'R') {
                        edges.setValueAt(side == 'L', i, 7);
                    }
                }
            }
            dirty = false;
            undo.discardAllEdits();
            snapshot = new Snapshot();
            status.setText(text("Animation.Available_clips") + String.join(" | ", owner.getControl(AnimComposer.class).getAnimClipsNames()));
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
                throw new IllegalArgumentException(text("Animation.Finish_editing_the_current_cell"));
            }
        }
        List<Parameter> ps = new ArrayList<>();
        List<State> ss = new ArrayList<>();
        List<Transition> ts = new ArrayList<>();
        for (int i = 0; i < parameters.getRowCount(); i++) {
            Type type = Type.valueOf(cell(parameters, i, 1));
            float initial = type == Type.BOOLEAN
                    ? booleanDefault(cell(parameters, i, 2)) : number(parameters, i, 2);
            ps.add(new Parameter(cell(parameters, i, 0), type, initial));
        }
        for (int i = 0; i < states.getRowCount(); i++) {
            String[] samples = cell(states, i, 2).split("\\|", -1);
            String[] thresholdText = cell(states, i, 3).split("\\|", -1);
            float[] thresholds = new float[thresholdText.length];
            String parameterY = cell(states, i, 7);
            float[] samplesY = parameterY.isEmpty() ? new float[0] : new float[thresholds.length];
            for (int j = 0; j < thresholds.length; j++) {
                if (parameterY.isEmpty()) {
                    thresholds[j] = Float.parseFloat(thresholdText[j].trim());
                } else {
                    String[] pair = thresholdText[j].split(",", -1);
                    if (pair.length != 2) {
                        throw new IllegalArgumentException(text("Animation.Unique_sample_xy"));
                    }
                    thresholds[j] = Float.parseFloat(pair[0].trim());
                    samplesY[j] = Float.parseFloat(pair[1].trim());
                }
            }
            String loop = cell(states, i, 4);
            if (!loop.equals("true") && !loop.equals("false")) {
                throw new IllegalArgumentException(text("Animation.Loop_must_be_true_or_false"));
            }
            ss.add(new State(cell(states, i, 0), cell(states, i, 1), parameterY, Arrays.asList(samples), thresholds, samplesY,
                    Boolean.parseBoolean(loop), number(states, i, 5), number(states, i, 6)));
        }
        for (int i = 0; i < edges.getRowCount(); i++) {
            String from = cell(edges, i, 0);
            String to = cell(edges, i, 1);
            if (ss.stream().noneMatch(s -> s.name.equals(from)) || ss.stream().noneMatch(s -> s.name.equals(to))) {
                tabs.setSelectedIndex(3);
                tables.get(2).setRowSelectionInterval(i, i);
                throw new IllegalArgumentException(String.format(text("Animation.UnknownTransitionState"), i + 1, from, to));
            }
            Operator operator = Operator.valueOf(cell(edges, i, 3));
            String parameter = cell(edges, i, 2);
            if (operator != Operator.ALWAYS) {
                Parameter declaration = ps.stream().filter(p -> p.name.equals(parameter)).findFirst().orElse(null);
                if (declaration == null) {
                    tabs.setSelectedIndex(3);
                    JTable transitionTable = tables.get(2);
                    transitionTable.setRowSelectionInterval(i, i);
                    transitionTable.scrollRectToVisible(transitionTable.getCellRect(i, 2, true));
                    throw new IllegalArgumentException(String.format(text(parameter.isEmpty()
                            ? "Animation.EmptyTransitionParameter" : "Animation.UnknownTransitionParameter"),
                            i + 1, cell(edges, i, 0), cell(edges, i, 1),
                            AnimationEditorMessages.value(operator), parameter));
                }
                Type expected = operator == Operator.TRIGGER ? Type.TRIGGER
                        : operator == Operator.TRUE || operator == Operator.FALSE ? Type.BOOLEAN : Type.FLOAT;
                if (declaration.type != expected) {
                    tabs.setSelectedIndex(3);
                    tables.get(2).setRowSelectionInterval(i, i);
                    throw new IllegalArgumentException(String.format(text("Animation.WrongTransitionParameterType"),
                            i + 1, cell(edges, i, 0), cell(edges, i, 1), parameter,
                            AnimationEditorMessages.value(expected)));
                }
            }
            ts.add(new Transition(cell(edges, i, 0), cell(edges, i, 1), cell(edges, i, 2),
                    Operator.valueOf(cell(edges, i, 3)), number(edges, i, 4), number(edges, i, 5), number(edges, i, 6)));
        }
        AnimationGraph result = new AnimationGraph(entry.getText().trim(), ps, ss, ts, overlay, rootMotion);
        result.validate(owner.getControl(AnimComposer.class));
        return result;
    }

    private static String cell(DefaultTableModel model, int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString().trim();
    }

    private static float booleanDefault(String value) {
        if ("true".equalsIgnoreCase(value.trim())) {
            return 1;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return 0;
        }
        // Preserve numeric defaults in existing graph documents.
        return Float.parseFloat(value.trim()) != 0 ? 1 : 0;
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
                    return text("Animation.Animation_graph");
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
            status.setText(text("Animation.Empty_graph_add_an_entry_state_before_previewing_or_saving_Import"));
        }
    }

    private final class Snapshot {
        private final List<AnimationGraphComments.Box> boxes = List.copyOf(comments);
        private final Overlay maskedOverride = overlay;
        private final RootMotion motion = rootMotion;
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
            overlay = maskedOverride;
            rootMotion = motion;
            canvas.clearSelection();
            snapshot = this;
            dirty = true;
            loading = false;
            canvas.repaint();
        }

        boolean sameContent(Snapshot other) {
            return Arrays.deepEquals(rows, other.rows) && entryText.equals(other.entryText)
                    && java.util.Objects.equals(preferred, other.preferred) && boxes.equals(other.boxes)
                    && maskedOverride == other.maskedOverride && motion == other.motion;
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
                            if (isShowing()) {
                                timer.start();
                            }
                        }
                    });
                });
            } catch (Exception exception) {
                AnimationEditorSupport.error(exception);
            }
        });
    }

    private void refreshGuidance() {
        if (states.getRowCount() == 0) {
            guidance.setText(text("Animation.Empty_graph_add_a_state_or_press_Ctrl_Z_to_restore_Preview_source"));
            return;
        }
        boolean constant = false;
        AnimComposer composer = owner.getControl(AnimComposer.class);
        for (int i = 0; i < states.getRowCount(); i++) {
            for (String name : cell(states, i, 2).split("\\|")) {
                var clip = composer.getAnimClip(name.trim());
                constant |= clip != null && AnimationEditorSupport.constantTransformClip(clip);
            }
        }
        guidance.setText(constant
                ? text("Animation.This_graph_includes_static_clips_See_Guide_for_diagnostics_or_cho")
                : text("Animation.Check_motion_with_Preview_source_clip_then_Apply_preview_Uncondit"));
    }

    private boolean emptyDraft() {
        if (states.getRowCount() != 0) {
            return false;
        }
        ++previewRevision;
        String hint = text("Animation.Empty_graph_add_a_state_or_use_Preview_source_clip_Ctrl_Z_restore");
        status.setText(hint);
        if (preview != null) {
            Preview app = preview;
            app.enqueue(() -> {
                if (app.player != null) {
                    app.player.close();
                    app.player = null;
                }
                app.stateName = "";
                app.message = hint;
            });
        }
        return true;
    }

    /** 第一步：先预览源片段；第二步：再创建状态与条件，Apply preview 检查图。 */
    private void previewSourceClip() {
        LinkedClipChoice clip = chooseEditorClip(clipNames(), "Animation.Select_a_source_clip_independent_of_the_graph",
                "Animation.Preview_source_clip");
        if (clip != null) {
            previewClip(clip.id());
        }
    }

    private void previewClip(String clip) {
        AnimationGraph definition = new AnimationGraph(text("Animation.Source_clip"), List.of(),
                List.of(new State(text("Animation.Source_clip"), "", List.of(clip), new float[]{0}, true, 0, 0)), List.of());
        applyPreview(definition);
    }

    private void showGuide() {
        JTextArea help = new JTextArea(AnimationEditorSupport.guide(owner));
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(help);
        scroll.setPreferredSize(new Dimension(760, 600));
        JOptionPane.showMessageDialog(this, scroll, text("Animation.Animation_Editor_Guide_and_clip_diagnostics"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void applyPreview() {
        if (emptyDraft()) {
            applyPreview(null);
            return;
        }
        try {
            applyPreview(graph());
        } catch (Exception exception) {
            AnimationEditorSupport.error(exception);
        }
    }

    private void applyPreview(AnimationGraph definition) {
        if (preview == null || disposed) {
            return;
        }
        AnimationGraph previewDefinition = previewDefaults(definition);
        try {
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
                                    app.install(clone.model(), clone.owner(), previewDefinition);
                                    EventQueue.invokeLater(() -> {
                                        if (!disposed && revision == previewRevision) {
                                            setInputsEnabled(live, true);
                                        }
                                    });
                                } catch (Exception | AssertionError exception) {
                                    app.message = AnimationEditorMessages.failure(exception);
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
            live.add(new JLabel(text("Animation.Live_parameters_preview_only_reapply_preview_after_editing_defini")));
            for (Parameter parameter : previewDefinition == null ? List.<Parameter>of() : previewDefinition.parameters) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                row.add(new JLabel(parameter.name));
                if (parameter.type == Type.TRIGGER) {
                    row.add(button(text("Animation.Trigger"), () -> enqueuePlayer(p -> p.trigger(parameter.name))));
                } else if (parameter.type == Type.BOOLEAN) {
                    JCheckBox value = new JCheckBox(AnimationEditorMessages.value(false));
                    value.addActionListener(e -> {
                        boolean checked = value.isSelected();
                        value.setText(AnimationEditorMessages.value(checked));
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
                boolean used = definition.transitions.stream().anyMatch(t -> parameter.name.equals(t.parameter))
                        || definition.states.stream().anyMatch(s -> parameter.name.equals(s.parameter)
                                || parameter.name.equals(s.parameterY))
                        || (definition.overlay != null && parameter.name.equals(definition.overlay.parameter));
                if (!used) {
                    row.add(new JLabel(text("Animation.Not_referenced_by_a_state_or_transition_no_effect_on_animation")));
                }
            }
            // Controls must not update the old player while the new clone is loading.
            setInputsEnabled(live, false);
            compactLiveRows();
            live.revalidate();
            live.repaint();
        } catch (Exception exception) {
            AnimationEditorSupport.error(exception);
        }
    }

    // Start preview with all switches off so opening the editor cannot trigger competing transitions.
    static AnimationGraph previewDefaults(AnimationGraph definition) {
        if (definition == null) {
            return null;
        }
        List<Parameter> parameters = definition.parameters.stream()
                .map(value -> value.type == Type.BOOLEAN && value.initial != 0
                        ? new Parameter(value.name, value.type, 0f) : value)
                .toList();
        return new AnimationGraph(definition.entry, parameters, definition.states, definition.transitions,
                definition.overlay, definition.rootMotion);
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
        if (emptyDraft() && savedGraph == null) {
            return;
        }
        if (saving) {
            return;
        }
        try {
            String text = states.getRowCount() == 0 ? null : graph().encode();
            String commentText = AnimationGraphComments.encode(comments);
            String portText = encodePorts();
            String expectedPorts = savedPorts;
            String expectedComments = savedComments;
            String expected = savedGraph;
            String preferred = preference.getSelectedItem().toString();
            Spatial target = owner;
            saving = true;
            setInputsEnabled(this, false);
            status.setText(text("Animation.Saving_J3O"));
            RequestProcessor.getDefault().post(() -> {
                try {
                    if (data.loadAsset() != root) {
                        throw new IllegalStateException(text("Animation.Model_was_reloaded_reopen_the_animation_editor_before_saving"));
                    }
                    com.jme3.gde.core.scene.SceneApplication.getApplication().enqueue(() -> {
                        if (!java.util.Objects.equals(expected, target.getUserData(AnimationGraph.USER_DATA))
                                || !java.util.Objects.equals(expectedComments, target.getUserData(AnimationGraphComments.USER_DATA))
                                || !java.util.Objects.equals(expectedPorts, target.getUserData(PORTS_DATA))) {
                            throw new IllegalStateException(text("Animation.Animation_definition_changed_elsewhere_reopen_the_editor"));
                        }
                        String previousPreference = root.getUserData(AnimationEditorSupport.PREFERENCE);
                        try {
                            target.setUserData(AnimationGraph.USER_DATA, text);
                            target.setUserData(AnimationGraphComments.USER_DATA, commentText);
                            target.setUserData(PORTS_DATA, portText);
                            root.setUserData(AnimationEditorSupport.PREFERENCE, preferred);
                            data.setModified(true);
                            AnimationEditorSupport.saveSnapshot(data, root);
                        } catch (Exception exception) {
                            target.setUserData(AnimationGraph.USER_DATA, expected);
                            target.setUserData(AnimationGraphComments.USER_DATA, expectedComments);
                            target.setUserData(PORTS_DATA, expectedPorts);
                            root.setUserData(AnimationEditorSupport.PREFERENCE, previousPreference);
                            throw exception;
                        }
                        return null;
                    }).get();
                    EventQueue.invokeLater(() -> {
                        savedGraph = text;
                        savedComments = commentText;
                        savedPorts = portText;
                        dirty = false;
                        status.setText(text("Animation.Saved") + data.getPrimaryFile().getNameExt());
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

    private void showAnimationLinks() {
        if (saving) {
            return;
        }
        try {
            List<Link> current = AnimationLinkResolver.links(owner);
            JList<Link> listing = new JList<>(current.toArray(Link[]::new));
            listing.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (!current.isEmpty()) {
                listing.setSelectedIndex(0);
            }
            String clip = text("Animation.Add_external_clip");
            String library = text("Animation.Add_external_library");
            String remove = text("Animation.Remove_external_link");
            String close = text("Animation.Close_external_links");
            String reload = text("Animation.Reload_external_links");
            String previewModel = text("Animation.Preview_external_model");
            String[] choices = {clip, library, remove, reload, previewModel, close};
            int choice = JOptionPane.showOptionDialog(this, new JScrollPane(listing),
                    text("Animation.External_links"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                    null, choices, clip);
            if (choice == 2 && listing.getSelectedValue() != null) {
                if (dirty) {
                    JOptionPane.showMessageDialog(this, text("Animation.Save_graph_before_removing_link"),
                            text("Animation.External_links"), JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                List<Link> edited = new ArrayList<>(current);
                edited.remove(listing.getSelectedValue());
                saveLinks(current, edited);
            } else if (choice == 0 || choice == 1) {
                chooseSource(current, choice == 1);
            } else if (choice == 3 && !current.isEmpty()) {
                saveLinks(current, current);
            } else if (choice == 4) {
                previewOnModel();
            }
        } catch (Exception failure) {
            AnimationEditorSupport.error(failure);
        }
    }

    private Path projectAssets() throws IOException {
        ProjectAssetManager manager = data.getLookup().lookup(ProjectAssetManager.class);
        if (manager == null || manager.getAssetFolder() == null) {
            throw new IOException(text("Animation.External_links_need_project"));
        }
        var folder = org.openide.filesystems.FileUtil.toFile(manager.getAssetFolder());
        if (folder == null) {
            throw new IOException(text("Animation.External_links_need_project"));
        }
        return folder.toPath().toRealPath();
    }

    private String assetKey(Path file) throws IOException {
        Path base = projectAssets();
        Path asset = file.toRealPath();
        if (!asset.startsWith(base)) {
            throw new IOException(text("Animation.External_links_need_project"));
        }
        return base.relativize(asset).toString().replace('\\', '/');
    }

    private String ownerAssetKey() throws IOException {
        var file = org.openide.filesystems.FileUtil.toFile(data.getPrimaryFile());
        if (file == null) {
            throw new IOException(text("Animation.External_links_need_project"));
        }
        return assetKey(file.toPath());
    }

    private void chooseSource(List<Link> current, boolean library) {
        try {
            String chosen = chooseJ3o();
            if (chosen == null) {
                return;
            }
            Link candidate = new Link(chosen, null);
            if (library) {
                appendLink(current, candidate);
                return;
            }
            beginLinkWork();
            RequestProcessor.getDefault().post(() -> {
                try {
                    List<String> clips = AnimationLinkResolver.clipIds(
                            data.getLookup().lookup(ProjectAssetManager.class), candidate.asset());
                    EventQueue.invokeLater(() -> {
                        finishLinkWork();
                        if (disposed || clips.isEmpty()) {
                            return;
                        }
                        String clip = chooseLinkedClip(clips, text("Animation.External_links"));
                        if (clip != null) {
                            appendLink(current, new Link(candidate.asset(), clip));
                        }
                    });
                    if (clips.isEmpty()) {
                        AnimationEditorSupport.error(new IOException(text("Animation.No_external_clips")));
                    }
                } catch (Exception failure) {
                    AnimationEditorSupport.error(failure);
                    EventQueue.invokeLater(this::finishLinkWork);
                }
            });
        } catch (Exception failure) {
            AnimationEditorSupport.error(failure);
        }
    }

    private String chooseJ3o() throws IOException {
        JFileChooser chooser = new JFileChooser(projectAssets().toFile());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("J3O", "j3o"));
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? assetKey(chooser.getSelectedFile().toPath()) : null;
    }

    private String chooseLinkedClip(List<String> clips, String title) {
        var choices = linkedClipChoices(clips);
        var selected = (LinkedClipChoice) JOptionPane.showInputDialog(this, text("Animation.Add_external_clip"),
                title, JOptionPane.PLAIN_MESSAGE, null, choices.toArray(), choices.get(0));
        return selected == null ? null : selected.id();
    }

    // Display the asset name in the picker, while keeping the resolver ID for persistence.
    static List<LinkedClipChoice> linkedClipChoices(List<String> ids) {
        return linkedClipChoices(ids, null);
    }

    private static List<LinkedClipChoice> linkedClipChoices(List<String> ids, String localAssetName) {
        List<LinkedClipChoice> choices = new ArrayList<>();
        for (String id : ids) {
            choices.add(new LinkedClipChoice(id, clipLabel(id, ids, localAssetName)));
        }
        var counts = choices.stream().collect(java.util.stream.Collectors.groupingBy(
                LinkedClipChoice::label, java.util.stream.Collectors.counting()));
        return choices.stream().map(choice -> counts.get(choice.label()) == 1 ? choice
                : new LinkedClipChoice(choice.id(), choice.label() + " (" + decodeClipLabel(choice.id()) + ")")).toList();
    }

    private static String linkedAsset(String id) {
        int separator = id.indexOf('#');
        if (separator <= 0 || id.indexOf('#', separator + 1) < 0) {
            return null;
        }
        String asset = decodeClipLabel(id.substring(0, separator));
        return asset.toLowerCase(java.util.Locale.ROOT).endsWith(".j3o") ? asset : null;
    }

    /** 第一步：关联 Idle.j3o，再添加 Clip State 显示 Idle。第二步：保存仍绑定原剪辑 ID。 */
    private static String clipLabel(String id, List<String> ids, String localAssetName) {
        String asset = linkedAsset(id);
        if (asset == null) {
            if (localAssetName != null && !id.isEmpty() && ids.contains(id)
                    && ids.stream().filter(candidate -> linkedAsset(candidate) == null).limit(2).count() == 1) {
                return localAssetName;
            }
            return id;
        }
        String name = asset.substring(asset.lastIndexOf('/') + 1, asset.length() - 4);
        boolean multiple = ids.stream().filter(candidate -> asset.equals(linkedAsset(candidate)))
                .limit(2).count() > 1;
        return multiple ? name + " — " + decodeClipLabel(id.split("#", 3)[2]) : name;
    }

    private String editorClipLabel(String id, List<String> ids) {
        return clipLabel(id, ids, data.getPrimaryFile().getName());
    }

    private LinkedClipChoice chooseEditorClip(List<String> clips, String message, String title) {
        if (clips.isEmpty()) {
            return null;
        }
        var choices = linkedClipChoices(clips, data.getPrimaryFile().getName());
        String initial = firstClip();
        return (LinkedClipChoice) JOptionPane.showInputDialog(this, text(message), text(title),
                JOptionPane.PLAIN_MESSAGE, null, choices.toArray(),
                choices.stream().filter(choice -> choice.id().equals(initial)).findFirst().orElse(null));
    }

    private static String uniqueStateName(String base, Set<String> names) {
        String name = base;
        for (int suffix = 2; names.contains(name); suffix++) {
            name = base + " " + suffix;
        }
        return name;
    }

    private String stateTitle(int row) {
        String name = cell(states, row, 0);
        String clip = cell(states, row, 2).split("\\|", 2)[0];
        if (!clip.isEmpty() && name.startsWith(clip)) {
            String suffix = name.substring(clip.length());
            if (suffix.isEmpty() || suffix.matches(" [0-9]+")) {
                return editorClipLabel(clip, clipNames()) + suffix;
            }
        }
        return name;
    }

    private static String decodeClipLabel(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidEscape) {
            return encoded;
        }
    }

    record LinkedClipChoice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void previewOnModel() throws IOException {
        String destination = chooseJ3o();
        if (destination == null) {
            return;
        }
        String source = ownerAssetKey();
        if (source.equals(destination)) {
            previewSourceClip();
            return;
        }
        var manager = data.getLookup().lookup(ProjectAssetManager.class);
        beginLinkWork();
        RequestProcessor.getDefault().post(() -> {
            try {
                List<String> clips = AnimationLinkResolver.clipIds(manager, source);
                if (clips.isEmpty()) {
                    throw new IOException(text("Animation.No_external_clips"));
                }
                EventQueue.invokeLater(() -> {
                    finishLinkWork();
                    if (disposed) {
                        return;
                    }
                    String choice = chooseLinkedClip(clips, text("Animation.Preview_external_model"));
                    if (choice != null) {
                        loadPreviewModel(manager, destination, new Link(source, choice));
                    }
                });
            } catch (Exception failure) {
                AnimationEditorSupport.error(failure);
                EventQueue.invokeLater(this::finishLinkWork);
            }
        });
    }

    private void loadPreviewModel(ProjectAssetManager manager, String destination, Link clip) {
        Preview app = preview;
        if (app == null) {
            return;
        }
        int revision = ++previewRevision;
        beginLinkWork();
        RequestProcessor.getDefault().post(() -> {
            try {
                Spatial model = manager.loadModel(destination);
                var tracks = AnimationLinkResolver.validate(manager, destination, model, List.of(clip));
                AnimComposer composer = model.getControl(AnimComposer.class);
                if (composer == null) {
                    composer = new AnimComposer();
                    model.addControl(composer);
                }
                composer.setLinkedAnimClips(tracks);
                String state = text("Animation.Source_clip");
                AnimationGraph graph = new AnimationGraph(state, List.of(), List.of(
                        new State(state, "", List.of(clip.clip()), new float[]{0}, true, 0, 0)), List.of());
                EventQueue.invokeLater(() -> {
                    if (!disposed && revision == previewRevision) {
                        app.enqueue(() -> app.install(model, model, graph));
                    }
                });
            } catch (Exception failure) {
                AnimationEditorSupport.error(failure);
            } finally {
                EventQueue.invokeLater(this::finishLinkWork);
            }
        });
    }

    private void appendLink(List<Link> current, Link link) {
        List<Link> edited = new ArrayList<>(current);
        edited.add(link);
        saveLinks(current, edited);
    }

    private void beginLinkWork() {
        saving = true;
        setInputsEnabled(this, false);
        status.setText(text("Animation.Loading_external_links"));
    }

    private void finishLinkWork() {
        saving = false;
        setInputsEnabled(this, true);
        updateHistoryButtons();
    }

    private void saveLinks(List<Link> expected, List<Link> revised) {
        Spatial target = owner;
        beginLinkWork();
        RequestProcessor.getDefault().post(() -> {
            try {
                String asset = ownerAssetKey();
                if (data.loadAsset() != root) {
                    throw new IOException(text("Animation.Model_was_reloaded_reopen_the_animation_editor_before_saving"));
                }
                var manager = data.getLookup().lookup(ProjectAssetManager.class);
                String added = com.jme3.gde.core.scene.SceneApplication.getApplication().enqueue(() -> {
                    if (!AnimationLinkResolver.links(target).equals(expected)) {
                        throw new IOException(text("Animation.Animation_definition_changed_elsewhere_reopen_the_editor"));
                    }
                    AnimComposer composer = target.getControl(AnimComposer.class);
                    var old = new ArrayList<>(composer.getAnimClips());
                    old.removeAll(composer.getEmbeddedAnimClips());
                    var bound = AnimationLinkResolver.validate(manager, asset, target, revised);
                    composer.setLinkedAnimClips(bound);
                    try {
                        String existingGraph = target.getUserData(AnimationGraph.USER_DATA);
                        if (existingGraph != null) {
                            AnimationGraph.decode(existingGraph).validate(composer);
                        }
                        AnimationLinkResolver.writeLinks(target, revised);
                        AnimationEditorSupport.saveSnapshot(data, root);
                    } catch (Exception failure) {
                        composer.setLinkedAnimClips(old);
                        AnimationLinkResolver.writeLinks(target, expected);
                        throw failure;
                    }
                    return bound.stream()
                            .filter(clip -> old.stream().noneMatch(previous -> clip.getName().equals(previous.getName())))
                            .min(java.util.Comparator.comparing(AnimationEditorSupport::constantTransformClip))
                            .map(com.jme3.anim.AnimClip::getName).orElse(null);
                }).get();
                EventQueue.invokeLater(() -> refreshLinks(added));
            } catch (Exception failure) {
                AnimationEditorSupport.error(failure);
                EventQueue.invokeLater(this::finishLinkWork);
            }
        });
    }

    private void refreshLinks(String added) {
        try {
            if (!disposed) {
                refreshOwnerChoices();
                if (!dirty && savedGraph == null) {
                    loadOwner();
                }
                refreshGuidance();
            }
        } finally {
            finishLinkWork();
        }
        if (!disposed) {
            if (added != null) {
                previewClip(added);
            } else {
                applyPreview();
            }
        }
    }

    private void exportEmbedded() {
        if (saving || dirty) {
            status.setText(text("Animation.Save_graph_before_link_edit"));
            return;
        }
        try {
            JFileChooser chooser = new JFileChooser(projectAssets().toFile());
            chooser.setSelectedFile(projectAssets().resolve(data.getPrimaryFile().getName() + "-embedded.j3o").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path output = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (!output.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".j3o")) {
                output = output.resolveSibling(output.getFileName() + ".j3o");
            }
            if (Files.exists(output)) {
                throw new IOException(text("Animation.Export_requires_new_file"));
            }
            writeEmbeddedCopy(output);
        } catch (Exception failure) {
            AnimationEditorSupport.error(failure);
        }
    }

    private void writeEmbeddedCopy(Path output) {
        beginLinkWork();
        RequestProcessor.getDefault().post(() -> {
            try {
                var manager = data.getLookup().lookup(ProjectAssetManager.class);
                String key = ownerAssetKey();
                Spatial copy = com.jme3.gde.core.scene.SceneApplication.getApplication()
                        .enqueue(() -> root.clone(true)).get();
                AnimationLinkResolver.embed(manager, key, copy);
                Path temporary = Files.createTempFile(output.getParent(), ".embedded-animation-", ".j3o");
                try {
                    new com.jme3.export.binary.BinaryExporter().save(copy, temporary.toFile());
                    Files.move(temporary, output);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                org.openide.filesystems.FileUtil.refreshFor(output.toFile());
                EventQueue.invokeLater(() -> status.setText(text("Animation.Saved") + output.getFileName()));
            } catch (Exception failure) {
                AnimationEditorSupport.error(failure);
            } finally {
                EventQueue.invokeLater(this::finishLinkWork);
            }
        });
    }

    private boolean discard() {
        return DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(
                text("Animation.Discard_unsaved_animation_changes_Use_Save_J3O_to_keep_them"),
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
    protected void componentShowing() {
        super.componentShowing();
        if (!disposed && preview != null) {
            timer.start();
        }
    }

    @Override
    protected void componentHidden() {
        timer.stop();
        super.componentHidden();
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
        private boolean linkFromLeft;
        private Point linkEnd;
        private double zoom = 1;
        private double panX;
        private double panY;
        private Point panStart;

        GraphCanvas() {
            setLayout(null);
            setPreferredSize(new Dimension(1600, 1200));
            setFocusable(true);
            setToolTipText(text("Animation.Shift_wheel_zoom_middle_drag_pan_Drag_empty_space_to_select_state"));
            graphAction("DELETE", "deleteSelection", this::deleteSelection);
            graphAction("C", "createComment", () -> {
                if (!selectedStates.isEmpty()) {
                    addComment(text("Animation.Comment"));
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
                        boolean left = event.getX() < b.getCenterX();
                        if (nearPort(event.getPoint(), b, left)) {
                            linking = i;
                            linkFromLeft = left;
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
                            EdgeRoute route = edgeRoute(i);
                            if (route != null && nearCurve(route.path(), event.getPoint())) {
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
                        int target = linkTarget(event.getPoint());
                        linkEnd = target < 0 ? event.getPoint() : port(bounds(target), !linkFromLeft);
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
                        int target = linkTarget(event.getPoint());
                        linking = -1;
                        linkEnd = null;
                        if (isEnabled() && target >= 0) {
                            addTransition(cell(states, source, 0), cell(states, target, 0), linkFromLeft);
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
            input.setToolTipText(text("Animation.Ctrl_Enter_apply_comment_Escape_cancel_text_edit"));
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
                EdgeRoute route = edgeRoute(i);
                if (route != null && nearCurve(route.path(), point)) {
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

        private Point port(Rectangle bounds, boolean left) {
            return new Point(left ? bounds.x : bounds.x + bounds.width, (int) bounds.getCenterY());
        }

        private boolean nearPort(Point point, Rectangle bounds, boolean left) {
            return port(bounds, left).distance(point) * zoom <= 18;
        }

        private int linkTarget(Point point) {
            for (int i = states.getRowCount() - 1; i >= 0; i--) {
                Rectangle node = bounds(i);
                boolean left = point.x < node.getCenterX();
                if (left != linkFromLeft && nearPort(point, node, left)) {
                    Rectangle source = bounds(linking);
                    if (source.getMaxX() <= node.getMinX() && linkFromLeft) {
                        continue;
                    }
                    if (node.getMaxX() <= source.getMinX() && !linkFromLeft) {
                        continue;
                    }
                    return i;
                }
            }
            return -1;
        }

        private void addTransition(String from, String to) {
            edges.addRow(new Object[]{from, to, "", "ALWAYS", "0", "0.2", "-1"});
            editTransition(edges.getRowCount() - 1);
        }

        private void addTransition(String from, String to, boolean sourceLeft) {
            edges.addRow(new Object[]{from, to, "", "ALWAYS", "0", "0.2", "-1", sourceLeft});
            editTransition(edges.getRowCount() - 1);
        }

        private void editTransition(int row) {
            tabs.setSelectedIndex(3);
            JTable table = tables.get(2);
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 3, true));
        }

        private EdgeRoute edgeRoute(int row) {
            String source = cell(edges, row, 0);
            String target = cell(edges, row, 1);
            int from = find(source);
            int to = find(target);
            if (from < 0 || to < 0) {
                return null;
            }
            Rectangle a = bounds(from);
            Rectangle b = bounds(to);
            int repeats = 0;
            boolean reciprocal = false;
            for (int i = 0; i < edges.getRowCount(); i++) {
                String edgeSource = cell(edges, i, 0);
                String edgeTarget = cell(edges, i, 1);
                if (i < row && edgeSource.equals(source) && edgeTarget.equals(target)) {
                    repeats++;
                }
                if (from != to && edgeSource.equals(target) && edgeTarget.equals(source)) {
                    reciprocal = true;
                }
            }
            // Port identity belongs to the edge; moving nodes only changes its route.
            boolean returnThroughGap = Boolean.TRUE.equals(edges.getValueAt(row, 7));
            double start = returnThroughGap ? a.getMinX() : a.getMaxX();
            double end = returnThroughGap ? b.getMaxX() : b.getMinX();
            double startY = a.y + 32;
            double endY = b.y + 32;
            if (returnThroughGap ? start > end : end > start) {
                double handle = Math.copySign(Math.min(80, Math.abs(end - start) / 3), end - start);
                double bend = -30 - 24 * repeats;
                if (returnThroughGap) {
                    bend = -bend;
                } else if (!reciprocal) {
                    bend = (repeats % 2 == 0 ? -1 : 1) * (30 + 24 * (repeats / 2));
                }
                var curve = new java.awt.geom.CubicCurve2D.Double(start, startY, start + handle, startY + bend,
                        end - handle, endY + bend, end, endY);
                return new EdgeRoute(curve, end, endY, Math.atan2(-bend, handle),
                        (start + end) / 2, (startY + endY) / 2 + .75 * bend);
            }
            // Use the gap between stacked nodes while keeping both ports fixed.
            // Overlapping vertical spans need room outside the nodes.
            double side = 26 + 8 * repeats + (reciprocal && returnThroughGap ? 12 : 0);
            double gapTop = startY <= endY ? a.getMaxY() : b.getMaxY();
            double gapBottom = startY <= endY ? b.getMinY() : a.getMinY();
            boolean stacked = gapBottom - gapTop >= 12;
            double right = returnThroughGap
                    ? (stacked ? start : Math.min(start, b.getMinX())) - side
                    : (stacked ? start : Math.max(start, b.getMaxX())) + side;
            double left = returnThroughGap
                    ? (stacked ? end : Math.max(end, a.getMaxX())) + side
                    : (stacked ? end : Math.min(end, a.getMinX())) - side;
            if (returnThroughGap && start > 0) {
                right = Math.max(0, right);
            } else if (!returnThroughGap && end > 0) {
                left = Math.max(0, left);
            }
            double outerClearance = 22 + 16 * repeats;
            double lane = stacked
                    ? gapTop + (gapBottom - gapTop)
                            * (reciprocal ? (returnThroughGap ? .65 : .35) : (1 - .5 / (repeats + 1)))
                    : Math.max(a.getMaxY(), b.getMaxY()) + outerClearance + (returnThroughGap ? 14 : 0);
            double[] x = {start, right, right, left, left, end};
            double[] y = {startY, startY, lane, lane, endY, endY};
            var path = new java.awt.geom.Path2D.Double();
            path.moveTo(start, startY);
            for (int i = 1; i < x.length - 1; i++) {
                double beforeX = x[i - 1] - x[i];
                double beforeY = y[i - 1] - y[i];
                double afterX = x[i + 1] - x[i];
                double afterY = y[i + 1] - y[i];
                double before = Math.abs(beforeX) + Math.abs(beforeY);
                double after = Math.abs(afterX) + Math.abs(afterY);
                double radius = Math.min(18, Math.min(before, after) / 2);
                path.lineTo(x[i] + beforeX * radius / before, y[i] + beforeY * radius / before);
                path.quadTo(x[i], y[i], x[i] + afterX * radius / after, y[i] + afterY * radius / after);
            }
            path.lineTo(end, endY);
            return new EdgeRoute(path, end, endY, returnThroughGap ? Math.PI : 0, (left + right) / 2, lane);
        }

        private record EdgeRoute(java.awt.Shape path, double endX, double endY,
                double angle, double labelX, double labelY) {
        }

        private boolean nearCurve(java.awt.Shape curve, Point point) {
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
                JMenuItem inspect = new JMenuItem(text("Animation.Edit_transition"));
                inspect.addActionListener(action -> editTransition(edge));
                menu.add(inspect);
                JMenuItem remove = new JMenuItem(text("Animation.Delete_transition"));
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
                JMenuItem editText = new JMenuItem(text("Animation.Edit_comment"));
                editText.addActionListener(action -> editComment(boxIndex));
                menu.add(editText);
                JMenuItem remove = new JMenuItem(text("Animation.Delete_comment_keep_states"));
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
                JMenuItem remove = new JMenuItem(text("Animation.Delete_selected_states"));
                remove.addActionListener(action -> deleteSelection());
                menu.add(remove);
            }
            if (!selectedStates.isEmpty()) {
                JMenuItem comment = new JMenuItem(text("Animation.Create_comment_from_selection_C"));
                comment.addActionListener(action -> {
                    addComment(text("Animation.Comment"));
                    editComment(comments.size() - 1);
                });
                menu.add(comment);
            }
            JMenuItem add = new JMenuItem(text("Animation.Add_clip_state"));
            add.addActionListener(action -> {
                var clips = clipNames();
                LinkedClipChoice clip = chooseEditorClip(clips, "Animation.Animation_clip", "Animation.New_state");
                if (clip != null) {
                    Set<String> names = new java.util.HashSet<>();
                    for (int row = 0; row < states.getRowCount(); row++) {
                        names.add(cell(states, row, 0));
                        names.add(stateTitle(row));
                    }
                    String stateName = uniqueStateName(editorClipLabel(clip.id(), clips), names);
                    edit(() -> {
                        states.addRow(new Object[]{stateName, "", clip.id(), "0", "true", point.x, point.y});
                        if (states.getRowCount() == 1) {
                            entry.setText(stateName);
                        }
                    });
                }
            });
            menu.add(add);
            if (selected >= 0) {
                String source = cell(states, selected, 0);
                JMenuItem start = new JMenuItem(text("Animation.Set_as_entry_state"));
                start.addActionListener(action -> edit(() -> entry.setText(source)));
                menu.add(start);
                JMenuItem connect = new JMenuItem(text("Animation.Add_transition_to"));
                connect.addActionListener(action -> {
                    String[] names = new String[states.getRowCount()];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = cell(states, i, 0);
                    }
                    Object target = JOptionPane.showInputDialog(AnimationEditorTopComponent.this, text("Animation.Target_state"),
                            text("Animation.New_transition"), JOptionPane.PLAIN_MESSAGE, null, names, source);
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
                EdgeRoute route = edgeRoute(i);
                if (route == null) {
                    continue;
                }
                int x2 = (int) route.endX();
                int y2 = (int) route.endY();
                g.setColor(i == hoveredEdge ? Color.CYAN : getForeground());
                g.setStroke(new BasicStroke(i == hoveredEdge ? 4 : 2));
                g.draw(route.path());
                double angle = route.angle();
                for (double direction : new double[]{angle - 0.45, angle + 0.45}) {
                    g.drawLine(x2, y2, x2 - (int) (12 * Math.cos(direction)), y2 - (int) (12 * Math.sin(direction)));
                }
                int labelX = (int) route.labelX();
                int labelY = (int) route.labelY();
                g.drawString(Integer.toString(i + 1), labelX, labelY - 5);
            }
            if (linking >= 0 && linking < states.getRowCount() && linkEnd != null) {
                Point source = port(bounds(linking), linkFromLeft);
                g.setColor(Color.CYAN);
                g.drawLine(source.x, source.y, linkEnd.x, linkEnd.y);
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
                g.drawString(stateTitle(i), b.x + 10, b.y + 23);
                g.drawString(!cell(states, i, 2).contains("|") ? text("Animation.Clip") : text("Animation.1D") + cell(states, i, 1), b.x + 10, b.y + 45);
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
        private AnimationTargetOverlay targetOverlay;
        private boolean targetsVisible;
        private boolean rootMotionVisible;
        private RootTrack focusedRootTrack;

        void focusRootTrack(RootTrack track) {
            focusedRootTrack = track;
            if (targetOverlay != null) {
                targetOverlay.focus(track == null ? null : track.path(), track != null && track.joint());
                targetOverlay.show(targetsVisible || track != null);
            }
        }

        void showTargets(boolean visible) {
            targetsVisible = visible;
            if (targetOverlay != null) {
                targetOverlay.show(visible || focusedRootTrack != null);
            }
        }

        private AnimationGraphPlayer player;
        private Vector3f center = new Vector3f();
        private float radius = 4;
        private float yaw;
        private float pitch = 0.15f;
        private com.jme3.light.SkyLight studioProbe;
        volatile String stateName = "";
        volatile String message = text("Animation.Preview_ready");

        @Override
        public void simpleInitApp() {
            super.simpleInitApp();
            rootNode.addLight(new AmbientLight(ColorRGBA.White.mult(0.6f)));
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-1, -2, -1).normalizeLocal());
            rootNode.addLight(sun);
            // Use the SDK studio environment so metallic glTF surfaces have something to reflect.
            Spatial environment = assetManager.loadModel("com/jme3/gde/core/sceneviewer/pbrenv.j3o");
            studioProbe = com.jme3.light.SkyLight.fromEnvironment((com.jme3.light.LightProbe) environment.getLocalLightList().get(0));
            rootNode.addLight(studioProbe);
            viewPort.setBackgroundColor(new ColorRGBA(0.12f, 0.14f, 0.18f, 1));
            setDisplayStatView(false);
            setDisplayFps(false);
        }

        void install(Spatial model, AnimationGraph graph) {
            install(model, model, graph);
        }

        void install(Spatial model, Spatial selectedOwner, AnimationGraph graph) {
            // Each animation tab has its own OpenGL context. A scene clone still shares GPU resources.
            new AnimationPreviewResources().isolate(model);
            if (player != null) {
                player.close();
                player = null;
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
            com.jme3.scene.Node motionWrapper = null;
            if (graph != null && graph.rootMotion != null && !graph.rootMotion.joint
                    && ".".equals(graph.rootMotion.path)) {
                motionWrapper = new com.jme3.scene.Node("Root motion preview");
                motionWrapper.setCullHint(Spatial.CullHint.Never);
            }
            selectedOwner.setUserData(AnimationGraph.USER_DATA, null);
            AnimComposer composer = selectedOwner.getControl(AnimComposer.class);
            composer.setEnabled(true);
            composer.reset();
            composer.setGlobalSpeed(1);
            player = graph == null ? null : new AnimationGraphPlayer(graph, composer);
            if (player != null && graph.rootMotion != null) {
                Spatial mover = motionWrapper == null ? selectedOwner : motionWrapper;
                player.setRootMotionConsumer(delta -> {
                    if (rootMotionVisible) {
                        delta.applyTo(mover);
                    }
                });
            }
            center.set(0, 0, 0);
            radius = 4;
            model.updateLogicalState(0);
            var poseBounds = SkinnedPreviewBounds.fit(model);
            if (poseBounds != null) {
                center = poseBounds.getCenter().clone();
                if (poseBounds instanceof BoundingBox box) {
                    radius = Math.max(0.2f, Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()))) * 3.5f;
                }
            }
            if (motionWrapper == null) {
                rootNode.attachChild(model);
            } else {
                motionWrapper.attachChild(model);
                rootNode.attachChild(motionWrapper);
            }
            rootNode.setCullHint(Spatial.CullHint.Never);
            targetOverlay = new AnimationTargetOverlay(assetManager, model, composer, radius * 0.006f,
                    graph == null ? null : graph.overlay);
            rootNode.attachChild(targetOverlay.geometry);
            focusRootTrack(focusedRootTrack);
            rootNode.updateGeometricState();
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
                message = String.format(text("Animation.s_2fs_s_Drag_orbit_Wheel_zoom"), stateName,
                        player.getStateTime(), player.isTransitioning() ? text("Animation.transition") : "");
            }
        }
    }
}
