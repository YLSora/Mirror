# Mirror × Oculus 兼容层三类视觉异常 —— 根因分析与优化方案

> 范围：Minecraft 1.20.1 · Forge 47.4.20 · Embeddium 0.3.31 · Oculus 1.8.0.1 · Mirror 0.1.0
> 结论性质：基于实机复现 + 日志/参数抓取 + 源码审查。本轮不修改源码，只输出分析与设计。

---

## 0. 复现环境与实测参数

| 项 | 值 |
|---|---|
| CPU / GPU | AMD Ryzen 9 8945HX / NVIDIA RTX 5070 Ti Laptop (OpenGL 4.6) |
| 光影后端 | Oculus 1.8.0.1（enableShaders=true，每包冷启动） |
| 测试世界 | `新的世界`，脚本化搭建场景（两镜相对 / 3×3 镜 + 红色背景墙） |
| 镜像配置 | recursionMode=RECURSIVE, maxRecursionDepth=4, reflectionFrameBudgetMs=20 |

复现方法：用 profiling/launch-build.json 的 JVM/classpath 直接拉起客户端（cpw.mods.bootstraplauncher.BootstrapLauncher，--quickPlaySingleplayer 新的世界），用 client-window.ps1 通过窗口消息发送 /setblock /fill /tp /time 等命令搭建场景并抓取窗口像素；同时采集 logs/latest.log 中的 [Mirror diagnostics]、Oculus 管线日志与 GL 错误。

---

## 1. 实测数据记录（关键参数）

### 1.1 四包代表性复现（同一世界、同一场景）

| 光影 | 异常类别 | 加载 | 视图数(maxPendingViews) | 递归结构 | temporalAttachmentResets | GL 错误 | 管线隔离/quarantine | 反射 pass 均值 |
|---|---|---|---|---|---|---|---|---|
| ComplementaryShaders v4.4 | ① 递归抖动 | OK | 3 | depth0/1/2 | 首帧 1 → 稳态 0 | 0 | 无 | ~1.5 ms |
| iterationRP Alpha 0.8.23 | ① 递归抖动 | OK | 3 | depth0/1/2 | 首帧 3 → 稳态 0 | 0 | 无 | ~2.5 ms（偶发 max 26ms） |
| photon_v1.3b | ② 阴影/透明 | OK | 3 | depth0/1/2 | 首帧 3 → 稳态 0 | 0 | 无 | ~1.2 ms |
| Nostalgia v5.1 | ③ 缺角 | OK | 1（3×3） | 单镜 | 首帧 3 → 稳态 0 | 0 | 无 | ~0.9 ms |

结论：四包均能加载、递归视图（depth0/1/2）稳定建立、无 GL_INVALID_OPERATION、无 framebuffer incomplete、无 shader 隔离/quarantine。视觉异常并非"崩溃/状态泄漏"级问题，而是"时域状态/深度判定/实体渲染路径"三个表现端问题。

### 1.2 关键日志证据

1. **mixin 冲突（全包出现，直接影响问题②）**
   @Redirect conflict. Skipping mirror.mixins.json:LevelRendererMixin
     -> @Redirect::mirror$allowLocalPlayerInReflection(Camera)Entity (priority 1000)
     already redirected by mixins.tweakeroo.json:MixinWorldRenderer
     -> @Redirect::allowRenderingClientPlayerInFreeCameraMode(Camera)Entity (priority 1001)
   Mirror 用于"反射中把本地玩家当作普通世界实体渲染"的 redirect 被 tweakeroo 以更高优先级覆盖，反射内本地玩家走不到 vanilla 的 LocalPlayer 渲染路径。

2. **递归管线按深度分槽（iterationRP 实测）**
   Prewarmed mirror pipeline 1/2, 2/2 (depth0/depth1 预热, 各 ~800~1200ms)
   Creating mirror pipeline slot PipelineSlot[recursionDepth=2, ResolutionBucket[256]] (~1076ms)
   Mirror pipeline READY ... terrain programs 51ms
   三个递归深度各占一个 256×256 槽位，预热/惰性创建均正常。

3. **时域附件重置只在首次使用**：temporalAttachmentResets 首窗=视图数，随后恒 0；稳态无 churn。

4. **静态场景帧间差分（iterationRP，玩家静止）**：连续 5 帧两两 meanAbsDiff≈4.2、changedPct≈2.6%，且高方差像素聚集在特定区域 —— 存在可见的时域不稳定（抖动/闪烁）信号，而非常见的 0 差分静止画面。

---

## 2. 三类异常的根本原因

### 2.1 问题①：Complementary / Sildur / iterationRP 递归反射中远处场景抖动、闪烁

