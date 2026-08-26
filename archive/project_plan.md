# Mirror 模组移植规划

## 1. 目标、边界与发布约束

目标是在本仓库现有 Vista（Minecraft 1.21.1、NeoForge/Fabric、多功能模组）中，识别并提取“镜子”功能，建立一个只包含镜子及其反射渲染所需代码和资源的独立 Minecraft 1.20.1 Forge 模组，并通过 Gradle 完成开发环境、运行验证和可发布 JAR 构建。

目标功能范围：

- 可合成、放置、破坏的镜子方块及对应物品。
- 保留原镜子获取闭环：晶珠（`crystalline`）材料、Elder Guardian 掉落和镜子配方。
- 水平方向朝向、近面/远面（`FAR`）放置模式。
- 相邻镜子自动连接成矩形，并由主方块实体保存连接尺寸。
- 基于玩家视点镜像的实时反射，而不是静态贴图。
- 连接镜面组的离屏渲染、屏幕尺寸匹配、帧缓存缓存、反射递归控制、距离 LOD 和首帧淡入。
- 镜面材质的底图、磨损/划痕、扰动、模糊、边缘阴影和覆盖层着色。
- 必要的客户端渲染 Mixins、资源注册、数据包、Forge 元数据和 Gradle 构建。

明确排除：电视、取景器、录像带/媒体、波门、直播广播、远端区块同步、Web/FFmpeg、图片带 UI、Create/Camera Mod/Exposure 等兼容层、电视专用电力系统，以及只为这些功能存在的网络包、服务端区块跟踪和配置项。与镜子没有直接运行时依赖的类不得复制到新模组。

### 1.1 Mod ID 决策

用户给出的名称为 `Mirror`。Forge 1.20.1 的 `modId` 规则要求小写字母开头，后续只能使用小写字母、数字和下划线；字面量 `Mirror` 不能作为技术 ID，否则 `mods.toml` 会在加载时被拒绝。因此本项目锁定技术 ID `mirror`，显示名 `displayName=Mirror`，产物名使用 `Mirror-<version>.jar`。不得同时提供大小写两个 ID，也不做旧 `vista` ID 的兼容别名。若交付方坚持技术 ID 必须逐字为 `Mirror`，该要求与 Forge 1.20.1 不兼容，必须在实施前变更需求，而不能用兼容层规避校验。

## 2. 现有镜子实现的完整理解

### 2.1 注册与游戏对象

当前注册入口在 `common/.../VistaMod.java`：

- `MIRROR`：`RegHelper.registerBlockWithItem` 注册 `MirrorBlock` 和稀有度为 RARE 的 `BlockItem`。
- `MIRROR_TILE`：注册 `MirrorBlockEntity`，绑定到镜子方块。
- `CRYSTALLINE`：镜子配方使用的稀有材料。它是镜子原始获取闭环的直接依赖，因此保留其物品、纹理和 Elder Guardian 掉落，但删除 Vista 中其他宝箱、苦力怕和末影人掉落注入。1.20.1 没有 1.21 的 `ENCHANTMENT_GLINT_OVERRIDE` 数据组件，应以只覆盖 `isFoil(ItemStack)` 的 `CrystallineItem` 保持附魔光效。
- 创造模式标签、配方条件和动态配置开关在 Vista 主类中；新模组应改成独立 Forge 注册/事件，不带入 Vista 的全局注册器和功能开关。

新模组的最小注册集合应为：`MirrorBlock`、`MirrorBlockEntity`、镜子 `BlockItem`、`CrystallineItem`、`BlockEntityType`、只注入 Elder Guardian 的全局掉落修改器、客户端 BlockEntityRenderer、客户端 Shader/RenderType，以及 Forge 事件订阅器。

### 2.2 方块状态、碰撞形状和连接行为

`MirrorBlock` 的职责如下：

