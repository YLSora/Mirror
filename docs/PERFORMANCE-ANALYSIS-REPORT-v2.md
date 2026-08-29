# Mirror 模组性能实测报告（第二轮：实时启动监控 + 与首轮对比 + 优化方案修订）

> 本轮通过外部工具真实启动 `Rapid Optimization` 整合包，进入存档后对四大场景进行实时监控并记录数据，
> 与首轮（纯日志/静态分析）对比，并针对 **RECURSIVE（递归）** 重点修订优化方案。
> 测试全程未改动模组代码；仅临时切换 Oculus 光影开关用于场景④，测试后已恢复。

---

## 0. 摘要（本轮新结论）

| 结论 | 说明 |
|---|---|
| 首轮分析得到实测验证 | 四大瓶颈的量化值与首轮日志分析高度吻合 |
| **重大新发现：递归可“爆炸”** | 实测 `maxPendingViews=457`、`139.5 pass/帧`，帧率 **60→~14 FPS**（远超首轮观察到的 17-27 视图） |
| 光影+递归同样致命 | 64 视图 → 64 pass/帧 → ~57ms/帧 → **15.3 FPS** |
| 线程压力得到 JFR 证实 | 镜面反射全部串行在 **Render thread**（最忙线程，26.6% 采样） |
| 优化方案优先级调整 | **P0 分帧预算/限流 + 新增“递归视图数硬顶”上调为最高优先** |

---

## 1. 测试方法与环境

- **启动方式**：解析 `Rapid Optimization.json` 版本清单，用 `D:\java21\bin\java.exe` + `cpw.mods.bootstraplauncher.BootstrapLauncher` 直接拉起 Forge 客户端；`--quickPlaySingleplayer 新的世界` 自动进入存档。
  - 关键坑：客户端 jar 是 **class file 65.0（Java 21 重编译）**，必须用 Java 21（Java 17 会报 `UnsupportedClassVersionError`）。
  - 命令行空格路径需正确引号（首版启动失败即此原因）。
- **监控手段**（外部工具）：
  1. 模组内置 `MirrorDiagnostics` 120 帧窗口（`latest.log`）。
  2. **JFR**（`-XX:StartFlightRecording=...,settings=profile,duration=240s`）→ `jfr summary`/`jfr print` 做线程/方法采样。
  3. **FPS 反演**：diagnostics 每 120 帧一条，`FPS = 120 / 相邻时间戳差`（零侵入）。
  4. `jfr`/`Get-Process` 等系统工具读进程/线程数据。
- **场景控制**：GUI 自动化（`SetForegroundWindow` + `SendKeys`）注入 `/tp`、`/setblock` 命令，构造/定位递归场景。

---

## 2. 四场景实时实测数据

### 场景① —— 创建镜面渲染的线程压力

**原版首帧/预热（4 镜，进入存档后首个 120 帧窗口）**：
```
reflectionPasses=454, avg=6.11 ms, max=97.00 ms, maxPendingViews=4   ← 首窗口
reflectionPasses=480, avg=2.30 ms → 1.65 → 1.29 → 1.04 ms ...      ← 快速收敛
稳态：avg 0.78~0.97 ms，偶发 max 93.22 / 9.53 / 7.40 ms            ← 抖动
```

**光影管线构建（Complementary v4.4，开启光影瞬间）**：
```
Prewarmed mirror pipeline 1/2 ... 494.03 ms
Prewarmed mirror pipeline 2/2 ... 338.91 ms
Prewarmed mirror terrain programs ... 47.67 ms (total 1768.07 ms)
Prewarmed mirror terrain programs ... 34.87 ms (total 1305.21 ms)
Constructed mirror pipeline recursionDepth=2 (256x256) ... 346.75 ms
Constructed mirror pipeline recursionDepth=3 (256x256) ... 377.80 ms
```

**JFR 线程压力证据**（240s，6604 采样）：
- **Render thread 1757 采样（26.6%）为最忙线程**；Server thread 1273、CullThread 328、10 个 Chunk Render Task Executor 各 ~240。
- 命中 `com.mirror` 的 161 采样全部集中在 Render thread：`MirrorLevelRenderer.render`(49)、`MirrorReflectionTexture.render`(24)、`MirrorTextureManager.processPending`(20)。
- → **镜面反射世界渲染完全串行在渲染线程，与主世界渲染竞争同一 16.67ms 帧预算**，无任何并行/分帧。

### 场景② —— 多镜面帧率

| 镜面数 | pass/帧 | 单pass | 反射成本/帧 | FPS |
|---|---|---|---|---|
| 1 | 1 | 0.90~1.00 ms | ~0.9-1.0 ms | 60 |
| 4（稳态） | 4 | 0.78~0.97 ms | **~3.1-3.9 ms** | 60 |

