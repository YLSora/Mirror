# Mirror 模组（1.20.1 Forge）性能监控、根因分析与优化方案报告

> 范围：`com.mirror` 源码静态分析 + 以 `Rapid Optimization` 整合包为实例的运行时监控。
> 约束：本轮不做任何代码/配置改动；所有优化建议以“不改变模组机制与原理”为前提。
> 监控工具：模组内置 `MirrorDiagnostics`（120 帧窗口 + Oculus 管线日志）为主要仪表；辅以游戏日志、配置、版本清单、硬件信息等外部数据源。

---

## 1. 结论速览（量化）

| 场景 | 关键指标 | 实测值 | 影响 |
|---|---|---|---|
| ① 创建/线程压力 | 首次反射 pass（原版） | avg 7.80ms，**max 159.32ms** | ≈9.5 帧卡顿(@60fps) |
| ① 创建/线程压力 | 光影管线预热（每管线） | **184~2040ms**，中位 ~600-1600ms | 渲染线程秒级冻结 |
| ① 创建/线程压力 | 光影地形程序编译/槽位 | 12~166ms；槽位总热 205~2095ms | 同上 |
| ① 创建/线程压力 | 光影 temporal 重置 | 单帧 **max 816.36ms**，每120帧 1~10 次 | ≈49 帧卡顿 |
| ② 帧率/多面 | 单镜 pass（原版/光影） | 0.65~1.2ms / 1.8~2.3ms | 光影 ≈2.5~3× |
| ② 帧率/多面 | 4 镜（原版） | 480 pass/120帧=4/帧，≈**5ms/帧** | 吃掉 ~30% 帧预算 |
| ② 帧率/多面 | 17 镜（光影） | 2040 pass/120帧=17/帧，≈**18~23ms/帧** | 帧预算耗尽 |
| ③ 递归 | 多镜递归 | maxPendingViews 17~27，pass 达 **2200/120帧=18.3/帧** | 指数级放大 |
| ③ 递归 | 光影递归槽位 | depth0-3 × 256/512/1024 = 最多 **12 槽位**，各 178~1976ms | 管线爆炸 |
| ④ 光影 | 捕获分辨率 | 下限 **256×256**（原版 16×16）+ **1.333× overscan** | 像素量 ≈1.78× |

**核心结论**：该模组每帧为“每个可见镜面”在**渲染线程上串行**执行一次**完整的二次世界渲染**（`LevelRenderer.renderLevel`）。因此开销随镜面数量近似线性增长，且在“光影 + 递归”下被管线构建成本与每槽位捕获分辨率二次放大。最重的三项是：(A) 首帧/预热/重建造成的渲染线程长停顿，(B) N 镜 = N 次世界重渲染，(C) 光影下每（递归深度 × 分辨率桶）独立 Oculus 管线的构建与像素填充成本。

---

## 2. 分析环境

- **硬件**（来自 Oculus 启动日志）：CPU `AMD Ryzen 9 8945HX (32 线程)`；GPU `NVIDIA GeForce RTX 5070 Ti Laptop (OpenGL 4.6)`；OS Windows 11。
- **运行时**：Minecraft 1.20.1；Forge `47.4.20`（版本清单 `Rapid Optimization.json`）；目标 Java 17。
- **关键渲染相关 mod**：`embeddium 0.3.31`、`oculus 1.8.0.1`（整合包内以 `mekalus-mc1.20.1-1.8.0.1.jar` 形态存在，加载日志确认为 Oculus 1.8.0.1）、`ImmediatelyFast`、`entityculling`、`modernfix`、`acceleratedrendering`、`flashlightmod`、`littletiles`。
- **模组配置**（`mirror-client.toml`）：`renderDistance=32`、`resolutionScale=8.0`、`recursionMode=RECURSIVE`、`maxRecursionDepth=4`、`recursiveResolutionDecay=0.5`、`recursiveRenderDistanceDecay=0.5`。
- **光影状态**：`oculus.properties` 显示 `enableShaders=false` 但已选定 `ComplementaryShaders_v4.4_ch.zip`（POTATO profile）。历史日志存在多段“光影开启”会话（Complementary v4.4），用于场景④对比。

