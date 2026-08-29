# 递归收敛近似（深度≥2 复用直接反射）—— 算法设计与实测评估

> 目标：深度≤1 追求高分辨率 + 实时帧响应；深度≥2 追求高效率（利用几何收敛，避免逐层全量世界重渲染）。
> 在 `reflectionFrameBudgetMs=0`（递归每帧实时运算、无时间预算）下验证帧数提升。

---

## 1. 渲染原理与算法

**几何收敛事实**：镜面每多反射一次，图像按 `(镜面尺寸/距离)` 的因子缩小。深度 2 之后通常已接近亚像素，
深层内容趋向于“镜面自身视角”的稳定图（无限镜隧道的收敛像）。

**算法**：`requestRecursive(mirror)` 在 `depth >= 2` 时不再创建链隔离纹理、不再做世界重渲染，
而是**直接复用该镜面的 depth-0 直接反射纹理**。

**为何隧道的视觉不丢**：depth-0 与 depth-1 已经互相采样——
- depth-0（镜 A）渲染时采样 depth-1（镜 B）；
- depth-1（镜 B）渲染时采样 depth-2 = 复用 A 的 depth-0。

于是 `A(depth0) ↔ B(depth1)` 相互引用，自动渲染出“无限镜中镜”隧道，而**无需任何 depth≥2 的世界渲染**。
渲染顺序按 depth 升序（先所有 depth0、再所有 depth1），复用始终采样已合成完成的纹理，**无 GL 自采样/回环**。

**复杂度**：由 O(N^D)（链隔离纹理组合爆炸）降为 **O(N)（direct）+ O(N²)（depth-1，受视锥与几何剔除约束）**。
depth≥2 从“每链一次全量世界渲染”变为“零渲染（仅纹理采样）”。

---

## 2. 改动内容

| 文件 | 改动 |
|---|---|
| `MirrorConfig.java` | 新增 `recursiveConvergenceReuse`（默认 true） |
| `MirrorTextureManager.java` | `requestRecursive()`：`depth>=2` 且开关开启时走 `reuseDirectTexture()`；`reuseDirectTexture()` 复用 depth-0 纹理；`recursiveDimensions()` 衰减改为从 depth-2 起（depth-1 全分辨率） |

关键代码：
```java
if (depth >= 2 && MirrorConfig.CLIENT.recursiveConvergenceReuse.get()) {
    return reuseDirectTexture(mirror);   // 复用直接反射，零世界渲染
}
```

`recursiveDimensions()`：`decay ^ max(0, depth-1)` → **depth-1 全分辨率**（“较好的分辨率”），decay 仅作用于 depth≥2（现已被复用取代）。

---

## 3. 实测对比（A/B，同位置 -95.19, 63, 698，budget=0）

| 指标 | 复用 OFF（run8） | 复用 ON（run7） | 提升 |
|---|---|---|---|
| 视图数 maxPendingViews | 43 | **7** | 6.1× |
| pass/帧 | 43 | **7** | 6.1× |
| 单 pass 成本 | 0.40 ms | 0.53 ms（depth-1 全分辨率，略贵） | — |
| 反射成本/帧 | 43×0.40 ≈ **17.2 ms** | 7×0.53 ≈ **3.7 ms** | 4.6× |
| **FPS** | **~47** | **~180**（maxFps=260 上限内） | **3.8×** |

（实测日志：`profiling/runs/run7_convergence_on.game.log`、`run8_convergence_off.game.log`）

**结论**：在 **0 预算阈值、递归每帧实时运算**下，收敛复用使帧率从 ~47 提升到 ~180，
达成了“深度≥2 高效率渲染、且不靠时间阈值”的目标。

---

## 4. 视觉正确性说明

- **深度 0 / 1**：每帧全新全分辨率世界渲染，保证玩家正对的镜面与第一层“镜中镜”清晰、响应即时（符合“深度≤1 追求高分辨率与帧响应”）。
- **深度 ≥ 2**：复用 depth-0 纹理，通过 depth0↔depth1 相互采样继续呈现“无限镜隧道”；深层内容本就亚像素，复用带来的 LOD/视角近似不可见。
- **顺延/截断兜底**：仍保留 P0 每帧预算（默认 20ms）、R0 视图硬顶（64）与几何剔除（1px），三者作为极端场景的安全网。

---

## 5. 产物与后续

- 源码改动：`MirrorConfig.java`、`MirrorTextureManager.java`（复用 + depth-1 全分辨率）。
- 已构建部署 `Mirror-0.1.0.jar`；配置默认：`reflectionFrameBudgetMs=20.0`、`maxRecursiveViews=64`、`recursiveCullMinPixels=1.0`、`recursiveConvergenceReuse=true`。
- 后续可选项：对 depth-1 也施加按 `apparentWidth` 的分辨率自适应（进一步压缩近距密布镜房的 depth-1 成本），
  以及对 SHARED 模式复用同一机制；二者均不改动反射语义。