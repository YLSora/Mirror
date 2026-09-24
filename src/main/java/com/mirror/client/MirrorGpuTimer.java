package com.mirror.client;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;

/** Bounded timestamp pairs; results are polled after three outer frames without waiting. */
final class MirrorGpuTimer {
    private static final int CAPACITY = 8;
    private final int[] starts = new int[CAPACITY];
    private final int[] ends = new int[CAPACITY];
    private final long[] frames = new long[CAPACITY];
    private int next;
    private int oldest;
    private int pending;
    private boolean initialized;
    private boolean supported;

    int begin(long frame) {
        if (!initialized) {
            initialized = true;
            var capabilities = GL.getCapabilities();
            supported = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query;
        }
        if (!supported || pending == CAPACITY) return -1;
        int slot = next;
        if (starts[slot] == 0) {
            starts[slot] = GL15C.glGenQueries();
            ends[slot] = GL15C.glGenQueries();
        }
        frames[slot] = frame;
        ARBTimerQuery.glQueryCounter(starts[slot], ARBTimerQuery.GL_TIMESTAMP);
        return slot;
    }

    void end(int slot) {
        if (slot < 0) return;
        ARBTimerQuery.glQueryCounter(ends[slot], ARBTimerQuery.GL_TIMESTAMP);
        next = (slot + 1) % CAPACITY;
        pending++;
    }

    void poll(long frame) {
        while (pending > 0 && frame - frames[oldest] >= 3) {
            if (GL15C.glGetQueryObjecti(ends[oldest], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0
                    || GL15C.glGetQueryObjecti(starts[oldest], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) return;
            long start = ARBTimerQuery.glGetQueryObjectui64(starts[oldest], GL15C.GL_QUERY_RESULT);
            long end = ARBTimerQuery.glGetQueryObjectui64(ends[oldest], GL15C.GL_QUERY_RESULT);
            MirrorDiagnostics.recordGpuBatch(frames[oldest], Math.max(0L, end - start));
            oldest = (oldest + 1) % CAPACITY;
            pending--;
        }
    }

    void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            if (starts[i] != 0) GL15C.glDeleteQueries(starts[i]);
            if (ends[i] != 0) GL15C.glDeleteQueries(ends[i]);
            starts[i] = 0;
            ends[i] = 0;
        }
        next = 0;
        oldest = 0;
        pending = 0;
        initialized = false;
        supported = false;
    }
}