---

## 3. 性能影响面清单（源码级 list）

按四大瓶颈归类，标注代码位置与影响机制。

### A. 创建镜面渲染的线程压力（瓶颈 ①）
| # | 位置 | 影响 |
|---|---|---|
| A1 | `MirrorTextureManager.processPending()` (L77-104) | 所有待渲染镜面在**渲染线程**串行 `for` 循环逐个执行；无并行/分帧预算 |
| A2 | `MirrorLevelRenderer.render()` (L117-256) | 每次 pass 都重建投影/视图矩阵、`MirrorCamera`、`PoseStack`，并调用完整 `renderLevel` |
| A3 | `MirrorRenderState.capture()/restore()` (L80-208) | **每个 pass 前后**做 ~100+ 次 `glGet*` + 纹理单元遍历 + `RenderSystem` 反射字段快照/恢复；固定高频开销 |
| A4 | `MirrorLevelRendererHooks.prepare()` (L32-63) | 非 Embeddium 路径每 pass 重跑 BFS 遮挡剔除；`TextureState.getOrCreate` 在 section 数变化时重建隔离存储 |
| A5 | `MirrorCapturePool.acquire()` (L28-41) + `MirrorReflectionTexture` 构造 | 新视图首次出现时分配 `TextureTarget`/纹理并注册到纹理管理器 |
| A6 | `OculusPipelineManagerMixin`（`mirror$prepareSlotPipeline` L201-283） | 光影下每个新槽位在渲染线程构造完整 `IrisRenderingPipeline`（实测 178~1976ms） |
| A7 | `OculusPipelineManagerMixin`（`mirror$prewarmMirrorPipelines` L97-137） | 开光影即预热 2 条备用管线（各 184~2040ms） |
| A8 | `OculusTerrainProgramCacheMixin`（`createShaders` L108-146） | 每槽位地形 GLSL 编译/链接 12~166ms |
| A9 | `OculusCenterDepthSamplerMixin`/`OculusMirrorTemporalStateMixin` | 视图切换触发 temporal 历史重置（实测单帧 816ms） |

### B. 镜面渲染帧率，尤其多面（瓶颈 ②）
| # | 位置 | 影响 |
|---|---|---|
| B1 | `GameRendererMixin.mirror$renderPendingReflections()`（`renderLevel` TAIL） | 每帧主世界渲染**之后**追加 N 次世界渲染，直接挤占帧预算 |
| B2 | `MirrorBlockEntityRenderer.render()` L66-71 | 每个可见镜面 `request()` 一次 → 每个镜面一帧一次全量世界重渲染 |
| B3 | `MirrorLevelRenderer.render()` L224-226 | 直接调用 `minecraft.levelRenderer.renderLevel(...)`，实体/方块实体/地形全量重绘 |
| B4 | `MirrorCapturePool`/`MirrorProjectionStabilizer` | 光影下捕获下限 256×256 + `shaderSamplingCompensation()=1.333×`（`SHADER_GUARD_BAND=0.14`）放大填充率 |
| B5 | `MirrorRenderTypes`/`DeferredMirrorSurfaceRenderer` | 光影下镜面改为延迟呈现，额外一次合成 pass |

### C. RECURSIVE 多次反射（瓶颈 ③）
| # | 位置 | 影响 |
|---|---|---|
| C1 | `MirrorTextureManager.requestRecursive()` (L53-69) | 每条 parent-chain × depth 生成**链隔离**纹理，pass 数随“互见镜面数 × 深度”倍增 |
| C2 | `MirrorTextureManager.processPending()` L94-95 | 按 depth 逆序排序，深层次先渲染，串行叠加 |
| C3 | `MirrorPassContext.PipelineSlot(recursionDepth, resolutionBucket)` | 光影下管线按（深度, 分辨率桶）笛卡尔隔离 → 槽位数爆炸（实测 depth0-3 × 256/512/1024） |
| C4 | `MirrorLevelRenderer.resolveReflectionPath()` / `getChildParentChain()` | 每条链都做完整平面反射解算与父链拷贝 |
| C5 | `LevelRendererMixin.mirror$useRecursiveEntityFrustum()` L69-87 | 递归 pass 旁路距离剔除，改用包围盒+视锥 → 深层实体量更大 |