- 继承 `HorizontalDirectionalBlock`，状态属性为 `FACING`、`FAR` 和 `CONNECTION`。
- `FACING` 指向镜面背向的方向；放置时使用玩家水平朝向的反向。
- `FAR=false` 使用靠近观察者的 2 像素厚模型，`FAR=true` 使用方块后侧 `z=14..16` 的凹进模型，反射平面相对前表面后退 `14/16` 方块。
- `getShape` 针对四个水平方向旋转近面/远面碰撞形状；`surfaceRecession` 给渲染和反射计算提供平面后退量。
- `CONNECTION` 使用 Vista 的 `ConnectionType` 八邻接/边界状态；连接只匹配相同朝向和相同 `FAR`，避免两个不共面的镜组被合并。
- `IConnectedBlock`/`AbstractGridAccess` 负责扫描邻接块、限制最大宽高、可选正方形比例、确定主方块位置和更新每块的连接状态。主方块位于组的底部右侧（渲染和反射计算依赖这一约定）。
- 放置后自动扩大连接；破坏、替换时缩小并重新选主方块。按住潜行放置时跳过自动合并。
- `MirrorGridAccess` 在网格重算前把旧主方块尺寸恢复为 `1x1`，在新主方块应用后写入实际矩形尺寸，并发送 BlockEntity 更新，防止客户端继续使用旧帧缓存大小。

1.20.1 移植时应先确认 Moonlight 1.20 API 是否有相同的 `Vec2i`、`Rect2D` 和 `MthUtils`。Vista 自有的连接层不能整组照搬：`GridTile`/`GridAccessor` 包含电视 `PowerState`，`AbstractGridAccess` 还调用平台 capability 失效逻辑。新模组应提取矩形查找算法和连接状态，但把 tile 精简为 `connectionType + hasBlockEntity`，删除 power、`setPower`、`VistaPlatStuff.invalidateBlockCapabilities` 等分支；若 Moonlight 的数学类型也不可用，再在 Mirror 包内实现所需的最小二维坐标/矩形扫描器。

### 2.3 方块实体、持久化与服务端逻辑

`MirrorBlockEntity` 保存：

- 稳定的随机 UUID，作为反射纹理缓存和递归链的键。
- 连接组宽高，初始为 `1x1`，NBT 键为 `ConnectionWidth`、`ConnectionHeight`。
- 服务器端每 10 tick（按方块坐标错峰）运行一次末影人观察逻辑；这是镜子与 Enderman 观察互动的可选附加功能，不能阻塞核心反射。
- 屏幕像素尺寸：`width*16-2`、`height*16-2`，对应固定每侧 1 像素边框。
- 屏幕矩形：以主方块为基准、沿镜面右轴和世界 Y 轴扩展，得到中心、法线、宽高，用于反射视点和视线检测。
- `setChanged` 在服务端发送 BlockEntity 更新包，让客户端及时调整纹理尺寸。

如果首个移植版本不实现末影人互动，应移除 `MirrorEndermanObservationController` 字段、服务端 ticker 和相关 Mixins，而不是保留无效类；若保留，需同时提取 `ScreenRect`、`AbstractEndermanObservationController`、`MirrorEndermanObservationController`、`EndermanLookResult` 和 Moonlight `FakePlayerManager` 依赖，并单独验收。

### 2.4 反射几何与镜面绘制

`MirrorReflection.compute` 对观察者位置 `eye` 和镜面平面 `(planePoint, normal)` 计算：

1. 有符号距离 `d=(eye-planePoint)·normal`。
2. 反射眼睛 `eye' = eye - 2*d*normal`。
3. 只有 `d>0`（观察者在镜面前方）才绘制。

`MirrorBlockEntityRenderer` 的当前流程：

1. 按 BlockEntityRenderer 可见距离和镜面平面做 LOD/背面裁剪。
2. 获取主摄像机位置；普通世界直接使用，子世界则先做空间变换（独立模组第一版排除 Sable 子世界路径）。
3. 根据当前渲染栈深度选择直接纹理或嵌套纹理。
4. 记录包含视图 bob 的眼睛位置，避免玩家移动时反射画面与镜面四边框发生抖动。
5. 从反射纹理取出动态纹理位置，按 `FACING`、`FAR`、连接组尺寸和固定 1 像素内缩绘制四边形。
6. 使用天空亮度打包 LightTexture，使用 `POLYGON_OFFSET_LAYERING`；在快速图形或嵌套离屏渲染下再增加 `0.01` 方块手动偏移，避免 z-fighting。

