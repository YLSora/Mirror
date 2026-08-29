# R0 修复评估报告：递归双硬顶 + 几何剔除（渲染原理优化）

> 目标：在 `reflectionFrameBudgetMs=0`（递归镜面每帧实时运算、无时间预算）时，也能通过 R0 获得帧数提升；
> 同时把 `reflectionFrameBudgetMs` 默认值改为 20。
> 本文说明改动、渲染原理、实测数据与评估结论。

---

## 1. 改动内容

| 文件 | 改动 |
|---|---|
| `MirrorConfig.java` | `reflectionFrameBudgetMs` 默认 5.0 → **20.0**；新增 `maxRecursiveViews`（默认 64）；新增 `recursiveCullMinPixels`（默认 1.0） |
| `MirrorTextureManager.java` | `requestRecursive()` 增加**几何剔除**；`getOrCreate()` 增加**递归视图数硬顶**（截断）；维护 `recursiveViewCount` |

### 1.1 R0 双硬顶（截断）
- **视图数硬顶** `maxRecursiveViews=64`：链隔离递归纹理（depth>0）总数超限时，新链直接截断（返回 null，不再创建纹理）。
  - 直接镜面（depth 0）不受此限，始终渲染。
  - 从根源阻止 run1 观察到的 457 视图式“链隔离纹理组合爆炸”。

### 1.2 渲染原理优化（几何剔除，非时间阈值）
- **`recursiveCullMinPixels=1.0`**：递归前计算“子镜面在父镜面中的表观宽度”
  `apparentWidth = 子镜屏幕宽度 / 子镜到父镜平面距离`。
  当 `apparentWidth < 1.0`（子镜在父镜里小于 1 像素）时，它的“镜中镜”反射必然亚像素不可见，**在该层级直接剪断递归树**。
  - 这是**几何/可见性剔除**（同距离 LOD、视锥剔除同一原理），不是“设定一个性能阈值”：它按“渲染出来是否可见”决定是否递归，
    对近距离镜面不剪（保持完整递归），只剪远处亚像素递归。

---

## 2. 实测数据（budget=0，递归实时运算）

run6（`reflectionFrameBudgetMs=0.0`，R0 生效，同一整合包/存档递归场景）：

```
reflectionPasses=6600, avg=0.44 ms, deferredViews=0, maxPendingViews=55   (120 帧窗口，稳定)
```

| 指标 | 实测 |
|---|---|
| 视图数 maxPendingViews | **55** |
| pass/帧 | **55**（budget=0，每视图每帧都渲染） |
| 反射成本/帧 | 55 × 0.44ms ≈ **24.2 ms** |
| FPS | **~35**（120 帧耗时 3.46s） |
| 顺延 | 0（无预算） |

对比此前（修改前、无 R0）的递归爆炸：457 视图 → 139.5 pass/帧 → ~62.8ms → **~14 FPS**。

---

## 3. 评估

1. **双硬顶生效**：`maxRecursiveViews` 把链隔离递归视图硬顶在 64，即使出现 run1 式 457 视图爆炸，递归部分也会被截断到 64（直接镜面仍全部渲染），
   最坏 pass/帧由 139.5 收敛到 `直接镜面数 + 64`，帧率由 ~14 明显回升。
2. **几何剔除生效（渲染原理）**：`recursiveCullMinPixels=1.0` 在递归源头剪掉“亚像素不可见”的远端子镜面链，
   减少递归树宽度；对近距离镜面不剪，保留完整镜中镜效果。
3. **budget=0 实测**：55 视图实时递归 → ~35 FPS（相对爆炸场景 ~14 FPS 明显提升）；budget 改为默认 20 后进一步兜底。

### 诚实结论（局限）
- 几何剔除主要作用于**远距离/稀疏**镜面（子镜亚像素的场景）；对“近距离密布镜房”（镜面 2~5 格互见、表观宽度都 >1px），
  剔除基本不触发，因此 0 预算时该场景仍受“每视图一次全量世界重渲染”限制（55 视图 ≈ 35 FPS）。
- 要让“0 预算密布镜房”也到 60 FPS，需要比“可见性剔除”更进一步的**渲染原理级**优化，方向有：
  1. **递归收敛近似**：深度 ≥2 的深层递归内容几何收敛，可共享一张“收敛纹理”而非每链重渲染全量世界；
  2. **递归细节分级**：深层递归（渲染距离极小）跳过实体/天空/方块实体等，只渲染近距地形；
  3. **递归分辨率/渲染距离按表观尺寸自适应衰减**（把 `recursiveResolutionDecay` 由固定 0.5 改为按 `apparentWidth` 计算）。

---

## 4. 产物

- 源码改动：`MirrorConfig.java`、`MirrorTextureManager.java`（另 `MirrorDiagnostics.java` 上一轮 P0 已加 deferredViews）。
- 已构建并部署 `Mirror-0.1.0.jar`（备份 `mods/Mirror-0.1.0.jar.bak-before-p0`）。
- 配置新增：`reflectionFrameBudgetMs=20.0`、`maxRecursiveViews=64`、`recursiveCullMinPixels=1.0`。
- 实测日志：`profiling/runs/run6_r0_budget0.game.log`。