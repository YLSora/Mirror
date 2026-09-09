package com.mirror.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mirror.common.ConnectionType;
import com.mirror.common.MirrorOrientation;
import com.mirror.common.ScreenRect;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MirrorOrientationTest {
    @ParameterizedTest
    @EnumSource(MirrorOrientation.class)
    void reflectedCameraAndSurfaceHaveMatchingAxes(MirrorOrientation orientation) {
        Matrix4f view = viewRotation(orientation);
        assertVector(new Vec3(0, 0, -1), view.transformDirection(orientation.normal().toVector3f()));
        assertVector(new Vec3(-1, 0, 0), view.transformDirection(orientation.right().toVector3f()));
        assertVector(new Vec3(0, 1, 0), view.transformDirection(orientation.up().toVector3f()));
        Matrix4f surface = new Matrix4f()
                .rotationY((float) Math.toRadians(180.0f - orientation.yaw()))
                .rotateX((float) Math.toRadians(-orientation.pitch()));
        assertVector(orientation.normal(), surface.transformDirection(new Vector3f(0, 0, -1)));
        assertVector(orientation.right(), surface.transformDirection(new Vector3f(-1, 0, 0)));
        assertVector(orientation.up(), surface.transformDirection(new Vector3f(0, 1, 0)));
    }

    @ParameterizedTest
    @EnumSource(MirrorOrientation.class)
    void offCenterReflectedRaysLandOnThePhysicalAperture(MirrorOrientation orientation) {
        ScreenRect screen = new ScreenRect(new Vec3(10, 64, 10), orientation, 3, 2);
        Vec3 eye = screen.center().add(screen.normal().scale(2))
                .add(screen.right().scale(0.7)).add(screen.up().scale(0.3));
        MirrorReflection reflection = MirrorReflection.compute(screen.center(), screen.normal(), eye);
        double inset = 1.0 / 16.0;
        MirrorProjection projection = MirrorProjection.forMirror(screen, reflection, inset);
        MirrorProjection.ViewportProjection viewport = projection.fitViewport(300, 200);
        Matrix4f camera = viewport.matrix(100).mul(viewRotation(orientation));
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                Vec3 corner = screen.center()
                        .add(screen.right().scale((1 - 2 * x) * (screen.width() / 2 - inset)))
                        .add(screen.up().scale((2 * y - 1) * (screen.height() / 2 - inset)));
                Vector3f projected = camera.transformProject(corner.subtract(reflection.reflectedEye()).toVector3f());
                assertEquals(x == 0 ? viewport.crop().minU() : viewport.crop().maxU(),
                        (projected.x + 1) / 2, 1.0e-5);
                assertEquals(y == 0 ? viewport.crop().minV() : viewport.crop().maxV(),
                        (projected.y + 1) / 2, 1.0e-5);
            }
        }
    }

    @Test
    void allBlockModelsMatchTheSurfaceAndConnectionAxes() throws Exception {
        JsonObject variants = resourceJson("/assets/mirror/blockstates/mirror.json").getAsJsonObject("variants");
        assertEquals(192, variants.size());
        for (Direction facing : Direction.values()) {
            MirrorOrientation orientation = MirrorOrientation.of(facing);
            for (boolean far : new boolean[]{false, true}) {
                for (ConnectionType connection : ConnectionType.values()) {
                    String key = "facing=" + facing.getSerializedName() + ",far=" + far
                            + ",connection=" + connection.getSerializedName();
                    JsonObject variant = variants.getAsJsonObject(key);
                    assertNotNull(variant, key);
                    int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
                    int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                    Matrix4f rotation = BlockModelRotation.by(x, y).getRotation().getMatrix();
                    assertVector(orientation.normal(), rotation.transformDirection(new Vector3f(0, 0, -1)));
                    assertVector(orientation.right(), rotation.transformDirection(new Vector3f(-1, 0, 0)));
                    assertVector(orientation.up(), rotation.transformDirection(new Vector3f(0, 1, 0)));
                    String model = variant.get("model").getAsString().substring("mirror:".length());
                    JsonObject element = resourceJson("/assets/mirror/models/" + model + ".json")
                            .getAsJsonArray("elements").get(0).getAsJsonObject();
                    double surfaceZ = element.getAsJsonArray("from").get(2).getAsDouble() / 16;
                    Vector3f planeOffset = rotation.transformDirection(new Vector3f(0, 0, (float) surfaceZ - 0.5f));
                    assertVector(orientation.normal().scale(0.5 - (far ? 14.0 / 16.0 : 0)), planeOffset);
                }
            }
        }
    }

    private static Matrix4f viewRotation(MirrorOrientation orientation) {
        return new Matrix4f().rotationX((float) Math.toRadians(orientation.pitch()))
                .rotateY((float) Math.toRadians(orientation.yaw() + 180));
    }

    private static JsonObject resourceJson(String path) throws Exception {
        try (var stream = MirrorOrientationTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void assertVector(Vec3 expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1.0e-5);
        assertEquals(expected.y, actual.y, 1.0e-5);
        assertEquals(expected.z, actual.z, 1.0e-5);
    }
}