### 2.5 离屏反射、缓存和递归

反射不是在 BlockEntityRenderer 内同步调用世界渲染，而是分两阶段：

- `MirrorTextureManager` 维护 UUID/连接尺寸/LOD/递归链到动态纹理的缓存，并把刷新请求放入 `PENDING` 队列。队列在客户端渲染帧结束处理，避免在当前 BufferSource 遍历中重入。
- `MirrorReflectionTexture` 基于双缓冲 `PerspectiveTexture`，在刷新时：
  - 计算连接组的实际镜面平面和四角。
  - 计算反射摄像机位置、相机朝向和非对称 frustum；近裁剪面放在镜面平面，远裁剪面为 1000。
  - 以镜面前方一方块作为区块可见性 BFS 起点，避免 flush 镜面把背后的墙错误当成可见起点。
  - 将当前渲染距离按递归深度指数缩减。
  - 调用独立的 `VistaLevelRenderer.render`，把世界渲染到纹理的写入目标，再交换读写目标。
  - 首次有效渲染后记录时间，材质通过 `Fade` 在约 300 ms 内淡入，避免白色未初始化帧闪现。
- `MirrorTextureManager` 的直接视图支持距离 LOD（24 格内原分辨率、24~40 半分辨率、40 以外四分之一）；递归层只按深度衰减分辨率。
- 递归模式：`OFF` 不绘制嵌套镜子；`SHARED` 复用直接纹理、成本低但视差不完全正确；`RECURSIVE` 以父镜 UUID 链为键、受最大深度限制，视差正确但成本随深度和镜子数量增长。规划默认先实现 `OFF`/`SHARED`，待基础版本稳定后再实现 `RECURSIVE`。

### 2.6 世界二次渲染所需的共享基础设施

当前 `VistaLevelRenderer` 并非镜子专用，包含电视/取景器直播和远端区块功能。镜子独立模组只保留以下核心：

- 保存/恢复 `Minecraft.mainRenderTarget`、`GameRenderer.mainCamera`、渲染距离、后处理状态、LevelRenderer 相机/可见区块状态和 RenderSystem 全局矩阵/纹理/雾/Shader 状态。
- 创建每个动态纹理专属的 `LevelRendererFrustumState`/区块遮挡图，支持递归调用时的栈式状态恢复。
- 设置反射摄像机的自定义 projection，调用 1.20.1 `LevelRenderer.renderLevel`，在渲染返回后恢复主场景。
- 在二次渲染上下文中标记 `isRenderingMirrorReflection`，供实体过滤、背面裁剪和 z-fighting 修正使用。
- 处理 Fabulous/透明度链、深度缓冲清理、Frustum 不强制包围摄像机立方体等会破坏非对称 frustum 的路径。

应从 `VistaLevelRenderer` 删除所有 `ViewFinderBlockEntity`、`LiveFeedTexture`、Sable、远端区块/Pin、电视分支和平台兼容分支，得到 `MirrorLevelRenderer`。不要把整个 VistaLevelRenderer 原样复制后再以条件判断隐藏无关功能。

### 2.7 客户端 Mixins 和实体过滤

镜子离屏渲染至少需要逐个验证以下当前 Mixins 在 1.20.1 Forge 的目标方法和参数：

- `GameRendererMixin`：在主渲染建立 bob 矩阵后捕获 bob 偏移；二次渲染期间禁止加载/替换主后处理链。
- `LevelRendererMixin`：替换 `setupRender` 以使用镜像摄像机/非对称 frustum；在 `renderEntity` 中屏蔽镜面黑名单实体；同步区块编译结果到镜面专属遮挡图；禁止二次渲染时实体轮廓和错误的本地玩家摄像机分支。
- `FrustumMixin`：二次渲染时跳过 `offsetToFullyIncludeCameraCube`，否则相机立方体偏移会破坏镜面精确裁剪。
- `MinecraftMixin`：二次渲染时关闭 Fabulous 透明度路径（若 1.20.1 的渲染目标状态仍需要）。
- `SectionOcclusionGraphMixin`/`RenderSectionMixin`/`ViewAreaMixin`：仅当镜面专属遮挡图无法使用现有 Vanilla 缓存时保留；删除 `CLIENT_EXTRA_CHUNK_VIEW_DATA` 和 pinned section 逻辑。
- `ItemRendererMixin`、`CameraMixin`、键鼠/玩家/取景器 Mixins：与镜子无直接依赖，排除。

