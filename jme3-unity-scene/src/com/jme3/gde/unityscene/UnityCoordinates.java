package com.jme3.gde.unityscene;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/** Unity left-handed +Z-forward to JME right-handed -Z-forward conversion. */
final class UnityCoordinates {
    private UnityCoordinates() {
    }

    static Vector3f position(Object value) {
        Vector3f unity = UnityYaml.vector3(value, Vector3f.ZERO);
        return new Vector3f(unity.x, unity.y, -unity.z);
    }

    static Quaternion rotation(Object value) {
        return rotation(UnityYaml.quaternion(value));
    }

    static Quaternion rotation(Quaternion unity) {
        return new Quaternion(-unity.getX(), -unity.getY(),
                unity.getZ(), unity.getW()).normalizeLocal();
    }

    static Quaternion toUnity(Quaternion jme) {
        return new Quaternion(-jme.getX(), -jme.getY(), jme.getZ(), jme.getW());
    }

    static Vector3f scale(Object value) {
        return UnityYaml.vector3(value, Vector3f.UNIT_XYZ);
    }

    /** Local Unity +Z forward expressed in the converted JME coordinate system. */
    static Vector3f forward(Quaternion worldRotation) {
        return worldRotation.mult(new Vector3f(0, 0, -1)).normalizeLocal();
    }
}