共同特征：这三者都是重度使用 TAA + 屏幕空间（SSR/时域降噪）的光影。重投影误差随距离放大，因此异常集中在"远处场景"。

根因（追溯到底层机制）：

1. 递归视图的时域历史（previous 矩阵/相机）与重投影假设不一致。
   OculusPreviousMatrixMixin / OculusCameraPositionTrackerMixin 从 MirrorViewHistory 逐视图供给 gbufferPreviousModelView/Projection/CameraPosition。递归（depth>0）视图的相机是"主眼经 1..N 次平面反射"得到的 resolveReflectionPath(mainEye, path)，其运动被反射放大；而 OculusCameraPositionTrackerMixin 用"当前帧的坐标平移量"去还原上一帧相机（previous + (current - unshifted)）。一旦该平移量跨帧变化（反射眼坐标域与主相机坐标域不同），上一帧相机被错置 → TAA 重投影在远处出现亚像素级错位 → 抖动。

2. 深度≥2 的"收敛近似"相机与真实链式视差不同。
   MirrorTextureManager.getDeepTexture() 对 depth≥2 用"镜面自身直接相机（空 parent 链）"渲染深纹理，作为链式递归的近似。该近似相机不经过真实反射路径，采样进父级时其内容视差/运动与真实几何不一致，被 TAA 累积后表现为远处内容"游动/闪烁"。

3. 投影稳定器周期性重稳定带来单帧重投影跳变。
   MirrorProjectionStabilizer（EXPANSION_HEADROOM=1.10, SHRINK_THRESHOLD=0.70）在包络变化超过阈值时一次性改变投影与 UV crop；改变当帧 TAA 用"旧投影"做重投影，产生一帧跳变。掠射/远距时该跳变在远处被放大。

4. 预算顺延破坏"逐帧连续"假设。
   当 reflectionFrameBudgetMs 触发、递归视图被顺延一帧时，MirrorViewHistory 的 previous 是"两帧前"，TAA 仍按"一帧前"重投影 → 远处跳变。本场景 3 视图未触发（deferredViews≈0），但多镜场景会触发。

### 2.2 问题②：Photon / Photon GAMS / Sundial 镜面中玩家阴影异常、玩家透明

共同特征：三者都是阴影驱动 / 物理向光影，对实体阴影图与实体渲染 pass 高度敏感。

根因：

1. （确认）tweakeroo mixin 冲突使反射内本地玩家丢失 vanilla 渲染路径 → "玩家透明"。
   见 1.2-1。mirror$allowLocalPlayerInReflection 被跳过后，反射相机是 MirrorCamera（dummy 实体），vanilla renderLevel 的 Camera.getEntity() != LocalPlayer 门控会跳过本地玩家。此前"镜中玩家正常"仅当 YSM（yes-steve-model）等第三方独立玩家模型路径恰好补偿时才成立；对 Photon/Sundial 的实体着色路径，玩家即表现为缺失/半透明。

2. （确认）嵌套阴影 pass 被整体取消，反射内阴影采样与反射相机不一致 → "阴影异常"。
   OculusMirrorShadowPassMixin 在反射内 cancel() renderShadows，反射直接复用主相机阴影图。阴影图虽由太阳方向决定（几何上本应可复用），但其级联(cascade)选择、阴影距离衰减、ShadowRenderer.renderDistance/MODELVIEW/PROJECTION 均按主相机配置；反射相机距离/视角不同时，级联与距离裁剪错位，玩家/实体的阴影软硬、有无、投影方向在镜中异常（光子/Sundial 这类硬阴影/接触阴影对级联尤其敏感）。

3. （关联）ShadowState 捕获/恢复只覆盖外层切换，未按反射相机重建阴影采样参数。
   OculusCompatImpl.ShadowState 保存了 visibleBlockEntities/renderDistance/MODELVIEW/PROJECTION/FRUSTUM，但只做"退出后还原"，反射 pass 内仍是主相机参数，未为反射相机重算。

### 2.3 问题③：Nostalgia / UShader 镜面缺失一角（随视角移动变化）

共同特征：Nostalgia、UShader 是 SEUS v10/v11 系光影，其 composite/final 阶段会消费或改写深度缓冲、并改变投影/深度约定。

根因（追溯到底层机制）：

1. （核心）延迟呈现 decal 依赖"光影 composite 之后残留的深度缓冲"，而 SEUS 系不保证该深度有效。
   镜面最终呈现走 DeferredMirrorSurfaceRenderer，在 IrisRenderingPipeline.finalizeLevelRendering 的 TAIL（即光影全部 composite/final 之后）才绘制 decal，DEFERRED_MIRROR_SURFACE 用 LEQUAL_DEPTH_TEST + POLYGON_OFFSET_LAYERING 与"当前深度缓冲"比较。此时深度缓冲已非可靠场景深度（被光影消费/变换），decal 的深度判定在视点相关区域随机失败 → 缺角，且随视角移动改变形状。代码注释已自证此风险（"shader packs that preserve/transform the scene depth differently ... lose large, view-dependent regions"），现有 polygon offset + 0.001 固定偏移在掠射角/反向-Z 下不足以兜底。