实体隐藏只读取新模组的 `mirror:cant_see_through_mirror` 标签。原 Vista 标签当前只列出可选的 `#vampirism:vampire`，新模组不应硬依赖 Vampirism；需要兼容时再通过 Forge `ModList` 检测和独立可选 Mixins 实现。

### 2.8 镜面材质 Shader

资源 `mirror_material.json/.vsh/.fsh` 是镜面表面最终合成：

- Sampler0：动态反射帧缓存。
- Sampler1：`block/mirror/underlay.png` 底材。
- Sampler2：Vanilla lightmap。
- Sampler3：`block/mirror/overlay.png` 覆盖层。
- `Tiles`：连接组宽高，用于每方块纹理重复和世界一致的噪声频率。
- `Fade`：首次渲染淡入。
- 片元逻辑包含扰动、5 次采样的粗糙模糊、边缘磨损、划痕、反射率、覆盖层 alpha 合成和右/下边缘阴影。

迁移时保留 GLSL 算法和四个采样槽，但按 1.20.1 ShaderInstance/RenderType JSON 格式重检 uniform 名称、`NEW_ENTITY` 顶点格式、lightmap 纹理单元和 `MultiTextureStateShard` 行为。不要把 `camera_view`、`static_noise`、电视后处理 shader 带入。

## 3. 提取清单

### 3.1 第一阶段必须提取/重写

Java：

- `MirrorMod`（新主入口，替代 `VistaMod`）。
- `common.mirror.MirrorBlock`、`MirrorBlockEntity`。
- 连接系统最小闭包：`ConnectionType`、`IConnectedBlock`、`AbstractGridAccess`、`GridTile`、`GridAccessor`、`RectFinder`、`RectSelection`；或以本地矩形扫描器替换，需行为测试证明等价。
- `common.ScreenRect`（若保留末影人/屏幕命中）。
- `client.MirrorReflection`。
- `client.textures.RenderTargetDynamicTexture`/`PerspectiveTexture`/`MirrorReflectionTexture`/`MirrorTextureManager`。优先复用目标 Moonlight 1.20 提供的动态纹理实现，只有 API 不足时才提取 Vista 自有实现。
- `client.renderer.MirrorBlockEntityRenderer`、`MirrorLevelRenderer`、`LevelRendererFrustumState`、`FeedSectionOcclusionGraph`（重命名后只保留镜子路径）、`RenderSystemState`、`FabulousDeferredState`、`SceneCameraSetup`。
- `client.MirrorRenderTypes` 中仅 `mirrorMaterial` 和其 shader 注册辅助。
- 仅必要的 Mixins 及 `MirrorMixinPlugin` 的镜子判断。

资源：

- 镜子方块状态、近面/远面全部 32 个方块模型、物品模型。
- `textures/item/mirror.png`、`textures/block/mirror/**`（front/back 分片、side、underlay、overlay）。
- `shaders/core/mirror_material.{json,vsh,fsh}`。
- 镜子英文/简体中文语言键、`recipe/mirror.json`、`advancement/recipes/decorations/mirror.json`、`loot_table/blocks/mirror.json`。
- `tags/entity_type/cant_see_through_mirror.json`。
- 晶珠物品模型、纹理、语言键，以及只面向 Elder Guardian 的 Forge Global Loot Modifier 数据和 codec 注册。

### 3.2 条件提取

- `AbstractEndermanObservationController`、`MirrorEndermanObservationController`、`EndermanLookResult`、`FakePlayerManager`：仅在需求明确保留“透过镜子看末影人”时加入。
- `GazeRedirect` 和 `EnderMan/EnderWatcher` Mixins：其实现同时依赖 TV、ViewFinder、BroadcastManager，不能直接复制；只有先设计“镜子单独反射视线”的新 API 并删去 TV 分支后才可作为独立扩展。
- Iris/Veil/Sodium/Simple Clouds 等兼容：第一版默认不带；每个兼容只能在目标 Forge 1.20.1 有明确依赖和可复现问题后单独加入。

