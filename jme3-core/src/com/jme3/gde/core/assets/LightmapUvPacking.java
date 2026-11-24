package com.jme3.gde.core.assets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repackages xatlas islands without changing its JNI ABI or UV parameterization. */
final class LightmapUvPacking {
    private static final class Chart {
        float minU = Float.POSITIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        int x;
        int y;
    }

    static void repack(float[] uv, int[] indices, LightmapUvGenerator.Settings settings) throws IOException {
        int[] parent = new int[uv.length / 2];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
            if (!Float.isFinite(uv[i * 2]) || !Float.isFinite(uv[i * 2 + 1])) {
                throw new IOException("Nonfinite xatlas coordinates");
            }
        }
        for (int i = 0; i < indices.length; i += 3) {
            int a = root(parent, indices[i]);
            parent[root(parent, indices[i + 1])] = a;
            parent[root(parent, indices[i + 2])] = a;
        }
        Map<Integer, Chart> charts = new LinkedHashMap<>();
        Chart[] owners = new Chart[parent.length];
        for (int i = 0; i < parent.length; i++) {
            Chart chart = charts.computeIfAbsent(root(parent, i), ignored -> new Chart());
            owners[i] = chart;
            chart.minU = Math.min(chart.minU, uv[i * 2]);
            chart.minV = Math.min(chart.minV, uv[i * 2 + 1]);
            chart.maxU = Math.max(chart.maxU, uv[i * 2]);
            chart.maxV = Math.max(chart.maxV, uv[i * 2 + 1]);
        }
        List<Chart> order = new ArrayList<>(charts.values());
        order.sort(Comparator.<Chart>comparingDouble(chart -> chart.maxV - chart.minV).reversed()
                .thenComparing(Comparator.comparingDouble((Chart chart) -> chart.maxU - chart.minU).reversed()));
        if (!pack(order, 0, settings)) {
            throw new IOException("Too many UV islands for this resolution and padding; increase resolution or reduce padding");
        }
        double span = 0;
        for (Chart chart : order) {
            span = Math.max(span, Math.max(chart.maxU - chart.minU, chart.maxV - chart.minV));
        }
        double low = 0;
        double high = settings.resolution() / Math.max(span, 1e-12);
        for (int i = 0; i < 40; i++) {
            double candidate = (low + high) * 0.5;
            if (pack(order, candidate, settings)) {
                low = candidate;
            } else {
                high = candidate;
            }
        }
        pack(order, low, settings);
        for (int i = 0; i < owners.length; i++) {
            Chart chart = owners[i];
            uv[i * 2] = (float) ((chart.x + settings.padding() + 0.5
                    + (uv[i * 2] - chart.minU) * low) / settings.resolution());
            uv[i * 2 + 1] = (float) ((chart.y + settings.padding() + 0.5
                    + (uv[i * 2 + 1] - chart.minV) * low) / settings.resolution());
        }
    }

    private static int root(int[] parent, int index) throws IOException {
        if (index < 0 || index >= parent.length) {
            throw new IOException("Invalid xatlas vertex index");
        }
        while (index != parent[index]) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    // Deterministic rectangular shelves are conservative: less dense than native
    // shape packing, but reserve the requested texel margin for every island.
    private static boolean pack(List<Chart> charts, double scale, LightmapUvGenerator.Settings settings) {
        int edge = settings.resolution() - settings.border();
        int x = settings.border();
        int y = settings.border();
        int rowHeight = 0;
        for (Chart chart : charts) {
            int width = (int) Math.ceil((chart.maxU - chart.minU) * scale) + 1 + 2 * settings.padding();
            int height = (int) Math.ceil((chart.maxV - chart.minV) * scale) + 1 + 2 * settings.padding();
            if (width > edge - settings.border() || height > edge - settings.border()) {
                return false;
            }
            if (x + width > edge) {
                x = settings.border();
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + height > edge) {
                return false;
            }
            chart.x = x;
            chart.y = y;
            x += width;
            rowHeight = Math.max(rowHeight, height);
        }
        return true;
    }
}
