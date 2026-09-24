package com.mirror.compat;

import org.joml.Matrix4f;

/** Previous matrices belong to a Voxy viewport, not to an Iris program or capture slot. */
public interface VoxyIrisHistoryAccess {
    Matrix4f mirror$previousProjection();
    Matrix4f mirror$previousModelView();
    Matrix4f mirror$previousViewProjection();
}
