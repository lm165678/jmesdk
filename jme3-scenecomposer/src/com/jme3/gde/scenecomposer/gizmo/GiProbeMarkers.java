package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeGrid;
import com.jme3.environment.gi.GiProbeStreamer.ResidentBrick;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.List;

/** Render-thread marker builder. Sampling halos never create duplicate markers. */
final class GiProbeMarkers {
    record Buffers(FloatBuffer positions, FloatBuffer colors, FloatBuffer movement) { }

    private record Brick(GiProbeData data, GiProbeGrid grid, Vector3f min, Vector3f max) { }

    private final Vector3f scale;
    private final FloatBuffer positions = BufferUtils.createFloatBuffer(8192 * 3);
    private final FloatBuffer colors = BufferUtils.createFloatBuffer(8192 * 4);
    private final FloatBuffer movement = BufferUtils.createFloatBuffer(8192 * 6);

    private GiProbeMarkers(Vector3f scale) {
        this.scale = scale.clone();
        // Unbaked authoring grids may temporarily have a collapsed axis.
        if (this.scale.x == 0) {
            this.scale.x = 1;
        }
        if (this.scale.y == 0) {
            this.scale.y = 1;
        }
        if (this.scale.z == 0) {
            this.scale.z = 1;
        }
    }

    static Buffers build(GiProbeVolume volume, GiProbeData coarse,
            List<ResidentBrick> resident, ProbeDisplayMode mode) {
        GiProbeMarkers result = new GiProbeMarkers(volume.getWorldScale());
        var directory = volume.getStreamingData();
        if (directory == null) {
            GiProbeGrid grid = coarse == null ? volume.getGrid() : coarse.getGrid();
            Vector3f point = new Vector3f();
            int stride = stride(grid);
            for (int i = 0; i < grid.getProbeCount(); i += stride) {
                grid.position(i, point);
                if (coarse == null) {
                    // Unbaked volume grids are local; baked grids already include world scale.
                    point.multLocal(result.scale);
                }
                result.append(coarse, i, point, null,
                        Boolean.TRUE.equals(volume.getUserData("jme.gi.adaptive.refinement")));
            }
        } else {
            GiProbeGrid fine = directory.getFineGrid();
            List<Brick> bricks = resident.stream().map(brick -> new Brick(brick.getData(),
                    brick.getData().getGrid(), brick.getOrigin(), brick.getOrigin().add(brick.getExtent()))).toList();
            Vector3f point = new Vector3f();
            if (mode != ProbeDisplayMode.COARSE) {
                int stride = stride(fine);
                for (int i = 0; i < fine.getProbeCount(); i += stride) {
                    fine.position(i, point);
                    Brick brick = covering(bricks, point);
                    if (brick != null) {
                        result.append(brick.data(), index(brick.grid(), point), point, null, true);
                    } else if (mode == ProbeDisplayMode.FINE) {
                        result.append(null, 0, point, null, true);
                    }
                }
            }
            if (mode != ProbeDisplayMode.FINE && coarse != null) {
                GiProbeGrid grid = coarse.getGrid();
                for (int i = 0; i < grid.getProbeCount(); i += stride(grid)) {
                    grid.position(i, point);
                    // Decimation picks a source fine probe. Its offset from the coarse
                    // lattice is bookkeeping, not a physical relocation.
                    Vector3f source = nearest(fine, point);
                    if (mode == ProbeDisplayMode.COARSE || covering(bricks, source) == null) {
                        result.append(coarse, i, point, source, false);
                    }
                }
            }
        }
        result.positions.flip();
        result.colors.flip();
        result.movement.flip();
        return new Buffers(result.positions, result.colors, result.movement);
    }

    private static int stride(GiProbeGrid grid) {
        return Math.max(1, (grid.getProbeCount() + 4095) / 4096);
    }

    private static Brick covering(List<Brick> bricks, Vector3f point) {
        for (Brick brick : bricks) {
            if (point.x >= brick.min().x - 0.0001f && point.x <= brick.max().x + 0.0001f
                    && point.y >= brick.min().y - 0.0001f && point.y <= brick.max().y + 0.0001f
                    && point.z >= brick.min().z - 0.0001f && point.z <= brick.max().z + 0.0001f) {
                return brick;
            }
        }
        return null;
    }

    private static int index(GiProbeGrid grid, Vector3f point) {
        Vector3f coordinate = point.subtract(grid.getOrigin()).divideLocal(grid.getSpacing());
        Vector3f counts = grid.getCounts();
        return grid.index(Math.max(0, Math.min((int) counts.x - 1, Math.round(coordinate.x))),
                Math.max(0, Math.min((int) counts.y - 1, Math.round(coordinate.y))),
                Math.max(0, Math.min((int) counts.z - 1, Math.round(coordinate.z))));
    }

    private static Vector3f nearest(GiProbeGrid grid, Vector3f point) {
        return grid.position(index(grid, point), new Vector3f());
    }

    private void append(GiProbeData data, int index, Vector3f nominal, Vector3f source, boolean fine) {
        if (data != null && data.isSurfaceLayout() && !data.isProbeValid(index)) {
            return;
        }
        Vector3f actual = nominal.clone();
        ColorRGBA color = ColorRGBA.Cyan;
        if (data != null) {
            data.getProbeOffset(index, actual).addLocal(nominal);
            boolean valid = data.isProbeValid(index);
            color = valid ? (fine ? new ColorRGBA(0.25f, 0.55f, 1, 1) : ColorRGBA.Green) : ColorRGBA.Red;
            Vector3f original = source == null ? nominal : source;
            // Half-precision metadata can leave a small resampling residual.
            float tolerance = Math.max(0.0001f, data.getGrid().getSpacing().length() * 0.001f);
            if (valid && !data.isSurfaceLayout() && actual.distanceSquared(original) > tolerance * tolerance) {
                color = ColorRGBA.Yellow;
                Vector3f start = original.divide(scale);
                Vector3f end = actual.divide(scale);
                movement.put(start.x).put(start.y).put(start.z);
                movement.put(end.x).put(end.y).put(end.z);
            }
            if (data.isSurfaceLayout()) {
                color = new ColorRGBA(1, 0.55f, 0.15f, 1);
            }
        }
        actual.divideLocal(scale);
        positions.put(actual.x).put(actual.y).put(actual.z);
        colors.put(color.r).put(color.g).put(color.b).put(color.a);
    }
}
