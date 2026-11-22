/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.gizmo.light;

import com.jme3.light.SpotLight;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

/**
 *
 * @author dokthar
 */
class LightRangeUpdate extends AbstractControl{

    private SpotLight light;
    private float lastRange = -1;
    
    public LightRangeUpdate(SpotLight l) {
        light = l;
    }

    @Override
    protected void controlUpdate(float f) {
        float r = light.getSpotRange();
        if(lastRange != r){
            lastRange = r;
            if(getSpatial() != null){
                getSpatial().setLocalTranslation(0, lastRange, 0);
            }
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
    }
    
}