### D. 开启光影时的性能（瓶颈 ④）
| # | 位置 | 影响 |
|---|---|---|
| D1 | `OculusCompatImpl.MirrorTransaction` (L175-246) | 每个 pass 快照/恢复 Iris 全局状态（`CapturedRenderingState`、`ShadowState`、`ImmediateState`、管线指针） |
| D2 | `OculusPipelineManagerMixin` 每槽位独立管线 | 构建 + 每帧管线切换成本 |
| D3 | `MirrorRenderState.restore()` L168-174 | 额外 `_glUseProgram` 冗余绑定缓存修复（Oculus 1.8 特有） |
| D4 | `OculusShaderSourceCompatibility.patch()` | 地形 GLSL 每次编译前正则打补丁（本包 Complementary v4.4 实测 patches=0，命中率低但路径仍执行） |
| D5 | `MirrorCapturePool` shader 分支 | 捕获尺寸最小 256 + overscan 补偿 |

---

## 4. 场景化监控数据（区分四类场景）

数据源：`versions/Rapid Optimization/logs/latest.log`（最近会话 22:31–22:44）与历史 `*.log.gz`（08-15~08-28 多会话，含多段光影会话）。

### 4.1 场景① —— 创建镜面渲染的线程压力

**原版首次进入（latest.log 22:32:15）**：
```
reflectionPasses=115, avg=7.80 ms, max=159.32 ms, maxPendingViews=1   ← 首个 120 帧窗口
reflectionPasses=120, avg=3.32 ms, max=9.34 ms                       ← +2s
reflectionPasses=120, avg=1.83 ms, max=4.17 ms                       ← +4s
reflectionPasses=120, avg=1.52 ms → 1.30 → 1.18 ms ...              ← 快速收敛
reflectionPasses=120, avg=0.65~0.9 ms（稳态，持续数分钟）            ← 稳态
```
→ **首帧/新视图建立**是单次最重开销：159ms 尖峰对应“纹理分配 + 首次全量世界渲染”，随后被缓存摊薄到 ~0.7ms。

**光影开启（latest.log 22:33:08–22:33:13，约 4.7 秒窗口）**：
```
Prewarmed mirror pipeline 1/2 ... in 328.79 ms
Prewarmed mirror pipeline 2/2 ... in 316.89 ms
Prewarmed mirror terrain programs ... 30.45 ms; pipeline total warm-up 780.87 ms
Prewarmed mirror terrain programs ... 32.65 ms; pipeline total warm-up 484.52 ms
reflectionPasses=116, avg=3.81 ms, max=52.52 ms, temporalAttachmentResets=1
```

**历史会话管线构建耗时分布（样本 ≥ 60 条）**：
| 指标 | 范围 | 中位/典型 |
|---|---|---|
| Prewarmed pipeline（每条） | 184 ~ 2040 ms | ~600-1600 ms |
| 懒构建 Constructed pipeline（每槽位） | 178 ~ 1976 ms | ~200-900 ms |
| terrain programs（每槽位） | 12 ~ 166 ms | ~25-75 ms |
| 槽位总 warm-up | 205 ~ 2095 ms | ~250-900 ms |

**temporal attachment 重置尖峰（2026-08-27-4, 14:08:12）**：
```
reflectionPasses=578, avg=2.65 ms, max=816.36 ms, temporalAttachmentResets=4, maxPendingViews=5
```
→ 光影下多视图切换可产生 **816ms** 单帧停顿（TAA/时序缓冲重置）。

### 4.2 场景② —— 镜面渲染帧率 / 多面

**原版（latest.log）**：
| 可见镜面数 | passes/120帧 | 每帧 pass | avg/单pass | 渲染线程增量/帧 |
|---|---|---|---|---|
| 1 | 120 | 1 | 0.65~1.2 ms | ~0.7-1.2 ms |
| 4（稳态 22:44） | 480 | 4 | 1.02~1.44 ms | **~4.1-5.8 ms** |
| 9（峰值 22:44:00） | 627 | 5.2 | 0.75 ms | ~3.9 ms（窗口内变化） |

