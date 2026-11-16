/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.gui.view;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.gui.NiftyGuiDataObject;
import com.jme3.gde.gui.nodes.GElementNode;
import com.jme3.gde.gui.nodes.GUINode;
import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.tools.resourceloader.ResourceLocation;
import jada.ngeditor.controller.CommandProcessor;
import jada.ngeditor.controller.GUIEditor;
import jada.ngeditor.guiviews.DND.PaletteDropTarget;
import jada.ngeditor.guiviews.DND.TrasferHandling;
import jada.ngeditor.guiviews.J2DNiftyView;
import jada.ngeditor.listeners.events.SelectionChanged;
import jada.ngeditor.model.GUI;
import jada.ngeditor.model.GuiEditorModel;
import jada.ngeditor.model.elements.GElement;
import jada.ngeditor.model.elements.GLayer;
import jada.ngeditor.model.exception.NoProductException;
import jada.ngeditor.persistence.GUIWriter;
import java.awt.Dimension;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Observable;
import java.util.Observer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import org.netbeans.core.spi.multiview.CloseOperationState;
import org.netbeans.core.spi.multiview.MultiViewElement;
import org.netbeans.core.spi.multiview.MultiViewElementCallback;
import org.openide.awt.UndoRedo;
import org.openide.explorer.ExplorerManager;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;
import org.openide.windows.TopComponent;

@MultiViewElement.Registration(
        displayName = "#LBL_NiftyGui_VISUAL",
        iconBase = "com/jme3/gde/gui/multiview/icons/game-monitor.png",
        mimeType = "text/x-niftygui+xml",
        persistenceType = TopComponent.PERSISTENCE_NEVER,
        preferredID = "NiftyGuiVisual",
        position = 2000)
@Messages("LBL_NiftyGui_VISUAL=Visual")
@SuppressWarnings({"unchecked", "rawtypes"})
public final class NiftyGuiVisualElement extends JPanel implements MultiViewElement , ExplorerManager.Provider,Observer {
    private static final Logger logger = Logger.getLogger(NiftyGuiVisualElement.class.getName());
    private final NiftyGuiDataObject obj;
    private final JToolBar toolbar = new JToolBar();
    private transient MultiViewElementCallback callback;
    private final GUIEditor editor;
    private final Nifty nifty;
    private final J2DNiftyView view;
    private final JComboBox layers = new JComboBox();
    private final ExplorerManager nodesManager;
    private final UndoRedo.Manager undoSupport;
    private int guiID;
    private GUI gui;
    private String synchronizedXml;
    private String serializedXml;
    private boolean loading;
    private boolean pending;
    private boolean closed;
    private final JLabel status = new JLabel();
     private final InstanceContent content = new InstanceContent();
     private final Lookup lookup;
     private final AssetManager assetManager;

    protected class ResourceLocationJmp implements ResourceLocation {

        @Override
        public InputStream getResourceAsStream(String path) {
            if (assetManager == null) {
                return null;
            }
            AssetKey<Object> key = new AssetKey<>(path);
            AssetInfo info = assetManager.locateAsset(key);
            if (info != null){
                return info.openStream();
            }else{
                return null;
            }
        }

        @Override
        public URL getResource(String path) {
            return null;
        }
    }
    
