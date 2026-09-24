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
            placementMode = builder.comment(
                            "镜片位置：NEAR 靠镜面朝向的一侧，FAR 靠相反一侧，BOTH 使用被点击的那一半。",
                            "潜行放置按玩家视线选择六向朝向；水平镜子在 BOTH 模式下按点击高度选择上下位置。",
                            "非潜行点击镜子薄边时继承原镜子的朝向和位置。")
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
            resolutionScale = builder.comment(
                            "直接反射按主视口中镜面的裁剪投影面积分配像素：8 为约 1:1 像素密度，12 为 1.5 倍。",
                            "目标尺寸使用稳定档位；不再按每方块模型像素或 24/40 格距离分档。")
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
            recursiveResolutionDecay = builder.comment(
                            "子反射相对父捕获中实际投影像素的线性密度倍率，从第一层镜中镜开始每层应用一次。")
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
                            "跳过父捕获有效镜面区域中投影面积小于此值平方的递归镜面。",
                            "单位是投影面积的等效边长像素。0 禁用面积阈值，视口外仍不渲染。")
                    .translation("mirror.config.recursiveCullMinPixels")
                    .defineInRange("recursiveCullMinPixels", 1.0D, 0.0D, 64.0D);
            builder.pop();

            builder.push("debug");
            debug = builder.comment(
                            "启用逐帧镜面需求、拒绝原因、捕获尺寸、CPU 阶段计时及延迟 GPU 计时日志，" +
                            "每 120 帧汇总 p50/p95/p99。详细诊断会增加日志开销，测量实际帧率时应关闭。")
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
