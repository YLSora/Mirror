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
            maxConnectedSize = builder.comment("单个连接镜子组的最大宽度和高度。")
                    .translation("mirror.config.maxConnectedSize")
                    .defineInRange("maxConnectedSize", 8, 1, 32);
            squareAspectRatio = builder.comment("仅允许宽高相等的连接镜子组。")
                    .translation("mirror.config.squareAspectRatio")
                    .define("squareAspectRatio", false);
            builder.pop();

            builder.push("placement");
            placementMode = builder.comment("NEAR 始终使用前表面，FAR 使用凹陷表面，BOTH 使用被点击的那一半。")
                    .translation("mirror.config.placementMode")
                    .defineEnum("placementMode", PlacementMode.BOTH);
            builder.pop();

            builder.push("enderman");
            enableEndermanObservation = builder.comment(
                            "允许透过镜子观察的玩家激怒并冻结附近的末影人。")
                    .translation("mirror.config.enableObservation")
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
        public final ForgeConfigSpec.DoubleValue reflectionFrameBudgetMs;
        public final ForgeConfigSpec.IntValue maxRecursiveViews;
        public final ForgeConfigSpec.DoubleValue recursiveCullMinPixels;
        public final ForgeConfigSpec.BooleanValue debug;

        private Client(ForgeConfigSpec.Builder builder) {
            builder.push("reflection");
            renderDistance = builder.comment("镜子反射渲染器被考虑的最大距离。")
                    .translation("mirror.config.renderDistance")
                    .defineInRange("renderDistance", 48, 1, 256);
            resolutionScale = builder.comment("反射渲染目标尺寸的倍率。")
                    // Vista 的倍率是像素密度乘数（默认每方块 8 像素），
                    // 而不是归一化的 0..1 渲染比例。
                    .translation("mirror.config.resolutionScale")
                    .defineInRange("resolutionScale", 8.0D, 1.0D, 32.0D);
            smoothSampling = builder.comment("可用时对反射纹理使用线性过滤。")
                    .translation("mirror.config.smoothSampling")
                    .define("smoothSampling", false);
            recursionMode = builder.comment("OFF 禁用嵌套镜子；SHARED 复用直接纹理；RECURSIVE 使用链隔离纹理，最多 maxRecursionDepth 层。")
                    .translation("mirror.config.recursionMode")
                    .defineEnum("recursionMode", RecursionMode.RECURSIVE);
            maxRecursionDepth = builder.comment(
                            "最大总反射次数，包含直接镜子 pass。1 表示仅直接反射；最高 8 允许更深的镜中镜反射。")
                    .translation("mirror.config.maxRecursionDepth")
                    .defineInRange("maxRecursionDepth", 2, 1, 8);
            recursiveResolutionDecay = builder.comment("每一层递归反射的分辨率倍率。")
                    .translation("mirror.config.recursiveResolutionDecay")
                    .defineInRange("recursiveResolutionDecay", 0.5D, 0.1D, 1.0D);
            reflectionFrameBudgetMs = builder.comment(
                            "每个外部帧中刷新镜子反射所允许的最大渲染线程时间（毫秒）。" +
                            "直接（depth 0）反射优先渲染并始终保持最新；预算耗尽后，剩余视图顺延到下一帧。" +
                            "0 禁用预算（每帧渲染全部）。")
                    .translation("mirror.config.reflectionFrameBudgetMs")
                    .defineInRange("reflectionFrameBudgetMs", 20.0D, 0.0D, 100.0D);
            maxRecursiveViews = builder.comment(
                            "R0 硬上限：同时保留的递归（depth > 0）镜子反射视图的最大数量。" +
                            "达到上限后，更深的镜中镜链会被截断，而不是无限增长链隔离纹理集。")
                    .translation("mirror.config.maxRecursiveViews")
                    .defineInRange("maxRecursiveViews", 64, 1, 512);
            recursiveCullMinPixels = builder.comment(
                            "渲染原理剔除：跳过在父镜子中表观宽度低于此像素数的镜子的递归" +
                            "（其自身反射将是亚像素且不可见）。0 禁用几何剔除。")
                    .translation("mirror.config.recursiveCullMinPixels")
                    .defineInRange("recursiveCullMinPixels", 1.0D, 0.0D, 64.0D);
            builder.pop();

            builder.push("debug");
            debug = builder.comment(
                            "启用镜子相关诊断日志：周期性 120 帧反射汇总与管线预热/构建消息。" +
                            "禁用可减少日志刷屏。")
                    .translation("mirror.config.debug")
                    .define("debug", false);
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