    private final ResourceLocation resourceLocation = new ResourceLocationJmp();
    
        
    public NiftyGuiVisualElement(Lookup lkp) {
        obj = lkp.lookup(NiftyGuiDataObject.class);
        assert obj != null;
        assetManager = obj.getLookup().lookup(AssetManager.class);


        initComponents();
        view = new J2DNiftyView(800, 600);
        view.init(resourceLocation);
        this.scrollArea.getViewport().addChangeListener(view);
        this.scrollArea.setViewportView(view);
        TrasferHandling tranf = new TrasferHandling();
        PaletteDropTarget tmp = new PaletteDropTarget();
        editor = obj.getLookup().lookup(GUIEditor.class);
        nodesManager = new ExplorerManager();
        nifty = view.getNifty();
        view.setTransferHandler(tranf);
        view.setDropTarget(tmp);
       // editor.addObserver(view);
       // editor.addObserver(tranf);

        this.createToolbar();
        this.undoSupport = new UndoRedo.Manager() {
            @Override
            public synchronized void undo() {
                activateGui();
                super.undo();
                synchronizeDocument();
            }

            @Override
            public synchronized void redo() {
                activateGui();
                super.redo();
                synchronizeDocument();
            }
        };
        CommandProcessor.getInstance().setUndoManager(undoSupport);
        this.content.set(Collections.singleton(obj.getNodeDelegate()), null);
        lookup = new org.openide.util.lookup.ProxyLookup(new AbstractLookup(content), obj.getLookup());
        toolbar.add(status);
    }
 /**
 * Old code
 * @author normenhansen
 */
     private void createToolbar() {
        toolbar.setPreferredSize(new Dimension(10000, 24));
        toolbar.setMaximumSize(new Dimension(10000, 24));
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Change Resolution"));
        JComboBox comboBox = new JComboBox(new String[]{"640x480", "480x800", "800x480", "800x600", "1024x768", "1280x720"});
        comboBox.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() != ItemEvent.SELECTED) {
                    return;
                }
                String string = (String) e.getItem();
                if (null == string) {
                    view.setResoltion(800, 600);
                } else switch (string) {
                    case "640x480" -> view.setResoltion(640, 480);
                    case "1024x768" -> view.setResoltion(1024, 768);
                    case "1280x720" -> view.setResoltion(1280, 720);
                    case "800x600" -> view.setResoltion(800, 600);
                    case "800x480" -> view.setResoltion(800, 480);
                    case "480x800" -> view.setResoltion(480, 800);
                    default -> view.setResoltion(800, 600);
                }
            }
        });
        toolbar.add(comboBox);
        comboBox.setSelectedItem("800x600");
        toolbar.add(new JLabel("Current Layer"));
        layers.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() != ItemEvent.SELECTED) {
                    return;
                }
                GLayer item = (GLayer) e.getItem();
                if (!loading && item != null && gui != null) {
                    activateGui();
                    editor.selectElement(item);
                }
            }
        });
        toolbar.add(layers);
     }
    @Override
    public String getName() {
        return "NiftyGuiVisualElement";
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        scrollArea = new javax.swing.JScrollPane();

        setLayout(new java.awt.BorderLayout());
        add(scrollArea, java.awt.BorderLayout.CENTER);
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JScrollPane scrollArea;
    // End of variables declaration//GEN-END:variables
    @Override
    public JComponent getVisualRepresentation() {
        return this;
    }

    @Override
    public JComponent getToolbarRepresentation() {
        return toolbar;
    }

    @Override
    public Action[] getActions() {
        return new Action[0];
    }

    @Override
    public Lookup getLookup() {
        return this.lookup ;
    }
    /**
     * The XML document owns saving and close confirmation. Switching tabs never
     * writes the file. Visual history survives saving and tab switches; editing
     * XML rebuilds the model and clears commands referencing its old elements.
     * All lifecycle callbacks and model mutations run on the Swing EDT.
     * Usage: 第一步：编辑 XML; 第二步：切换 Visual;
     * 第三步：使用撤销/重做; 第四步：Ctrl+S 保存.
     * Invalid XML stays available for correction in the XML tab.
     */
    @Override
    public void componentOpened() {
        closed = false;
    }

    @Override
    public void componentClosed() {
        closed = true;
        detachGui();
        undoSupport.discardAllEdits();
    }

    @Override
    public void componentShowing() {
        loadGui();
    }

    @Override
    public void componentHidden() {
        synchronizeDocument();
    }

    private void activateGui() {
        if (gui != null) {
            GuiEditorModel model = (GuiEditorModel) CommandProcessor.getInstance().getObservable();
            model.setCurrentGUI(guiID);
            CommandProcessor.getInstance().setUndoManager(undoSupport);
        }
    }

    @Override
    public void componentActivated() {
        activateGui();
    }

    @Override
    public void componentDeactivated() {
        synchronizeDocument();
    }

    @Override
    public UndoRedo getUndoRedo() {
        return this.undoSupport;
    }

    @Override
    public void setMultiViewCallback(MultiViewElementCallback callback) {
        this.callback = callback;
    }

    @Override
    public CloseOperationState canCloseElement() {
        return CloseOperationState.STATE_OK;
    }

    @Override
    public ExplorerManager getExplorerManager() {
       return nodesManager;
    }

    @Override
    public void update(Observable source, Object event) {
        if (loading || closed || gui == null) {
            return;
        }
        if (event instanceof SelectionChanged) {
            SelectionChanged selection = (SelectionChanged) event;
            for (GElementNode previous : lookup.lookupAll(GElementNode.class)) {
                content.remove(previous);
            }
            if (!selection.getNewSelection().isEmpty()) {
                content.add(new GElementNode(selection.getElement()));
            }
        } else if (source == gui && !pending) {
            // Notifications may arrive in the middle of a command.
            pending = true;
            javax.swing.SwingUtilities.invokeLater(() -> {
                pending = false;
                synchronizeDocument();
            });
        }
    }

    private javax.swing.text.StyledDocument document() throws IOException {
        return obj.getLookup().lookup(org.openide.cookies.EditorCookie.class).openDocument();
    }

    private void detachGui() {
        if (gui != null) {
            com.jme3.gde.gui.nodes.ReferenceEdit.unbind(gui);
            gui.deleteObserver(this);
            gui.getSelection().deleteObserver(this);
        }
    }

    private String serialize() throws Exception {
        try (InputStream input = new GUIWriter(gui).getDocumentStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void loadGui() {
        loading = true;
        try {
            javax.swing.text.StyledDocument doc = document();
            String xml = doc.getText(0, doc.getLength());
            if (gui != null && xml.equals(synchronizedXml)) {
                activateGui();
                return;
            }
            ProjectAssetManager manager = obj.getLookup().lookup(ProjectAssetManager.class);
            if (manager == null) {
                throw new IOException("Open this GUI in a project with an assets folder.");
            }
            try (InputStream input = new java.io.ByteArrayInputStream(
                    xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                editor.createNewGui(nifty, input, new File(manager.getAssetFolder().getPath()));
            }
            detachGui();
            gui = editor.getGui();
            guiID = gui.getGUIid();
            com.jme3.gde.gui.nodes.ReferenceEdit.bind(gui, undoSupport);
            undoSupport.discardAllEdits();
            activateGui();
            view.newGui(gui);
            nodesManager.setRootContext(new GUINode(gui));
            gui.getSelection().addObserver(this);
            gui.addObserver(this);
            layers.setModel(new DefaultComboBoxModel<>(gui.getLayers().toArray(GLayer[]::new)));
            layers.setSelectedItem(gui.getCurrentLayer());
            serializedXml = serialize();
            synchronizedXml = xml;
            scrollArea.setViewportView(view);
            status.setText("");
        } catch (Exception ex) {
            scrollArea.setViewportView(new JLabel("Cannot preview XML: " + ex.getMessage()));
            status.setText("Preview initialization failed; see the SDK log for details");
            logger.log(Level.WARNING, "Cannot load Nifty XML", ex);
        } finally {
            loading = false;
        }
    }

    private void synchronizeDocument() {
        if (gui == null || loading || closed || scrollArea.getViewport().getView() != view) {
            return;
        }
        try {
            javax.swing.text.StyledDocument doc = document();
            String current = doc.getText(0, doc.getLength());
            if (!current.equals(synchronizedXml)) {
                return; // Never overwrite newer XML edits.
            }
            String xml = serialize();
            if (xml.equals(serializedXml)) {
                return; // Previewing or selecting alone must not dirty the file.
            }
            org.openide.text.NbDocument.runAtomic(doc, () -> {
                try {
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, xml, null);
                } catch (javax.swing.text.BadLocationException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            serializedXml = xml;
            synchronizedXml = xml;
            status.setText("");
        } catch (Exception ex) {
            status.setText("Cannot synchronize GUI: " + ex.getMessage());
            logger.log(Level.WARNING, "Cannot synchronize Nifty document", ex);
        }
    }
}
