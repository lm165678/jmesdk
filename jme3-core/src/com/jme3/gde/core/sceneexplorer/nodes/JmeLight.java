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
import com.jme3.light.Light;
import com.jme3.scene.Spatial;
import java.awt.Image;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.swing.Action;
import org.openide.actions.DeleteAction;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.nodes.Sheet;
import org.openide.util.Exceptions;
import org.openide.util.actions.SystemAction;

/**
 *
 * Managed shadow strength workflow:
 * 第一步：选择方向光、点光或聚光灯，打开 Shadows 属性组。
 * 第二步：Shadow strength (0..1) 默认 1；0.5 减弱遮挡，0 无遮挡。
 * 第三步：恢复 1 回到原有完整阴影；保存场景后偏好随灯光保存。
 * 此参数不改变 GI 数据或其他灯；重新烘焙仍使用完整遮挡。
 * Changes use the existing embedded-property render-thread mutation path.
 *
 * @author normenhansen
 */
@org.openide.util.lookup.ServiceProvider(service = SceneExplorerNode.class)
@SuppressWarnings({"unchecked", "rawtypes"})
public class JmeLight extends AbstractSceneExplorerNode {

    private Spatial spatial;
    private Light light;
    private static Image smallImage = IconList.lightYellow.getImage();

    public JmeLight() {
    }

    public JmeLight(Spatial spatial, Light light) {
        super(Children.LEAF);
        this.spatial = spatial;
        this.light = light;
        lookupContents.add(light);
        lookupContents.add(this);
        setName("Light");
    }

    @Override
    protected void fireSave(boolean modified) {
        Node parent = getParentNode();
        if (parent instanceof AbstractSceneExplorerNode) {
            AbstractSceneExplorerNode par=(AbstractSceneExplorerNode)parent;
            par.fireSave(modified);
        }
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
        Sheet sheet = Sheet.createDefault();
        Sheet.Set set = Sheet.createPropertiesSet();
        set.setDisplayName("Light");
        set.setName(Light.class.getName());
        Light obj = light;
        if (obj == null) {
            return sheet;
        }

        createFields(Light.class, set, obj);
        // We don't want the user to mess with them
        set.remove("Frustum Check Needed");
        set.remove("Intersects Frustum");
        if (obj.getType() == Light.Type.Ambient || obj.getType() == Light.Type.Probe) {
            set.remove("Cast Shadow");
        }
        set.remove("Intensity");
        if (obj.getType() != Light.Type.Probe && obj.getType() != Light.Type.Sky) {
            var intensity = makeProperty(obj, float.class, "getIntensity", "setIntensity", "Intensity");
            intensity.setShortDescription("Scale the light without changing its color. 1 is the default. 0 emits no light.");
            set.put(intensity);
        }
        sheet.put(set);
        if (obj instanceof com.jme3.light.DirectionalLight || obj instanceof com.jme3.light.PointLight
                || obj instanceof com.jme3.light.SpotLight) {
            Sheet.Set shadows = Sheet.createPropertiesSet();
            shadows.setName("ManagedShadows");
            shadows.setDisplayName("Shadows");
            var settings = obj.getShadowSettings();
            Class<?> type = com.jme3.shadow.LightShadowSettings.class;
            shadows.put(makeEmbedProperty(settings, type, int.class, "getMapSize", "setMapSize", "Resolution (64-4096)"));
            // 阴影强度默认 1；0 无遮挡，调低只影响该灯直射光，不修改 GI 烘焙。
            shadows.put(makeEmbedProperty(settings, type, float.class, "getStrength", "setStrength", "Shadow strength (0..1)"));
            shadows.put(makeEmbedProperty(settings, type, int.class, "getFilterRadius", "setFilterRadius", "PCF radius (0 / 1 / 2)"));
            shadows.put(makeEmbedProperty(settings, type, float.class, "getDepthBias", "setDepthBias", "Depth bias"));
            shadows.put(makeEmbedProperty(settings, type, float.class, "getNormalBias", "setNormalBias", "Normal bias"));
            if (obj instanceof com.jme3.light.DirectionalLight) {
                shadows.put(makeEmbedProperty(settings, type, int.class, "getCascadeCount", "setCascadeCount", "Cascades (1-4)"));
                shadows.put(makeEmbedProperty(settings, type, float.class, "getSplitLambda", "setSplitLambda", "Split lambda (0-1)"));
                shadows.put(makeEmbedProperty(settings, type, float.class, "getShadowDistance", "setShadowDistance", "Shadow distance"));
            }
            sheet.put(shadows);
        }
        return sheet;

    }

    @Override
    public Action[] getActions(boolean context) {
        return new SystemAction[]{
                    //                    SystemAction.get(CopyAction.class),
                    //                    SystemAction.get(CutAction.class),
                    //                    SystemAction.get(PasteAction.class),
                    SystemAction.get(DeleteAction.class)
                };
    }

    @Override
    public boolean canDestroy() {
        return true;
    }

    /**
     * Deletes this light and its owning sky, if present, on the render thread.
     * 第一步：使用 SkyFactory 创建天空并挂到场景中，保留自动生成的 SkyLightControl。
     * 第二步：删除 Sky 或它对应的 SkyLight；按 control.getLight() 的对象身份关联，
     * 不按名称匹配。J3O 保存重载保持此关联；无关联的独立灯光仅删除自身。
     */
    @Override
    public void destroy() throws IOException {
        try {
            fireSave(true);
            SceneApplication.getApplication().enqueue(new Callable<Void>() {

                public Void call() throws Exception {
                    // SkyFactory's control owns this exact light, including after J3O reload.
                    // Delete the associated sky on the render thread; unrelated lights stay intact.
                    java.util.List<Spatial> skies = new java.util.ArrayList<>();
                    spatial.depthFirstTraversal(child -> {
                        com.jme3.environment.SkyLightControl sky = child.getControl(
                                com.jme3.environment.SkyLightControl.class);
                        if (sky != null && sky.getLight() == light) {
                            skies.add(child);
                        }
                    });
                    for (Spatial sky : skies) {
                        sky.removeFromParent();
                    }
                    spatial.removeLight(light);
                    return null;
                }
            }).get();
            if (getParentNode() instanceof JmeNode) {
                JmeNode node = ((JmeNode) getParentNode());
                if (node != null) {
                    node.refresh(false);
                }
            }
        } catch (InterruptedException ex) {
            Exceptions.printStackTrace(ex);
        } catch (ExecutionException ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    public Spatial getSpatial() {
        return spatial;
    }
    
    @Override
    public Class getExplorerObjectClass() {
        return Light.class;
    }

    @Override
    public Class getExplorerNodeClass() {
        return JmeLight.class;
    }
    
    protected void setModified(){
        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                fireSave(true);
            }
        });
    }
}
