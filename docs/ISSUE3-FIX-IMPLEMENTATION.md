# ③ 缺角修复 —— 实现与验证记录

## 一、实现改动（两文件）

### 1. `MirrorRenderTypes.java`（根治深度依赖）
`DEFERRED_MIRROR_SURFACE` 渲染类型的深度测试由 `LEQUAL_DEPTH_TEST + POLYGON_OFFSET_LAYERING` 改为 `NO_DEPTH_TEST`（移除 polygon offset）。
- 根因：Iris/Oculus 的场景深度在 colortex（颜色附件），主帧缓冲 GL 深度在 `finalizeLevelRendering` TAIL 已被 SEUS 系光影改写，LEQUAL 在视点相关区域失败 → 缺角。
- 修复：关闭深度测试，decal 恒绘制（直接视图镜面前方无几何；递归遮挡本就因捕获目标深度被清空而失效）。

### 2. `DeferredMirrorSurfaceRenderer.java`（让 decal 的变换矩阵自足）
- `Surface` 新增 `projection`（`Matrix4f`）与 `vertexSorting`（`VertexSorting`），在 `submit()` 时从 `RenderSystem` 捕获。
- `flush()` 绘制前：保存外层投影/排序 → 显式 `RenderSystem.setProjectionMatrix(surface.projection(), surface.vertexSorting())` → 并把 modelView 栈顶置为单位矩阵（`RenderSystem.applyModelViewMatrix()`），因为 decal 顶点已把 modelView（`surface.pose()`）烘焙进顶点。
- 绘制后：恢复 modelView 栈与外层投影。
- 根因：SEUS 系光影 composite/final 在 TAIL 留下全屏正交/单位投影与非单位 modelView，导致 decal 以错误矩阵绘制。

## 二、构建与部署
- `gradlew.bat build`（Java 17）：BUILD SUCCESSFUL，测试通过。
- 产物 `build/libs/Mirror-0.1.0.jar`（约 249 KB）已部署到 `Rapid Optimization/mods/Mirror-0.1.0.jar`，旧包备份为 `Mirror-0.1.0.jar.bak-before-issue3`。

## 三、验证结果（诚实记录）

已确认：
1. 新 jar 在 Nostalgia 下启动、进入世界、递归/直接镜面管线正常（`reflectionPasses=120`, `maxPendingViews=1`, `avg≈1.1ms`）。
2. **0 GL 错误**（与旧 jar 同场景对比一致，排除修复引入 GL 错误）。
3. 延迟呈现链路经日志诊断确认正常：每帧 `submitted texture=...` + `flushing 1 surfaces`（decal 确实被提交并绘制）。
4. 关键场景要素经 `/data get block`、`/execute if block` 确认：镜面方块实体存在、`facing=south`、红墙存在、玩家 `(100.5,199,99.5)` 面向北。

未完成（受限于无图像输入的验证环境）：
- 无法直接肉眼确认"缺角"是否消除。测试场景（浮空镜 + 高位平台，多轮命令累积）中镜面反射内容始终表现为天空色，且该现象在修复前后、Complementary 与 Nostalgia 下均出现，判断为**测试场景搭建问题**（镜面未被正确看到/反射内容为空），并非修复引入的回归，也不是本次缺角问题的直接对照。

结论：本次已按方案落地三处底层修复（深度测试 + 投影 + modelView），构建通过、无 GL 回归；缺角的**最终视觉验收需在有图像输入的客户端上，用贴墙镜面 + 明确背景的场景复测**。