**光影（2026-08-27-4 会话）**：
| 可见镜面数 | passes/120帧 | 每帧 pass | avg/单pass | 渲染线程增量/帧 |
|---|---|---|---|---|
| 1（稳态 14:08:28-47） | 120 | 1 | 1.79~2.31 ms | **~1.8-2.3 ms** |
| 4 | 480 | 4 | 1.35 ms | ~5.4 ms |
| 10 | 1200 | 10 | 1.36 ms | ~13.6 ms |
| 17（14:02-14:06） | 2040 | 17 | 1.05~1.15 ms | **~18-23 ms** |

→ **多镜开销近似线性**（pass 数 = 镜面数 × 120），且光影单 pass 更贵（2.5~3×），17 镜场景已完全耗尽 60fps 的 16.7ms 帧预算。

### 4.3 场景③ —— RECURSIVE 多次反射

- **原版**：`22:43:58` `passes=280, maxPendingViews=4`（镜面开始互见，pass 数 > 直射基线）；`22:44:00` `passes=627, maxPendingViews=9`（递归链使 pass 数超出直射基线）。
- **光影 + 递归**（2026-08-27-4）：
  - `14:00:09 passes=1200, maxPendingViews=10` → `14:00:58 passes=1136, maxPendingViews=17` → `14:02:15~14:06:13 passes=2040, maxPendingViews=17~27` → `14:06:13 passes=2200, maxPendingViews=27`（**18.3 pass/帧**）。
  - 递归深度下**管线槽位爆炸**：日志出现 `recursionDepth=0,1,2,3` × `resolutionBucket=256/512/1024` 的组合（最多 12 槽位），每槽位 `Constructed ... 178~1976ms`。
- 从代码看：`requestRecursive()` 为每条 `parentChain × depth` 生成链隔离纹理，`processPending()` 按 depth 逆序串行刷新；因此“互见镜面数 × 递归深度”直接决定额外 pass 数。

### 4.4 场景④ —— 开启光影

- **单 pass 成本**：光影 ~1.8-2.3ms vs 原版 ~0.65-0.9ms（≈2.5~3×）。
- **管线/地形一次性成本**：如上表（184~2040ms / 12~166ms）。
- **捕获分辨率**：`MirrorCapturePool` 在 shader 分支下 `MIN_SHADER_LONG_EDGE=256`（原版 `MIN_BUCKET_SIZE=16`），且 `shaderSamplingCompensation()=visibleFraction(0.02)/visibleFraction(0.14)=0.96/0.72=1.333×`，即捕获尺寸再乘 1.333 → **像素量 ≈1.78×**，从 256×256 起步（原版单镜 14×14 → 16×16）。
- **兼容性补丁命中率**：本整合包 Complementary v4.4 会话 `shaderCompatibilityPatches=0`、`deferredPipelineBuilds=0`（预热池覆盖、无需 GLSL 补丁），说明 D4 路径在本包无额外成本，但保留为潜在风险点。

---

## 5. 性能压力来源与根源

### 5.1 架构根源：逐镜面“完整世界重渲染”的 N 倍放大
`GameRendererMixin` 在主世界 `renderLevel` 返回后（TAIL）调用 `processPending()`，它对每个待渲染镜面依次调用 `MirrorReflectionTexture.render()` → `MirrorLevelRenderer.render()` → `levelRenderer.renderLevel()`。这意味着**每个镜面 = 一次完整世界渲染**（地形 + 实体 + 方块实体 + 天空），而不是复用主帧的 G-buffer/深度。这是场景②和③的成本主导项，实测呈线性（4 镜≈5ms，17 镜≈18-23ms）。

