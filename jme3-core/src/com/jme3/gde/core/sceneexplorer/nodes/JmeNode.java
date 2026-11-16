/*
 *  Copyright (c) 2009-2010 jMonkeyEngine
 *  All rights reserved.
 * 
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 * 
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 
 *  * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 *  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.core.sceneexplorer.nodes;

import com.jme3.gde.core.icons.IconList;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.sceneexplorer.nodes.actions.AddUserDataAction;
import com.jme3.gde.core.sceneexplorer.nodes.actions.ControlsPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewControlPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewLightPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewSpatialPopup;
import com.jme3.gde.core.sceneexplorer.nodes.actions.ToolPopup;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.awt.Image;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.swing.Action;
import org.openide.actions.CopyAction;
import org.openide.actions.CutAction;
import org.openide.actions.DeleteAction;
import org.openide.actions.PasteAction;
import org.openide.actions.RenameAction;
import org.openide.awt.Actions;
import org.openide.loaders.DataObject;
import org.openide.nodes.Sheet;
import org.openide.util.Exceptions;
import org.openide.util.actions.SystemAction;
import org.openide.util.datatransfer.PasteType;

/**
 *
 * @author normenhansen
 */
@org.openide.util.lookup.ServiceProvider(service = SceneExplorerNode.class)
public class JmeNode extends JmeSpatial {

    private static Image smallImage = IconList.node.getImage();
    private Node node;

    public JmeNode() {
    }

    public JmeNode(Node spatial, JmeSpatialChildren children) {
        super(spatial, children);
        this.node = spatial;
    }

    @Override
    public Image getIcon(int type) {
        return smallImage;
    }

    @Override
    public Image getOpenedIcon(int type) {
        return smallImage;
    }

    @Override
    protected Sheet createSheet() {
        //TODO: multithreading..
        Sheet sheet = super.createSheet();
        Sheet.Set set = Sheet.createPropertiesSet();
        set.setDisplayName("Node");
        set.setName(Node.class.getName());
        Node obj = node;//getLookup().lookup(Spatial.class);
        if (obj == null) {
            return sheet;
        }
        sheet.put(set);
        return sheet;

    }

    @Override
    public PasteType getDropType(final Transferable transfer, int action, int index) {
        if (((JmeSpatialChildren) jmeChildren).readOnly || !transfer.isDataFlavorSupported(SPATIAL_FLAVOR)) {
            return null;
        }
        final ClipboardSpatial data;
        try {
            data = (ClipboardSpatial) transfer.getTransferData(SPATIAL_FLAVOR);
        } catch (Exception error) {
            return null;
        }
        return new PasteType() {
            @Override
            public Transferable paste() throws IOException {
                try {
                    SceneApplication.getApplication().enqueue(() -> {
                        Spatial pasted = data.cutSource() == null ? data.createSpatial() : data.cutSource();
                        if (pasted == null) {
                            throw new IOException("Clipboard model could not be loaded.");
                        }
                        if (data.cutSource() != null && (!data.cutBelongsToCurrentScene() || pasted.getParent() == null)) {
                            throw new IOException("Cut source is no longer in this scene. Copy again.");
                        }
                        for (Spatial ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
                            if (ancestor == pasted) {
                                throw new IOException("Cannot paste a node inside itself or its descendants.");
                            }
                        }
                        Node previousParent = pasted.getParent();
                        int previousIndex = previousParent == null ? 0 : previousParent.getChildIndex(pasted);
                        node.attachChild(pasted);
                        org.openide.util.Lookup.getDefault().lookup(com.jme3.gde.core.undoredo.SceneUndoRedoManager.class)
                                .addEdit(this, new com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit() {
                            public void sceneUndo() {
                                pasted.removeFromParent();
                                if (previousParent != null) {
                                    previousParent.attachChildAt(pasted, Math.min(previousIndex, previousParent.getQuantity()));
                                }
                            }
                            public void sceneRedo() {
                                node.attachChild(pasted);
                            }
                            public void awtUndo() {
                                refreshTree();
                            }
                            public void awtRedo() {
                                refreshTree();
                            }
                        });
                        return null;
                    }).get();
                    refreshTree();
                    return data.cutSource() == null ? transfer : null;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                } catch (ExecutionException error) {
                    throw new IOException(error.getCause());
                }
            }
        };
    }

    private void refreshTree() {
        org.openide.nodes.Node root = this;
        while (root.getParentNode() instanceof JmeNode) {
            root = root.getParentNode();
        }
        ((JmeNode) root).refresh(true);
        fireSave(true);
    }

    @Override
    protected void createPasteTypes(Transferable t, List<PasteType> s) {
        super.createPasteTypes(t, s);
        PasteType paste = getDropType(t, DnDConstants.ACTION_COPY_OR_MOVE, -1);
        if (null != paste) {
            s.add(paste);
        }
    }

    @Override
    public Action[] getActions(boolean context) {
//        return super.getActions(context);
        if (((JmeSpatialChildren) jmeChildren).readOnly) {
            return new Action[]{
                        SystemAction.get(CopyAction.class),};
        } else {
            return new Action[]{
                        com.jme3.gde.core.assets.AnimationEditorSupport.nodeAction(spatial),
                        new NewSpatialPopup(this),
                        new NewControlPopup(this),
                        new NewLightPopup(this),
                        Actions.alwaysEnabled(new AddUserDataAction(this), "Add User Data", "", false),
                        new ToolPopup(this),
                        new ControlsPopup(this),
                        SystemAction.get(RenameAction.class),
                        SystemAction.get(CopyAction.class),
                        SystemAction.get(CutAction.class),
                        SystemAction.get(PasteAction.class),
                        SystemAction.get(DeleteAction.class)
                    };
        }
    }

    @Override
    public void destroy() throws IOException {
        // Detach the subtree as one undoable operation; never destroy its children.
        super.destroy();
    }

    @Override
    public Class<?> getExplorerObjectClass() {
        return Node.class;
    }

    @Override
    public Class<?> getExplorerNodeClass() {
        return JmeNode.class;
    }

    @Override
    public org.openide.nodes.Node[] createNodes(Object key, DataObject key2, boolean cookie) {
        JmeSpatialChildren children = new JmeSpatialChildren((com.jme3.scene.Spatial) key);
        children.setReadOnly(cookie);
        children.setDataObject(key2);
        return new org.openide.nodes.Node[]{new JmeNode((Node) key, children).setReadOnly(cookie)};
    }
}
