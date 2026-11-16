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
package com.jme3.gde.core.assets.actions;

import com.jme3.gde.core.assets.BinaryModelDataObject;
import com.jme3.gde.core.assets.SpatialAssetDataObject;
import com.jme3.gde.core.util.notify.MessageUtil;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.progress.ProgressHandle;

/**
 * Imports each source on a worker, then shows its detected conversion defaults on
 * EDT. Batch selections get one options dialog per source so static and animated
 * files do not inherit each other's bake/editor choices. Cancel stops the batch;
 * already completed conversions remain saved. Inspection never bakes or exports.
 * <pre>
 * 第一步：选择一个或多个 GLTF/GLB 等源文件，执行 Convert to J3O。
 * 第二步：后台加载后逐个显示检测结果；动画模型默认关闭 SDF 和表面属性烘焙。
 * 第三步：确认后才导出；取消停止后续转换，已完成的文件不回滚。
 * </pre>
 */
public final class ConvertModel implements ActionListener {
    protected static final Logger logger = Logger.getLogger(ConvertModel.class.getName());
    private final List<SpatialAssetDataObject> context;

    public ConvertModel(List<SpatialAssetDataObject> context) {
        this.context = context;
    }

    @Override
    public void actionPerformed(ActionEvent ev) {
        for (SpatialAssetDataObject source : context) {
            if (!(source instanceof BinaryModelDataObject)
                    && source.getPrimaryFile().getParent().getFileObject(source.getPrimaryFile().getName(), "j3o") != null) {
                Object answer = org.openide.DialogDisplayer.getDefault().notify(new org.openide.NotifyDescriptor.Confirmation(
                        "Replace existing " + source.getPrimaryFile().getName() + ".j3o?", org.openide.NotifyDescriptor.YES_NO_OPTION));
                if (answer != org.openide.NotifyDescriptor.YES_OPTION) {
                    return;
                }
            }
        }
        Runnable run = new Runnable() {
            @Override
            public void run() {
                ProgressHandle progressHandle = ProgressHandle.createHandle("Converting Model");
                progressHandle.start();
                for (SpatialAssetDataObject spatialAssetDataObject : context) {
                    if (!(spatialAssetDataObject instanceof BinaryModelDataObject)) {
                        try {
                            progressHandle.progress("Inspecting " + spatialAssetDataObject.getName());
                            var model = spatialAssetDataObject.loadAsset();
                            var analysis = com.jme3.gde.core.assets.ModelConversionAnalysis.inspect(model);
                            var choice = new java.util.concurrent.atomic.AtomicReference<com.jme3.gde.core.assets.ModelConversionOptions>();
                            javax.swing.SwingUtilities.invokeAndWait(() -> choice.set(
                                    com.jme3.gde.core.assets.ModelConversionOptions.ask(analysis,
                                            spatialAssetDataObject.getPrimaryFile().getNameExt())));
                            if (choice.get() == null) {
                                progressHandle.finish();
                                return;
                            }
                            spatialAssetDataObject.saveAsset(choice.get());
                            spatialAssetDataObject.closeAsset();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception ex) {
                            //Exceptions.printStackTrace(ex); // does only print the stacktrace when launching the sdk in debug mode
                            //bad for user reports, so we use error() and loggers
                            MessageUtil.error("Unable to convert the model: An Exception has occured.\n"
                                    + "Please look into the Output Window and report that Exception\n"
                                    + "(including the full stacktrace) to us at \n"
                                    + "https://github.com/jMonkeyEngine/sdk/issues");
                            logger.log(Level.SEVERE, "An Exception has occured.", ex);
                        }
                    }
                }
                progressHandle.finish();
            }
        };
        new Thread(run).start();
    }
}
