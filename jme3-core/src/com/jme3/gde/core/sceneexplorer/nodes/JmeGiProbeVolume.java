package com.jme3.gde.core.sceneexplorer.nodes;



import com.jme3.environment.gi.GiProbeVolume;

import com.jme3.math.Vector3f;

import org.openide.loaders.DataObject;

import org.openide.nodes.Sheet;

import org.openide.util.lookup.ServiceProvider;



/**

 * GI volume inspector. Creation, drag/reparent and transform tools use JmeNode's

 * existing undoable workflow. Coverage/count property setters invalidate old data.

 * Scale multiplies local Coverage; Probe counts controls density independently.

 * Rotation is unsupported. After a move/resize, rebake before expecting lighting.

 * Bake from the Properties inspector or Tools / Bake GI volume; save the scene

 * to persist completed probe data. The SDK applies valid data only while drawing.

 */

@ServiceProvider(service = SceneExplorerNode.class)

public final class JmeGiProbeVolume extends JmeNode {

    private GiProbeVolume volume;



    public JmeGiProbeVolume() {

    }



    public JmeGiProbeVolume(GiProbeVolume volume, JmeSpatialChildren children) {

        super(volume, children);

        this.volume = volume;

        // The superclass may create its sheet before this field is assigned.

        refreshProperties();

    }



    @Override

    protected Sheet createSheet() {

        Sheet sheet = super.createSheet();

        if (volume != null) {

            Sheet.Set properties = Sheet.createPropertiesSet();

            properties.setName("giVolume");

            properties.setDisplayName("GI Light Probe Volume");

            properties.put(makeProperty(volume, Vector3f.class, "getCoverage", "setCoverage", "Coverage"));

            properties.put(makeProperty(volume, Vector3f.class, "getProbeCounts", "setProbeCounts", "Probe counts"));

            properties.put(makeProperty(volume, float.class, "getIntensity", "setIntensity", "GI intensity"));
            // Render-thread property polling; old GI remains visible until manual rebake succeeds.
            properties.put(makeProperty(volume, String.class, "getSkyBakeStatus", null, "Sky bake status"));

            org.openide.nodes.Node.Property<?> blending = makeProperty(volume, boolean.class,

                    "isStabilizedBlending", "setStabilizedBlending", "Stabilized probe blending");

            blending.setShortDescription("Smooth low-confidence probe mixing without rebaking. May reduce dark patches but increase light leaks. Disable to restore strict visibility.");

            properties.put(blending);

            // 第一步：默认八探针。第二步：按需开启宽邻域，保存 J3O 保留设置。

            // 第三步：关闭恢复八探针；不需要重新烘焙。

            org.openide.nodes.Node.Property<?> wide = makeProperty(volume, boolean.class,

                    "isWideProbeSampling", "setWideProbeSampling", "27-probe quality mode");

            wide.setShortDescription("Off by default: eight-probe mixing. Enable the more expensive 27-probe regular-grid stencil without rebaking. Ignored by surface layouts.");

            properties.put(wide);

            // 第一步：默认关闭以兼容旧 GI；第二步：曲面出现分面亮条时开启。
            // 第三步：关闭恢复旧平面修正；保存场景保留选项，无需重烘焙。
            org.openide.nodes.Node.Property<?> curved = makeProperty(volume, boolean.class,
                    "isCurvedReceiverCorrection", "setCurvedReceiverCorrection", "Curved receiver correction");
            curved.setShortDescription("Off by default: preserve the original receiver-plane projection. Enable to reduce smooth curved-surface GI seams. Eight-probe grids only; no rebake needed. Disable to restore old lighting.");
            properties.put(curved);

            org.openide.nodes.Node.Property<?> layout = makeProperty(volume, boolean.class,

                    "isSurfaceLayout", "setSurfaceLayout", "Surface probe layout");

            layout.setShortDescription("Free probes outside surfaces; replaces lattice interpolation. Requires rebaking. Counts set bin resolution and slot budget; no GI beyond local probe support.");

            properties.put(layout);

            properties.put(makeProperty(volume, float.class, "getFadeDistance", "setFadeDistance", "Boundary fade distance"));

            sheet.put(properties);

        }

        return sheet;

    }



    @Override

    public Class<?> getExplorerObjectClass() {

        return GiProbeVolume.class;

    }



    @Override

    public Class<?> getExplorerNodeClass() {

        return JmeGiProbeVolume.class;

    }



    @Override

    public org.openide.nodes.Node[] createNodes(Object key, DataObject file, boolean readOnly) {

        JmeSpatialChildren children = new JmeSpatialChildren((GiProbeVolume) key);

        children.setReadOnly(readOnly);

        children.setDataObject(file);

        return new org.openide.nodes.Node[]{new JmeGiProbeVolume((GiProbeVolume) key, children).setReadOnly(readOnly)};

    }

}