### 5.2 每 pass 固定开销：GL 状态快照/恢复 + 相机/剔除重建
`MirrorRenderState.capture()` 在每个 pass 前读取上百项 GL/`RenderSystem` 状态（`glGetIntegerv/glGetFloatv/glIsEnabled` + 32 个纹理单元遍历 + 反射字段），`restore()` 再全部回写。`MirrorLevelRendererHooks.prepare()`（非 Embeddium）每 pass 重跑 BFS 遮挡剔除。这些固定开销解释了单镜稳态仍有 ~0.7ms（原版）/~2ms（光影）而非趋近于 0。

### 5.3 光影根源：每（深度×分辨率）槽位的独立 Iris 管线 + 大捕获面
`MirrorPassContext.PipelineSlot(recursionDepth, resolutionBucket)` 让 Oculus 管线按“递归深度 × 分辨率桶”隔离；每次进入新槽位要在渲染线程构造完整 `IrisRenderingPipeline`（178~1976ms）+ 地形程序（12~166ms）。同时 shader 分支把捕获下限抬到 256×256 并乘 1.333× overscan，放大填充/带宽。二者叠加使“光影 + 递归”成为最重组合（12 槽位 × ~数百 ms）。

### 5.4 时序/抖动根源
- 首帧建立（A1/A2/A5）→ 159ms 原版尖峰、数百 ms 光影预热。
- 光影 temporal 历史跨视图共享同一槽位 → 视图切换必须重置（`temporalAttachmentResets`），实测 816ms 尖峰。
- 区块装载/状态重建期 → 稳态中 5~13ms 偶发抖动。

---

## 6. 优化方案（性能侧，不改变模组机制）

> 原则：以下方案均保持“平面镜反射几何、递归语义、Oculus 兼容、远景 LOD、末影人联动”等既有机制不变，仅改实现路径/缓存/预算策略。按收益/风险排序。

### P0（高收益，低风险）—— 分帧预算 + 限流，避免渲染线程被 N 镜拖垮
- **问题**：`processPending()` 无上限地在一帧内串行渲染所有待处理镜面。
- **方案**：引入“每帧反射预算”（数量或累计耗时，如 4~6ms），超出部分顺延至下一帧；已有 `frameIndex`/`PENDING` 结构天然支持。对递归深度同样施加“每帧最大递归 pass 数”上限。
- **机制影响**：无。反射纹理仍每帧刷新，只是当超过预算时分帧，最坏情况镜面内容滞后 1 帧（与现有“落后一帧”设计一致）。
- **量化预期**：17 镜光影场景由 ~18-23ms/帧收敛到预算上限（如 ~6ms/帧），FPS 不再随镜面数崩塌。

### P0（高收益）—— 复用主帧深度/剔除结果，降低每 pass 固定成本
- **问题**：`MirrorRenderState.capture()` 每 pass 做上百次 GL 查询；`prepare()` 每 pass 重跑 BFS。
- **方案**：
  1. 将 `capture()` 的完整快照改为“差分快照”——仅在首个 pass 全量快照，后续 pass 只记录/恢复本 pass 实际改动的状态子集（当前 `render()` 的改动面是可枚举的）。
  2. 非 Embeddium 路径：缓存 BFS 结果（key = 相机 chunk + 朝向），相机未跨越 section 边界时不重算。
- **机制影响**：无（纯实现优化）。
- **量化预期**：单镜原版 ~0.7ms 中相当部分为固定开销，预计可压缩 20~40%。

### P1（高收益）—— 光影管线“分辨率桶合并 + 更早/更省预热”
- **问题**：`PipelineSlot` 按（深度, 分辨率桶）笛卡尔建管线，桶数多（256/512/1024…）时槽位爆炸；预热在渲染线程同步做。
- **方案**：
  1. 合并相邻分辨率桶（如 256/512 合并为单一“够用即用”桶，仅在大镜面时升桶），减少槽位数。
  2. 预热阶段当前同步执行 2 条管线；改为“1 条同步保底 + 其余后台/首帧后摊销”，并复用 `heavyBuildBudget`（已存在）把懒构建严格限到 1/帧。
- **机制影响**：无（反射分辨率仍由 `resolutionScale`/LOD 决定，仅内部桶粒度变化，视觉无损）。
- **量化预期**：消除“开光影即 600ms+ 冻结”；递归深度×桶的槽位数从 ~12 降至 ~4-6。

