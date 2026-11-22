/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.gizmo.light;

import com.jme3.asset.AssetManager;
import com.jme3.gde.scenecomposer.gizmo.GizmoFactory;
import com.jme3.gde.scenecomposer.gizmo.GizmoIcon;
import com.jme3.gde.scenecomposer.gizmo.light.shape.ProbeRadiusShape;
import com.jme3.gde.core.sceneexplorer.nodes.JmeDirectionalLight;
import com.jme3.gde.core.sceneexplorer.nodes.JmeLight;
import com.jme3.gde.core.sceneexplorer.nodes.JmeLightProbe;
import com.jme3.gde.core.sceneexplorer.nodes.JmePointLight;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpotLight;
import com.jme3.gde.scenecomposer.gizmo.shape.RadiusShape;
import com.jme3.gde.scenecomposer.gizmo.shape.Triangle;
import com.jme3.light.Light;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.BillboardControl;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Sphere;

/**
 * Handles the creation of the appropriate light gizmo according to the light type.
 * @author Nehon, dokthar
 */
public class LightGizmoFactory {
    private static Quaternion pitch90 = new Quaternion().fromAngleAxis(FastMath.HALF_PI, Vector3f.UNIT_X);  
    
    public static Spatial createGizmo(AssetManager assetManager, JmeLight lightNode) {
        Light light = lightNode.getLookup().lookup(Light.class);
        if (light == null || light instanceof com.jme3.light.SkyLight) {
            return null;
        }
        switch (light.getType()) {
            case Point:
                return createPointGizmo(assetManager, (JmePointLight) lightNode, light);
            case Spot:
                return createSpotGizmo(assetManager, (JmeSpotLight) lightNode, light);
            case Directional:
                return createDirectionalGizmo(assetManager, (JmeDirectionalLight) lightNode, light);
                
            case Probe:
                return createLightProbeGizmo(assetManager, (JmeLightProbe)lightNode);

            //  default:
            //      return createDefaultGizmo(assetManager, lightNode);
        }
        return null;
    }
    
    private static Node createPointGizmo(AssetManager assetManager, JmePointLight jmeLight, Light light) {
        PointLightGizmo gizmo = new PointLightGizmo(jmeLight);
        gizmo.addControl(new LightPositionUpdate(light, gizmo));
        
        Node billboardNode = new Node("billboard lightGizmo");
        billboardNode.addControl(new BillboardControl());
        gizmo.attachChild(billboardNode);
        billboardNode.attachChild(createLightBulb(assetManager));
        
        Geometry radius = RadiusShape.createShape(assetManager, "radius shape");
        radius.addControl(new LightRadiusUpdate((PointLight) light));
        radius.addControl(new LightColorUpdate(light, radius.getMaterial(), "Color"));
        billboardNode.attachChild(radius);
        
        return gizmo;
    }
    
    private static Node createDirectionalGizmo(AssetManager assetManager, JmeDirectionalLight jmeLight, Light light) {
        DirectionalLightGizmo gizmo = new DirectionalLightGizmo(jmeLight);
        gizmo.move(0, 5, 0);
        gizmo.addControl(new LightDirectionUpdate(light, gizmo));
        
        Node billboardNode = new Node("billboard lightGizmo");
        billboardNode.addControl(new BillboardControl());
        
        billboardNode.attachChild(createLightBulb(assetManager));
        
        gizmo.attachChild(createDirectionArrow(assetManager, light));
        gizmo.attachChild(billboardNode);
        
        jmeLight.setGizmo(gizmo);
        return gizmo;
    }
    
    private static Node createSpotGizmo(AssetManager assetManager, JmeSpotLight jmeLight, Light light) {
        SpotLightGizmo gizmo = new SpotLightGizmo(jmeLight);
        gizmo.addControl(new LightDirectionUpdate(light, gizmo));
        gizmo.addControl(new LightPositionUpdate(light, gizmo));
        
        Node billboardNode = new Node("billboard lightGizmo");
        gizmo.attachChild(billboardNode);
        billboardNode.addControl(new BillboardControl());
        billboardNode.attachChild(createLightBulb(assetManager));
        
        Node radiusNode = new Node("radius Node");
        gizmo.attachChild(radiusNode);
        
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.White);
        gizmo.attachChild(createDirectionArrow(assetManager, light));
        
        Geometry inRadius = RadiusShape.createShape(assetManager, "inner radius shape");
        inRadius.rotate(pitch90);
        inRadius.addControl(new LightColorUpdate(light, inRadius.getMaterial(), "Color"));
        inRadius.getMaterial().setFloat("DashSize", 0.875f);
        radiusNode.attachChild(inRadius);
        
