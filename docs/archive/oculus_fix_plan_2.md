# 此前的修复

当前项目已完成主要代码链路实施，但实际验收未完全通过，不能宣称问题已彻底解决。

    已完成：
    - 镜像 pass 强制使用 VanillaRenderingPipeline，保存真实 PipelineManager/LevelRenderer pipeline。
    - 移除旧 Oculus 兼容层与静默 fallback，加入 Embeddium/Oculus 版本校验。
    - 暂停 Fabulous 后处理链并恢复主 framebuffer：src/main/java/com/mirror/client/MirrorLevelRenderer.java:106
    - 增加精确 Oculus Mixin：src/main/java/com/mirror/mixin/OculusPipelineManagerMixin.java:19、src/main/java/com/
  mirror/

    结果：

    - ./gradlew compileJava test：通过。
    - git diff --check：通过。
    - 实际 Forge + Embeddium 0.3.31 + Oculus 1.8.0 客户端：成功启动并进入世界。
    - 日志确认镜像事务中 Manager 与 LevelRenderer 均为 VanillaRenderingPipeline。
    - 但 Complementary v4.4 开启且镜像 pass 执行时，主画面仍出现黑屏；已测试 START/END 时序及 RenderSystem 状态恢复，仍
  未消除。

    因此当前代码已落地架构修复，但视觉验收矩阵仍有关键失败项。请继续探索


# 新的结论

