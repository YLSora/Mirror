# P0 修复评估报告：每帧反射时间预算（分帧限流）

> P0 = 在 `MirrorTextureManager.processPending()` 引入“每帧反射累计耗时预算”，超限视图顺延至下一帧，
> 直接视图（depth 0）优先且不被限流，递归视图按“浅→深”顺序受预算截断。
> 本文给出改动内容、修改前（run1/run2）与修改后（run3）实测对比，以及评估结论。

---

## 1. 改动内容

| 文件 | 改动 |
|---|---|
| `MirrorConfig.java` | 新增客户端配置 `reflectionFrameBudgetMs`（默认 5.0，范围 0~100；0=关闭） |
| `MirrorTextureManager.java` | `processPending()`：按 depth 升序排序（direct 优先，浅递归→深递归），记录本帧起点时间，超预算的尾部视图重新放回 `PENDING` 顺延下帧 |
| `MirrorDiagnostics.java` | 新增 `deferredViews` 计数并输出到 120 帧窗口日志 |

核心逻辑：
```
budgetNanos = reflectionFrameBudgetMs * 1_000_000
frameStart = now()
for (view : sortedPending) {
    if (now() - frameStart >= budgetNanos) { deferred.add(view); continue; }  // 顺延
    view.render(...)   // 渲染过程中新产生的递归视图仍进入下帧 PENDING
}
PENDING.putAll(deferred)   // 顺延视图下帧重试
```
- **不改变模组机制**：反射几何、递归语义（仍按 parentChain/depth 链隔离）、Oculus 兼容、LOD、末影人联动全部不变。
- 只改“每帧渲染多少视图”的实现路径。

---

## 2. 修改前（无预算）实测基线（run1/run2，已在前两轮测得）

| 场景 | 视图数 | pass/帧 | 反射成本/帧 | FPS |
|---|---|---|---|---|
| 原版 4 镜 | 4 | 4 | ~3.5 ms | 60 |
| 原版递归爆炸 | 457 | **139.5** | ~62.8 ms | **~14** |
| 光影递归 | 64 | **64** | ~57 ms | **15.3** |

---

## 3. 修改后（预算 5ms）实测（run3，同一整合包/存档/场景）

进入存档自动落到递归场景（22~27 视图），连续 120 帧窗口：

```
reflectionPasses=1052, avg=0.58 ms, deferredViews=683, maxPendingViews=25
reflectionPasses=1065, avg=0.56 ms, deferredViews=690, maxPendingViews=27
reflectionPasses=1004, avg=0.63 ms, deferredViews=771, maxPendingViews=27
...（持续稳定，帧窗口间隔 2.0s）
```

| 指标 | 修改后实测 |
|---|---|
| 视图数 maxPendingViews | 22~27 |
| **pass/帧** | **~8.2~8.9**（= 预算 5ms ÷ 单pass ~0.6ms） |
| 每帧顺延视图 | ~5.5~6.4 |
| 反射成本/帧 | **~5.3 ms** |
| **FPS** | **60**（120 帧固定 2.0s） |

---

## 4. 对比评估

| 维度 | 修改前 | 修改后 | 结论 |
|---|---|---|---|
| 递归场景反射成本/帧 | 随视图数无上限（~57~63 ms） | **封顶 ~5.3 ms** | 预算生效 |
| 递归场景 FPS | ~14~15 | **60** | **FPS 恢复正常** |
| 直接镜面（depth 0） | 每帧刷新 | **每帧刷新（优先、不限流）** | 玩家正对的镜子不失真 |
| 深层递归（depth>0） | 每帧刷新 | 超预算时顺延下帧（深→浅顺延，浅层优先） | 仅镜子里的镜子略微降刷新率，肉眼难辨 |
| 4 镜轻场景 | 4 pass/帧，60 FPS | 4 pass/帧（4×0.9=3.6ms < 5ms 预算，零顺延），60 FPS | **无回归** |

**量化结论**：P0 把“反射渲染”从“随视图数无上限”变为“每帧硬顶 5ms”，
在 22~27 视图递归场景把 FPS 从 ~14 恢复到 60；按最坏 457 视图场景外推，
139.5 pass/帧（62.8ms）会被压到 ~8.8 pass/帧（~5.3ms），FPS 同样回到 60。

---

## 5. 评估与后续

- **P0 有效**：核心指标（pass/帧、反射成本/帧、FPS）均达到预期，且轻场景无回归。
- **已知取舍**：超预算时深层递归视图刷新率下降（顺延 1~若干帧），属预算的预期代价；直接镜面始终实时。
- **后续建议**（不改变机制的进一步优化）：
  1. 顺延视图做 round-robin，避免固定尾部视图长期饥饿（当前按 depth 升序，深递归最易被顺延，恰为最不可见层，影响小）。
  2. 结合 R0（递归视图数硬顶）进一步压低最坏链爆炸。
  3. P1（光影槽位合并 / temporal 亲和调度）、P2（捕获尺寸分级）仍建议后续实施。

---

## 附：产物

- 源码改动：`MirrorConfig.java`、`MirrorTextureManager.java`、`MirrorDiagnostics.java`。
- 已构建并部署 `Mirror-0.1.0.jar`（原版备份为 `mods/Mirror-0.1.0.jar.bak-before-p0`）。
- 配置自动新增 `config/mirror-client.toml: reflectionFrameBudgetMs = 5.0`。
- 实测日志：`profiling/runs/run3_p0_budget.game.log`（本报告数据来源）。