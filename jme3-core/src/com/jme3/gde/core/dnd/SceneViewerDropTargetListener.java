/*
 *  Copyright (c) 2009-2025 jMonkeyEngine
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
package com.jme3.gde.core.dnd;

import com.jme3.gde.core.assets.AssetDataNode;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.math.Vector2f;
import java.awt.Cursor;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles dropping Materials or Spatial from the AssetBrowser and Projects tab
 * to the SceneViewer
 *
 * @author rickard
 */
public class SceneViewerDropTargetListener implements DropTargetListener {

    private static final Cursor droppableCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final Cursor notDroppableCursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);

    private final SceneViewerTopComponent rootPanel;

    public SceneViewerDropTargetListener(final SceneViewerTopComponent rootPanel) {
        this.rootPanel = rootPanel;
    }

    @Override
    public void dragEnter(final DropTargetDragEvent dtde) {
    }

    @Override
    public void dragOver(final DropTargetDragEvent dtde) {
        if (!this.rootPanel.getCursor().equals(droppableCursor)) {
            this.rootPanel.setCursor(droppableCursor);
        }
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent dtde) {
    }

    @Override
    public void dragExit(final DropTargetEvent dte) {
        this.rootPanel.setCursor(notDroppableCursor);
    }

    @Override
    public void drop(final DropTargetDropEvent dtde) {
        this.rootPanel.setCursor(Cursor.getDefaultCursor());
        boolean accepted = false;
        try {
            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
            accepted = true;
            Transferable transfer = dtde.getTransferable();
            String asset = null;
            org.openide.filesystems.FileObject source = null;
            for (DataFlavor candidate : transfer.getTransferDataFlavors()) {
                Object value = transfer.getTransferData(candidate);
                if (value instanceof AssetNameHolder holder) {
                    asset = holder.getAssetName();
                    source = holder.getAssetFile();
                    break;
                }
                if (value instanceof AssetDataNode node) {
                    asset = node.getAssetName();
                    source = node.getLookup().lookup(org.openide.filesystems.FileObject.class);
                    break;
                }
            }
            var request = com.jme3.gde.core.scene.SceneApplication.getApplication().getCurrentSceneRequest();
            if (asset == null || request == null || request.getManager() == null) {
                dtde.dropComplete(false);
                return;
            }
            if (source != null) {
                String relative = org.openide.filesystems.FileUtil.getRelativePath(request.getManager().getAssetFolder(), source);
                if (relative == null) {
                    throw new IOException("The dropped asset must belong to the active scene project. Copy it into that project's Assets first.");
                }
                asset = relative;
            }
            var file = request.getManager().getAssetFolder().getFileObject(asset);
            boolean model = file != null && org.openide.loaders.DataObject.find(file)
                    instanceof com.jme3.gde.core.assets.SpatialAssetDataObject;
            Vector2f position = new Vector2f(dtde.getLocation().x, dtde.getLocation().y);
            if (model) {
                rootPanel.addModel(asset, position);
            } else if (isMaterialExtension(asset)) {
                rootPanel.applyMaterial(asset, position);
            } else {
                dtde.dropComplete(false);
                return;
            }
            dtde.dropComplete(true);
        } catch (Exception error) {
            if (accepted) {
                dtde.dropComplete(false);
            } else {
                dtde.rejectDrop();
            }
            org.openide.DialogDisplayer.getDefault().notifyLater(new org.openide.NotifyDescriptor.Message(
                    error.getMessage(), org.openide.NotifyDescriptor.ERROR_MESSAGE));
        }
    }

    /**
     * Determines if the asset key represents a model/spatial asset by checking its file extension.
     *
     * @param assetKey The asset key (typically a filename or path); this method checks if it ends with the model file extension.
     * @return true if the asset key is for model files
     */
    private boolean isModelExtension(String assetKey) {
        return assetKey.endsWith("j3o");
    }

    /**
     * Determines if the asset key represents a material asset by checking its file extension.
     *
     * @param assetKey The asset key (typically a filename or path); this method checks if it ends with the material file extension.
     * @return true if the asset key is for material files
     */
    private boolean isMaterialExtension(String assetKey) {
        return assetKey.toLowerCase(java.util.Locale.ROOT).endsWith(".j3m");
    }

}
