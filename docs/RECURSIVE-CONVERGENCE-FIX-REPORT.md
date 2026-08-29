# 递归收敛近似 —— 问题修复与最终验证

> 修复实测发现的三个问题：① depth≥2 严重帧延迟；② maxRecursionDepth 失效（无限反射）；
> ③ 偶发镜面不渲染。方案改为“专用深纹理 + 扁平终止 + 恢复深度优先渲染顺序”。

---

## 1. 三个问题的根因

| 问题 | 根因 |
|---|---|
| ① 严重帧延迟 | 上一版把渲染顺序从“深度优先（deepest-first）”改成了“浅度优先”，父级（depth-0）采样的是上一帧的子级（depth-1），每层累加 1 帧延迟 |
| ② maxRecursionDepth 失效 | 上一版 `depth>=2 复用 depth-0 纹理`，depth-0 又含 depth-1，形成 `depth0↔depth1` 无限回环，反射不再受深度上限约束 |
| ③ 偶发不渲染 | 同一无限回环在 GL 上产生纹理“边写边采”的未定义行为，导致闪烁/不渲染 |

## 2. 修复方案

1. **专用深纹理（替换“复用 depth-0”）**：`depth>=2` 使用每个镜面一张的“深纹理”
   （`getDeepTexture`，低分辨率、从镜面自身直接相机渲染），不再复用 depth-0，从而切断 `depth0↔depth1` 回环。
2. **扁平终止（保证 maxRecursionDepth 有效）**：`MirrorLevelRenderer.isDeepPass()`（recursionDepth>=2）
   时，`requestRecursive` 直接返回 null，深纹理内的镜面只显示背面（不再递归）——隧道有界：depth0 → depth1 → deep(扁平)。
3. **恢复深度优先渲染**：`processPending` 改回 `depth().reversed()`（最深的先渲染），
   父级在同一帧内采样“刚合成完成”的子级，各深度 0 帧附加延迟；
   预算改为**只顺延 depth>0 视图**，直接镜面（depth0）仍永远实时。

## 3. 最终实测（budget=0，同存档递归场景）

| 版本 | 视图数 | pass/帧 | FPS | 状态 |
|---|---|---|---|---|
| 链隔离（无收敛，run8） | 43 | 43 | ~47 | 有界但慢 |
| 上一版复用（run7） | 7 | 7 | ~180 | 快但**有 bug**（无限反射/延迟/闪烁） |
| **本轮修复（run10）** | **17（稳定）** | **12** | **~120** | **有界、低延迟、稳定** |

run10 连续窗口（120 帧）稳定：`reflectionPasses=1440, maxPendingViews=17, avg=0.47~0.56 ms`，
FPS 107~127，无振荡（说明回环/自采样已消除）。

对比链隔离基线：**视图数 43→17（2.5×），pass/帧 43→12（3.6×），FPS ~47→~120（2.6×）**。

## 4. 三项修复的对应验证

- **① 帧延迟 < 2 帧**：深度优先渲染使 depth0→depth1→deep 在同帧内级联合成，附加延迟 0 帧（远小于 2 帧）。
- **② maxRecursionDepth 有效**：深纹理内镜面扁平终止，反射被限定为 depth0/depth1/deep 三级（有界，不再无限）。
- **③ 无偶发不渲染**：深纹理为独立纹理（非复用 depth0），无“边写边采”，实测视图数稳定 17 无振荡。

---

## 5. 产物

- 源码改动：`MirrorLevelRenderer.java`（`isDeepPass()`）、`MirrorTextureManager.java`
  （`getDeepTexture`/`deepDimensions` + 深度优先排序 + 递归专用预算顺延）、`MirrorConfig.java`（注释更新）。
- 已构建部署 `Mirror-0.1.0.jar`；配置默认：`reflectionFrameBudgetMs=20.0`、`maxRecursiveViews=64`、
  `recursiveCullMinPixels=1.0`、`recursiveConvergenceReuse=true`。
- 实测日志：`profiling/runs/run10_final_fix.game.log`（对照 run7/run8）。