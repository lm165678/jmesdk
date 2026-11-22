/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.assets.BinaryModelDataObject;
import com.jme3.gde.core.assets.AssetOpenProgress;
import com.jme3.scene.Spatial;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.NotifyDescriptor.Confirmation;

public final class OpenSceneComposer implements ActionListener {

    private final BinaryModelDataObject context;

    public OpenSceneComposer(BinaryModelDataObject context) {
        this.context = context;
    }

    public void actionPerformed(ActionEvent ev) {
        final ProjectAssetManager manager = context.getLookup().lookup(ProjectAssetManager.class);
        if (manager == null) {
            if (ev.getSource() instanceof AssetOpenProgress progress) {
                progress.close();
            }
            return;
        }
        AssetOpenProgress progress = ev.getSource() instanceof AssetOpenProgress routed
                && AssetOpenProgress.current(context) == routed ? routed : AssetOpenProgress.begin(context);
        if (progress == null) {
            return;
        }
        Runnable call = () -> {
            try {
                manager.clearCache();
                final Spatial asset = context.loadAsset();
                if (asset != null) {
                    java.awt.EventQueue.invokeLater(() -> {
                        try {
                            SceneComposerTopComponent composer = SceneComposerTopComponent.findInstance();
                            composer.openScene(asset, context, manager);
                        } catch (Exception exception) {
                            progress.close();
                            org.openide.util.Exceptions.printStackTrace(exception);
                        }
                    });
                } else {
                    progress.close();
                    Confirmation msg = new NotifyDescriptor.Confirmation(
                            "Error opening " + context.getPrimaryFile().getNameExt(),
                            NotifyDescriptor.OK_CANCEL_OPTION,
                            NotifyDescriptor.ERROR_MESSAGE);
                    DialogDisplayer.getDefault().notify(msg);
                }
            } catch (Exception exception) {
                progress.close();
                org.openide.util.Exceptions.printStackTrace(exception);
            }
        };
        new Thread(call).start();
    }
}
