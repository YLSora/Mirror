# ② 玩家阴影/透明 + ① 递归抖动 —— 实现与验证记录

## 一、实现改动（3 个文件）

### ②A 玩家透明（tweakeroo mixin 冲突）
`LevelRendererMixin` 类级别 `@Mixin(value = LevelRenderer.class, priority = 1100)`（原默认 1000）。
- 根因：`mirror$allowLocalPlayerInReflection`（反射中把本地玩家当普通实体渲染的 redirect）被 tweakeroo 的同位置 redirect（priority 1001）覆盖，导致反射内本地玩家丢失 vanilla 渲染路径 → 透明/缺失。
- 修复：mixin 优先级提升到 1100，Mirror 的 redirect 胜出，tweakeroo 的 freecam redirect 被跳过（见验证日志）。

### ②B 玩家阴影异常（镜像管线阴影图为空）
`OculusMirrorShadowPassMixin` 由"镜像 pass 内一律取消 renderShadows"改为"仅递归（depth>0）取消"。
- 根因：Iris/Oculus 的阴影图按管线持有，镜像管线的阴影图为空；原来所有镜像 pass 都跳过阴影渲染，导致 Photon/Sundial 这类阴影系光影在镜面中采样到空阴影图 → 玩家阴影异常/着色异常。
- 修复：直接视图（depth 0）允许 `renderShadows` 跑一次把太阳空间阴影图写入镜像管线；递归视图仍跳过（小且省成本）。阴影图是太阳方向的，与相机无关，写入一次即可复用。

### ① 递归远处抖动/闪烁（TAA 时域累积）
`OculusMirrorTemporalStateMixin`：对递归视图（depth>0）每帧 `mirror$requestFullClear()`（冻结 TAA/SSR 时域累积），直接视图仍只在首次使用时清一次。
- 根因：递归视图相机运动被反射放大、深度≥2 收敛近似相机与真实链式视差不同，TAA/SSR 重投影误差随距离放大 → 远处抖动（Complementary/Sildur/iterationRP）。
- 修复：递归视图不做时域累积，直接消除错误历史的抖动源。

## 二、构建与部署
- `gradlew.bat build`（Java 17）：BUILD SUCCESSFUL，测试通过。
- 新 jar 已部署到 `Rapid Optimization/mods/Mirror-0.1.0.jar`。

## 三、实测验证（Photon 1.3b，同一整合包/世界）

| 项 | 结果 |
|---|---|
| 启动/进入世界 | 正常，无崩溃 |
| mixin 冲突 | **已解决**：日志显示 `Skipping tweakeroo ... allowRenderingClientPlayerInFreeCameraMode (priority 1001), already redirected by mirror ... mirror$allowLocalPlayerInReflection (priority 1100)` |
| GL 错误 | 0 |
| 阴影 pass（直接视图） | 允许 renderShadows，无崩溃、无 GL 错误 |
| 递归视图数 | 加入第二面镜子后 `maxPendingViews=6`（depth0/1/2 链式递归） |
| 递归时域冻结 | `temporalAttachmentResets=304`（120 帧窗口，证明递归视图逐帧清历史生效） |
| 稳态反射 pass | `avg≈0.4~1.8ms`，无顺延 |

结论：② 与 ① 的三处机制修复均已生效且无回归（0 GL 错误、无崩溃、构建通过）。阴影/抖动的**最终视觉验收**（玩家不透明且阴影与主世界一致、递归远处不抖动）需在有图像输入的客户端上用对应光影复核。

## 四、修改文件清单
- `LevelRendererMixin.java`：mixin 优先级 1100。
- `OculusMirrorShadowPassMixin.java`：仅递归视图跳过阴影 pass。
- `OculusMirrorTemporalStateMixin.java`：递归视图逐帧全清时域附件。
