package com.mirror.client;

/** Per-view pixel demand and hysteresis; independent of allocated surface capacity. */
final class MirrorCaptureSizing {
    private long demandFrame = Long.MIN_VALUE;
    private int requestedWidth = 16;
    private int requestedHeight = 16;
    private int width;
    private int height;
    private int lowDemandFrames;
    private int highDemandFrames;
    private static final int SHADER_SHRINK_CONFIRM_FRAMES = 120;
    private static final int SHADER_GROW_CONFIRM_FRAMES = 4;

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

    void update(int maximum, boolean shaderCapture) {
        if (!shaderCapture) {
            width = stableDimension(width, requestedWidth, maximum);
            height = stableDimension(height, requestedHeight, maximum);
            return;
        }

        // Oculus keeps a complete pipeline and temporal attachment set per square resolution
        // bucket. A grazing/partly occluded mirror can change its projected area by orders of
        // magnitude for a few frames while the camera moves. Shrinking immediately would churn
        // those slots and expose an un-warmed blue surface; require sustained low demand before
        // accepting a lower bucket. A newly created view still establishes its first target
        // immediately; only subsequent growth is delayed.
        if (width == 0 || height == 0) {
            lowDemandFrames = 0;
            highDemandFrames = 0;
            width = stableDimension(width, requestedWidth, maximum);
            height = stableDimension(height, requestedHeight, maximum);
            return;
        }
        if (requestedWidth > width || requestedHeight > height) {
            // A projected-area estimate can jump for a few frames while the camera crosses a
            // mirror edge. Growing immediately would allocate a new Oculus slot for that transient
            // spike, then pay another full shader pipeline/attachment transition on the way back.
            // Keep the current valid image until the larger demand is stable.
            highDemandFrames++;
            if (highDemandFrames < SHADER_GROW_CONFIRM_FRAMES) return;
            highDemandFrames = 0;
            lowDemandFrames = 0;
            width = stableDimension(width, requestedWidth, maximum);
            height = stableDimension(height, requestedHeight, maximum);
            return;
        }
        highDemandFrames = 0;
        if (requestedWidth < width * 0.35 || requestedHeight < height * 0.35) {
            lowDemandFrames++;
            if (lowDemandFrames < SHADER_SHRINK_CONFIRM_FRAMES) return;
        } else {
            lowDemandFrames = 0;
            return;
        }
        lowDemandFrames = 0;
        width = stableDimension(width, requestedWidth, maximum);
        height = stableDimension(height, requestedHeight, maximum);
    }

    private static int stableDimension(int current, int requested, int maximum) {
        if (current != 0 && requested <= current && requested >= current * 0.35) return current;
        int required = Math.max(16, (int) Math.min(maximum, Math.ceil(requested * 1.125)));
        return Math.min(maximum, Integer.highestOneBit(required - 1) << 1);
    }

    int width() { return width; }
    int height() { return height; }
}
