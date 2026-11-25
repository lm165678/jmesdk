/*
 *  Copyright (c) 2009-2026 jMonkeyEngine
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
package com.jme3.gde.materials;

import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.MaterialKey;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.PreviewRequest;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneListener;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.editor.icons.Icons;
import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RendererException;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Sphere;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.JLabel;

/**
 * Handles rendering of materials in preview widgets of Material and Shader Node
 * editor.
 *
 * @author Nehon
 */
public class MaterialPreviewRenderer implements SceneListener {

    private Geometry sphere;
    private Geometry box;
    private Geometry quad;
    private Node teapot;
    private Spatial currentSpatial;
    private Material currentMaterial;
    private boolean init = false;
    private final JLabel label;
    private final boolean highDpi;
    private final ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(5);
    private volatile boolean previewRequested;
    private boolean refreshPending;
    private volatile boolean disposed;
    private volatile float orbitYaw, orbitPitch, lightYaw = -0.5f, lightPitch = 0.7f;
    private volatile boolean environmentEnabled = true;

    /** EDT interaction: orbit the camera or the isolated preview key light.
     * 第一步：左键拖动预览旋转观察角度；第二步：按住 L 拖动调整方向光。
     * Defaults to environment lighting on; this never edits the material or scene.
     */
    public void dragPreview(float dx, float dy, boolean lighting) {
        if (lighting) {
            lightYaw += dx * 0.01f;
            lightPitch = Math.max(-1.5f, Math.min(1.5f, lightPitch + dy * 0.01f));
        } else {
            orbitYaw -= dx * 0.01f;
            orbitPitch = Math.max(-1.5f, Math.min(1.5f, orbitPitch + dy * 0.01f));
        }
        refreshOnly();
    }

    /** Toggles baked lighting for this preview. The next request applies its own settings. */
    public void setEnvironmentEnabled(boolean enabled) {
        environmentEnabled = enabled;
        refreshOnly();
    }

    private void configurePreview(PreviewRequest request) {
        float cp = FastMath.cos(orbitPitch);
        request.getCameraRequest().setLocation(new Vector3f(
                7 * FastMath.sin(orbitYaw) * cp, 7 * FastMath.sin(orbitPitch), 7 * FastMath.cos(orbitYaw) * cp));
        request.getCameraRequest().setLookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        float lp = FastMath.cos(lightPitch);
        request.setMaterialLighting(new Vector3f(-FastMath.sin(lightYaw) * lp,
                -FastMath.sin(lightPitch), -FastMath.cos(lightYaw) * lp), environmentEnabled);
    }

    public enum DisplayType {

        Sphere,
        Box,
        Quad,
        Teapot
    }

    public MaterialPreviewRenderer(JLabel label) {
        this(label, false);
    }

    /** Enables DPI-aware supersampling only for the J3M editor thumbnail. */
    public MaterialPreviewRenderer(JLabel label, boolean highDpi) {
        this.label = label;
        this.highDpi = highDpi;
        if (highDpi) {
            label.addComponentListener(sizeListener);
            label.addPropertyChangeListener("graphicsConfiguration", scaleListener);
        }
    }