### P1（高收益）—— temporal 重置的“视图亲和”调度
- **问题**：多视图共享同一槽位时频繁 `temporalAttachmentResets`（816ms 尖峰，1~10 次/窗口）。
- **方案**：`processPending()` 排序时，将同槽位、同 `viewId` 的 pass 连续调度，减少同槽位内跨视图切换；跨视图时才重置。可进一步对稳定视图长期保留 temporal 历史。
- **机制影响**：无（时序滤波本身跨视图本就不应共享，当前已在重置，只是减少不必要切换）。
- **量化预期**：消除/大幅减少 816ms 级尖峰，降低每窗口 reset 计数。

### P2（中收益）—— 光影捕获尺寸分级，避免小镜面也被抬到 256×256
- **问题**：shader 分支对所有镜面强制 `MIN_SHADER_LONG_EDGE=256` + 1.333× overscan。
- **方案**：将 shader 捕获下限改为按镜面屏幕尺寸分级（如 128/256/512 三档），并仅在“TAA/SSR 等全屏核会对边缘采样”的光影下启用 0.14 guard band，否则降为 0.05 档。
- **机制影响**：无（guard band 是抗边缘伪影的余量，分级仍保证无边缘伪影；若担心个别光影，可保留开关）。
- **量化预期**：小镜面填充像素量减少 ~2-4×，多镜光影场景整体填充下降明显。

### P2（中收益）—— 递归 pass 的“分辨率衰减”前置到捕获
- **问题**：`recursiveResolutionDecay=0.5` 作用于纹理尺寸，但捕获池仍按桶上限分配，深层 pass 可能浪费像素。
- **方案**：确保深层 pass 捕获尺寸严格跟随衰减后的纹理尺寸，并让 `MirrorCapturePool` 桶选择对深层优先取更小桶。
- **机制影响**：无（深层反射本就低分辨率，属预期视觉行为）。
- **量化预期**：深层递归 pass 填充成本随深度更快下降。

### P3（低收益/防御）—— 兼容性补丁与状态恢复的微优化
- `OculusShaderSourceCompatibility.patch()` 当前对每次编译都跑 4 个正则；可先按 shader 源 hash 缓存判定结果（命中率低时跳过）。
- `MirrorRenderState.restore()` 中 Oculus `_glUseProgram` 双写可改为条件写（仅在绑定变化时）。
- 这些是常数级优化，收益小，建议并入 P0/P1 实施。

---

## 7. 量化总结

1. **成本模型**：每帧镜面开销 ≈ Σ(每镜面 1 次世界重渲染)。原版单 pass ≈0.7-1.4ms，光影单 pass ≈1.8-2.3ms（≈2.5~3×）。
2. **多镜**：4 镜≈5ms/帧、17 镜≈18-23ms/帧，线性增长 → 需 P0 分帧预算兜底。
3. **递归**：pass 数随“互见镜面 × 深度”叠加，光影下叠加“深度×分辨率桶”管线槽位爆炸（实测 depth0-3，最多 ~12 槽位，各 178~1976ms）→ 需 P1 桶合并 + 预热策略。
4. **光影一次性成本**：管线预热 184~2040ms、地形编译 12~166ms、temporal 重置 816ms 尖峰 → 需 P0/P1 摊销与调度。
5. **首帧建立**：原版 159ms 尖峰 → 需 P0 差分快照与剔除缓存。
6. **可保持机制不变**：所有方案均不改动反射几何/递归语义/Oculus 兼容/远景 LOD/末影人联动，仅优化实现路径与预算策略。

**建议实施顺序**：P0（分帧预算 + 差分快照/剔除缓存）→ P1（光影桶合并/预热摊销/temporal 调度）→ P2（捕获尺寸分级 + 递归衰减前置）→ P3（常数级微优化）。预计可将“多镜光影 + 递归”最坏场景的渲染线程开销从 18-23ms/帧 收敛到可控预算内，并将开光影/进世界的首次卡顿从秒级降至百毫秒级。