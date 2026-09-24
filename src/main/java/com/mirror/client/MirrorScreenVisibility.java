package com.mirror.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Clips the submitted surface in homogeneous coordinates before measuring its pixel area. */
final class MirrorScreenVisibility {
    private final Matrix4f transform = new Matrix4f();
    private final Vector4f[] first = vertices();
    private final Vector4f[] second = vertices();

    double projectedArea(Matrix4fc projection, Matrix4fc modelView, Matrix4fc surfacePose,
                         float left, float right, float bottom, float top, int width, int height,
                         MirrorProjection.UvRect crop) {
        if (width <= 0 || height <= 0) return 0.0;
        transform.set(projection).mul(modelView).mul(surfacePose);
        first[0].set(left, bottom, 0.0f, 1.0f).mul(transform);
        first[1].set(right, bottom, 0.0f, 1.0f).mul(transform);
        first[2].set(right, top, 0.0f, 1.0f).mul(transform);
        first[3].set(left, top, 0.0f, 1.0f).mul(transform);
        for (int i = 0; i < 4; i++) {
            if (!first[i].isFinite()) return 0.0;
        }

        // Clipping edges, rather than testing corners, retains mirrors spanning the viewport.
        Vector4f[] input = first;
        Vector4f[] output = second;
        int count = 4;
        for (int plane = 0; plane < 6 && count >= 3; plane++) {
            int nextCount = 0;
            Vector4f previous = input[count - 1];
            double previousDistance = distance(previous, plane, crop);
            for (int i = 0; i < count; i++) {
                Vector4f current = input[i];
                double currentDistance = distance(current, plane, crop);
                if ((previousDistance >= 0.0) != (currentDistance >= 0.0)) {
                    double fraction = previousDistance / (previousDistance - currentDistance);
                    output[nextCount++].set(
                            (float) (previous.x + fraction * (current.x - previous.x)),
                            (float) (previous.y + fraction * (current.y - previous.y)),
                            (float) (previous.z + fraction * (current.z - previous.z)),
                            (float) (previous.w + fraction * (current.w - previous.w)));
                }
                if (currentDistance >= 0.0) output[nextCount++].set(current);
                previous = current;
                previousDistance = currentDistance;
            }
            count = nextCount;
            Vector4f[] swap = input;
            input = output;
            output = swap;
        }
        if (count < 3) return 0.0;

        double twiceArea = 0.0;
        Vector4f previous = input[count - 1];
        for (int i = 0; i < count; i++) {
            Vector4f current = input[i];
            if (previous.w <= 0.0f || current.w <= 0.0f) return 0.0;
            twiceArea += ((double) previous.x * current.y - (double) current.x * previous.y)
                    / ((double) previous.w * current.w);
            previous = current;
        }
        return Math.min(1.0, Math.abs(twiceArea) / 8.0) * width * height;
    }

    private static double distance(Vector4f vertex, int plane, MirrorProjection.UvRect crop) {
        return switch (plane) {
            case 0 -> vertex.x - (2.0 * crop.minU() - 1.0) * vertex.w;
            case 1 -> (2.0 * crop.maxU() - 1.0) * vertex.w - vertex.x;
            case 2 -> vertex.y - (2.0 * crop.minV() - 1.0) * vertex.w;
            case 3 -> (2.0 * crop.maxV() - 1.0) * vertex.w - vertex.y;
            case 4 -> (double) vertex.w + vertex.z;
            case 5 -> (double) vertex.w - vertex.z;
            default -> throw new IllegalArgumentException("Unknown clip plane");
        };
    }

    private static Vector4f[] vertices() {
        Vector4f[] result = new Vector4f[12];
        for (int i = 0; i < result.length; i++) result[i] = new Vector4f();
        return result;
    }
}
