# Mirror × Oculus 兼容修复与验证工作流

## 支持范围与目标

- Minecraft 1.20.1
- Forge 47.2.0
- Embeddium 0.3.31+mc1.20.1
- Oculus（运行时版本不设上下限；开发编译基线为 1.8.0）
- Java 17

Oculus 依赖声明不限制运行时版本；Mixin 目标或 Embeddium terrain override
签名不一致时在客户端初始化阶段直接失败，不保留旧实现或后备渲染路径。

验收目标只有两个：光影开启时最终窗口画面不能变黑；镜面必须执行同一光影包的
完整 world pipeline，并在 pass 结束后完整恢复外层渲染状态。F2 截图只证明主
RenderTarget 内容有效，不能替代最终窗口呈现验收。

## 已确认的根因

1. 旧代码用伪维度名区分镜面 pipeline。Oculus 会把它当作一个真实维度，重置
   `SystemTimeUniforms` 并按错误的维度语义选择 shader program。
2. Oculus 1.8.0 的 `IrisChunkProgramOverrides` 只按一个
   `ShaderChunkRenderer` 缓存一组 terrain programs。镜面切换 pipeline 后，program
   仍来自外层 pipeline，而 framebuffer 已来自镜面 pipeline，最终形成 program/FBO
   不匹配。
3. 旧兼容层取消了整个 `renderShadows()`。这不仅关闭阴影，还跳过该方法中的
   renderer preparation，破坏完整的 Iris/Oculus pass 顺序。
4. attachment version 和目标绑定回调只能掩盖单个 FBO 的症状，不能解决多个
   color/depth target 与 terrain program 的所有权问题。
5. Oculus 1.8.0 的 `ProgramUniforms.Builder` 在返回 uniform location 后才检查 GLSL
   类型。标准 uniform 会在后处理阶段被移除，但 `CustomUniforms` 已经独立保存了
   location，仍会在每个 composite pass 调用错误的 `glUniform*`。Photon 的
   `composite19` 把 `isEyeInWater` 声明为 `float`，而 Oculus 提供 `int`，因此主
   pipeline 与镜面 pipeline 都会持续产生 `GL_INVALID_OPERATION`；镜面只是放大
   了 Oculus 原有的状态污染，并不是 FBO 黑屏。
6. Forge 的 `RenderTick END` 位于 `GameRenderer.render()` 之后、
   `mainRenderTarget.blitToScreen()` 之前。旧调度在 END 执行镜面嵌套渲染，因而让
   Oculus 的 final/composite pass 成为主画面之后的最后一组 GL 操作，污染最终
   窗口 blit 所依赖的呈现状态。主 RenderTarget 的像素仍然正确，所以 F2 正常，
   但显示器上的 3D 画面为黑色而 HUD 仍可见。

## 当前实现

### 1. 真实维度语义，独立资源身份

`OculusPipelineManagerMixin` 在镜面 pass 中不进入 Oculus 原有的维度缓存，而是用：

`真实维度 + 递归深度 + 分辨率槽`

缓存镜面 pipeline。创建 pipeline 时传入的仍是 `minecraft:overworld` 等真实维度；
镜子 UUID 不进入 key。同一槽中的镜子串行复用 pipeline，不会按镜子数量编译
shader。

### 2. 稳定 capture target

`MirrorCapturePool` 把请求分辨率向上归入方形 2 次幂槽，并让同一递归深度的同一槽
复用一个 capture target。渲染完成后立即把结果合成到每面镜子自己的 surface
target。pipeline 看到的主 target 因而具有稳定的尺寸和 attachment 生命周期。

### 3. Embeddium terrain program 按 pipeline 隔离

`OculusTerrainProgramCacheMixin` 将 Oculus 1.8.0 的单组 terrain programs 改为按
`WorldRenderingPipeline` 身份保存。`getProgramOverride()` 与 `bindFramebuffer()`
现在必然读取同一 pipeline；Oculus 全局 reload counter 改变时，所有组统一删除并
重新编译。

### 4. 完整 shader pass 与事务边界

- 不取消 shadow、composite、final 或 renderer preparation。
- 嵌套 `renderLevel()` 不再推进全局 frame counter/timer。
- 进入镜面前解除 Iris 的 blend/depth-color lock；退出时清除 active uniforms 和
  samplers，并恢复外层 pipeline、CapturedRenderingState、ShadowRenderer、
  ImmediateState、主 RenderTarget、相机与 GL 状态。
