import com.jme3.gde.core.assets.ModelCollisionShapes;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
public class SphereBoundsSmoke {
    static float radius(Spatial s) {
        var shape = (CompoundCollisionShape) ModelCollisionShapes.create(s, true);
        return ((MultiSphere) shape.listChildren()[0].getShape()).getRadius(0);
    }
    static void check(float actual, float expected) {
        if (Math.abs(actual - expected) > .0001f) {
            throw new AssertionError(actual + " != " + expected);
        }
    }
    public static void main(String[] args) {
        com.jme3.system.NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        var sphere = new Geometry("Sphere", new Sphere(32,32,1));
        check(radius(sphere), 1);
        sphere.setLocalScale(4);
        sphere.setLocalTranslation(20,0,0);
        check(radius(sphere), 1);
        var root = new Node();
        root.attachChild(sphere);
        check(radius(root), 4);
        check(radius(new Geometry("Box", new Box(1,1,1))), (float)Math.sqrt(3));
        System.out.println("SPHERE_BOUNDS_PASS");
    }
}
