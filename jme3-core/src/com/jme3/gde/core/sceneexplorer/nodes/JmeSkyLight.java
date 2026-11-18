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

import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import java.beans.PropertyEditor;
import java.lang.reflect.InvocationTargetException;
import org.openide.nodes.PropertySupport;
import org.openide.nodes.Sheet;

/**
 *
 * Independent sky properties; no reflection radius or local-probe bake action.
 * 第一步：选择 SkyLight，修改强度或漫反射开关。
 * 第二步：天空贴图变化后点击 Refresh；渲染线程执行重新捕捉。
 * 第三步：检查 GI Volume 的 Sky bake status，手动重烘焙过期反弹光。
 */
@org.openide.util.lookup.ServiceProvider(service=SceneExplorerNode.class)
@SuppressWarnings({"unchecked", "rawtypes"})
public class JmeSkyLight extends JmeLight {
    protected SkyLight lightProbe;

    public JmeSkyLight() {
    }

    public JmeSkyLight(Spatial spatial, SkyLight lightProbe) {
        super(spatial, lightProbe);
        this.lightProbe = lightProbe;
        lookupContents.add(lightProbe);
        setName("SkyLight");
    }

    @Override
    protected Sheet createSheet() {
        //TODO: multithreading..
        Sheet sheet = super.createSheet();
        Sheet.Set set = Sheet.createPropertiesSet();
        set.setDisplayName(org.openide.util.NbBundle.getMessage(JmeSkyLight.class,
                "SkyLight.Title"));
        set.setName(SkyLight.class.getName());
        SkyLight obj = lightProbe;
        if (obj == null) {
            return sheet;
        }

        set.put(makeEmbedProperty(obj, com.jme3.light.SkyLight.class, float.class,
                "getIntensity", "setIntensity", "Sky light intensity (>=0)"));
        set.put(makeProperty(obj, boolean.class, "isDiffuseEnabled", "setDiffuseEnabled", "Diffuse enabled"));
        com.jme3.environment.SkyLightControl control = findSkyControl();
        if (control != null) {
            set.put(makeEmbedProperty(control, com.jme3.environment.SkyLightControl.class,
                    int.class, "getResolution", "setResolution", "Capture resolution"));
            set.put(createButtonProperty());
        }
        sheet.put(set);
        return sheet;
    }

    com.jme3.environment.SkyLightControl findSkyControl() {
        final com.jme3.environment.SkyLightControl[] found = {null};
        getSpatial().depthFirstTraversal(child -> {
            var control = child.getControl(com.jme3.environment.SkyLightControl.class);
            if (control != null && control.getLight() == lightProbe) {
                found[0] = control;
            }
        });
        return found[0];
    }

    public SkyLight getSkyLight() {
        return lightProbe;
    }

    @Override
    public Class getExplorerObjectClass() {
        return SkyLight.class;
    }

    @Override
    public Class getExplorerNodeClass() {
        return JmeSkyLight.class;
    }

     private Property createButtonProperty() {
        return new PropertySupport.ReadWrite<Object>("update", Object.class, "Refresh maps", "Click here to refresh environment maps ") {
            JmeSkyLightButtonProperty pe;

            @Override
            public Object getValue() throws IllegalAccessException, InvocationTargetException {
                return "";
            }

            @Override
            public PropertyEditor getPropertyEditor() {
                if (pe == null) {
                    pe = new JmeSkyLightButtonProperty(JmeSkyLight.this, getSpatial());
                    // The property sheet supplies PropertyEnv via attachEnv; it is null here.
                }
                return pe;
            }

            @Override
            public void setValue(Object t) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
            }
        };
    }
}
