package com.jme3.gde.core.scene.processors;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Quad;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Isolated real OpenGL query-LOD debug shader regression; does not start SDK. */
public final class LightmapMipDebugSmoke extends SimpleApplication {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Throwable failure;
    private PreviewDebugProcessor debug;
    private Geometry geometry;
    private Material original;
    private Texture2D mip;
    private Texture2D base;
    private FrameBuffer target;
    private int stage;
    private int frames;
    private boolean configured;
    private final float[][] colors = {{.125f,.125f,.125f}, {0,.8f,.1f}, {.6f,.1f,1}, {.35f,.35f,.35f},
            {1,0,.8f}, {.8f,0,0}, {1,.8f,0}, {.125f,.125f,.125f}};

    public static void main(String[] args) throws Exception {
        NativeLibraryLoader.setCustomExtractionFolder(Files.createTempDirectory("lightmap-mip-debug-").toString());
        LightmapMipDebugSmoke app = new LightmapMipDebugSmoke();
        app.setShowSettings(false);
        app.setDisplayFps(false);
        app.setDisplayStatView(false);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(256,192);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(false);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.start(JmeContext.Type.OffscreenSurface);
        if (!app.done.await(40, TimeUnit.SECONDS)) throw new AssertionError("Mip debug GPU timeout");
        app.stop(true);
        if (app.failure != null) throw new AssertionError(app.failure);
        System.out.println("LIGHTMAP_MIP_DEBUG_GPU_PASS: actual near0/far3, disabled, base-only, missing, dynamic, Lit restore");
        System.exit(0);
    }

    private Texture2D texture(boolean levels) {
        int[] sizes = levels ? new int[]{64*128*8,32*64*8,16*32*8,8*16*8} : null;
        int size = sizes == null ? 64*128*8 : java.util.Arrays.stream(sizes).sum();
        ByteBuffer bytes = BufferUtils.createByteBuffer(size);
        Image image = sizes == null ? new Image(Image.Format.RGBA16F,64,128,bytes,ColorSpace.Linear)
                : new Image(Image.Format.RGBA16F,64,128,bytes,sizes,ColorSpace.Linear);
        Texture2D result = new Texture2D(image);
        result.setWrap(Texture.WrapMode.EdgeClamp);
        result.setMinFilter(levels ? Texture.MinFilter.Trilinear : Texture.MinFilter.BilinearNoMipMaps);
        return result;
    }

    @Override public void simpleInitApp() {
        try {
            getSceneServices().setEnabled(false);
            flyCam.setEnabled(false);
            cam.setParallelProjection(true);
            cam.setFrustum(.1f,100,-4,4,3,-3);
            cam.setLocation(Vector3f.ZERO);
            cam.lookAtDirection(new Vector3f(0,0,-1),Vector3f.UNIT_Y);
            Mesh mesh = new Quad(1,1);
            mesh.setBuffer(VertexBuffer.Type.TexCoord2,2,new float[]{0,0,1,0,1,1,0,1});
            geometry = new Geometry("Receiver",mesh);
            geometry.setMobility(Mobility.Static);
            original = new Material(assetManager,"Common/MatDefs/Misc/Unshaded.j3md");
            original.setColor("Color",new ColorRGBA(.125f,.125f,.125f,1));
            geometry.setMaterial(original);
            rootNode.attachChild(geometry);
            mip=texture(true);
            base=texture(false);
            target=new FrameBuffer(256,192,1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
            viewPort.setOutputFrameBuffer(target);
            debug=new PreviewDebugProcessor(assetManager,rootNode,message -> {});
            viewPort.addProcessor(debug);
        } catch(Throwable error) { handleError("Initialize",error); }
    }

    @Override public void simpleUpdate(float tpf) {
        if (configured || debug==null || done.getCount()==0) return;
        configured=true;
        frames=0;
        float scale=stage==2 || stage==3 ? .25f : 4f;
        geometry.setLocalScale(scale);
        geometry.setLocalTranslation(-scale*.5f,-scale*.5f,-3);
        geometry.setMobility(stage==6 ? Mobility.Dynamic : Mobility.Static);
        geometry.setResidentProbeLightmap(stage==5 ? null : stage==4 ? base : mip,true);
        mip.setMinFilter(stage==3 ? Texture.MinFilter.BilinearNoMipMaps : Texture.MinFilter.Trilinear);
        debug.setMode(stage==0 || stage==7 ? PreviewDebugProcessor.Mode.Lit : PreviewDebugProcessor.Mode.LightmapMip);
    }

    @Override public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (debug==null || done.getCount()==0 || ++frames<4) return;
        try {
            if (geometry.getMaterial()!=original) throw new AssertionError("Debug material leaked after frame");
            ByteBuffer bytes=BufferUtils.createByteBuffer(256*192*16);
            renderer.readFrameBufferWithFormat(target,bytes,Image.Format.RGBA32F);
            int center=(96*256+128)*16;
            for(int c=0;c<3;c++) {
                float actual=bytes.getFloat(center+c*4);
                if(Math.abs(actual-colors[stage][c])>.015f) {
                    throw new AssertionError("stage="+stage+" component="+c+" actual="+actual+" expected="+colors[stage][c]);
                }
            }
            BufferUtils.destroyDirectBuffer(bytes);
            System.out.println("MIP_DEBUG_STAGE "+stage+" passed");
            if(++stage==colors.length) {
                done.countDown();
            } else {
                configured=false;
            }
        } catch(Throwable error) { handleError("Render",error); }
    }
    @Override public void handleError(String message,Throwable error) {
        failure=error;
        done.countDown();
    }
}