    private final java.awt.event.ComponentAdapter sizeListener = new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent event) {
            refreshOnly();
        }
    };
    private final java.beans.PropertyChangeListener scaleListener = event -> refreshOnly();

    /**
     * 第一步：按标签逻辑尺寸和显示器 DPI 创建离屏图像，双倍超采样平滑轮廓。
     * 第二步：用多分辨率图标保留逻辑布局大小，屏幕切换后重新请求预览。
     * Called on the EDT. Only material/shader editor thumbnails use this policy.
     */
    private PreviewRequest previewRequest() {
        if (!highDpi) {
            return new PreviewRequest(this, currentSpatial, label.getWidth(), label.getHeight());
        }
        java.awt.GraphicsConfiguration configuration = label.getGraphicsConfiguration();
        java.awt.geom.AffineTransform transform = configuration == null
                ? new java.awt.geom.AffineTransform() : configuration.getDefaultTransform();
        return new MaterialImageRequest(this, currentSpatial, Math.max(1, label.getWidth()),
                Math.max(1, label.getHeight()), transform.getScaleX(), transform.getScaleY());
    }

    static final class MaterialImageRequest extends PreviewRequest {
        final int logicalWidth;
        final int logicalHeight;
        final int pixelWidth;
        final int pixelHeight;

        MaterialImageRequest(Object requester, Spatial spatial, int width, int height, double scaleX, double scaleY) {
            super(requester, spatial, 2 * (int) Math.ceil(width * scaleX), 2 * (int) Math.ceil(height * scaleY));
            logicalWidth = width;
            logicalHeight = height;
            pixelWidth = (int) Math.ceil(width * scaleX);
            pixelHeight = (int) Math.ceil(height * scaleY);
        }

        ImageIcon icon(java.awt.image.BufferedImage image) {
            java.awt.image.BufferedImage physical = resize(image, pixelWidth, pixelHeight);
            java.awt.image.BufferedImage logical = resize(image, logicalWidth, logicalHeight);
            return new ImageIcon(new java.awt.image.BaseMultiResolutionImage(logical, physical));
        }

        private static java.awt.image.BufferedImage resize(java.awt.image.BufferedImage source, int width, int height) {
            java.awt.image.BufferedImage target = new java.awt.image.BufferedImage(width, height,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            return target;
        }
    }

    private void init() {
        SceneApplication.getApplication().addSceneListener(this);
        Sphere sphMesh = new Sphere(32, 32, 2.5f);
        sphMesh.setTextureMode(Sphere.TextureMode.Projected);
        sphMesh.updateGeometry(32, 32, 2.5f, false, false);
        Logger log = Logger.getLogger(MikktspaceTangentGenerator.class.getName());
        log.setLevel(Level.SEVERE);
        sphere = new Geometry("previewSphere", sphMesh);
        sphere.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.QUARTER_PI, Vector3f.UNIT_X));
        MikktspaceTangentGenerator.generate(sphere);

        Box boxMesh = new Box(1.75f, 1.75f, 1.75f);
        box = new Geometry("previewBox", boxMesh);
        box.setLocalRotation(new Quaternion().fromAngleAxis(-FastMath.DEG_TO_RAD * 30, Vector3f.UNIT_X).multLocal(new Quaternion().fromAngleAxis(FastMath.QUARTER_PI, Vector3f.UNIT_Y)));
        MikktspaceTangentGenerator.generate(box);

        Quad quadMesh = new Quad(4.5f, 4.5f);
        quad = new Geometry("previewQuad", quadMesh);
        quad.setLocalTranslation(new Vector3f(-2.25f, -2.25f, 0));
        MikktspaceTangentGenerator.generate(quad);

        teapot = ((Node)SceneApplication.getApplication().getAssetManager()
                .loadModel("Models/Teapot/teapot.j3o"));
        teapot.scale(0.75f);
        teapot.rotate(FastMath.PI, FastMath.QUARTER_PI * 1.5f, 0);
        teapot.setLocalTranslation(new Vector3f(-0.5f, 1.75f, 0));
        MikktspaceTangentGenerator.generate(teapot);

        currentSpatial = sphere;
        init = true;
    }

    public void showMaterial(final ProjectAssetManager assetManager, final String materialFileName) {
        if (!init) {
            init();
        }
        exec.execute(() -> {
            MaterialKey key = new MaterialKey(assetManager.getRelativeAssetPath(materialFileName));
            assetManager.deleteFromCache(key);
            Material mat = assetManager.loadAsset(key);
            if (mat != null) {
                showMaterial(mat);
            }
        });

    }

    public void showMaterial(final Material m) {
        showMaterial(m, null);
    }

    public void showMaterial(final Material m, final String techniqueName) {
        if (!init) {
            init();
        }
        SceneApplication.getApplication().enqueue(() -> {
            if (techniqueName != null) {
                try {
                    m.selectTechnique(techniqueName, SceneApplication.getApplication().getRenderManager());
                } catch (Exception e) {
                    //
                }
            }
            final Material mat = reloadMaterial(m);
            if (mat != null) {
                java.awt.EventQueue.invokeLater(() -> {
                    currentMaterial = mat;
                    currentSpatial.setMaterial(mat);
                    try {
                        if (currentMaterial != null) {
                            PreviewRequest request = previewRequest();
                            configurePreview(request);
                            request.getCameraRequest().setLookAt(new Vector3f(0, 0, 0), Vector3f.UNIT_Y);
                            SceneApplication.getApplication().createPreview(request);
                        }
                    } catch (Exception e) {
                        java.awt.EventQueue.invokeLater(() -> {
                            label.setIcon(Icons.error);
                        });
                        smartLog("Error rendering material{0}", e.getMessage());
                    }
                });

            }
            return mat;
        });
    }

    private static int lastErrorHash = 0;

    private void smartLog(String expText, String message) {
        int hash = message.hashCode();
        if (hash != lastErrorHash) {
            Logger.getLogger(MaterialPreviewRenderer.class.getName()).log(Level.SEVERE, expText, message);
            lastErrorHash = hash;
        }
    }

    public Material reloadMaterial(Material mat) {
        Material dummy;
        try {
            ((ProjectAssetManager) mat.getMaterialDef().getAssetManager()).clearCache();

            //creating a dummy mat with the mat def of the mat to reload
            dummy = new Material(mat.getMaterialDef());

            for (MatParam matParam : mat.getParams()) {
                dummy.setParam(matParam.getName(), matParam.getVarType(), matParam.getValue());
            }
            if (mat.getActiveTechnique() != null) {
                dummy.selectTechnique(mat.getActiveTechnique().getDef().getName(), SceneApplication.getApplication().getRenderManager());
            }
            dummy.getAdditionalRenderState().set(mat.getAdditionalRenderState());

            //creating a dummy geom and assigning the dummy material to it
            Geometry dummyGeom = new Geometry("dummyGeom", new Box(1f, 1f, 1f));
            dummyGeom.setMaterial(dummy);

            //preloading the dummyGeom, this call will compile the shader again
            SceneApplication.getApplication().getRenderManager().preloadScene(dummyGeom);
        } catch (RendererException e) {
            //compilation error, the shader code will be output to the console
            //the following code will output the error
            //System.err.println(e.getMessage());
            //Logger.getLogger(MaterialDebugAppState.class.getName()).log(Level.SEVERE, e.getMessage());
            smartLog("{0}", e.getMessage());

            java.awt.EventQueue.invokeLater(() -> label.setIcon(Icons.error));
            return null;
        } catch (NullPointerException npe) {
            //utterly bad, but for some reason I get random NPE here and can't figure out why so to avoid bigger issues, I just catch it.
            //the printStackTrace is intended, it will show up in debug mode, but won't be displayed in standzrd mode
            npe.printStackTrace();
            return null;
        } catch (AssetNotFoundException a) {
            smartLog("Could not fully load Shader: Missing File: {0}", a.getMessage());
            return null;
        }
        return dummy;
    }

    public void switchDisplay(DisplayType type) {
        switch (type) {
            case Box -> currentSpatial = box;
            case Sphere -> currentSpatial = sphere;
            case Quad -> currentSpatial = quad;
            case Teapot -> currentSpatial = teapot;
        }
        showMaterial(currentMaterial);
    }

    @Override
    public void sceneOpened(SceneRequest request) {
    }

    @Override
    public void sceneClosed(SceneRequest request) {
    }

    @Override
    public void previewCreated(PreviewRequest request) {
        if (request.getRequester() == this) {
            final ImageIcon icon = request instanceof MaterialImageRequest imageRequest
                    ? imageRequest.icon(request.getImage()) : new ImageIcon(request.getImage());
            java.awt.EventQueue.invokeLater(() -> {
                label.setIcon(icon);
                previewRequested = false;
                if (refreshPending) {
                    refreshPending = false;
                    refreshOnly();
                }
            });
        }
    }

    public void cleanUp() {
        disposed = true;
        label.removeComponentListener(sizeListener);
        label.removePropertyChangeListener("graphicsConfiguration", scaleListener);
        SceneApplication.getApplication().removeSceneListener(this);
        exec.shutdownNow();
    }

    public boolean isPreviewRequested() {
        return previewRequested;
    }

    /**
     * A more lightweight refresh than showMaterials that doesn't rebuild the
     * material
     */
    public void refreshOnly() {
        if (!java.awt.EventQueue.isDispatchThread()) {
            java.awt.EventQueue.invokeLater(this::refreshOnly);
            return;
        }
        if (disposed) {
            return;
        }
        if (previewRequested) {
            refreshPending = true;
            return;
        }
        if (currentMaterial == null || label.getWidth() <= 0 || label.getHeight() <= 0) {
            return;
        }
        PreviewRequest request = previewRequest();
        previewRequested = true;
        SceneApplication.getApplication().enqueue((Callable<Object>) () -> {
            if (currentMaterial != null) {
                configurePreview(request);
                request.getCameraRequest().setLookAt(new Vector3f(0, 0, 0), Vector3f.UNIT_Y);
                SceneApplication.getApplication().createPreview(request);
            }
            return null;
        });
    }

}