### 3.3 明确不提取

`TVBlock*`、`ViewFinder*`、`LiveFeed*`、`Broadcast*`、`PictureTape*`、`Cassette*`、`WaveGate*`、`ServerCameraChunkManager`、`ExtraChunkViewData`、`PinnedChunks`、FFmpeg/Web、所有电视渲染和取景器 UI、平台的 Fabric/NeoForge 实现、Create/Camera Mod/Exposure/Refurbished Furniture/Watermedia 集成，以及与这些模块绑定的 access widener 条目。

## 4. 1.20.1 Forge 工程方案

仓库已有远端分支 `origin/1.20.1`，其 Forge 基线为 Minecraft 1.20.1、Forge 47.x、Java 17 和 Moonlight 1.20.x，但该分支没有 `MirrorBlock`、`MirrorBlockEntity`、镜面 renderer、shader 或镜子资源。实施时只把它作为 1.20.1 Gradle 坐标、`LevelRenderer` 方法签名、Access Transformer 和事件注册方式的参考；镜子功能必须从当前 1.21.1 源码按本规划移植，不能把旧分支误当成可直接 cherry-pick 的镜子版本。

### 4.1 工程骨架

创建独立 Gradle 工程（建议单 `forge` source set，避免再次引入 Fabric/NeoForge 多平台层）：

```text
Mirror/
  build.gradle
  settings.gradle
  gradle.properties
  gradlew / gradlew.bat
  src/main/java/<package>/MirrorMod.java
  src/main/java/<package>/common/...
  src/main/java/<package>/client/...
  src/main/resources/META-INF/mods.toml
  src/main/resources/<id>.mixins.json
  src/main/resources/pack.mcmeta
  src/main/resources/assets/<id>/...
  src/main/resources/data/<id>/...
```

使用 Forge 1.20.1（建议当前维护的 47.x 稳定构建）、Java 17、官方 Mojang mappings 加 Parchment 1.20.1 对应版本。`minecraft_version=1.20.1`、`loader_version_range=[47,)`，具体 Forge 版本在锁定依赖后写死，保证开发与 CI 一致。

构建采用单模块 ForgeGradle 6，不再沿用当前仓库的 Architectury 多模块插件。资源和数据包的 `pack.mcmeta` 对 Minecraft 1.20.1 使用 pack format 15；如果 ForgeGradle 数据生成输出独立 client/data 格式，则以目标 1.20.1 运行时日志为最终校验。

### 4.2 依赖原则

- 必选：Forge、Minecraft、目标版本可用的 Moonlight/Selene 1.20.x（若连接系统和动态纹理确实使用其 API）。
- 可选：MixinExtras Forge 版本，仅在 Mixins 表达式/Wrap 注入需要时加入。
- 不引入 Fabric Loader、Architectury、NeoForge API、MixinSquared、Sable Companion、Candlelight、FFmpeg 或 Vista 本体。
- 依赖坐标、许可证和传递依赖在锁版本时逐一核对；不要假设 1.21.1 的 Moonlight API 与 1.20.x 二进制兼容。

### 4.3 Forge 注册与事件

- 使用 `DeferredRegister` 注册 `BLOCKS`、`ITEMS`、`BLOCK_ENTITY_TYPES`，主类通过 `@Mod(<id>)` 注册到 mod event bus。
- 客户端在 `FMLClientSetupEvent`/`EntityRenderersEvent.RegisterRenderers` 注册 BlockEntityRenderer；在 `RegisterShadersEvent` 注册镜面 shader；在客户端 tick/render frame 事件末尾调用待处理反射刷新。
- 使用 Forge `RegisterClientReloadListenersEvent` 或等价事件清理资源；在 `ClientPlayerNetworkEvent.LoggingOut` 和 Level unload 清空纹理缓存、渲染栈、动态纹理和临时帧缓存。
- 创造标签通过 `BuildCreativeModeTabContentsEvent` 添加镜子和晶珠；不复制 Vista 的动态资源提供器和全局配置屏幕。
- 用 Forge 1.20.1 Global Loot Modifier 只向 `minecraft:entities/elder_guardian` 增加晶珠掉落。不得复制 `ModLootOverrides` 的宝箱、苦力怕、末影人或配置开关分支。
- 使用 `SimpleCondition`/Forge 配方条件仅在确实保留可开关配置时加入；最小版本直接提供配方，不带 Vista 的 `flag` 条件系统。

