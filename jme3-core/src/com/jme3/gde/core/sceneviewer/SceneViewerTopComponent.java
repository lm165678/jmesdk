/*
 * Copyright (c) 2009-2026 jMonkeyEngine All rights reserved. <p/>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. <p/> * Redistributions
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. <p/> * Neither the name of
 * 'jMonkeyEngine' nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written
 * permission. <p/> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.core.sceneviewer;

import com.jme3.asset.AssetManager;
import com.jme3.asset.MaterialKey;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.gde.core.dnd.SceneViewerDropTargetListener;
import com.jme3.gde.core.sceneexplorer.nodes.actions.SceneSpawnEvent;
import com.jme3.gde.core.sceneexplorer.nodes.actions.SceneSpawnTransfer;
import com.jme3.gde.core.filters.FilterExplorerTopComponent;
import com.jme3.gde.core.icons.IconList;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.input.awt.AwtKeyInput;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.material.Material;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.awt.Component;
import java.awt.dnd.DropTarget;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.UndoRedo;
import org.openide.util.HelpCtx;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * This is the component containing the whole scene/model
 * It also contains the top bar.
 */
@ConvertAsProperties(dtd = "-//com.jme3.gde.core.sceneviewer//SceneViewer//EN",
        autostore = false)
public final class SceneViewerTopComponent extends TopComponent {

    private static SceneViewerTopComponent instance;
    /**
     * path to the icon used by the component and its open action
     */
    static final String ICON_PATH = IconList.JME_LOGO;
    private static final String PREFERRED_ID = "SceneViewerTopComponent";
    private SceneApplication app;
    private HelpCtx helpContext = new HelpCtx("com.jme3.gde.core.sceneviewer");
    private Component oglCanvas;
    private SceneEditorGuide editorGuide;
    private javax.swing.Timer guideTimer;

    private com.jme3.gde.core.scene.processors.PreviewDebugProcessor debugPreview;

