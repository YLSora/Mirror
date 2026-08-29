# ②① 进一步修复 —— 补充记录

## 本轮新增改动（3 处）

### ① 二次反射整体抽动（深度1 顺延 + 反射眼量化）
1. `MirrorTextureManager.processPending`：预算顺延阈值由 `depth > 0` 改为 `depth > 1`。
   - 根因：depth 1（可见的"镜中镜"）被顺延后纹理停一帧，追上时整体跳变 → 抽动。改为只顺延 depth>=2（深收敛小纹理），depth0/depth1 每帧刷新。
2. `MirrorLevelRenderer.resolveReflectionPath`：递归眼（path 非空）量化到 1/64 方块网格。
   - 根因：二次反射把主眼亚像素抖动放大，加上递归分辨率衰减（0.5×）后，亚像素漂移变成整像素跳变。量化反射眼使递归相机与投影 crop 稳定；直接视图（path 空）不做量化。

### ② 玩家实体阴影（depth1 阴影图同样为空）
3. `OculusMirrorShadowPassMixin`：取消条件由 `isRecursivePass()`（depth>0）改为 `isDeepPass()`（depth>=2）。
   - 根因：Iris/Oculus 阴影图按管线持有，depth1 管线阴影图同样为空。现在 depth0 与 depth1 都允许 `renderShadows` 写入自身阴影图，仅 depth>=2 深收敛纹理跳过（小且低分辨率）。

## 实测（Photon 1.3b / Complementary，递归场景 maxPendingViews=12）

| 项 | 结果 |
|---|---|
| 崩溃 | 无（无 "Shadow program requested"） |
| GL 错误 | 0 |
| tweakeroo 冲突 | 已解决（tweakeroo 被跳过，Mirror priority 1100 胜出） |
| 递归视图 | maxPendingViews=12 |
| 递归时域冻结 | temporalAttachmentResets≈1000~1080/120帧 |
| 顺延 | **deferredViews=0**（depth1 不再被顺延） |
| 稳态 pass | avg≈1.2~1.9ms |

机制验证全部通过。阴影/抽动的最终视觉确认需有图像输入的客户端复核。

## 累计修改文件（②①相关）
- `LevelRendererMixin.java`（priority 1100）
- `OculusMirrorShadowPassMixin.java`（仅 depth>=2 跳过阴影）
- `OculusMirrorTemporalStateMixin.java`（递归视图逐帧全清）
- `MirrorTextureManager.java`（顺延阈值 depth>1）
- `MirrorLevelRenderer.java`（递归眼量化）