### 4.4 配置

移植为 Forge `ForgeConfigSpec`（COMMON/CLIENT 分离）或确认 Moonlight 1.20 配置 API 后使用其原生方式。仅保留：

- 最大连接镜子尺寸。
- 是否强制正方形连接组。
- 近面/远面/按点击深度放置模式。
- 镜面渲染距离、分辨率倍率、距离 LOD。
- 递归模式、最大递归深度、递归分辨率/渲染距离衰减。
- 镜面平滑采样开关。

删除 TV、取景器、录像带、波门、电力、远程区块和 FFmpeg 配置；每个配置项都要有默认值、范围、注释，并验证客户端/服务端同步边界。

## 5. 分阶段实施工作链

### 阶段 0：基线与法律/版本确认

1. 固定源提交（当前 `master` 的镜子代码）和目标 Forge/Minecraft/Moonlight 版本。
2. 按 Forge 1.20.1 规则使用 `mirror` 技术 ID 和 `Mirror` 显示名，并在发布说明中记录该约束。
3. 阅读源项目许可证及 Moonlight 许可证，记录新模组需要携带的许可证、NOTICE 和作者归属；不复制与镜子无关的 Vista 版权说明。
4. 建立源符号到新包符号的映射表，标记“直接复制、按 1.20 API 改写、删除、待决策”四种状态。
5. 在空白 Forge 工程运行 `gradlew tasks` 和 `gradlew runClient`，确保 JDK、Gradle、Forge MDK 环境可用。
6. 明确不提供 `vista:*` 方块 ID、资源路径、NBT 或存档迁移；旧 Vista 世界中的镜子不会自动转换为新模组镜子。

### 阶段 1：最小可玩镜子（先不做反射）

1. 创建独立 Gradle 工程和 `mods.toml`，完成合法 mod ID、Java 17、Forge 47.x 元数据。
2. 注册 MirrorBlock、BlockItem、BlockEntityType；移植 `FACING/FAR/CONNECTION` 状态、碰撞箱、放置逻辑和连接矩形算法。
3. 提取近面/远面模型、方块状态、物品模型、纹理、语言、掉落表和配方。
4. 编写连接算法单元测试/游戏测试：单块、横向/纵向拼接、L 形限制、最大尺寸、不同朝向不连接、近面/远面不连接、破坏主块后重选主块、NBT 保存/同步。
5. 在客户端验证四个朝向、近/远模型、遮挡/碰撞/破坏掉落和创造标签。

阶段 1 完成后，必须已经是一个不依赖 Vista、可在 1.20.1 Forge 加载和游玩的独立镜子模组。

### 阶段 2：单块直接反射 MVP

1. 先不实现连接组和递归，只让 `1x1` 镜面得到一个动态纹理。
2. 移植双缓冲动态纹理；明确纹理注册、释放、窗口尺寸变化和世界卸载生命周期。
3. 实现 `MirrorReflection.compute`、反射摄像机、对称/非对称 projection 和主渲染状态保存恢复。
4. 实现 `MirrorLevelRenderer.render` 的最小主循环，确保 LevelRenderer 可以渲染到离屏 RenderTarget 后继续正常渲染主画面。
5. 加入最小 Mixins（GameRenderer、LevelRenderer、Frustum、必要的 Minecraft/遮挡图注入），逐个启动游戏确认目标方法只注入一次。
6. 使用简单实体、方块、透明水和天气场景验证反射视差、上下方向、背面不显示、镜面前方近裁剪和主画面无状态污染。

### 阶段 3：连接组、材质和性能控制

