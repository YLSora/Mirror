package com.mirror.client;

import org.joml.Matrix4f;

public interface EmbeddiumViewportState {
    Matrix4f mirror$getCullMatrix();
    void mirror$setCullMatrix(Matrix4f matrix);
}
