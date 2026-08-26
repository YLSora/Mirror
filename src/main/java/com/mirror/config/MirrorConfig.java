package com.mirror.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class MirrorConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder common = new ForgeConfigSpec.Builder();
        COMMON = new Common(common);
        COMMON_SPEC = common.build();

        ForgeConfigSpec.Builder client = new ForgeConfigSpec.Builder();
        CLIENT = new Client(client);
        CLIENT_SPEC = client.build();
    }

    private MirrorConfig() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }

    public static final class Common {
        public final ForgeConfigSpec.IntValue maxConnectedSize;
        public final ForgeConfigSpec.BooleanValue squareAspectRatio;
        public final ForgeConfigSpec.EnumValue<PlacementMode> placementMode;
        public final ForgeConfigSpec.BooleanValue enableEndermanObservation;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("connection");
            maxConnectedSize = builder.comment("Maximum width and height of one connected mirror group.")
                    .defineInRange("maxConnectedSize", 8, 1, 32);
            squareAspectRatio = builder.comment("Only allow connected mirror groups whose width equals their height.")
                    .define("squareAspectRatio", false);
            builder.pop();

            builder.push("placement");
            placementMode = builder.comment("NEAR always uses the front surface, FAR the recessed surface, BOTH uses the clicked half.")
                    .defineEnum("placementMode", PlacementMode.BOTH);
            builder.pop();

            builder.push("enderman");
            enableEndermanObservation = builder.comment(
                            "Allow players looking through a mirror to anger and freeze nearby Endermen.")
                    .define("enableObservation", true);
            builder.pop();
        }
    }

    public static final class Client {
        public final ForgeConfigSpec.IntValue renderDistance;
        public final ForgeConfigSpec.DoubleValue resolutionScale;
        public final ForgeConfigSpec.BooleanValue smoothSampling;
        public final ForgeConfigSpec.EnumValue<RecursionMode> recursionMode;
        public final ForgeConfigSpec.IntValue maxRecursionDepth;
        public final ForgeConfigSpec.DoubleValue recursiveResolutionDecay;
        public final ForgeConfigSpec.DoubleValue recursiveRenderDistanceDecay;

        private Client(ForgeConfigSpec.Builder builder) {
            builder.push("reflection");
            renderDistance = builder.comment("Maximum distance at which mirror reflection renderers are considered.")
                    .defineInRange("renderDistance", 48, 1, 256);
            resolutionScale = builder.comment("Multiplier for reflection render target dimensions.")
                    // Vista's scale is a pixel-density multiplier (8 pixels per block at the
                    // default), rather than a normalized 0..1 render fraction.
                    .defineInRange("resolutionScale", 8.0D, 1.0D, 32.0D);
            smoothSampling = builder.comment("Use linear filtering for reflection textures when available.")
                    .define("smoothSampling", false);
            recursionMode = builder.comment("OFF disables nested mirrors; SHARED reuses direct textures; RECURSIVE uses chain-isolated textures up to maxRecursionDepth.")
                    .defineEnum("recursionMode", RecursionMode.RECURSIVE);
            maxRecursionDepth = builder.comment("Hard upper bound for recursive reflection renders.")
                    .defineInRange("maxRecursionDepth", 2, 0, 8);
            recursiveResolutionDecay = builder.comment("Resolution multiplier applied at each recursive reflection depth.")
                    .defineInRange("recursiveResolutionDecay", 0.5D, 0.1D, 1.0D);
            recursiveRenderDistanceDecay = builder.comment("Render-distance multiplier applied at each recursive reflection depth.")
                    .defineInRange("recursiveRenderDistanceDecay", 0.5D, 0.1D, 1.0D);
            builder.pop();
        }
    }

    public enum PlacementMode {
        NEAR,
        FAR,
        BOTH
    }

    public enum RecursionMode {
        OFF,
        SHARED,
        RECURSIVE
    }
}