2. （辅助）flush 时刻的投影矩阵未显式恢复。
   DeferredMirrorSurfaceRenderer.flush() 直接 BufferUploader.drawWithShader，未显式设置投影矩阵，依赖 flush 时刻 RenderSystem 的残留投影。若光影 composite 阶段改写了投影，decal 会以错误投影/错误深度绘制 → 视点相关的错位/裁剪。

3. （辅助）镜面块自身几何与 decal 近平共面。
   镜面方块薄面板前表面与 decal 仅差 0.001，加上屏幕空间 polygon offset，在掠射角下深度比较精度不足，进一步加剧缺角。

---

## 3. 优化方案设计（底层源码 · 光影无关 · 非侵入）

设计原则：落到镜面底层源码；长效稳定、不依赖具体光影；不改变现有渲染机制/性能端优化/既有 Oculus 兼容层的主体结构；不引入新问题。每项均给出最小侵入的落点与风险。

### 3.1 问题③（缺角）—— 让延迟 decal 的投影与深度"自足"

> 落点：DeferredMirrorSurfaceRenderer + MirrorRenderTypes

- 修复 A（P0，最小，直接对因）：Surface 记录 submit 时的世界投影矩阵；flush() 绘制前显式 RenderSystem.setProjectionMatrix(surface.projection(), VertexSorting.DISTANCE_TO_ORIGIN)，绘制后还原外层投影。消除"依赖 composite 残留投影"的隐患。
- 修复 B（P1，根治深度依赖）：为镜面建立自足深度。在实体/半透明 pass（深度仍有效）期间，用一次"只写深度、不写颜色"的四边形把镜面表面深度写入深度缓冲；flush() 的 decal 用 GL_EQUAL（或保留 LEQUAL）与自身深度比较。这样即使 composite 清空了其它深度，镜面自身与"更近几何（玩家）"的遮挡关系仍然正确、pack 无关。
- 修复 C（P2，防御兜底）：当处于"光影 active 且 deferred flush 深度不可靠"时，对仅直接视图（depth0）可回退为"无深度测试"（镜面后无几何/贴墙的物理事实），递归视图（depth>0）仍走深度路径，避免递归中玩家被镜面错误覆盖。

> 风险控制：A 仅补一个矩阵保存/恢复；B 增加一次 depth-only 绘制（每镜每帧开销极小，且可复用现有 RenderType 管线）；C 只在极端兜底启用。三者均不改反射几何、捕获槽位、分辨率分级或 Oculus 管线隔离。

### 3.2 问题②（阴影/透明）—— 恢复本地玩家渲染路径 + 对齐反射阴影采样

> 落点：LevelRendererMixin + OculusMirrorShadowPassMixin + OculusCompatImpl.ShadowState

- 修复 A（P0，直接修复"玩家透明"）：消除与 tweakeroo 的 @Redirect 优先级冲突。把 mirror$allowLocalPlayerInReflection 的 mixin priority 提到高于 1001（如 1100），或改用 @Inject(HEAD, cancellable)+@ModifyVariable/局部变量改写，使"反射中返回 LocalPlayer"不再被 tweakeroo 覆盖，且严格仅当 MirrorLevelRenderer.isRenderingReflection() 时生效（不影响 tweakeroo freecam 的正常语义）。
- 修复 B（P1，修复"阴影异常"）：不再"整体取消"嵌套阴影，而是复用主阴影图、但按反射相机重建阴影采样参数。保留 OculusMirrorShadowPassMixin 防止递归重建阴影图（避免性能爆炸），但把 OculusCompatImpl.ShadowState 从"仅保存/恢复"扩展为"在反射 pass 内按反射相机重算 renderDistance、级联选择与 shadowModelView/Projection 的采样语义"。玩家/实体的阴影方向、软硬、接触阴影与主世界一致，且不新增阴影图渲染。
- 修复 C（P2，防御）：为 Photon/Sundial 这类对实体渲染 pass 敏感的包，确认反射内实体走不透明实体 pass（而非半透明），避免"玩家变透明"。这由修复 A 保证的 vanilla 路径 + 现有 ImmediateState.isRenderingLevel 捕获/恢复共同达成。

> 风险控制：A 是优先级/注入器层面的定点修复；B 只改阴影采样参数、不引入第二张阴影图（零额外显存/渲染成本）；C 属验证项。