结论先说：目前已找到一个此前未覆盖、且与 Vista/Iris 实现存在决定性差异的链路。它很可能是黑屏主因，但仍需实机验证，当前
  不能宣称已解决。

  核心问题不是 PipelineManager 和 LevelRenderer 没有切换成功，而是：

  > Oculus 1.8.0 的 Embeddium terrain shader 缓存不随当前 pipeline 对象切换。

  因此日志中两个 pipeline 都显示 VanillaRenderingPipeline，并不能证明镜像 pass 真正使用了 Vanilla terrain program。

  ## 一、发现的新根因

  当前事务在 src/main/java/com/mirror/client/OculusCompat.java:291 和 src/main/java/com/mirror/mixin/
  OculusPipelineManagerMixin.java:29 中替换了：

  - PipelineManager.pipeline
  - LevelRenderer.pipeline

  但 Oculus 的 Embeddium 兼容层还有第三份独立状态：

  IrisChunkProgramOverrides
    ├─ shadersCreated
    ├─ programs[GBUFFER_SOLID]
    ├─ programs[GBUFFER_CUTOUT]
    └─ programs[GBUFFER_TRANSLUCENT]

  主画面第一次用 Complementary 渲染后，这些字段已经缓存了光影包 terrain program。

  镜像 pass 随后发生：

  主画面 Complementary pass
      └─ 缓存 Complementary terrain program

  镜像事务开始
      ├─ PipelineManager = VanillaRenderingPipeline
      ├─ LevelRenderer = VanillaRenderingPipeline
      └─ IrisChunkProgramOverrides 仍保留 Complementary program
                                        │
                                        └─ 在镜像 TextureTarget 上继续执行
                                           Complementary terrain shader

  Oculus 的 getProgramOverride() 只有在 versionCounterForSodiumShaderReload 改变时才删除缓存；否则只要 shadersCreated ==
  true，就直接返回旧 program，完全不复核当前 pipeline 身份。Oculus 缓存实现
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/sodiumCompatibility/java/net/irisshaders/iris/compat/sodium/impl/shader_overrides/IrisChunkProgramOverrides.java#L323-L359)

  而当前事务只是替换字段，没有也不应该调用 destroyPipeline()，所以版本号不变。

  结果是：

  - bindFramebuffer() 看到当前 Vanilla pipeline，没有绑定 Oculus GBuffer。
  - getProgramOverride() 却仍返回此前缓存的 Complementary program。
  - Complementary terrain program 被放到只有单颜色附件的镜像 TextureTarget 上执行。
  - 该 program 同时操作真实 Iris pipeline 创建的 samplers、images、blend override 和 uniform 状态。
  - 镜像 pass 结束后虽然恢复了 pipeline 引用，但 shader program 内部缓存已经经历了不合法的 pipeline/FBO 配对。

  这与“世界最终合成变黑，但 GUI 仍能显示”的现象高度吻合。

  ## 二、为什么 Vista 没有遇到同样的问题

  Vista 使用的 Iris 1.8.8/Sodium 0.6 实现已经改掉了 Oculus 1.8.0 的这种缓存结构。

  Iris 1.8.8 在每一次 ShaderChunkRenderer.begin() 时都会读取当前 pipeline：

  WorldRenderingPipeline pipeline =
          Iris.getPipelineManager().getPipelineNullable();

  if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
      // 使用 Iris program
  }

  if (program == null) {
      return this.compileProgram(options); // 使用 Sodium 原生 program
  }

  也就是说：

  - 当前是 IrisRenderingPipeline：使用 shader-pack terrain program。
  - 当前是 Vista 的 VanillaRenderingPipeline：直接退回 Sodium 原生 program。

  它不是仅依赖曾经缓存过什么。Iris 1.8.8 的实现
  (https://github.com/IrisShaders/Iris/blob/25d756f9c773879fb50e59626e5dd7f5bba1348f/common/src/main/java/net/irisshaders/iris/compat/sodium/mixin/MixinShaderChunkRenderer.java#L21-L36)

  Vista 的 pipeline 切换本身与当前项目接近：Vista IrisCompat
  (https://github.com/MehVahdJukaar/cameramod/blob/9de351948c8f483090ed2b0a3b38a8e1e3824b23/common/src/main/java/net/mehvahdjukaar/vista/integration/iris/IrisCompat.java#L175-L219)。真正缺失的是
  Iris 1.8.8 已经具备、而 Oculus 1.8.0 尚未具备的“每次按当前 pipeline 选择 terrain program”语义。

  因此，Vista 的实现原理不能只移植 pipeline 替换，还必须补上这一版本差异。

  ## 三、第一阶段修复设计：阻断缓存的 Oculus program

  应增加一个精确针对 Oculus 1.8.0 的 Mixin：

  目标：
  net.irisshaders.iris.compat.sodium.impl.shader_overrides
      .IrisChunkProgramOverrides#getProgramOverride

  条件：
  OculusCompat.isMirrorPass() == true

  行为：
  直接返回 null

  null 不是自定义 fallback，而是 Oculus 已有的正式协议。Oculus 的 MixinShaderChunkRenderer 收到 null 后，会继续执行
  Embeddium 自己的 ShaderChunkRenderer.begin()。Oculus 调用方
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/sodiumCompatibility/java/net/irisshaders/iris/compat/sodium/mixin/shader_overrides/MixinShaderChunkRenderer.java#L44-L70)

  最终语义应为：

   渲染阶段      当前 pipeline               terrain program
  ━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   主画面        IrisRenderingPipeline       Complementary/Oculus program
  ────────────  ──────────────────────────  ──────────────────────────────
   镜像画面      VanillaRenderingPipeline    Embeddium 原生 program
  ────────────  ──────────────────────────  ──────────────────────────────
   恢复主画面    原 IrisRenderingPipeline    原 Complementary program

  不应采用以下方案：

  - 不要调用 destroyPipeline()。
  - 不要修改 Sodium reload 版本号。
  - 不要在每个镜像 pass 删除和重新编译 shader。
  - 不要把 WorldRenderingSettings 临时改成 Vanilla。
  - 不要清空 IrisChunkProgramOverrides.programs。

  这些方案会导致每帧 shader 重编译、区块重建，甚至让恢复后的主 pass 继续拿到空缓存。

  最终 Mixin 还应具有启动时结构校验：确认目标类确实被注入。Oculus 已经固定为 1.8.0，因此目标方法缺失时应直接失败，不应静
  默跳过后继续黑屏。

  ## 四、第二个确定存在的问题：镜面材质会被 Oculus 主动屏蔽

  当前镜面表面使用的是自定义 ShaderInstance：

  src/main/java/com/mirror/client/MirrorRenderTypes.java:31

  .setShaderState(MIRROR_SHADER)

  Oculus 1.8.0 明确规定：当 Iris 正在接管世界渲染时，既不是 ExtendedShader、也不是 FallbackShader 的自定义
  ShaderInstance 会调用：

  DepthColorStorage.disableDepthColor();

  该方法会关闭：

  - depth write
  - R/G/B/A 全部 color write

  Oculus MixinShaderInstance
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/main/java/net/irisshaders/iris/mixin/MixinShaderInstance.java#L73-L89)

  因此即使第一阶段恢复了主世界，当前 mirror_material 在 Oculus 主世界 pass 中仍可能完全不输出。这个问题不应通过“给自定义
  shader 加白名单”解决，因为该 shader：

  - 不输出 Complementary 所需的多个 GBuffer attachment。
  - 不提供 normal/specular/material 信息。
  - 不遵循 shader pack 的 framebuffer 和 blending 约定。

  强行绕过 Oculus 的保护只会产生新的跨 shader-pack 不兼容。

  ## 五、镜面材质的最终安全架构

  建议把当前“三纹理自定义材质”从世界渲染阶段移到纹理生成阶段。

  Vanilla 镜像世界捕获
          │
          ▼
  captureTarget
          │
          │ 镜面合成 shader
          │ reflection + underlay + overlay
          ▼
  surfaceTarget
          │
          │ 标准 Minecraft RenderType
          ▼
  主世界 Iris/Oculus GBuffer

  具体结构：

  1. MirrorReflectionTexture 持有两个目标：
      - captureTarget：带 depth，供 LevelRenderer.renderLevel() 渲染镜像世界。
      - surfaceTarget：不带 depth，保存最终镜面材质。

  2. 镜像世界渲染结束后，在 RenderTickEvent.END 中执行一次屏幕空间合成：
      - Sampler0：captureTarget
      - Sampler1：underlay
      - Sampler2：overlay
      - 保留现有 distortion、blur、edge wear、scratch、fade 和 edge shadow 算法。
      - 输出固定为不透明 RGBA。

  3. TextureManager 对外注册的是 surfaceTarget，不再是原始捕获 target。
  4. src/main/java/com/mirror/client/MirrorBlockEntityRenderer.java:98 不再使用自定义 mirror_material，改为 Oculus 可以
     识别并替换的标准 RenderType，例如：
      - 优先验证 RenderType.entityCutoutNoCull(surfaceTexture)。
      - 使用 LightTexture.FULL_BRIGHT，避免已经包含世界光照的反射被二次变暗。

  5. 删除旧的“在世界 pass 中直接执行三采样器自定义 ShaderInstance”路径。所有环境统一走预合成纹理，不保留 Oculus 专属材质
     fallback。

  这样既能保留现有效果，也能让主世界中的镜面只是一个普通方块实体纹理，由 Oculus 正常映射到 BLOCK_ENTITY_DIFFUSE 等
  ExtendedShader。

  ## 六、推荐的实施与验证顺序

  ### 阶段 A：证明 terrain program 泄漏

  先做仅记录、不改变行为的探针：

  - 在 getProgramOverride() 返回点记录：
      - 是否处于镜像 pass。
      - 当前 Manager pipeline 类型。
      - 返回的 program 是否非空。

  - 预期当前版本会出现：

  mirrorPass=true
  pipeline=VanillaRenderingPipeline
  returnedProgram!=null

  这将直接证明日志中的“Vanilla pipeline”并未传导到 Embeddium terrain program。

  ### 阶段 B：最小黑屏修复

  只加入镜像 pass 返回 null 的 terrain guard，其余代码不变。

  验收：

  - Complementary 开启。
  - 镜像 pass 持续执行。
  - 主世界不再变黑。
  - RenderDoc 中镜像 terrain draw 使用 Embeddium 原生 program。
  - 主 pass 仍使用 Complementary/Oculus program。

  如果这一步主世界恢复，即可确认黑屏主因。

  ### 阶段 C：隔离镜面材质问题

  临时把镜面表面改成标准单纹理 RenderType，只用于诊断：

  - 如果主世界正常且反射纹理能出现，证明剩余问题就是自定义 ShaderInstance。
  - 不把这个简化材质作为最终版本，因为它会丢失现有磨损、模糊和 overlay 效果。

  ### 阶段 D：实现双目标预合成

  完成 captureTarget → surfaceTarget 合成，并统一替换旧材质路径。

  需要确保：

  - 不发生同一纹理同时采样和写入。
  - 合成结束总是重新绑定真实 main framebuffer。
  - surfaceTarget 与 captureTarget 一起销毁。
  - 资源重载、窗口缩放和镜子 LOD 变化时两个目标尺寸同步。

  ### 阶段 E：最终验收矩阵

  至少覆盖：

  - Forge 原生，无 Embeddium/Oculus。
  - Embeddium，无 Oculus。
  - Oculus，关闭 shader pack。
  - Oculus + Complementary v4.4。
  - Oculus + 至少另一个使用不同 GBuffer 布局的 shader pack。
  - Fast/Fancy/Fabulous。
  - 单镜子、多镜子、连接镜面。
  - 递归 OFF、SHARED 和递归模式。
  - 游戏中开启/关闭/重载 shader pack。
  - 切换维度、调整窗口、资源重载、退出世界。

  通过标准：

  - 连续运行至少 300 个镜像更新帧，主世界无黑屏或闪烁。
  - 主 pass 的 Oculus pipeline/program 始终保持原对象。
  - 镜像 pass 的 terrain override 始终为 null。
  - 无 framebuffer incomplete、GL error 或 shader 重编译循环。
  - 镜面方向、UV、fade、overlay 和递归结果正确。
  - 关闭世界后两个 RenderTarget 均被释放。

  本轮没有修改任何项目文件。当前状态应定义为：已经找到一个高度可信、可直接验证的 Oculus/Embeddium 缓存缺口，以及一个确定
  存在的自定义材质不兼容；仍需按上述阶段完成实机验证后，才能认定黑屏问题解决。