（与首轮 4 镜≈5ms/帧、单镜≈0.7-1.2ms 基本一致；本机 RTX 5070 Ti 使单 pass 略快。）

### 场景③ —— RECURSIVE 递归（重点）

**原版递归爆炸（玩家进入多镜互见房间时）**：
```
00:26:48  passes=121,  views=4
00:26:57  passes=16742, views=457   ← 爆炸：16742/120 = 139.5 pass/帧
00:26:59  passes=2281,  views=63
00:27:01  passes=2919,  views=42
00:27:03  passes=1852,  views=42
00:27:08  passes=7603,  views=119
```
- **457 视图、139.5 pass/帧、avg 0.45ms/单pass → ~62.8ms/帧 → 帧率 60→~14 FPS**（120 帧耗时 8.4s）。
- 递归 pass 数随“互见镜面数 × 递归深度”**组合式爆炸**，而非线性增长。

**光影 + 递归（稳态）**：
```
maxPendingViews=64, reflectionPasses=7680/120 = 64 pass/帧
avg 0.85~0.92 ms/单pass → ~54~59 ms/帧 → FPS = 15.3~16.2
单帧 max 峰值 460.60 ms（首个窗口，temporalAttachmentResets=64）
```

### 场景④ —— 光影

| 指标 | 实测 | 说明 |
|---|---|---|
| 管线预热（2 条） | 494 + 339 = **833 ms** | 渲染线程同步阻塞 |
| 每槽位懒构建 | **347 / 378 ms**（recursionDepth 2/3） | 递归槽位额外构建 |
| 地形程序 | 34.9 / 47.7 ms | |
| 单 pass 成本 | **0.85~1.64 ms**（vs 原版 0.78~0.97） | 约 1.1~1.9× |
| 捕获分辨率 | **512×512 / 256×256**（vs 原版 16×16） | 像素量放大 256~1024× |
| temporal 重置 | 首窗口 **64 次**，峰值 460ms | 视图切换代价 |

---

## 3. 与首轮对比

| 维度 | 首轮（日志分析） | 本轮（实测） | 结论 |
|---|---|---|---|
| 原版单镜 pass | 0.65~1.2 ms | 0.90~1.00 ms | 一致 |
| 原版 4 镜/帧 | ~5 ms | ~3.1-3.9 ms | 一致（本机略快） |
| 原版首帧尖峰 | 159 ms | **97 ms** | 一致（量级） |
| 光影管线预热 | 184~2040 ms | **339~494 ms** | 落在区间低端（POTATO 档） |
| 光影单 pass | 1.8~2.3 ms | 0.85~1.64 ms（递归低分辨率桶） | 一致（分桶差异） |
| **递归最大视图** | 17~27 | **457** | ⚠️ 大幅上调 |
| **递归最大 pass/帧** | ~18 | **139.5** | ⚠️ 大幅上调 |
| 递归导致 FPS | 未直接测得 | **60→~14（原版）/ 15.3（光影）** | 首次量化 |

**差异根因**：首轮仅看到 08-27 会话的“温和递归”（17-27 视图、~1 pass/视图），本轮通过构造“镜面互见”场景触发 `requestRecursive()` 的**链隔离纹理键组合爆炸**，暴露了真实上限。

---

## 4. RECURSIVE 递归——重点根因分析

**机制**（源码）：
- `MirrorTextureManager.requestRecursive()`（L53-69）为每条 `(mirrorId, parentChain, depth)` 生成一个**独立纹理键**。
- `processPending()`（L94-103）每帧把 `PENDING` 中**所有**待渲染视图在渲染线程串行刷新（无数量/耗时上限）。
- `MirrorPassContext.PipelineSlot(recursionDepth, resolutionBucket)` 使光影下管线槽位随（深度×分辨率）笛卡尔增长。

**量化模型**（实测拟合）：
- 设 N 为互见镜面数、D=maxRecursionDepth。链隔离视图数 ≈ N × (N−1)^(D−1)，呈**组合爆炸**；本轮 D=4 时实测视图数冲到 457、pass/帧冲到 139.5。
- 帧率影响：每 pass ~0.45ms（原版，递归分辨率衰减后）→ 139.5 pass/帧 ≈ 62.8ms，叠加主世界渲染后 **~14 FPS**；光影下单 pass ~0.89ms → 64 pass/帧 ≈ 57ms → **~15 FPS**。

**结论**：递归是四瓶颈中唯一能把帧率从 60 拉到 ~14 的**指数级**因素，必须优先治理。

---

## 5. 优化方案修订（RECURSIVE 优先，不改变模组机制）