- shader reload、世界卸载和资源清理先销毁镜面 pipelines，再销毁 pooled targets；
  terrain programs 通过 Oculus reload counter 一次性失效。

### 5. uniform location 的类型边界

`OculusUniformTypeValidationMixin` 在 `ProgramUniforms.Builder.location()` 返回位置前，
使用 OpenGL 的 active-uniform 元数据核对 Oculus supplier 类型与 GLSL 实际类型。
不匹配或 Oculus 不支持的类型直接返回空位置，使标准与自定义 uniform 走同一个
“禁用该 uniform”结果。校验只发生在 shader program 创建阶段，不进入逐帧更新；
也不识别 Photon、IterationT 或任何光影包名称。

### 6. 让外层渲染拥有最终呈现

镜面请求仍在正常世界渲染期间收集，但统一在下一帧 `RenderTick START` 消费。
镜面 pass 完成后，外层 `GameRenderer` 随即执行一次完整的正常世界渲染，最后由
Minecraft 自己解除主目标并 blit 到窗口。这样最终呈现不再依赖逐项猜测或恢复
Oculus 内部的临时 GL 状态。

这个调整没有增加可见延迟：在旧 END 调度中生成的纹理也只能被下一帧镜面材质
采样；START 只是把同一次生成移到下一帧采样之前。嵌套 world pass 数量、pipeline
数量和分辨率槽均不变，因此没有额外逐帧性能成本。

## 已移除的旧路径

- 伪维度 pipeline key。
- RenderTarget attachment version 人工递增。
- 整个 shadow pass 的取消逻辑。
- `LevelRenderer.allChanged()` 的广泛取消。
- 镜面进入/退出时的 target-bound 版本回调。
- 每面镜子独占一个 capture target。

## 自动化与客户端验证

构建命令：

```powershell
.\gradlew.bat build
```

当前结果：12 个测试、0 failure、0 error；reobfuscated JAR 构建成功。

固定客户端场景使用 854×480 窗口、VSync 120、单面 2×2 镜子、
ComplementaryShaders v4.4 默认低配 preset。连续验证顺序：

1. 光影开启并保持镜子可见 15 秒。
2. 按 K 关闭光影，确认主画面和镜面正常。
3. 按 K 重新开启光影，等待 pipeline 重建，确认主画面和镜面正常。
4. 横向移动玩家，确认镜中玩家和光照同步更新。
5. 正常关闭客户端，确认镜面 pipeline 只销毁一次并成功保存世界。

本次运行结果：

- 修复前黑屏截图的采样平均亮度为 7.02，黑像素比例为 94.1199%。
- 黑屏帧：0；三张验收截图的采样平均亮度分别为 145.15、103.89、130.79。
- 采样像素中 RGB 全部低于 8 的比例均为 0.3115%，远低于黑屏阈值 10%。
- `GL_INVALID*`：0。
- framebuffer incomplete/error：0。
- Mixin apply/Injection error：0。
- NPE、IllegalState、OOM：0。
- 镜面可见时 F3 采样为 118 FPS（120 FPS VSync 上限），GPU 约 59%—62%。
- Oculus/Embeddium 的既有 taint 与光影解析提示不属于 GL/FBO/Mixin injection
  异常；验收时仍需单独核对，不能用它们掩盖新错误。

### 多光影包与重载回归（2026-08-25）

在同一客户端进程、同一世界与同一镜面视角中执行：

`Photon 冷启动 → IterationT 热切换 → Complementary 热切换 → Photon 热切换
→ K 关闭光影 → K 重新开启 Photon → 持续渲染 30 秒 → 正常退出`

结果：

- shader pack 加载 5 次，镜面 pipeline 创建 6 次、运行中销毁 5 次；退出时最后
  一条镜面 pipeline 正常销毁，创建/销毁总数最终相等。
- `GL_INVALID*`：0；`Wrong component type or count`：0；framebuffer error：0。
- Photon 的类型冲突只在每个 program 创建时记录一次并立即禁用，不再逐帧增长；
  30 秒观察窗口内错误计数保持不变。
- Photon、IterationT、Complementary 的主画面、镜面人物、天空、水面、阴影均可见；
  Photon 关闭/重新开启后镜面仍继续更新。