1. 将纹理尺寸改为 `(16*w-2) x (16*h-2)`，实现主方块矩形四角和 1 像素内缩绘制。
2. 提取 `MirrorRenderTypes.mirrorMaterial`、四纹理绑定和 uniform 设置；完成 underlay/overlay/Lightmap 纹理单元核对。
3. 移植镜面 GLSL，逐项对比 1.21.1 截图：底材、反射混合、边缘磨损、划痕、模糊、阴影和淡入。
4. 加入距离 LOD、分辨率倍率、刷新队列；确认同一帧多个镜子不会重入或互相覆盖。
5. 测量帧时间和显存：1 个、4 个、8x8 镜组在近/中/远距离的刷新成本，设置合理默认值和日志警告。

### 阶段 4：递归和可选附加行为

1. 默认 `OFF`，再实现 `SHARED`；使用父镜 UUID 链测试两面镜子相对放置时的最大递归深度、缓存键隔离和递归分辨率衰减。
2. 只有性能和状态恢复稳定后才实现 `RECURSIVE`，并设置硬上限；禁止无限递归和每帧创建无限纹理。
3. 若产品要求，最后移植末影人观察互动。为它建立独立功能开关和测试，不让末影人类加载失败阻止纯反射客户端启动。
4. 按实际用户报告再评估 Iris/Embeddium/Veil 兼容；每种兼容单独模块化，不把可选 mod 的类直接引用到必装路径。

### 阶段 5：清理、发布和构建

1. 用 `rg` 检查新工程中不存在 `vista`、`TV`、`ViewFinder`、`Cassette`、`WaveGate`、`Sable`、`FFmpeg` 等无关引用（许可证/迁移说明中的历史文字除外）。
2. 检查资源命名空间、语言键、模型 parent、纹理路径、Shader sampler 和 Forge 生成资源是否全部使用新 ID。
3. 配置 `processResources` 替换 `mods.toml` 变量；技术 ID 固定为 `mirror`，设置 `archivesBaseName`/`base.archivesName` 生成 `Mirror-<version>.jar`；生成 sources JAR 和 reproducible JAR。
4. 配置 Gradle Wrapper、CI（Windows/Linux 至少各一次）、缓存和 `--no-daemon` 构建；禁止依赖本地 `mods/` 才能编译。
5. 执行清洁构建和运行验证：

   ```text
   ./gradlew clean build
   ./gradlew runClient
   ./gradlew runServer
   ```

6. 用全新 1.20.1 Forge 实例只放入 Mirror JAR 和必需 Moonlight JAR，验证启动、创建世界、多人客户端连接、存档重载和升级/降级失败信息。
7. 发布 JAR、sources JAR、许可证/NOTICE、配置说明、已知兼容性和性能建议；不发布包含 Vista 其他内容的 fat JAR。

## 6. 1.20.1 API 迁移检查表

- `ResourceLocation.fromNamespaceAndPath`、数据组件、`MapCodec`、Block 注册和 BlockEntity 生命周期：逐个对照 1.20.1 mappings，不能直接套用 1.21.1 签名。
- `BlockEntity#loadAdditional/saveAdditional/getUpdateTag` 在 1.20.1 使用的 `HolderLookup.Provider` 签名可能不同；按目标 mappings 改写并测试客户端同步。
- `LevelRenderer.renderLevel`、`setupRender`、`prepareCullFrustum`、`SectionOcclusionGraph`、`ViewArea` 字段在 1.20.1 与 1.21.1 差异很大；先用 Forge 运行时/反编译确认 descriptor，再写 Mixin。
- 1.20.1 的动态纹理/RenderTarget、ShaderInstance、`MultiTextureStateShard`、`RenderType.CompositeState` API 与当前 1.21.1 有差异；优先采用 Moonlight 1.20 已提供的抽象。
- 1.20.1 Forge 没有 NeoForge 的事件名和注册 API；所有 `net.neoforged.*` 必须删除并替换为 `net.minecraftforge.*`。
- MixinExtras 的注入器版本、目标方法 ordinal 和 `require` 值必须在 1.20.1 客户端逐项验证；不使用宽泛 `require=0` 掩盖失效注入。
- Access Transformer/Access Widener 不能直接复制。只为实际使用的 1.20.1 私有字段/方法生成 Forge `accesstransformer.cfg`，并将其纳入 JAR。