> 原则不变：不改变平面镜反射几何、递归语义、Oculus 兼容、远景 LOD、末影人联动等既有机制，仅改实现路径/缓存/预算。

### R0（新增，最高优先）—— 递归“视图数 + pass 数”硬顶
- **问题**：`requestRecursive` 组合爆炸使 PENDING 视图数与每帧 pass 数无上限。
- **方案**：引入“最大并发递归视图数”（如 32/64）与“每帧最大递归 pass 数”（如 8~16）两个预算；超限的深层链丢弃/顺延（不渲染），保证**深度 0 直接反射永远优先**。
- **机制影响**：无。递归语义保留（仍按深度/链隔离），仅在极端互见场景截断过深的尾端反射，视觉上等价于“镜子里的镜子”多一两次就看不见，属既有 `maxRecursionDepth` 的自然延伸。
- **量化预期**：把 139.5 pass/帧、~14 FPS 的最坏场景压回预算内（如 ≤16 pass/帧），FPS 由 ~14 恢复到 ~50+。

### P0（上调优先级）—— 每帧反射总预算 + 串行→分批
- 首轮 P0 保留并强化：`processPending` 增加“每帧反射累计耗时预算”（4~6ms），超限剩余视图顺延下一帧；递归 pass 同样计入预算。
- 量化预期：17 镜光影、64 视图递归等场景由 18-57ms/帧 收敛到预算上限，FPS 不再随视图数崩塌。

### P0 —— GL 状态“差分快照” + 剔除缓存（不变）
- 每 pass 前 `MirrorRenderState.capture()` 上百次 GL 查询 → 首 pass 全量、后续 pass 差分。JFR 已证实该固定开销占用渲染线程。

### P1（上调）—— 光影“槽位合并 + temporal 视图亲和”
- 槽位按（深度, 分辨率桶）笛卡尔建管线 → 合并 256/512 桶、复用 `heavyBuildBudget` 限 1 槽/帧。
- **实测新增证据**：光影首个窗口 `temporalAttachmentResets=64` + 460ms 尖峰，直接验证了“跨视图共享槽位导致时序历史反复重置”的根因；排序时按 `viewId` 连续调度同槽位视图即可消除大部分重置。

### P2 —— 光影捕获尺寸分级 + 递归衰减前置到捕获（不变，证据强化）
- 实测捕获桶 512/256×256（vs 原版 16×16），是小镜面填充率的 256~1024× 浪费；分级后小镜面降桶，收益明确。

### P3 —— GLSL 补丁 hash 缓存 + `_glUseProgram` 条件写（不变，低优先）
- 本轮 Complementary v4.4 会话 `shaderCompatibilityPatches=0`，补丁路径无额外成本，但保留为防御项。

---

## 6. 量化总结（本轮实测）

| 场景 | 关键实测 | 帧率 |
|---|---|---|
| 原版 4 镜 | 4 pass/帧，~3.5ms | 60 FPS |
| 原版递归爆炸 | **139.5 pass/帧，~62.8ms** | **~14 FPS** |
| 光影预热 | 833ms + 每槽 347/378ms | 秒级冻结 |
| 光影递归 | 64 pass/帧，~57ms | **15.3 FPS** |
| 光影单 pass | 0.85-1.64ms（vs 原版 ~0.9ms） | — |

**实施顺序（修订）**：**R0（递归视图/pass 硬顶）** → P0（每帧反射预算 + 差分快照）→ P1（槽位合并 + temporal 亲和调度）→ P2（捕获尺寸分级）→ P3（微优化）。
预计可将最坏“递归/光影+递归”场景由 14~15 FPS 恢复到 50+ FPS，并把开光影/进世界的秒级卡顿降到百毫秒级，全程不改变模组机制。

---

## 附：测试环境与遗留

- 环境：AMD Ryzen 9 8945HX / RTX 5070 Ti Laptop / Java 21 / Forge 47.4.20 / Embeddium 0.3.31 / Oculus 1.8.0.1 / Complementary v4.4 (POTATO)。
- 数据文件：`profiling/runs/run1_vanilla_recursive.game.log`、`run2_shader.game.log`、`profiling/jfr/run1_vanilla_recursive.jfr`。
- 遗留：测试中向存档 `新的世界` 放置的 2 面测试镜已用 `/setblock air` 移除，玩家已 `/tp` 回出生点；`oculus.properties` 已恢复 `enableShaders=false`。
- 局限性：GUI 自动化无法精准控制视角，递归爆炸为进入互见镜房后的真实瞬态；如需更可控的递归数据，建议后续用 GameTest 结构（源码已有 `mirrorgridgametests`）构建固定递归场景复测。