### 3.3 问题①（递归抖动）—— 冻结/纠正递归视图的时域累积

> 落点：OculusMirrorTemporalStateMixin + MirrorViewHistory + OculusPreviousMatrixMixin

- 修复 A（P0，最直接）：对递归视图（depth>0）逐帧请求 full-clear（在 OculusMirrorTemporalStateMixin 中当 MirrorPassContext.current().recursionDepth()>0 时每帧 mirror$requestFullClear()）。等效于"递归视图不做 TAA 时域累积"。递归视图本身已随深度分辨率衰减，且其重投影误差被多重反射放大；去掉累积后 Complementary/Sildur/iterationRP 的远处内容不再因错误历史而抖动。代价仅是递归层无 TAA（轻微锯齿，远轻于抖动），且直接视图（depth0）仍保留 TAA。
- 修复 B（P1，纠正跳帧历史）：MirrorViewHistory.commit 记录帧号；previousOr 仅当"上一帧是连续帧"才返回历史，否则返回 current（重投影置零）。覆盖"budget 顺延/视图重建"导致的跳帧场景，避免用过期历史做重投影。
- 修复 C（P2，收敛近似的时域一致性）：对 depth≥2 的 deep 收敛纹理同样冻结 TAA（配合 A），并把其采样改为"最近邻 + 无时域"，避免"近似视差"被 TAA 放大为游动。

> 风险控制：A/B/C 都不改变递归几何、收敛近似结构、分辨率/渲染距离衰减或预算机制；只改"递归视图是否做时域累积"与"历史是否逐帧连续"，对性能端无负面影响（甚至略减 TAA 开销）。

### 3.4 不做的事（守住既有边界）

- 不按光影名做白名单/黑名单（违反"光影无关"）。
- 不重建第二张阴影图、不改捕获槽位/分辨率分级、不动 MirrorCapturePool/MirrorTextureManager 的递归收敛与预算逻辑。
- 不移除 deferred 呈现主路径（递归中"子镜面在父级 tone-mapping 后再合成"的语义必须保留）。
- 不改反射几何、投影数学、frustum/区块剔除或 Embeddium 深度 pass 隔离。

---

## 4. 量化总结

| # | 异常 | 触发光影 | 根因（底层） | 方案要点 | 预期量化效果 |
|---|---|---|---|---|---|
| ① | 递归反射远处抖动/闪烁 | Complementary / Sildur / iterationRP | 递归视图时域历史与 TAA 重投影假设不一致 + 收敛近似视差 + 投影稳定器跳变 + 预算顺延跳帧 | 递归视图逐帧清历史（关闭递归 TAA）+ 连续帧校验 + deep 视图同冻结 | 递归层远处像素帧间方差归零（对照实测 changedPct≈2.6%）；直接视图 TAA 不受影响 |
| ② | 玩家阴影异常/透明 | Photon / Photon GAMS / Sundial | tweakeroo 覆盖 mirror$allowLocalPlayerInReflection → 本地玩家丢失 vanilla 路径；嵌套阴影整体取消 → 阴影级联/距离与反射相机错位 | 提升 mixin 优先级/改注入器恢复玩家渲染；复用主阴影图但按反射相机重建采样参数 | 玩家实体恢复不透明渲染；阴影方向/软硬与主世界一致，且零额外阴影图开销 |
| ③ | 镜面缺角（随视角变化） | Nostalgia / UShader | 延迟 decal 依赖光影 composite 后已失效/被改写的深度缓冲 + flush 投影未显式恢复 + 近共面深度精度 | flush 显式恢复世界投影 + 自足深度（depth-only 预写 + EQUAL 比较）+ 直接视图无深度测试兜底 | 缺角消除，遮挡（玩家遮挡镜面）在递归视图仍正确 |

实测基线（供回归对照）：四包均 reflectionPasses≈120~360/120帧、avg≈0.9~2.5ms、GL错误=0、quarantine=0、temporalAttachmentResets 稳态=0；迭代RP 静止场景 changedPct≈2.6%（问题①的时域不稳定信号）。方案落地后的验收应复核上述基线不劣化，且①的 changedPct 在递归区收敛、②玩家不透明且阴影一致、③无缺角。

### 附：复现产物

- 启动/切换脚本：run/repro/set-shaderpack.ps1、run/repro/launch.ps1
- 运行日志：run/repro/logs/issue1-comp.stdout.log、issue1-itrp.*、issue2-photon.*、issue3-nostalgia.*
- 窗口抓取：run/repro/captures/（issue1-comp-recursive.png、itrp-frame1..5.png、nostalgia-1m.png 等）
- 游戏侧日志：<版本目录>/logs/latest.log（Oculus 管线、Mirror diagnostics、mixin 冲突）
