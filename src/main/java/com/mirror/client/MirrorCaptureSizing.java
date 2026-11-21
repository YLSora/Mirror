package com.mirror.client;

/** Per-view pixel demand and hysteresis; independent of allocated surface capacity. */
final class MirrorCaptureSizing {
    private long demandFrame = Long.MIN_VALUE;
    private int requestedWidth = 16;
    private int requestedHeight = 16;
    private int width;
    private int height;

    void request(long frame, double pixels, double aspect, double density, int maximum) {
        if (!Double.isFinite(pixels) || pixels <= 0.0 || !Double.isFinite(aspect) || aspect <= 0.0) {
            throw new IllegalArgumentException("positive finite mirror pixel demand required");
        }
        double w = Math.sqrt(pixels * aspect) * density;
        double h = Math.sqrt(pixels / aspect) * density;
        double limit = Math.min(1.0, maximum / Math.max(w, h));
        int nextWidth = Math.max(16, (int) Math.ceil(w * limit));
        int nextHeight = Math.max(16, (int) Math.ceil(h * limit));
        if (frame != demandFrame) {
            requestedWidth = nextWidth;
            requestedHeight = nextHeight;
            demandFrame = frame;
        } else {
            requestedWidth = Math.max(requestedWidth, nextWidth);
            requestedHeight = Math.max(requestedHeight, nextHeight);
        }
    }

    void update(int maximum) {
        width = stableDimension(width, requestedWidth, maximum);
        height = stableDimension(height, requestedHeight, maximum);
    }

    private static int stableDimension(int current, int requested, int maximum) {
        if (current != 0 && current <= maximum && requested <= current && requested >= current * 0.35) return current;
        int required = Math.max(16, (int) Math.min(maximum, Math.ceil(requested * 1.125)));
        return Math.min(maximum, Integer.highestOneBit(required - 1) << 1);
    }

    int width() { return width; }
    int height() { return height; }
    int requestedLongEdge() { return Math.max(requestedWidth, requestedHeight); }

}
