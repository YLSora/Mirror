  ## 结论

  黑屏的根因是：镜子渲染递归调用 LevelRenderer.renderLevel 时，Oculus 的真实 IrisRenderingPipeline 曾经把镜子的
  TextureTarget 当成 Minecraft 主渲染目标，修改了 Oculus 全局 gbuffer 的尺寸和深度附件；返回真实主目标时，Oculus 1.8.0
  又没有可靠识别“RenderTarget 实例已经变了”，最终让主画面的 gbuffer 保留错误的深度纹理附件，下一帧合成输出全黑。

  这不是普通的 OpenGL 状态遗漏，也不是 Embeddium 剔除问题。MirrorRenderState 即使把 viewport、blend、depth、shader、矩阵
  全部恢复，也无法恢复 Oculus RenderTargets 内部持有的 framebuffer attachment 拓扑。

  最可靠的修复方向应直接采用 Vista 的默认策略：

  > 主世界继续使用 Oculus 光影；镜子的离屏世界 pass 强制使用 VanillaRenderingPipeline，绝不允许真实
  > IrisRenderingPipeline 接触镜子 RenderTarget。

  这会让镜中世界使用无光影的原版/Embeddium 渲染，但可以彻底隔离主光影管线，也是 Vista 当前默认的安全行为。

  ———

  ## 一、完整故障链

  当前镜面纹理在 /E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorReflectionTexture.java:31 中创建独立
  TextureTarget，随后在 /E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorLevelRenderer.java:106 中：

  1. 保存真实 mainRenderTarget。
  2. 把 Minecraft.mainRenderTarget 临时改成镜子目标。
  3. 再次调用 LevelRenderer.renderLevel。
  4. 最后换回真实主目标。

  问题发生在第 3 步。

  Oculus 1.8.0 的 IrisRenderingPipeline.beginLevelRendering() 每次都读取当前 Minecraft.getMainRenderTarget()，然后调用：

  renderTargets.resizeIfNeeded(
      mainTarget.iris$getDepthBufferVersion(),
      mainTarget.getDepthTextureId(),
      mainTarget.width,
      mainTarget.height,
      ...
  );

  可在 Oculus IrisRenderingPipeline
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/main/java/net/irisshaders/iris/pipeline/IrisRenderingPipeline.java#L900-L934)
  中确认。

  但是 RenderTargets.resizeIfNeeded
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/main/java/net/irisshaders/iris/targets/RenderTargets.java#L147-L185)
  只有在以下条件成立时才更新深度纹理：

  cachedDepthBufferVersion != newDepthBufferVersion

  它不比较：

  - RenderTarget 对象身份；
  - 新旧 depth texture ID；
  - framebuffer attachment 当前实际指向的纹理。

  而 Oculus 注入到 RenderTarget 的两个版本号只在 destroyBuffers() 时递增；新目标的版本从相同初始值开始，见
  MixinRenderTarget
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/main/java/net/irisshaders/iris/mixin/MixinRenderTarget.java#L17-L40)。

  因此会出现：

  真实主目标
    ↓
  Iris gbuffer 绑定真实主深度纹理
    ↓
  切换到镜子 TextureTarget
    ↓
  Iris gbuffer 改成镜子尺寸/镜子深度附件
    ↓
  切回真实主目标
    ↓
  新旧 depthBufferVersion 恰好相同
    ↓
  Oculus 不重新挂接真实主深度纹理
    ↓
  主尺寸颜色附件 + 镜子深度附件
    ↓
  Framebuffer 不一致，主画面最终合成为黑色

  Oculus 在 resize 后不会重新验证所有已有 framebuffer 的完整性，所以日志中通常不会出现异常或崩溃，只表现为全黑。

  ———

  ## 二、日志结论

  日志确认实际环境为：

  - Embeddium 0.3.31+mc1.20.1：/E:/Minecraft/others/Mirror/run/logs/debug.log:80
  - Oculus 1.8.0：/E:/Minecraft/others/Mirror/run/logs/debug.log:82
  - Complementary Shaders v4.4：/E:/Minecraft/others/Mirror/run/logs/debug.log:1605

  Oculus 对 RenderTarget 的监听 Mixin 已生效：

  - MixinRenderTarget
  - state_tracking.MixinRenderTarget

  见 /E:/Minecraft/others/Mirror/run/logs/debug.log:477。

  当前未提交的 OculusPipelineManagerMixin 也被加载了，见 /E:/Minecraft/others/Mirror/run/logs/debug.log:510。但这只能证
  明 Mixin 类被合并，不能证明每次镜面 pass 都成功返回了 VanillaRenderingPipeline。

  目前所有关键兼容点都用了：

  - require = 0
  - 反射失败后静默忽略
  - modifiedPipeline() 失败后返回 null

  所以任意一次构造、字段查找或注入点失效，镜子渲染都会无日志地退回真实 Iris 管线。只需有一次真实管线接触镜子目标，就足以
  污染全局 gbuffer。

  日志里的以下内容不是本次黑屏根因：

  - rainStrength 自定义 uniform 警告：发生在光影包构建阶段。
  - Embeddium “tainted” 警告：来自 Oculus 自身对 Embeddium 的正常兼容注入。
  - 开发环境 refmap 警告：不解释运行时 framebuffer 黑屏。
  - Sampler2 警告：出现于光影启用之前。

  ———

  ## 三、与 Vista 的差异

  Vista 当前实现集中在 IrisCompat.java
  (https://github.com/MehVahdJukaar/cameramod/blob/9de351948c8f483090ed2b0a3b38a8e1e3824b23/common/src/main/java/net/mehvahdjukaar/vista/integration/iris/IrisCompat.java)。

   关注点                             Vista                                     当前项目
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   镜面/摄像机管线                    默认返回无操作的                          基线代码允许真实 Iris 管线进入镜面
                                      VanillaRenderingPipeline                  pass；当前未提交代码尝试动态替换
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   管线替换位置                       PipelineManager.preparePipeline HEAD      已有试验 Mixin，但失败全部静默
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   PipelineManager 恢复               显式保存、恢复原 pipeline                 当前以广泛反射快照恢复
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   LevelRenderer pipeline             保存精确的 WorldRenderingPipeline 字段    扫描所有同类型字段
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   Iris 全局状态                      精确保存 CapturedRenderingState           反射保存所有非 final 字段
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   blend/depth lock                   进入离屏 pass 前显式释放                  当前试验代码已经移植
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   ImmediateState.isRenderingLevel    显式恢复                                  当前试验代码已经移植
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   时间计数器                         使用 feed-local 计数器                    当前试验代码已经移植 getter，但仍会让真
                                                                                实计数器执行 beginFrame
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   RenderTarget 身份                  有版本号补偿逻辑                          当前没有
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   失败策略                           对固定 Iris API 编译并使用明确字段        当前反射失败后继续执行危险路径
  ─────────────────────────────────  ────────────────────────────────────────  ─────────────────────────────────────────
   Sodium/Embeddium                   Sodium 负责自己的 section culling         当前 EmbeddiumCompat 已采取相同原则

  Vista 的 PipelineManager Mixin 会在 feed pass 直接返回替代管线，见 CompatIrisMixin.java
  (https://github.com/MehVahdJukaar/cameramod/blob/9de351948c8f483090ed2b0a3b38a8e1e3824b23/common/src/main/java/net/mehvahdjukaar/vista/mixins/CompatIrisMixin.java#L24-L47)。

  替代管线本身不会执行光影、阴影或 gbuffer resize；Oculus 1.8.0 的 VanillaRenderingPipeline
  (https://github.com/Asek3/Oculus/blob/b3b278134f719afe32ba8b6b5d3a93f052175afc/src/main/java/net/irisshaders/iris/pipeline/VanillaRenderingPipeline.java#L23-L43)
  只会绑定当前 Minecraft 主目标并使用 program 0。

  需要说明：Vista 并没有让镜中画面完整支持 Iris/Oculus 光影。它的公开兼容说明也仍然承认 Iris/Oculus 存在视觉和性能限制。
  Vista 当前的安全方案本质上是“主视图有光影，离屏 feed/mirror 不运行光影”。

  ———

  ## 四、建议的最终架构

  支持范围应明确为：

  - Minecraft 1.20.1 Forge
  - Embeddium 0.3.31
  - Oculus 1.8.0
  - 主世界光影继续工作
  - 镜面离屏 pass 使用 Vanilla/Embeddium 管线
  - 不提供旧 Oculus 包名、旧版本兼容或自动后备路径

  核心事务顺序应固定如下：

  进入镜面 pass
    ↓
  保存精确的 Oculus/RenderSystem 状态
    ↓
  PipelineManager 暂时切换到 MIRROR_VANILLA_PIPELINE
    ↓
  释放 Iris blend/depth-color locks
    ↓
  Minecraft.mainRenderTarget = mirrorTarget
    ↓
  mirrorTarget.bindWrite
    ↓
  LevelRenderer.renderLevel
    ↓
  恢复 ImmediateState / CapturedRenderingState / ShadowRenderer
    ↓
  Minecraft.mainRenderTarget = realMainTarget
    ↓
  恢复真实 PipelineManager.pipeline
    ↓
  realMainTarget.bindWrite
    ↓
  退出镜面 pass

  最重要的不变量是：

  > 在 Minecraft.mainRenderTarget == mirrorTarget 的整个时间段内，PipelineManager.pipeline 不得是
  > IrisRenderingPipeline。

  只要这个不变量成立，Oculus 的 RenderTargets.resizeIfNeeded 就不会看到镜子目标，版本碰撞和附件污染自然消失。

  ———

  ## 五、具体代码设计

  ### 1. 正式声明编译期兼容目标

  在 build.gradle 添加 Oculus 1.8.0、Embeddium 0.3.31 的 compileOnly/开发运行依赖，使兼容代码直接使用真实类型和方法签
  名。

  在 mods.toml 增加客户端可选依赖和明确支持范围。Oculus 缺失时镜子仍正常运行；Oculus 存在但版本不满足时应明确拒绝，而不
  是尝试旧包名或反射猜测。

  ### 2. 重写 OculusCompat

  删除当前“扫描所有字段”的通用反射快照，改成固定的状态记录：

  - ShadowRenderer.ACTIVE
  - ImmediateState.isRenderingLevel
  - CapturedRenderingState 的：
      - model-view matrix
      - projection matrix
      - fog color/density
      - tick delta
      - entity/block entity/item ID
      - alpha test
      - cloud time

  - 当前 PipelineManager.pipeline
  - 当前镜面事务深度

  矩阵和向量必须复制值，不能只保存可变对象引用。

  使用 ThreadLocal<Integer> 或事务栈管理嵌套深度，和 Vista 一致。

  ### 3. 安全创建替代管线

  VanillaRenderingPipeline 构造函数会修改 WorldRenderingSettings：

  - 关闭 extended vertex format
  - 清除 block IDs
  - 修改 AO 和 separate entity draws
  - 设置 reloadRequired

  因此不能在光影已经运行时裸构造。

  应在客户端初始化、光影包建立之前创建一次；构造前后仍按照 Vista 保存和恢复 WorldRenderingSettings，确保不会触发
  Embeddium terrain rebuild。

  ### 4. 用 Mixin 接口管理 PipelineManager

  让 OculusPipelineManagerMixin：

  - @Shadow 精确的 WorldRenderingPipeline pipeline
  - 实现项目自己的 MirrorPipelineAccess
  - 提供：
      - mirror$enterPipeline(WorldRenderingPipeline)
      - mirror$restorePipeline()

  - 内部使用栈保存旧 pipeline
  - 在 preparePipeline(NamespacedId) HEAD 检测镜面事务，返回固定的 vanilla pipeline

  这样无需反射私有字段，也不会扫描 pipelinesPerDimension 或版本计数器。

  Mixin 注入不应使用 require = 0。针对明确支持的 Oculus 1.8.0，签名不匹配必须在启动阶段暴露，而不是运行到镜子前静默失
  效。

  ### 5. 修正时间状态

  当前 OculusFrameCounterMixin/OculusTimerMixin 只覆盖 getter，但 Oculus 在进入每次 renderLevel 时仍会调用真实的：

  - FrameCounter.beginFrame()
  - Timer.beginFrame()

  这会让主光影的 TAA 计数器每个镜子多前进一次。

  既然镜面 pass 不运行 shader pack，更简单的实现是：镜面事务期间直接取消这两个 beginFrame，而不是维护另一套 feed clock。
  这样主光影每个游戏帧只前进一步。

  ### 6. 精简 Mixin

  当镜面 pass 始终使用 VanillaRenderingPipeline 后，可以删除：

  - OculusPipelineMixin 中针对 IrisRenderingPipeline.renderShadows 的运行时取消；
  - skipInitialAllChanged；
  - 当前 getter 型 OculusFrameCounterMixin；
  - 当前 getter 型 OculusTimerMixin；
  - LevelRendererMixin 中强行扫描并覆盖 pipeline 字段的注入。

  原因是：

  - VanillaRenderingPipeline.renderShadows() 本身为空；
  - PipelineManager.preparePipeline 已在 HEAD 被取消，原方法的 allChanged() 不会执行；
  - Embeddium shader override 会看到 vanilla pipeline，自然走非 Iris 地形程序；
  - 单一明确的管线替换点比多个补丁点可靠。

  ### 7. 保留 Embeddium 的现有职责分离

  /E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/EmbeddiumCompat.java:16 当前让 Embeddium 自己管理 section
  culling，这个方向与 Vista 一致，应保留。

  不要在镜面 pass 中替换 Embeddium 的：

  - SodiumWorldRenderer
  - RenderSectionManager
  - compiled section graph
  - chunk vertex buffers

  也不能因创建 vanilla pipeline 而触发 LevelRenderer.allChanged()。

  ———

  ## 六、实施工作链

  建议按以下可独立验证的提交推进：

  1. 建立可观测基线

     在开发构建中记录每个镜面 pass 前后的：
      - Minecraft main target 对象和纹理 ID；
      - draw framebuffer binding；
      - framebuffer status；
      - PipelineManager pipeline 类和对象身份；
      - main target depth version；
      - ShadowRenderer.ACTIVE；
      - ImmediateState.isRenderingLevel。

  2. 固定依赖和 API

     添加 Oculus 1.8.0、Embeddium 0.3.31 编译依赖与可选模组声明，移除旧 net.coderbot 路径和版本猜测。

  3. 创建单例镜面 vanilla pipeline

     在光影包初始化前构造；保存并恢复 WorldRenderingSettings；验证不会出现新的 allChanged()。

  4. 实现精确的 PipelineManager 事务接口

     进入时保存真实 pipeline 并安装替代管线，退出时按栈恢复。

  5. 接入 MirrorLevelRenderer

     固定绑定顺序：
      - 先安装替代 pipeline；
      - 再设置 Minecraft.mainRenderTarget；
      - 再 bind 镜子目标；
      - 返回时先恢复 Minecraft 主目标；
      - 再恢复真实 pipeline；
      - 最后 bind 真实主目标。

  6. 实现精确状态快照

     只保存 Vista 已证明需要的 Oculus 状态，删除广泛反射。

  7. 阻止额外时间推进

     镜面事务期间取消 Oculus frame counter/timer 的 beginFrame。

  8. 删除旧试验路径

     删除重复、require=0、静默失败的 pipeline/shadow/timer Mixin，确保只有一个镜面管线切换入口。

  9. 加入开发期不变量检查

     调用镜面 renderLevel 前确认当前 pipeline 是替代管线。若不是，应立即报告兼容初始化失败，绝不能继续调用真实 Iris 管
     线。

  10. 完成回归矩阵后移除临时详细日志

  ———

  ## 七、验收矩阵

  至少覆盖：

   场景                           验收目标
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   Forge 原版                     镜子正常
  ─────────────────────────────  ───────────────────────────────────
   Embeddium，无 Oculus           镜子和区块渲染正常
  ─────────────────────────────  ───────────────────────────────────
   Oculus，光影关闭               镜子正常，无性能明显回退
  ─────────────────────────────  ───────────────────────────────────
   Oculus + Complementary v4.4    主画面、HUD 不黑；镜面稳定
  ─────────────────────────────  ───────────────────────────────────
   多面不同分辨率镜子             不发生 gbuffer 重分配或目标污染
  ─────────────────────────────  ───────────────────────────────────
   窗口缩放/全屏切换              主目标深度附件正确
  ─────────────────────────────  ───────────────────────────────────
   光影开关/光影包重载            pipeline 恢复，无旧目标残留
  ─────────────────────────────  ───────────────────────────────────
   递归镜面                       事务栈正确，计数器只前进一次
  ─────────────────────────────  ───────────────────────────────────
   世界/维度切换                  所有镜面目标销毁后无悬挂 pipeline
  ─────────────────────────────  ───────────────────────────────────
   长时间观察镜子                 显存稳定，无持续 framebuffer 分配

  关键验收指标：

  - 镜面 pass 前后的真实 PipelineManager.pipeline 对象身份相同。
  - 真实 Iris RenderTargets 的宽高不因镜子改变。
  - 真实主目标重新绑定后 GL_FRAMEBUFFER_COMPLETE。
  - 镜子可见时不触发 LevelRenderer.allChanged()。
  - 日志中没有每帧 Creating pipeline/Destroying pipeline。
  - 主光影 TAA 帧计数每个游戏帧只增加一次。
  - 主画面与 GUI 在镜子进入/离开视野时始终可见。

  ## 最终建议

  第一阶段不要尝试让镜中画面也运行完整 Oculus 光影。那需要每个镜面目标拥有独立 Iris pipeline、独立 gbuffer 生命周期和明
  确的销毁入口，复杂度及显存开销远高于本次问题。

  本次应采用 Vista 已验证的最小安全语义：

  > 主画面保留光影，镜面离屏渲染强制 vanilla pipeline，并以严格事务恢复 Oculus 全局状态。

  这能同时解决黑屏、避免 gbuffer 每帧在主窗口与镜子分辨率之间反复重分配，也最符合当前 1.20.1 Forge + Embeddium + Oculus
  环境。