## 7. 测试与验收标准

### 7.1 功能验收

- Forge 1.20.1 客户端和专用服务端加载成功；没有 Vista、Fabric、NeoForge 类缺失。
- 镜子可合成、放置、破坏、掉落、保存和重载；所有朝向、近/远模式和连接状态正确。
- 连接组在最大尺寸内正确合并，镜面画面没有拉伸、错位或边框重复；主块丢失后客户端/服务端尺寸一致。
- 反射视点随玩家移动产生正确视差，玩家在镜面背面时不显示，镜面平面附近不会穿墙或显示观察者自身不应可见的块。
- 主场景渲染、GUI、天气、透明方块、实体、Fabulous/快速图形在反射前后状态一致；退出世界和窗口调整不会泄漏纹理或崩溃。
- 首帧没有白闪；无效/未完成纹理不绘制镜面四边形。
- 递归关闭时不渲染嵌套镜子；共享/递归模式的深度和性能上限有效。

### 7.2 兼容性验收

- 纯 Forge + Moonlight 基线。
- 无 shader、快速/高品质/Fabulous 三种图形设置。
- 可选地测试 Embeddium、Iris/Oculus、Veil；每个兼容失败必须有明确日志和降级行为，不得影响无该 mod 的启动。
- 单人、专用服务端、多客户端同时查看同一镜组；确认 UUID/NBT 和服务端方块状态不会因客户端刷新改变。

### 7.3 工程验收

- `gradlew clean build` 在干净工作区成功，生成主 JAR、sources JAR，且 JAR 内无无关 Vista 包/资源。
- `mods.toml` 的技术 ID 为 `mirror`，版本、依赖范围、许可证和显示名正确；README/发布元数据明确展示名为 `Mirror`。
- 对连接算法、反射数学、缓存键/递归链、生命周期清理至少有自动化测试或可重复的游戏测试步骤。
- 性能测试记录近中远距离和多镜组的帧时间/显存，默认配置不会在普通视距下无限生成 RenderTarget。

## 8. 风险、决策点与回滚策略

| 风险 | 影响 | 处理 |
|---|---|---|
| 用户要求字面量 `Mirror` 技术 ID 与 Forge 规则冲突 | 无法加载 | 固定 `modId=mirror`、`displayName=Mirror`、产物名 `Mirror-<version>.jar`，不做双 ID 兼容 |
| 1.20.1 LevelRenderer/遮挡图私有 API 差异 | Mixin 失效或崩溃 | 先建立 API 对照表和最小 1x1 MVP；每个注入点启动时验证 |
| 二次渲染污染 RenderSystem/LevelRenderer 状态 | 主场景闪烁、崩溃、区块消失 | 栈式 capture/restore、异常 finally、F3+A/切维度/窗口调整回归测试 |
| 多镜/递归造成 GPU 和显存爆炸 | 严重卡顿 | 默认关闭递归、距离 LOD、分辨率和深度硬上限；缓存按 UUID/尺寸复用 |
| Moonlight 1.20 API 不足或版本不一致 | 编译失败/运行时错误 | 锁定具体 Maven/CurseForge 构件，必要时提取最小动态纹理和连接代码，不引入整套 Vista |
| Shader/纹理单元在 1.20.1 不一致 | 镜面白屏/材质错误 | 以 RenderDoc/Shader 日志和固定截图逐 sampler 验证，保留无 shader 的纯反射降级 | 
| 可选模组类被必装路径加载 | 启动崩溃 | `ModList`/可选 Mixin 隔离，基线构建不声明这些依赖 |
| 源许可证/资产归属遗漏 | 发布合规风险 | 阶段 0 建立许可证清单，JAR 和仓库携带 NOTICE；未确认的外部资源不复制 |

任何阶段如果镜面核心被复杂兼容阻塞，先回退到已通过验收的上一阶段（方块、1x1 反射、连接组），而不是引入电视/取景器代码或保留无法工作的兼容层。规划遵循“先端到端可用，再逐项扩展”的顺序。
