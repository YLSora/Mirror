package com.mirror.client;

/** Access to the exact LevelRenderer pipeline field added by Oculus 1.8.0. */
public interface OculusLevelRendererAccess {
    Object mirror$getPipeline();

    void mirror$setPipeline(Object pipeline);
}