        Geometry outRadius = RadiusShape.createShape(assetManager, "outer radius shape");
        outRadius.addControl(new LightColorUpdate(light, outRadius.getMaterial(), "Color"));
        outRadius.getMaterial().setFloat("DashSize", 0.125f);
        radiusNode.attachChild(outRadius);
        outRadius.rotate(pitch90);
        
        Geometry cone = new Geometry("cone shape", new Triangle(1f, -1f));
        cone.setMaterial(mat);
        BillboardControl bc = new BillboardControl();
        bc.setAlignment(BillboardControl.Alignment.AxialY);
        cone.addControl(bc);
        cone.addControl(new LightColorUpdate(light, outRadius.getMaterial(), "Color"));
        cone.addControl(new LightConeRadiusUpdate((SpotLight) light, inRadius, outRadius));
        radiusNode.attachChild(cone);
        
        radiusNode.addControl(new LightRangeUpdate((SpotLight) light));
        
        return gizmo;
    }
    
    private static Spatial createLightProbeGizmo(AssetManager assetManager, JmeLightProbe probe){
        LightProbeGizmo gizmo = new LightProbeGizmo(probe);//new Node("Environment debug Node");
        gizmo.addControl(new LightPositionUpdate(probe.getLightProbe(), gizmo));
        gizmo.addControl(new LightProbeUpdate(probe));
        
        Sphere s = new Sphere(16, 16, 0.5f);
        Geometry debugGeom = new Geometry(probe.getLightProbe().getName(), s);
        Material debugMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        debugMaterial.setColor("Color", ColorRGBA.Orange);
        debugGeom.setMaterial(debugMaterial);
        debugGeom.addControl(new ScreenSizeControl(24f, 1f));
        Spatial debugBounds = ProbeRadiusShape.createShape(assetManager);
        
        gizmo.attachChild(debugGeom);
        gizmo.attachChild(debugBounds);
        
        return gizmo;        
    }
    
    protected static Geometry createLightBulb(AssetManager assetManager) {
        return GizmoIcon.create(assetManager, "Light source", "sun.svg",
                "com/jme3/gde/scenecomposer/lightbulb32.png", false);
    }

    private static Node createDirectionArrow(AssetManager assetManager, Light light) {
        var material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.White);
        var arrow = new Node("direction arrow");
        var shaft = new Geometry("shaft", new Cylinder(2, 12, .018f, .81f, true));
        shaft.rotate(-FastMath.HALF_PI, 0f, 0f);
        shaft.setLocalTranslation(0f, .405f, 0f);
        shaft.setMaterial(material);
        arrow.attachChild(shaft);
        var head = new Geometry("tip", GizmoFactory.createCone(12, .044f, .19f));
        head.setLocalTranslation(0f, .81f, 0f);
        head.setMaterial(material);
        arrow.attachChild(head);
        // Extend directional markers past the transform handles without widening the tip.
        if (light instanceof com.jme3.light.DirectionalLight) {
            shaft.setLocalScale(1f, 1f, 1.8f);
            shaft.setLocalTranslation(0f, .729f, 0f);
            head.setLocalTranslation(0f, 1.458f, 0f);
        }
        arrow.addControl(new ScreenSizeControl(108f, 1f));
        arrow.addControl(new LightColorUpdate(light, material, "Color"));
        return arrow;
    }
    
    /**
     * Helper Method to convert a Vector3f Scale into a radius. This is required,
     * because the Gizmos are scaled like regular jME Nodes.
     *
     * Note: In case of non-uniform scaling, the code picks the minimum or maximum
     * of all three components.
     * 
     * @param scale The Scale to convert
     * @return The Radius
     */
    protected static float scaleToRadius(Vector3f scale) {
        final float eps = 0.0000125f;        
        float m;

        float x = FastMath.abs(scale.x);
        float y = FastMath.abs(scale.y);
        float z = FastMath.abs(scale.z);
        float max = Math.max(Math.max(x, y), z);
        float min = Math.min(Math.min(x, y), z);

        if (max - min <= eps) {
            // x == y == z
            m = x;
        } else {
            int nbMax = 0;
            if (max - x <= eps) {
                nbMax++;
            }
            if (max - y <= eps) {
                nbMax++;
            }
            if (max - z <= eps) {
                nbMax++;
            }
            if (nbMax >= 2) {
                m = min;
            } else {
                m = max;
            }
        }
        
        return m;
    }
    
}