- Photon 自带的 `#endif without #if`、Complementary 的自定义 uniform 名称冲突及
  光影选项警告来自 Oculus/光影包解析，未产生 GL/FBO 错误，也不影响本次渲染。

新增验收截图：

- `run/screenshots/2026-08-25_00.02.00.png`：Photon 冷启动。
- `run/screenshots/2026-08-25_00.07.41.png`：热切换到 IterationT。
- `run/screenshots/2026-08-25_00.09.02.png`：热切换到 Complementary。
- `run/screenshots/2026-08-25_00.11.15.png`：再次热切换到 Photon（日间）。
- `run/screenshots/2026-08-25_00.12.05.png`：关闭光影。
- `run/screenshots/2026-08-25_00.12.52.png`：重新开启 Photon 并持续渲染 30 秒。

验收截图：

- `run/screenshots/2026-08-24_23.31.41.png`：光影开启，主画面与镜面正常。
- `run/screenshots/2026-08-24_23.32.10.png`：光影关闭，主画面与镜面正常。
- `run/screenshots/2026-08-24_23.32.33.png`：热切换后重新开启光影。
- `run/screenshots/2026-08-24_23.32.55.png`：移动后的光影镜像。
- `run/screenshots/2026-08-24_23.33.19.png`：F3 性能采样。

### 最终窗口呈现回归（2026-08-25）

F2 会直接读取 Minecraft 主 RenderTarget。为覆盖之后发生的窗口 blit，本轮同时
使用操作系统桌面像素采集 Minecraft 客户区，且仅在窗口位于前台、尺寸稳定后
取样。两种采集必须在同一时刻都通过，窗口采集是黑屏判定的最终依据。

修复前的对照结果：

- `run/screenshots/2026-08-25_00.29.12.png`：IterationT 的 F2 截图正常。
- `run/captures/iteration-before-window-focused2.png`：同一运行中的实际窗口为黑色，
  只剩 HUD 和准星；4 像素步进采样平均亮度 5.55、黑像素比例 91.30%，确认问题
  位于主目标之后的最终呈现阶段。

将镜面处理移到 `RenderTick START` 后，在同一世界、同一视角完成
`IterationT → Photon → IterationT → 关闭光影 → 重新开启 IterationT → 30 秒观察`
回归：

- `run/captures/iteration-after-window.png`：IterationT 冷加载，主画面与镜面正常。
- `run/captures/photon-after-window.png`：热切换 Photon，多面不同尺寸镜子正常。
- `run/captures/iteration-after-hot-switch-window.png`：切回 IterationT 后镜中玩家正常。
- `run/captures/shaders-off-after-window.png`：关闭光影后正常。
- `run/captures/iteration-reenabled-window.png`：重新开启并观察 30 秒后仍正常。
- `run/screenshots/2026-08-25_00.35.19.png`：Photon 的同步 F2 对照也正常。

修复后的五张实际窗口采样平均亮度依次为 88.73、110.99、127.46、107.22、
133.98；黑像素比例依次为 0.31%、1.41%、0.31%、0.31%、0.31%，全部远低于
10% 黑屏阈值。Photon 的同步 F2 黑像素比例为 0.32%，与实际窗口结论一致。

本轮正常退出后日志计数：`GL_INVALID* = 0`、framebuffer error = 0、
`Wrong component type or count = 0`；镜面 pipeline 创建 12 次、销毁 12 次。
Photon 与 IterationT 的实际窗口不再出现“F2 正常但显示器黑屏”。

## 后续回归门槛

每次改动必须在固定世界与固定 shader preset 下重复以上流程，并满足：

- 10000 帧黑屏帧为 0；实际窗口客户区与 F2 必须同时采样，二者整帧黑像素比例均
  不得超过 10%。只检查 F2 不得作为通过依据。
- GL/FBO/Mixin/Injection 异常为 0。
- 热切换前后只存在当前使用的 `真实维度 + depth + resolution slot` pipelines。
- warm-up 后，同一槽不得继续创建 capture target 或重新编译 terrain programs。
- 10 分钟测试中 VRAM 不得呈单调增长；退出后所有镜面 pipeline 和 target 必须销毁。
- 新增测试按单镜面 → 多镜面同槽 → 多尺寸跨槽 → 递归 depth 1/2 →
  Overworld/Nether/End 的顺序分层加入，任一阶段失败时停止扩展并修复当前层。