    public SceneViewerTopComponent() {
        initComponents();
        oGLPanel.setMinimumSize(new java.awt.Dimension(10, 10));
        setFocusable(true);
        installSceneEditActions();
        javax.swing.ActionMap explorerActions = com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance().getActionMap();
        for (String key : new String[]{"copy-to-clipboard", "cut-to-clipboard", "paste-from-clipboard", "delete"}) {
            javax.swing.Action action = explorerActions.get(key);
            if (action != null) {
                getActionMap().put(key, action);
            }
        }
        setName(NbBundle.getMessage(SceneViewerTopComponent.class, "CTL_SceneViewerTopComponent"));
        setToolTipText(NbBundle.getMessage(SceneViewerTopComponent.class, "HINT_SceneViewerTopComponent"));
        setIcon(ImageUtilities.loadImage(ICON_PATH, true));
        try {
            app = SceneApplication.getApplication();
            oglCanvas = app.getMainPanel();
            oglCanvas.setFocusable(false);
            oGLPanel.add(oglCanvas);
            installRenderPathSelector();

        } catch (Exception | Error e) {
            SceneApplication.showStartupErrorMessage(e);
        }

        addMouseWheelListener((final MouseWheelEvent e) -> {
            SceneApplication.getApplication().enqueue(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    String action;
                    if (e.getWheelRotation() < 0) {
                        action = "MouseWheel";
                    } else if (e.getWheelRotation() > 0) {
                        action = "MouseWheel-";
                    } else {
                        return null;
                    }
                    if (app.getActiveCameraController() != null) {
                        app.getActiveCameraController().onAnalog(action, e.getWheelRotation(), 0);
                    }
                    return null;
                }
            });
        });
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                // NetBeans can briefly move focus when activating this tab.
                // Check again after focus settles.
                java.awt.EventQueue.invokeLater(() -> {
                    java.awt.Component owner = java.awt.KeyboardFocusManager
                            .getCurrentKeyboardFocusManager().getFocusOwner();
                    if (owner == SceneViewerTopComponent.this
                            || (owner != null && javax.swing.SwingUtilities.isDescendingFrom(
                                    owner, SceneViewerTopComponent.this))) {
                        return;
                    }
                    app.enqueue(() -> {
                        if (app.getActiveCameraController() != null) {
                            app.getActiveCameraController().resetInputState();
                        }
                        return null;
                    });
                });
            }
        });
        oglCanvas.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                if (org.openide.windows.TopComponent.getRegistry().getActivated()
                        != SceneViewerTopComponent.this) {
                    requestActive();
                }
                if (!isFocusOwner()) {
                    requestFocusInWindow();
                }
            }
        });
        addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent evt) {
            }

            @Override
            public void keyPressed(final KeyEvent evt) {
                if (handleSceneEditShortcut(evt)) {
                    return;
                }
                // A key pressed before focus arrived may be missing from our key state.
                // Include the AWT modifier mask.
                if (evt.isControlDown() || evt.isAltDown() || evt.isMetaDown()) {
                    return;
                }
                SceneApplication.getApplication().enqueue(() -> {
                    int code = AwtKeyInput.convertAwtKey(evt.getKeyCode());
                    KeyInputEvent keyEvent = new KeyInputEvent(code, evt.getKeyChar(), true, false);
                    keyEvent.setTime(evt.getWhen());
                    if (app.getActiveCameraController() != null) {
                        app.getActiveCameraController().onKeyEvent(keyEvent);
                    }
                    return null;
                });
            }

            @Override
            public void keyReleased(final KeyEvent evt) {
                SceneApplication.getApplication().enqueue(() -> {
                    int code = AwtKeyInput.convertAwtKey(evt.getKeyCode());
                    KeyInputEvent keyEvent = new KeyInputEvent(code, evt.getKeyChar(), false, false);
                    keyEvent.setTime(evt.getWhen());
                    if (app.getActiveCameraController() != null) {
                        app.getActiveCameraController().onKeyEvent(keyEvent);
                    }
                    return null;
                });
            }
        });

        oGLPanel.setDropTarget(new DropTarget(this, new SceneViewerDropTargetListener(this)));
    }

    /**
     * Adds a preview-only path selector; it never changes the saved scene or the
     * renderer default used by thumbnails, overlays and GUI viewports. Forward
     * is the initial/restoration choice. The custom engine's FrameGraph remains
     * the viewport pipeline in every mode; GPU tiled mode falls back to CPU.
     * <pre>{@code
     * // 第一步：在 SceneViewer 顶部选择 Forward / Deferred / TiledDeferredGPU。
     * // 第二步：所有修改排入渲染线程，保持 Swing 与 GL 的线程边界。
     * app.enqueue(() -> app.getViewPort().setRenderPath(RenderPath.TiledDeferredGPU));
     * // 第三步：选择 Forward 恢复；此选项不会写进模型或影响缩略图。
     * }</pre>
     */
    /**
     * Offers the four-step guide once per SDK user profile, after scene layout.
     * Guide replays it on demand; Skip/Done dismiss it without modifying assets.
     * Calls from scene callbacks are marshalled to Swing; held camera input is reset
     * on the render thread. Unsupported translucent windows use a plain guide card.
     * <pre>
     * 第一步：首次打开场景，依次阅读左键旋转、右键漫游、Q/Z 升降和 Q/W/E/R 工具说明。
     * 第二步：点击 Next 前进，Skip 或 Esc 跳过；关闭引导后再进行场景操作。
     * 第三步：工具栏 Guide 随时重新打开；不改变场景或用户的渲染设置。
     * </pre>
     */
    public void offerEditorGuide() {
        java.awt.EventQueue.invokeLater(() -> {
            if (org.openide.util.NbPreferences.forModule(SceneViewerTopComponent.class)
                    .getBoolean("basicSceneGuideSeen", false) || editorGuide != null) {
                return;
            }
            if (guideTimer != null) {
                guideTimer.stop();
            }
            guideTimer = new javax.swing.Timer(500, event -> showEditorGuide());
            guideTimer.setRepeats(false);
            guideTimer.start();
        });
    }

    private void showEditorGuide() {
        if (!isShowing() || oglCanvas == null || !oglCanvas.isShowing() || editorGuide != null) {
            return;
        }
        java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (owner == null) {
            return;
        }
        resetGuideInput();
        editorGuide = new SceneEditorGuide(owner, oglCanvas, () -> {
            editorGuide = null;
            resetGuideInput();
        });
        editorGuide.show();
        org.openide.util.NbPreferences.forModule(SceneViewerTopComponent.class)
                .putBoolean("basicSceneGuideSeen", true);
    }

    private void resetGuideInput() {
        app.enqueue(() -> {
            if (app.getActiveCameraController() != null) {
                app.getActiveCameraController().resetInputState();
            }
            return null;
        });
    }

    private void closeEditorGuide() {
        if (guideTimer != null) {
            guideTimer.stop();
        }
        if (editorGuide != null) {
            editorGuide.close();
        }
    }

    /**
     * Viewport shortcuts operate on the currently open scene, independent of the
     * explorer selection. Swing owns command dispatch; SaveCookie and scene undo
     * marshal mutations to the render thread. Never use window-wide bindings,
     * which would steal undo/save from a source editor or text field.
     * <pre>
     * 第一步：点击 3D 视口取得焦点，编辑场景。
     * 第二步：Ctrl+S 保存当前场景，Ctrl+Z 撤销；无需切回场景树。
     * 第三步：Ctrl+Y 或 Ctrl+Shift+Z 重做；切换场景后自动使用新场景。
     * 第四步：Ctrl+C 复制选中物体，Ctrl+V 粘贴到同级；选中场景根时粘贴为子物体。
     * </pre>
     */
    private void installSceneEditActions() {
        getActionMap().put("sdk.saveScene", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                SceneRequest request = app.getCurrentSceneRequest();
                if (request == null || request.getDataObject() == null) {
                    return;
                }
                org.openide.cookies.SaveCookie save = request.getDataObject().getLookup()
                        .lookup(org.openide.cookies.SaveCookie.class);
                if (save != null) {
                    try {
                        save.save();
                    } catch (java.io.IOException error) {
                        org.openide.util.Exceptions.printStackTrace(error);
                    }
                }
            }
        });
        for (String command : new String[]{"sdk.undoScene", "sdk.redoScene"}) {
            getActionMap().put(command, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (app.getCurrentSceneRequest() == null) {
                        return;
                    }
                    UndoRedo history = getUndoRedo();
                    if ("sdk.undoScene".equals(command)) {
                        if (history.canUndo()) {
                            history.undo();
                        }
                    } else if (history.canRedo()) {
                        history.redo();
                    }
                }
            });
        }
        for (String key : new String[]{"copy-to-clipboard", "paste-from-clipboard"}) {
            getActionMap().put("sdk." + key, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (key.equals("paste-from-clipboard")) {
                        pasteSceneSibling();
                        return;
                    }
                    javax.swing.Action action = com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent
                            .findInstance().getActionMap().get(key);
                    if (action != null && action.isEnabled()) {
                        action.actionPerformed(event);
                    }
                }
            });
        }
        int ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK;
        bindSceneShortcut(KeyEvent.VK_C, ctrl, "sdk.copy-to-clipboard");
        bindSceneShortcut(KeyEvent.VK_V, ctrl, "sdk.paste-from-clipboard");
        bindSceneShortcut(KeyEvent.VK_S, ctrl, "sdk.saveScene");
        bindSceneShortcut(KeyEvent.VK_Z, ctrl, "sdk.undoScene");
        bindSceneShortcut(KeyEvent.VK_Y, ctrl, "sdk.redoScene");
        bindSceneShortcut(KeyEvent.VK_Z, ctrl | java.awt.event.InputEvent.SHIFT_DOWN_MASK, "sdk.redoScene");
    }

    /** Paste beside the selected scene object; the scene root receives children. */
    private void pasteSceneSibling() {
        var explorer = com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance();
        var selected = explorer.getExplorerManager().getSelectedNodes();
        if (selected.length != 1) {
            return;
        }
        org.openide.nodes.Node destination = selected[0];
        if (destination.getParentNode() instanceof com.jme3.gde.core.sceneexplorer.nodes.JmeNode) {
            destination = destination.getParentNode();
        }
        if (!(destination instanceof com.jme3.gde.core.sceneexplorer.nodes.JmeNode model) || model.isReadOnly()) {
            return;
        }
        java.awt.datatransfer.Clipboard clipboard = org.openide.util.Lookup.getDefault()
                .lookup(org.openide.util.datatransfer.ExClipboard.class);
        if (clipboard == null) {
            clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        }
        try {
            var contents = clipboard.getContents(null);
            if (contents == null) {
                return;
            }
            var types = destination.getPasteTypes(contents);
            if (types.length > 0) {
                var replacement = types[0].paste();
                if (replacement != null) {
                    clipboard.setContents(replacement, null);
                }
            }
        } catch (java.io.IOException | IllegalStateException error) {
            org.openide.util.Exceptions.printStackTrace(error);
        }
    }

    private void bindSceneShortcut(int key, int modifiers, String command) {
        javax.swing.KeyStroke stroke = javax.swing.KeyStroke.getKeyStroke(key, modifiers);
        getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(stroke, command);
        getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, command);
    }

    private boolean handleSceneEditShortcut(KeyEvent event) {
        if (event.isConsumed()) {
            return true;
        }
        if (!event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return false;
        }
        String command = switch (event.getKeyCode()) {
            case KeyEvent.VK_C -> event.isShiftDown() ? null : "sdk.copy-to-clipboard";
            case KeyEvent.VK_V -> event.isShiftDown() ? null : "sdk.paste-from-clipboard";
            case KeyEvent.VK_S -> event.isShiftDown() ? null : "sdk.saveScene";
            case KeyEvent.VK_Z -> event.isShiftDown() ? "sdk.redoScene" : "sdk.undoScene";
            case KeyEvent.VK_Y -> event.isShiftDown() ? null : "sdk.redoScene";
            default -> null;
        };
        if (command == null) {
            return false;
        }
        event.consume();
        getActionMap().get(command).actionPerformed(new java.awt.event.ActionEvent(
                this, java.awt.event.ActionEvent.ACTION_PERFORMED, command));
        return true;
    }

    private static String ui(String key) {
        return com.jme3.gde.core.util.EditorMessages.text(key);
    }

    private void installRenderPathSelector() {
        javax.swing.JToolBar controls = new javax.swing.JToolBar();
        controls.setFloatable(false);
        controls.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
        javax.swing.JLabel status = new javax.swing.JLabel("ⓘ");
        app.enqueue(() -> {
            debugPreview = new com.jme3.gde.core.scene.processors.PreviewDebugProcessor(
                    app.getAssetManager(), app.getRootNode(), message -> java.awt.EventQueue.invokeLater(() -> {
                        boolean sdfMessage = message.contains("SDF") || message.contains("Ray-marched");
                        status.setText(sdfMessage ? (message.length() > 64
                                ? message.substring(0, 61) + "..." : message) : "ⓘ");
                        status.setToolTipText(message);
                    }));
            app.getViewPort().addProcessor(debugPreview);
            return null;
        });
        javax.swing.JComboBox<com.jme3.renderer.RenderPath> paths = new javax.swing.JComboBox<>(
                new com.jme3.renderer.RenderPath[] {
                    com.jme3.renderer.RenderPath.Forward, com.jme3.renderer.RenderPath.Deferred,
                    com.jme3.renderer.RenderPath.TiledDeferredCPU, com.jme3.renderer.RenderPath.TiledDeferredGPU,
                    com.jme3.renderer.RenderPath.Auto
                });
        paths.setToolTipText("Lit rendering path; GPU tiled falls back to CPU when unavailable.");
        paths.addActionListener(event -> {
            com.jme3.renderer.RenderPath path = (com.jme3.renderer.RenderPath) paths.getSelectedItem();
            app.enqueue(() -> {
                debugPreview.setRenderPath(path);
                return null;
            });
        });
        javax.swing.JComboBox<com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode> modes =
                new javax.swing.JComboBox<>(com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.values());
        modes.setToolTipText(ui("SdfViewTip"));
        modes.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value == com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.SDFSelected) {
                    setText(ui("SdfSelected"));
                } else if (value == com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.SDFGlobal) {
                    setText(ui("SdfGlobal"));
                }
                return this;
            }
        });
        javax.swing.JComboBox<String> cascades = new javax.swing.JComboBox<>(new String[]{
                ui("SdfColored"), ui("SdfAuto"), ui("SdfCascade0"), ui("SdfCascade1"), ui("SdfCascade2")});
        cascades.setMaximumSize(new java.awt.Dimension(115, 26));
        cascades.setToolTipText(ui("SdfCascadeTip"));
        cascades.setVisible(false);
        javax.swing.JLabel cellLabel = new javax.swing.JLabel(ui("SdfCell") + " ");
        cellLabel.setVisible(false);
        javax.swing.JSpinner cellSize = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(0.5, 0.001, 10000.0, 0.1));
        cellSize.setMaximumSize(new java.awt.Dimension(70, 26));
        cellSize.setToolTipText(ui("SdfCellTip"));
        cellSize.setVisible(false);
        Runnable applySdfOptions = () -> {
            int choice = cascades.getSelectedIndex();
            int level = choice < 2 ? -1 : choice - 2;
            boolean colors = choice != 1;
            float cell = ((Number) cellSize.getValue()).floatValue();
            app.enqueue(() -> {
                debugPreview.setSdfOptions(level, colors, cell);
                return null;
            });
        };
        cascades.addActionListener(event -> applySdfOptions.run());
        cellSize.addChangeListener(event -> applySdfOptions.run());
        modes.addActionListener(event -> {
            var mode = (com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode) modes.getSelectedItem();
            boolean global = mode == com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.SDFGlobal;
            cascades.setVisible(global);
            cellLabel.setVisible(global);
            cellSize.setVisible(global);
            jToolBar1.revalidate();
            app.enqueue(() -> {
                debugPreview.setMode(mode);
                app.configureEditorPostProcessing("Lit", mode == com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.Lit ? 1 : 0);
                return null;
            });
        });
        javax.swing.JButton refresh = new javax.swing.JButton("Refresh");
        refresh.addActionListener(event -> app.enqueue(() -> {
            debugPreview.refresh();
            return null;
        }));
        javax.swing.JButton help = new javax.swing.JButton(ui("Help"));
        help.setToolTipText(ui("HelpTip"));
        help.addActionListener(event -> javax.swing.JOptionPane.showMessageDialog(this,
                ui("HelpText"), ui("HelpTitle"), javax.swing.JOptionPane.INFORMATION_MESSAGE));
        controls.add(new javax.swing.JLabel(ui("Render") + "  "));
        controls.add(paths);
        controls.addSeparator();
        controls.add(new javax.swing.JLabel(ui("View") + "  "));
        controls.add(modes);
        controls.add(cascades);
        controls.add(cellLabel);
        controls.add(cellSize);
        controls.addSeparator();

        jToolBar1.add(help);
        javax.swing.JComboBox<String> theme = new javax.swing.JComboBox<>(new String[]{"Dark", "Light"});
        theme.setMaximumSize(new java.awt.Dimension(95, 32));
        theme.setSelectedItem(com.jme3.gde.core.util.EditorUiStyle.theme());
        theme.setToolTipText("Free FlatLaf editor theme");
        theme.addActionListener(event -> com.jme3.gde.core.util.EditorUiStyle.setTheme((String) theme.getSelectedItem()));
        theme.setFocusable(false);
        jToolBar1.addSeparator();
        jToolBar1.add(new javax.swing.JLabel("Theme  "));
        jToolBar1.add(theme);
        for (java.awt.Component component : controls.getComponents()) {
            component.setFocusable(false);
        }
        paths.setMaximumSize(new java.awt.Dimension(190, 32));
        modes.setMaximumSize(new java.awt.Dimension(135, 26));
        // The form still declares the old toggles. Hide them because view modes are exclusive.
        enableWireframe.setVisible(false);
        enableNormalView.setVisible(false);
        // One toolbar row: diagnostics live in the info tooltip, not a second row.
        jToolBar1.removeAll();
        jToolBar1.add(enableCamLight);
        jToolBar1.add(jToggleButton1);
        jToolBar1.add(enablePBRSky);
        javax.swing.JButton renderDocCapture =
                com.jme3.gde.core.renderdoc.RenderDocSupport.installButton(jToolBar1);
        jToolBar1.addSeparator();
        for (java.awt.Component component : controls.getComponents()) {
            jToolBar1.add(component);
        }
        jToolBar1.add(status);
        status.setToolTipText("Lit: selected rendering path");
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 6));
        javax.swing.JButton guide = new javax.swing.JButton(ui("Guide"));
        guide.setToolTipText(ui("GuideTip"));
        guide.addActionListener(event -> showEditorGuide());
        jToolBar1.add(guide);
        help.setText(ui("Help"));
        jToolBar1.add(help);
        enableStats.setText(ui("Stats"));
        enableStats.setIcon(null);
        enableStats.setToolTipText(ui("StatsTip"));
        jToolBar1.add(enableStats);
        javax.swing.JButton limit = new javax.swing.JButton(java.text.MessageFormat.format(ui("Cap"), app.getPreviewFrameLimit()));
        limit.setToolTipText("Preview frame limit: 30 / 60 / 120 / Unlimited. Hidden previews still throttle.");
        javax.swing.JPopupMenu limits = new javax.swing.JPopupMenu();
        for (int rate : new int[]{30, 60, 120, -1}) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(rate < 0 ? ui("Unlimited") : rate + " FPS");
            item.addActionListener(event -> {
                app.setPreviewFrameLimit(rate);
                limit.setText(rate < 0 ? ui("Uncapped") : java.text.MessageFormat.format(ui("Cap"), rate));
            });
            limits.add(item);
        }
        limit.addActionListener(event -> limits.show(limit, 0, limit.getHeight()));
        jToolBar1.add(limit);
        javax.swing.JLabel fps = new javax.swing.JLabel("— FPS");
        fps.setPreferredSize(new java.awt.Dimension(82, 28));
        fps.setMaximumSize(new java.awt.Dimension(82, 28));
        fps.setToolTipText("Measured preview frames/second, including rendering and AWT transfer (0.5s average).");
        jToolBar1.add(fps);
        javax.swing.Timer fpsTimer = new javax.swing.Timer(500, event -> fps.setText(
                String.format(java.util.Locale.ROOT, "%.1f FPS", app.getMeasuredPreviewFps())));
        addHierarchyListener(event -> {
            if (isShowing()) {
                fpsTimer.start();
            } else {
                fpsTimer.stop();
            }
        });
        javax.swing.JButton options = new javax.swing.JButton("⋯");
        options.setToolTipText("Refresh diagnostic view / editor theme");
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem refreshItem = new javax.swing.JMenuItem(ui("RefreshView"));
        refreshItem.addActionListener(event -> refresh.doClick());
        menu.add(refreshItem);
        menu.addSeparator();
        for (String name : new String[]{"Dark", "Light"}) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(ui(name + "Theme"));
            item.addActionListener(event -> com.jme3.gde.core.util.EditorUiStyle.setTheme(name));
            menu.add(item);
        }
        options.addActionListener(event -> menu.show(options, 0, options.getHeight()));
        jToolBar1.add(options);
        com.jme3.gde.core.util.EditorUiStyle.toolbar(jToolBar1);
        limit.setPreferredSize(new java.awt.Dimension(75, 26));
        limit.setMinimumSize(new java.awt.Dimension(75, 26));
        limit.setMaximumSize(new java.awt.Dimension(75, 26));
        paths.setMaximumSize(new java.awt.Dimension(140, 26));
        modes.setMaximumSize(new java.awt.Dimension(85, 26));
        paths.setPreferredSize(new java.awt.Dimension(140, 26));
        paths.setMinimumSize(new java.awt.Dimension(70, 26));
        modes.setPreferredSize(new java.awt.Dimension(85, 26));
        modes.setMinimumSize(new java.awt.Dimension(60, 26));
        modes.setPreferredSize(new java.awt.Dimension(135, 26));
        modes.setMaximumSize(new java.awt.Dimension(135, 26));
        status.setPreferredSize(new java.awt.Dimension(180, 26));
        status.setMaximumSize(new java.awt.Dimension(180, 26));
        javax.swing.JSpinner flySpeed = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                (double) app.getEditorFlySpeed(), 0.01, 10000.0, 1.0));
        flySpeed.setEditor(new javax.swing.JSpinner.NumberEditor(flySpeed, "0.##"));
        flySpeed.setPreferredSize(new java.awt.Dimension(78, 26));
        flySpeed.setMaximumSize(new java.awt.Dimension(78, 26));
        flySpeed.setToolTipText(ui("FlySpeedTip"));
        flySpeed.addChangeListener(event -> app.setEditorFlySpeed(((Number) flySpeed.getValue()).floatValue()));
        javax.swing.JButton gi = new javax.swing.JButton(ui("Visualize"));
        gi.setToolTipText(ui("VisualizeTip"));
        javax.swing.JPopupMenu giMenu = new javax.swing.JPopupMenu();
        javax.swing.JCheckBoxMenuItem showGi = new javax.swing.JCheckBoxMenuItem(ui("GiShow"), app.isGiVolumesVisible());
        showGi.setToolTipText(ui("GiVolumeTip"));
        showGi.addActionListener(event -> app.setGiVolumesVisible(showGi.isSelected()));
        giMenu.add(showGi);
        ProbeDisplayMenu probeDisplay = new ProbeDisplayMenu(app);
        giMenu.add(probeDisplay);
        for (com.jme3.light.Light.Type type : new com.jme3.light.Light.Type[]{
                com.jme3.light.Light.Type.Probe, com.jme3.light.Light.Type.Directional,
                com.jme3.light.Light.Type.Point, com.jme3.light.Light.Type.Spot}) {
            javax.swing.JCheckBoxMenuItem marker = new javax.swing.JCheckBoxMenuItem(
                    ui("Gizmos" + type.name()), app.isLightGizmosVisible(type));
            marker.addActionListener(event -> app.setLightGizmosVisible(type, marker.isSelected()));
            giMenu.add(marker);
        }
        giMenu.addSeparator();
        javax.swing.JCheckBoxMenuItem applyGi = new javax.swing.JCheckBoxMenuItem(ui("GiApply"), app.isGiPreviewEnabled());
        applyGi.setToolTipText(ui("GiApplyTip"));
        applyGi.addActionListener(event -> app.setGiPreviewEnabled(applyGi.isSelected()));
        giMenu.add(applyGi);
        javax.swing.JCheckBoxMenuItem gbuffer = new javax.swing.JCheckBoxMenuItem("GBuffer", app.isGBufferVisible());
        gbuffer.setToolTipText(ui("GBufferTip"));
        gbuffer.addActionListener(event -> app.setGBufferVisible(gbuffer.isSelected()));
        giMenu.add(gbuffer);
        gi.addActionListener(event -> {
            showGi.setSelected(app.isGiVolumesVisible());
            probeDisplay.refreshSelection();
            applyGi.setSelected(app.isGiPreviewEnabled());
            gbuffer.setSelected(app.isGBufferVisible());
            applyGi.setToolTipText(app.getGiPreviewStatus());
            giMenu.show(gi, 0, gi.getHeight());
        });
        com.jme3.gde.core.util.OverflowToolbar.install(jToolBar1,
                new java.awt.Component[]{enableCamLight, jToggleButton1, enablePBRSky, renderDocCapture},
                new java.awt.Component[]{new javax.swing.JLabel(ui("Render") + " "), paths},
                new java.awt.Component[]{new javax.swing.JLabel(ui("View") + " "), modes},
                new java.awt.Component[]{gi},
                new java.awt.Component[]{cascades},
                new java.awt.Component[]{cellLabel, cellSize},
                new java.awt.Component[]{new javax.swing.JLabel(ui("FlySpeed") + " "), flySpeed},
                new java.awt.Component[]{guide},
                new java.awt.Component[]{help},
                new java.awt.Component[]{enableStats},
                new java.awt.Component[]{limit, fps},
                new java.awt.Component[]{options},
                new java.awt.Component[]{status});

    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jToolBar1 = new javax.swing.JToolBar();
        enableCamLight = new javax.swing.JToggleButton();
        jToggleButton1 = new javax.swing.JToggleButton();
        jSeparator1 = new javax.swing.JToolBar.Separator();
        enableWireframe = new javax.swing.JToggleButton();
        enablePBRSky = new javax.swing.JToggleButton();
        enableNormalView = new javax.swing.JToggleButton();
        jPanel1 = new javax.swing.JPanel();
        enableStats = new javax.swing.JToggleButton();
        oGLPanel = new javax.swing.JPanel();

        setLayout(new java.awt.BorderLayout());

        jToolBar1.setRollover(true);
        jToolBar1.setMinimumSize(new java.awt.Dimension(110, 40));
        jToolBar1.setPreferredSize(new java.awt.Dimension(110, 40));

        enableCamLight.setIcon(IconList.lightOff);
        org.openide.awt.Mnemonics.setLocalizedText(enableCamLight, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableCamLight.text")); // NOI18N
        enableCamLight.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableCamLight.toolTipText")); // NOI18N
        enableCamLight.setFocusable(false);
        enableCamLight.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        enableCamLight.setMaximumSize(new java.awt.Dimension(32, 32));
        enableCamLight.setMinimumSize(new java.awt.Dimension(32, 32));
        enableCamLight.setPreferredSize(new java.awt.Dimension(32, 32));
        enableCamLight.setSelectedIcon(IconList.lightOn);
        enableCamLight.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        enableCamLight.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enableCamLightActionPerformed(evt);
            }
        });
        jToolBar1.add(enableCamLight);

        jToggleButton1.setIcon(IconList.eyeOff);
        org.openide.awt.Mnemonics.setLocalizedText(jToggleButton1, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.jToggleButton1.text")); // NOI18N
        jToggleButton1.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.jToggleButton1.toolTipText")); // NOI18N
        jToggleButton1.setFocusable(false);
        jToggleButton1.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        jToggleButton1.setMaximumSize(new java.awt.Dimension(32, 32));
        jToggleButton1.setMinimumSize(new java.awt.Dimension(32, 32));
        jToggleButton1.setPreferredSize(new java.awt.Dimension(32, 32));
        jToggleButton1.setSelectedIcon(IconList.eyeGreen);
        jToggleButton1.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        jToggleButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jToggleButton1ActionPerformed(evt);
            }
        });
        jToolBar1.add(jToggleButton1);

        jSeparator1.setPreferredSize(new java.awt.Dimension(32, 32));
        jToolBar1.add(jSeparator1);

        enableWireframe.setIcon(IconList.colorBox);
        org.openide.awt.Mnemonics.setLocalizedText(enableWireframe, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableWireframe.text")); // NOI18N
        enableWireframe.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableWireframe.toolTipText")); // NOI18N
        enableWireframe.setFocusable(false);
        enableWireframe.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        enableWireframe.setMaximumSize(new java.awt.Dimension(32, 32));
        enableWireframe.setMinimumSize(new java.awt.Dimension(32, 32));
        enableWireframe.setPreferredSize(new java.awt.Dimension(32, 32));
        enableWireframe.setSelectedIcon(IconList.wireBox);
        enableWireframe.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        enableWireframe.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enableWireframeActionPerformed(evt);
            }
        });
        jToolBar1.add(enableWireframe);

        enablePBRSky.setIcon(IconList.enablePbrSky);
        org.openide.awt.Mnemonics.setLocalizedText(enablePBRSky, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enablePBRSky.text")); // NOI18N
        enablePBRSky.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enablePBRSky.toolTipText")); // NOI18N
        enablePBRSky.setFocusable(false);
        enablePBRSky.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        enablePBRSky.setMaximumSize(new java.awt.Dimension(32, 32));
        enablePBRSky.setMinimumSize(new java.awt.Dimension(32, 32));
        enablePBRSky.setPreferredSize(new java.awt.Dimension(32, 32));
        enablePBRSky.setSelectedIcon(IconList.enablePbrSky);
        enablePBRSky.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        enablePBRSky.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enablePBRSkyActionPerformed(evt);
            }
        });
        jToolBar1.add(enablePBRSky);

        enableNormalView.setIcon(IconList.normalView);
        org.openide.awt.Mnemonics.setLocalizedText(enableNormalView, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableNormalView.text")); // NOI18N
        enableNormalView.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableNormalView.toolTipText")); // NOI18N
        enableNormalView.setFocusable(false);
        enableNormalView.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        enableNormalView.setSelectedIcon(IconList.normalView);
        enableNormalView.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        enableNormalView.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enableNormalViewActionPerformed(evt);
            }
        });
        jToolBar1.add(enableNormalView);
        jToolBar1.add(jPanel1);

        enableStats.setIcon(IconList.sceneInfo);
        org.openide.awt.Mnemonics.setLocalizedText(enableStats, org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableStats.text")); // NOI18N
        enableStats.setToolTipText(org.openide.util.NbBundle.getMessage(SceneViewerTopComponent.class, "SceneViewerTopComponent.enableStats.toolTipText")); // NOI18N
        enableStats.setFocusable(false);
        enableStats.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        enableStats.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        enableStats.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enableStatsActionPerformed(evt);
            }
        });
        jToolBar1.add(enableStats);

        add(jToolBar1, java.awt.BorderLayout.NORTH);

        oGLPanel.setPreferredSize(new java.awt.Dimension(100, 100));
        oGLPanel.setLayout(new java.awt.GridLayout(1, 0));
        add(oGLPanel, java.awt.BorderLayout.CENTER);
    }// </editor-fold>//GEN-END:initComponents

    private void enableCamLightActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enableCamLightActionPerformed
        app.enableCamLight(enableCamLight.isSelected());
    }//GEN-LAST:event_enableCamLightActionPerformed

    private void enableWireframeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enableWireframeActionPerformed
        app.enableWireFrame(enableWireframe.isSelected());
    }//GEN-LAST:event_enableWireframeActionPerformed

    private void enableStatsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enableStatsActionPerformed
        app.enableStats(enableStats.isSelected());
    }//GEN-LAST:event_enableStatsActionPerformed

    private void jToggleButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jToggleButton1ActionPerformed
        FilterExplorerTopComponent.findInstance().setFilterEnabled(jToggleButton1.isSelected());
    }//GEN-LAST:event_jToggleButton1ActionPerformed

    private void enablePBRSkyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enablePBRSkyActionPerformed
        app.enablePBRSkybox(enablePBRSky.isSelected());
    }//GEN-LAST:event_enablePBRSkyActionPerformed

    private void enableNormalViewActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enableNormalViewActionPerformed
        app.enableNormalView(enableNormalView.isSelected());
    }//GEN-LAST:event_enableNormalViewActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JToggleButton enableCamLight;
    private javax.swing.JToggleButton enableNormalView;
    private javax.swing.JToggleButton enablePBRSky;
    private javax.swing.JToggleButton enableStats;
    private javax.swing.JToggleButton enableWireframe;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JToolBar.Separator jSeparator1;
    private javax.swing.JToggleButton jToggleButton1;
    private javax.swing.JToolBar jToolBar1;
    private javax.swing.JPanel oGLPanel;
    // End of variables declaration//GEN-END:variables

    /**
     * Gets default instance. Do not use directly: reserved for *.settings files
     * only, i.e. deserialization routines; otherwise you could get a
     * non-deserialized instance. To obtain the singleton instance, use
     * {@link #findInstance}.
     *
     * @return
     */
    public static synchronized SceneViewerTopComponent getDefault() {
        if (instance == null) {
            instance = new SceneViewerTopComponent();
        }
        return instance;
    }

    /**
     * Obtain the SceneViewerTopComponent instance. Never call
     * {@link #getDefault} directly!
     *
     * @return
     */
    public static synchronized SceneViewerTopComponent findInstance() {
        TopComponent win = WindowManager.getDefault().findTopComponent(PREFERRED_ID);
        if (win == null) {
            Logger.getLogger(SceneViewerTopComponent.class.getName()).warning(
                    "Cannot find " + PREFERRED_ID + " component. It will not be located properly in the window system.");
            return getDefault();
        }
        if (win instanceof SceneViewerTopComponent sceneViewerTopComponent) {
            return sceneViewerTopComponent;
        }
        Logger.getLogger(SceneViewerTopComponent.class.getName()).warning(
                "There seem to be multiple components with the '" + PREFERRED_ID
                + "' ID. That is a potential source of errors and unexpected behavior.");
        return getDefault();
    }

    @Override
    public int getPersistenceType() {
        return TopComponent.PERSISTENCE_NEVER;
    }

    @Override
    public HelpCtx getHelpCtx() {
        //this call is for single components:
        //HelpCtx.setHelpIDString(this, "com.jme3.gde.core.sceneviewer");
        return helpContext;
    }

    public void setHelpContext(HelpCtx ctx) {
        this.helpContext = ctx;
    }

    @Override
    public void componentOpened() {
        super.componentOpened();
    }

    @Override
    protected void componentShowing() {
        super.componentShowing();
    }

    @Override
    protected void componentHidden() {
        closeEditorGuide();
        super.componentHidden();
    }

    @Override
    public void componentClosed() {
        closeEditorGuide();
        super.componentClosed();
//        oglCanvas.setActiveUpdates(false);
        SceneRequest req = SceneApplication.getApplication().getCurrentSceneRequest();
        if (req != null) {
            enableCamLight.setSelected(false);
            enableStats.setSelected(false);
            SceneApplication.getApplication().closeScene(req);
        }
    }

    /** Clears the lighting preview switches on the EDT when the scene changes. */
    public void resetSceneLightingButtons() {
        enableCamLight.setSelected(false);
        enablePBRSky.setSelected(false);
    }

    void writeProperties(java.util.Properties p) {
        // better to version settings since initial version as advocated at
        // http://wiki.apidesign.org/wiki/PropertyFiles
        p.setProperty("version", "1.0");
        // TODO store your settings
    }

    Object readProperties(java.util.Properties p) {
        if (instance == null) {
            instance = this;
        }
        instance.readPropertiesImpl(p);
        return instance;
    }

    private void readPropertiesImpl(java.util.Properties p) {
        String version = p.getProperty("version");
        // TODO read your settings according to their version
    }

    @Override
    protected String preferredID() {
        return PREFERRED_ID;
    }

    @Override
    public UndoRedo getUndoRedo() {
        return Lookup.getDefault().lookup(UndoRedo.class);
    }

    /**
     * Queues one placement ray on the render thread after a viewport drop.
     * <pre>
     * 第一步：在 SceneComposer 展开 Spawn，从 Spatial 或 Light 拖动按钮到视口。
     * 第二步：松手时取最近表面交点；空处使用与模型拖放相同的默认深度。
     * 如有配置对话框，确认后创建。局部对象挂到场景根节点。
     * 天空盒、NavMesh 和全局灯光保留各自的放置规则。
     * 第三步：Ctrl+Z 撤销，Ctrl+S 保存。
     * </pre>
     */
    public boolean spawnObject(SceneSpawnTransfer transfer, java.awt.Component target, java.awt.Point drop) {
        SceneRequest request = transfer.request();
        if (request != app.getCurrentSceneRequest() || request.getJmeNode() == null
                || request.getJmeNode().isReadOnly()
                || !transfer.action().isEnabled() || oglCanvas == null) {
            return false;
        }
        java.awt.Point point = javax.swing.SwingUtilities.convertPoint(target, drop, oglCanvas);
        if (!oglCanvas.contains(point)) {
            return false;
        }
        float x = point.x / (float) oglCanvas.getWidth();
        float y = 1f - point.y / (float) oglCanvas.getHeight();
        app.enqueue(() -> {
            if (request != app.getCurrentSceneRequest()) {
                return null;
            }
            Camera camera = app.getCamera();
            Vector2f pixels = new Vector2f(x * camera.getWidth(), y * camera.getHeight());
            Vector3f world = findDropPosition(camera, request.getRootNode(), pixels, true);
            java.awt.EventQueue.invokeLater(() -> {
                if (request == app.getCurrentSceneRequest() && !request.getJmeNode().isReadOnly()) {
                    transfer.action().actionPerformed(new SceneSpawnEvent(request, world));
                }
            });
            return null;
        });
        return true;
    }

    private static Vector3f findDropPosition(Camera camera, Spatial scene, Vector2f pixels, boolean visibleOnly) {
        Spatial updateRoot = scene;
        while (updateRoot.getParent() != null) {
            updateRoot = updateRoot.getParent();
        }
        updateRoot.updateGeometricState();
        Vector3f origin = camera.getWorldCoordinates(pixels, 0);
        Vector3f direction = camera.getWorldCoordinates(pixels, 1).subtractLocal(origin).normalizeLocal();
        CollisionResults hits = new CollisionResults();
        scene.collideWith(new Ray(origin, direction), hits);
        for (CollisionResult hit : hits) {
            if (!visibleOnly || hit.getGeometry().getCullHint() != Spatial.CullHint.Always) {
                return hit.getContactPoint().clone();
            }
        }
        return camera.getWorldCoordinates(pixels, .95f);
    }

    /** Converts top-left AWT drop coordinates into bottom-left framebuffer pixels. */
    private Vector2f dropPixels(Vector2f logical) {
        float scaleX = app.getCamera().getWidth() / (float) Math.max(1, oglCanvas.getWidth());
        float scaleY = app.getCamera().getHeight() / (float) Math.max(1, oglCanvas.getHeight());
        return new Vector2f(logical.x * scaleX, app.getCamera().getHeight() - logical.y * scaleY);
    }

    /** Drops a material using logical AWT coordinates, including HiDPI conversion. */
    public void applyMaterial(String assetName, Vector2f cursorPosition) {
        Vector2f pixels = dropPixels(cursorPosition);
        SceneRequest request = app.getCurrentSceneRequest();
        app.enqueue(() -> {
            if (request == null || request != app.getCurrentSceneRequest()) {
                return null;
            }
            if (request.getJmeNode() == null || request.getJmeNode().isReadOnly()) {
                return null;
            }
            Spatial scene = request.getRootNode();
            Spatial updateRoot = scene;
            while (updateRoot.getParent() != null) {
                updateRoot = updateRoot.getParent();
            }
            updateRoot.updateGeometricState();
            Vector3f origin = app.getCamera().getWorldCoordinates(pixels, 0);
            Vector3f direction = app.getCamera().getWorldCoordinates(pixels, 1).subtractLocal(origin).normalizeLocal();
            com.jme3.collision.CollisionResults hits = new com.jme3.collision.CollisionResults();
            scene.collideWith(new Ray(origin, direction), hits);
            Spatial spatial = hits.size() == 0 ? null : hits.getClosestCollision().getGeometry();
            if (spatial != null) {
                Material material = request.getManager().loadAsset(new MaterialKey(assetName));
                java.util.Map<com.jme3.scene.Geometry, Material> previous = new java.util.IdentityHashMap<>();
                spatial.depthFirstTraversal(child -> {
                    if (child instanceof com.jme3.scene.Geometry geometry) {
                        previous.put(geometry, geometry.getMaterial());
                    }
                });
                spatial.setMaterial(material);
                registerDropEdit(request, () -> previous.forEach(com.jme3.scene.Geometry::setMaterial),
                        () -> spatial.setMaterial(material));
            }
            return null;
        });
    }

    /**
     * Imports a dropped model under the selected scene Node (a Geometry uses its
     * parent; no selection uses the saved scene root). Source models are converted
     * on a worker with default options before insertion. An existing sibling J3O
     * is reused to preserve edits; explicit reconversion uses the options dialog.
     * A scene/parent removed while conversion runs cancels insertion. Scene
     * insertion is undoable, while the converted asset remains on disk.
     * <pre>
     * 第一步：选择目标后从 Asset Browser 拖入模型；灯光/几何体等条目向上查找 Node，没有有效父节点则使用场景根节点。
     * 第二步：非 J3O 自动按默认选项转换；已有同名 J3O 则使用它。
     * 第三步：完成后 Ctrl+Z 撤销场景插入，Ctrl+S 保存场景。
     * </pre>
     */
    public void addModel(String assetName, Vector2f cursorPosition) {
        addModel(assetName, cursorPosition, false);
    }

    /** Insert on the render thread; Link persists an external J3O reference.
     * 第一步：拖入 J3O 后选择 Link 或 AddTo。
     * 第二步：Link 保存外部引用，AddTo 保存独立副本；两者都支持撤销。
     */
    public void addModel(String assetName, Vector2f cursorPosition, boolean linked) {
        Vector2f pixels = dropPixels(cursorPosition);
        SceneRequest request = app.getCurrentSceneRequest();
        if (request == null || request.getManager() == null || request.getJmeNode() == null
                || request.getJmeNode().isReadOnly()) {
            return;
        }
        Spatial selected = null;
        var nodes = com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance()
                .getExplorerManager().getSelectedNodes();
        if (nodes.length == 1) {
            if (nodes[0] instanceof com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode n && n.isReadOnly()) {
                return;
            }
            // Walk up from light or control entries to the scene node that owns them.
            // Their editor gizmos cannot receive dropped spatials.
            org.openide.nodes.Node cursor = nodes[0];
            while (cursor != null) {
                if (!(cursor instanceof com.jme3.gde.core.sceneexplorer.nodes.JmeLight)) {
                    Spatial candidate = cursor.getLookup().lookup(Spatial.class);
                    if (candidate instanceof Node) {
                        selected = candidate;
                        break;
                    }
                }
                cursor = cursor.getParentNode();
            }
        }
        final Spatial selection = selected;
        // Capture the parent and position before the conversion runs on a worker.
        app.enqueue(() -> {
            Spatial scene = request.getRootNode();
            Node parent = selection instanceof Node node ? node : selection == null ? null : selection.getParent();
            if (parent == null || (parent != scene && (!(scene instanceof Node) || !parent.hasAncestor((Node) scene)))) {
                parent = scene instanceof Node node ? node : null;
            }
            if (parent == null) {
                reportAssetDrop("Select a Node in an open scene before dropping a model.");
                return null;
            }
            Vector3f world = findDropPosition(app.getCamera(), scene, pixels, false);
            final Node destination = parent;
            org.openide.util.RequestProcessor.getDefault().post(() -> {
                try {
                    var assets = request.getManager();
                    var source = assets.getAssetFolder().getFileObject(assetName);
                    if (source == null) {
                        throw new java.io.IOException("Asset not found: " + assetName);
                    }
                    var output = source;
                    if (!source.getExt().equalsIgnoreCase("j3o")) {
                        output = source.getParent().getFileObject(source.getName(), "j3o");
                        if (output == null) {
                            if (!(org.openide.loaders.DataObject.find(source)
                                    instanceof com.jme3.gde.core.assets.SpatialAssetDataObject model)
                                    || model.loadAsset() == null) {
                                throw new java.io.IOException("Cannot load model: " + assetName);
                            }
                            model.saveAsset(com.jme3.gde.core.assets.ModelConversionOptions.defaults());
                            output = source.getParent().getFileObject(source.getName(), "j3o");
                        }
                    }
                    if (output == null) {
                        throw new java.io.IOException("Conversion did not produce a J3O");
                    }
                    String key = org.openide.filesystems.FileUtil.getRelativePath(assets.getAssetFolder(), output);
                    app.enqueue(() -> {
                        try {
                            if (request != app.getCurrentSceneRequest() || (destination != scene
                                    && (!(scene instanceof Node) || !destination.hasAncestor((Node) scene)))) {
                                return null;
                            }
                            assets.deleteFromCache(new com.jme3.asset.ModelKey(key));
                            Spatial loaded = assets.loadModel(key);
                            final Spatial spatial;
                            if (linked) {
                                com.jme3.scene.AssetLinkNode link = new com.jme3.scene.AssetLinkNode();
                                link.setName(loaded.getName());
                                link.attachLinkedChild(loaded, new com.jme3.asset.ModelKey(key));
                                spatial = link;
                            } else {
                                spatial = loaded;
                            }
                            spatial.setLocalTranslation(destination.worldToLocal(world, null));
                            // Compile shaders before attaching the model. A broken shader in the live
                            // scene would fail every time the model is drawn.
                            spatial.updateGeometricState();
                            app.getRenderManager().preloadScene(spatial);
                            destination.attachChild(spatial);
                            registerDropEdit(request, spatial::removeFromParent, () -> destination.attachChild(spatial));
                            java.awt.EventQueue.invokeLater(() -> {
                                if (request != app.getCurrentSceneRequest()) {
                                    return;
                                }
                                var node = request.getJmeNode().getChild(destination);
                                if (node != null) {
                                    node.refresh(true);
                                    com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance()
                                            .selectAddedObject(node, spatial);
                                }
                            });
                        } catch (RuntimeException error) {
                            reportAssetDrop(error.toString());
                        }
                        return null;
                    });
                } catch (Exception error) {
                    reportAssetDrop(error.toString());
                }
            });
            return null;
        });
    }

    private void reportAssetDrop(String message) {
        org.openide.DialogDisplayer.getDefault().notifyLater(new org.openide.NotifyDescriptor.Message(
                message, org.openide.NotifyDescriptor.ERROR_MESSAGE));
    }

    private void registerDropEdit(SceneRequest request, Runnable undo, Runnable redo) {
        java.awt.EventQueue.invokeLater(() -> request.getDataObject().setModified(true));
        Lookup.getDefault().lookup(com.jme3.gde.core.undoredo.SceneUndoRedoManager.class).addEdit(this,
                new com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit() {
            public void sceneUndo() {
                undo.run();
            }
            public void sceneRedo() {
                redo.run();
            }
            public void awtUndo() {
                request.getDataObject().setModified(true);
                if (request.getJmeNode() != null) {
                    request.getJmeNode().refresh(true);
                }
            }
            public void awtRedo() {
                awtUndo();
            }
        });
    }

    public static Spatial pickWorldSpatial(Camera cam, Vector2f mouseLoc, Node jmeRootNode) {
        CollisionResult cr = pick(cam, mouseLoc, jmeRootNode);
        if (cr != null) {
            return cr.getGeometry();
        } else {
            return null;
        }
    }

    private static CollisionResult pick(Camera cam, Vector2f mouseLoc, Node node) {
        CollisionResults results = new CollisionResults();
        Ray ray = new Ray();
        Vector3f pos = cam.getWorldCoordinates(mouseLoc, 0).clone();
        Vector3f dir = cam.getWorldCoordinates(mouseLoc, 0.125f).clone();
        dir.subtractLocal(pos).normalizeLocal();
        ray.setOrigin(pos);
        ray.setDirection(dir);
        node.collideWith(ray, results);
        CollisionResult result = results.getClosestCollision();
        return result;
    }
}
