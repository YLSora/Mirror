# Mirror × 沉浸车辆：第一人称手部、物品与快捷栏透明问题

## 2026-09-09 最终结论与验收

**剩余的手部、方块物品及快捷栏图标消失问题已定位并修复，用户确认表现问题解决。根因是 Mirror 把过期 EBO 重新写入了被镜面阶段复用的 VAO，破坏了 VertexBuffer 的 GPU 状态与 Java 缓存之间的一致性。** 不是本次故障 draw 的贴图绑定错误，也不是车辆必须处于乘坐状态才会主动隐藏手部。

本节取代下方历史调查中的“尚未定位”“尚未修改”等当前状态表述。历史记录保留用于追溯此前假设与用户对照，不应再按其中的旧行号、旧日志或待实施方案描述最终版本。

### 1. 同进程实测证据

实际整合包保持 MTS 24.0.0、Tweakerge 0.1.5、ImmediatelyFast、Embeddium 0.3.31 和 Oculus 1.8.0.1 加载，光影关闭。使用原存档、同一镜面和车辆，玩家未乘坐。临时诊断包只切换 `MirrorRenderState.restore()` 是否重新绑定捕获的 EBO；没有为对照卸载模组或修改配置。

采样直接覆盖 `GlStateManager._drawElements`，并在 `VertexBuffer.draw` 比对对象预期的 EBO 与真实 GL 绑定。铁块故障帧中 shader、FBO、投影、颜色、纹理逻辑值/缓存/实际绑定一致，实际索引却错误：

| 项目 | 原逻辑 | 只跳过 EBO 恢复 |
| --- | --- | --- |
| VAO | 74 | 74 |
| Java 预期 EBO | 7 | 7 |
| GPU 实际 EBO | 10 | 7 |
| 绘制 | TRIANGLES，36 个索引，UNSIGNED_INT | 相同 |
| 头六个实际索引 | 65536, 196610, 327684, 458758, 589832, 720906 | 0, 1, 2, 2, 3, 0 |
| 第一人称铁块 | 消失 | 正常 |
| 空手与缺失的快捷栏图标 | 消失 | 正常 |
| 镜中车辆与玩家 | 可见 | 仍可见 |

关键原始记录：

```text
RESTORE vao=74 capturedEbo=10 currentEbo=7 skip=false
OWNER vao=74 expectedEbo=7 type=INT actualEbo=10
INDICES buffer=10 size=30576 values=[65536, 196610, 327684, 458758, ...]

RESTORE vao=74 capturedEbo=10 currentEbo=7 skip=true
OWNER vao=74 expectedEbo=7 type=INT actualEbo=7
INDICES buffer=7 size=318720 values=[0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4]
```

切回原逻辑后，空手及快捷栏图标再次消失，预期/实际 EBO 再次错配；再次启用修复后恢复。这是同一进程中的可逆单变量对照，不是重启或重新加载资源后偶然恢复。

两份对照日志累计记录原逻辑 207 次 EBO 错配、1 次匹配；修复逻辑 191 次匹配、0 次错配。这是有限采样统计，不是全帧覆盖。空手实际使用模型模组的 `rendertype_entity_translucent`，同样走顺序索引；因此不能简单按 shader 名称把错误分成“透明/不透明”两类。

### 2. 根因机制

`GL_ELEMENT_ARRAY_BUFFER` 的绑定属于 VAO。恢复 VAO 已经选中了它当前关联的 EBO；额外绑定旧 EBO 是修改该 VAO 的对象状态，并非恢复一个独立的全局绑定。

```text
主世界车辆阶段结束，Mirror 捕获 VAO 74 / EBO 10
  -> 反射及镜面合成复用同一个立即绘制 VertexBuffer
  -> VAO 的 EBO 和 Java sequentialIndices 更新为顺序四边形索引缓冲 7
  -> 旧 restore 把 VAO 的 EBO 写回 10，但 Java 缓存仍为 7
  -> 手部上传请求相同 sequentialIndices，且缓冲容量足够
  -> VertexBuffer.uploadIndexBuffer 跳过绑定
  -> draw 按缓冲 7 的 INT 类型读取缓冲 10 的内容
  -> 索引越过当前物品顶点范围，几何消失或异常
```

原版 `VertexBuffer.uploadIndexBuffer` 只在顺序缓冲对象改变或容量不足时调用其 `bind()`；`getIndexType()` 也从该顺序缓冲对象取得索引类型。EBO 10 中的 16 位连续值 `0,1,2,3...` 被当作 32 位值读出，就得到 `65536,196610...`。铁块仅有 24 个顶点，显然无法用这些索引正常绘制。

绿宝石等实际走显式上传索引的路径，每次上传会重新绑定自有 EBO，因而能够恢复绘制。它与铁块可以使用相同图集，差异来自索引路径，不是图集是否损坏。索引错配也能造成看似 UV/材质错乱的外观，但不能据此追溯证明此前每一种历史截图都只有这一种原因。

`BufferUploader.invalidate()` 只处理“最后绑定的立即绘制 VertexBuffer”缓存，不清除 `VertexBuffer.sequentialIndices`，因此此前已有的 invalidate 无法修正这个问题。MTS 的绘制顺序与索引路径让错误恢复条件在当前场景稳定出现；正式修复应位于 Mirror 的状态所有权边界，不应屏蔽车辆或强制每个手部 draw 重新绑定。

### 3. 最终代码方案

- 从 `MirrorRenderState` 删除 `elementArrayBuffer` 字段、EBO 捕获和 EBO 恢复。恢复 VAO 时保留其所有者已经更新的对象状态。
- 保留 `BufferUploader.invalidate()` 及 VAO/global ARRAY_BUFFER 恢复，两者仍有独立职责。
- 保留之前已完成的纹理实际绑定/缓存同步和 `BlendMode.lastApplied` 快照恢复；它们不是本次剩余故障的充分修复，但不能因找到 EBO 根因而撤销前面的状态契约修正。
- 保留 Tweakerge/VS 的 `ModifyExpressionValue` 修复，旧独占 redirect 已移除，不增加优先级争抢或后备路径。
- 删除本次临时采样类、五个诊断 mixin、命令开关及所有调用点，正式包无 `/mirrortrace`，不持续写诊断日志，不增加新配置。

Tweakerge 的旧注入冲突属于已独立处理的问题。实际导出的变换后 `LevelRenderer` 中，偏移 1526 调用 Tweakerge 的 `allowRenderingClientPlayerInFreeCameraMode`，1531 再调用 Mirror 的 `mirror$allowLocalPlayerInReflection`；VS 的可见性 redirect 后也接着执行 Mirror 的结果修正。故此次仍存在的手部故障发生在这些注入已经共存的状态下，不能继续归咎于 Tweakerge 主动取消手部。卸载 Tweakerge 会改变镜中玩家参与与绘制顺序，历史对照中的表现变化不等于它拥有这次 EBO 恢复错误。

### 4. 验收与投放

- `gradlew.bat --offline test build` 成功，22 项现有 JUnit 测试全部通过。离线模式避开 Forge Maven 当时的证书校验失败，没有禁用证书校验。
- 本次 EBO 修复通过上述真实整合包 GPU 调用采样、画面对照及反向切换验证；没有新增覆盖 EBO 的自动化 JUnit 测试。
- 使用正式源码产物复跑既有独立 GPU 混合状态回归夹具：原版 Forge、Oculus/Embeddium 两种环境均 PASS。覆盖六种缓存/混合状态组合、三个合成 pass、嵌套快照和异常恢复；像素结果为 baseline `[255,0,0,64]`，旧混合缓存恢复模拟 `[64,0,191,207]`，修复 `[255,0,0,64]`。这是前序混合状态修复的回归，不冒充 EBO 自动化测试。
- 正式包已经替换指定整合包的 `mods/Mirror-0.1.0.jar` 并正常重启。用户随后确认表现问题解决，进入验收收尾；未强制结束游戏、改动模组配置或破坏测试场景。
- 正式 JAR 与本地构建的 SHA-256 均为 `15122C22EE27FC41B52A24E61DE8910AFA03B3B5F5A507F204239C275D332103`。
- `git diff --check` 通过；正式源码和 JAR 中无本次临时 trace 注入。多镜递归、光影开启、车辆驾驶专用相机并未在本轮重新完整验收，不扩大此次通过范围。

本地证据位置：

- 整合包 `logs/mirror-hand-trace-1788964919763.log`、`logs/mirror-hand-trace-1788965571031.log`：可逆对照和最终 fixed 采样。
- 项目 `build/diagnostics/ebo-original-iron.png`、`ebo-fixed-iron.png`、`ebo-original-hand.png`、`ebo-fixed-hand.png`：对照截图。
- 项目 `build/diagnostics/ebo-ab-instrumentation.zip`、`Mirror-ebo-ab.jar`：已移出正式源码的诊断实现与可复核测试包，保留原逻辑/修复逻辑切换。
- 项目 `build/hand-blend-test/{vanilla,oculus}/ebo-final-stdout.log`：正式版本 GPU 回归 PASS 记录。
- 整合包 `.codex-backups/mirror-trace-20260909-214450`：此前正式包备份。临时诊断源码删除前已单独归档，可以恢复；用户文件与无关改动未清理。

补充发现：`config/logfilter-common.toml` 中的规则 `.*[ToggleBuffHUD].*` 使用了正则字符类，会误过滤大量无关日志。这解释了此前标准日志缺少诊断输出；本次改用独立文件取证。该配置不属于 Mirror 修复范围，未修改，也不属于渲染根因。

## 历史调查记录（以下为 2026-09-05 状态）

调查日期：2026-09-05；已根据用户第二轮对照实验更新。范围：当前 Mirror 源码、指定运行日志、整合包实际 JAR 与配置、用户复现结果。仅更新分析和修复方案，未修改模组代码、整合包或存档；文中的游戏内现象来自用户实测。

## 1. 结论与证据强度

**新证据支持把现象分为两个待分别处理的部分：Tweakerge 参与原先的渲染缺失；移除它后，Mirror + MTS 仍会触发材质错乱。AR 和 ImmediatelyFast 已不再是首要候选。** 这不等于已经证明两个完全独立的底层 bug，也没有把剩余材质错乱的唯一根因锁定到某一行。

| 用户对照 | 结果 | 可得结论 |
| --- | --- | --- |
| 禁用 Tweakerge | 物品恢复绘制；空手、手持方块仍材质错乱，普通手持物品正常 | Tweakerge 对原现象有因果参与，但移除它不能完整解决问题 |
| 破除镜面或车辆之一 | 恢复正常 | 剩余故障依赖镜面绘制与周围车辆同时存在 |
| 禁用 AR | 无明显改变 | 不再优先追查 AR 批次 |
| 禁用 ImmediatelyFast | 无明显改变 | 不再优先追查 IF 批次 |

源码确认两个具体问题点：

1. **Tweakerge 的独占 redirect 即使在自由相机关闭时，也会使 Mirror 的镜中本地玩家修正失效。** 它返回原始相机实体，不会替 Mirror 执行反射相机分支。这能改变镜面内玩家是否参与渲染；从这里到主视图手部缺失的最后一步仍未被故障帧证明。
2. **Mirror 的纹理恢复没有统一保证真实 GL 绑定和 GlStateManager 缓存一致。** 0–11 槽恢复可能被缓存跳过；12 及以上槽只恢复 GL，不更新缓存。该缺陷值得做最小定点修正和验证，但高位槽错配不能自动证明低位手部采样已经出错。

用户补充确认：站在地面、没有乘坐车辆时也会发生。结合 MTS 的事件与相机代码，车辆驾驶 HUD 或车辆摄像机主动隐藏界面的解释优先级很低。

建议先在保持 Tweakerge 禁用的复现条件下，验证第 7 节的纹理恢复修正；单独处理 Tweakerge 的注入冲突。不要继续重复 AR/IF 的对照，也不要通过移除镜中车辆来掩盖问题。

## 2. 实际调查对象

项目提交：`1d5cceec4812bfee2b8f3a4a2ffe8857b616d6b0`。

日志目录：`E:/Minecraft/MyMC/MyMC game/.minecraft/versions/MyMC/logs/`。**日志已经被后续运行覆盖。** 以下环境表及第 6 节旧冲突行号来自首轮 08:33 会话；不能用那些旧行号定位当前文件。第二轮读取的 `latest.log` 为 09:10 会话：1158 行确认 DSA 启用，1159–1160 行确认光影关闭，1199–1200 行仍有 VS 冲突，但模组清单和冲突记录已没有 Tweakerge。AR、IF 仍分别列于 69、191 行，因此这份日志支持“禁用 Tweakerge”这次实验，不能代替用户另外进行的 AR/IF 对照结果。

| 项目 | 核实结果 | 证据 |
| --- | --- | --- |
| Minecraft / Forge | 1.20.1 / 47.4.9 | latest.log:1、169、238 |
| Mirror | 0.1.0 | latest.log:240、实际 JAR |
| Immersive Vehicles / MTS | 24.0.0 | latest.log:250 |
| MTS Official Pack | 29 | latest.log:251 |
| Accelerated Rendering | 1.0.14-1.20.1-alpha | latest.log:69、实际 JAR |
| Embeddium | 0.3.31+mc1.20.1 | latest.log:148 |
| Oculus 模组 ID | 1.8.0.1 | latest.log:263 |
| ImmediatelyFast | 1.5.5+1.20.4 | latest.log:1171 |
| Valkyrien Skies | 2.4.11 | latest.log:330 |
| 光影 | 该日志会话明确关闭 | latest.log:1175–1176 |

首轮核验时，实际安装的 `mods/Mirror-0.1.0.jar` 与项目 `build/libs/Mirror-0.1.0.jar` 的 SHA-256 相同：

```text
D3FE7F46C7B202AA37D8047852A5D50C171A9F5A826FA1E6340533687349C2C9
```

并用 `javap` 抽查了关键类：实际 JAR 已包含 `BufferUploader.invalidate()`、12 个 RenderSystem shader texture 槽的保存恢复，以及在 `GameRenderer.renderHand` 字段读取前执行镜面更新的注入。不能按“运行的是没有这些修复的旧包”分析本案。这里确认的是关键实现对应关系，并非对全部源码和字节码做了等价证明。

## 3. 已核实的渲染调用链

```text
GameRenderer.renderLevel
  ├─ 外层 LevelRenderer.renderLevel：主世界，含 MTS 车辆
  ├─ Mirror 在读取 renderHand 前处理待更新镜面
  │    ├─ 保存外层 GL / RenderSystem 状态
  │    ├─ 切换至镜面 FBO、相机和投影
  │    ├─ 再次调用 LevelRenderer.renderLevel
  │    │    └─ MTS 的 HEAD / 实体阶段 / TAIL 注入也会执行
  │    ├─ 恢复相机、渲染目标及状态
  │    └─ 镜面合成结束后再次恢复外层状态
  ├─ 第一人称手、手持物品
  └─ 后续 GUI / 快捷栏
```

定位：

- [GameRendererMixin.java:62](E:/Minecraft/others/Mirror/src/main/java/com/mirror/mixin/GameRendererMixin.java:62)：`mirror$renderPendingReflections` 的注入位置。
- [MirrorTextureManager.java:102](E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorTextureManager.java:102)：额外世界 pass 是在外层世界返回后执行；125–129 行对整个镜面阶段做快照和 `finally` 恢复。
- [MirrorLevelRenderer.java:202](E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorLevelRenderer.java:202)：切换主渲染目标；240 行直接调用 `LevelRenderer.renderLevel`；243–266 行恢复。

**这里首先是同一帧内顺序执行的多次世界渲染，不应直接认定为外层 `LevelRenderer` 尚未结束时的重入。** 因此，“某模组只有一个 renderingLevel 布尔值，内层返回将外层状态清掉”不足以解释当前调用位置。

### MTS 的实际行为

核验对象为 `mods/Immersive Vehicles-1.20.1-24.0.0.jar`：

- `mcinterface1201.mixin.client.LevelRendererMixin.inject_renderLevelDataGetter` 保存静态 `InterfaceRender.projectionMatrix`。
- solid / blended 注入更新静态 `matrixStack`、`renderBuffer`、`renderCameraOffset`，调用 `InterfaceRender.doRenderCall`。
- TAIL 的 blended 注入执行车辆渲染并 `BufferSource.endBatch()`。
- `doRenderCall` 遍历 MTS 世界的 `renderableEntities`；车辆实际是否提交几何还取决于各自渲染逻辑。因此车辆不必走原版单实体渲染器，单纯过滤 Mirror 的 `renderEntity` 入口无法可靠隔离它。

客户端配置 `config/mtsconfigclient.json:63–65` 的 `renderingMode.value=0`。该分支由 `InterfaceRender.renderBuffers(float)` 直接提交 VBO：

```text
RenderType.setupRenderState
→ 设置 shader samplers / projection / color / fog / lighting
→ ShaderInstance.apply → VertexBuffer.bind → VertexBuffer.draw
→ VertexBuffer.unbind → ShaderInstance.clear → RenderType.clearRenderState
```

实际 JAR 的关键字节码偏移：`renderBuffers` 83–90 为 setup/getShader，325–349 为 apply/bind/draw，355–364 为 unbind/clear/clearRenderState。MTS 自定义透明度状态的 setup/teardown 也成对设置 blend、depthMask 与默认混合函数；没有证据表明它在正常返回路径上单纯漏掉了 `disableBlend()`。

上游可读代码：[MTS LevelRendererMixin](https://github.com/DonBruce64/MinecraftTransportSimulator/blob/dabd002b675e90ebb55f82ce066d60d387ba5287/mcinterfaceforge1201/src/main/java/mcinterface1201/mixin/client/LevelRendererMixin.java#L36)、[MTS InterfaceRender](https://github.com/DonBruce64/MinecraftTransportSimulator/blob/dabd002b675e90ebb55f82ce066d60d387ba5287/mcinterfaceforge1201/src/main/java/mcinterface1201/InterfaceRender.java#L301)。链接固定到调查时的上游提交；24.0.0 的行为以安装 JAR 字节码为准，不能把当前上游所有实现都等同于发布版本。

## 4. 排除过度归因

### 4.1 Mirror 已恢复的状态

[MirrorRenderState.java:81](E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorRenderState.java:81) 的捕获与 156 行开始的恢复覆盖：FBO、viewport/scissor、VAO/VBO/EBO、纹理绑定、shader/program、投影和模型视图、shader color/fog、depth/blend/cull、混合因子、color mask 等。`restore()` 在绑定保存的 VAO 前明确执行 `BufferUploader.invalidate()`。

所以“补 `enableBlend()`”“把 shader color alpha 设回 1”“增加 VAO invalidate”“从 4 槽增加至 12 槽”都不能作为已经查明的修复方案。**第二轮进一步发现：覆盖了纹理恢复项，不等于恢复调用一定实际执行，也不等于同步了绑定缓存。** 具体见第 5 节。

### 4.2 ShaderInstance program 缓存不是已证明的根因

核验 Minecraft 1.20.1 字节码：`ShaderInstance.clear()` 会将 program 缓存设为 `-1`、当前 shader 缓存设为 `null`。MTS 正常执行 clear 后，下一次 `apply()` 会重新绑定程序。Mirror 恢复 GL program 而保留这两个失效缓存，本身不会必然导致下一次画错 shader。

仍可检查 `BlendMode.lastApplied`：它没有随 `ShaderInstance.clear()` 一起清除。若镜面末次 shader 留下缓存 B，Mirror 将真实 GL 混合状态恢复为 A，而下一个 shader 恰好也使用 B，BlendMode 可能跳过需要的更新。不过这需要实际的 A/B 状态和后续调用共同成立；RenderType 也会设置透明度状态。**目前没有故障帧证据满足这些条件，不建议据此直接增加缓存修补。**

### 4.3 MTS 主动取消手部 / HUD

MTS 的 `onIVRenderHand`、`onIVRenderArm` 会在使用枪械或存在 activeCamera 等条件下取消事件；`onIVPreLayer` 会在自定义摄像机 overlay 或驾驶座 HUD 条件下取消快捷栏。

但镜面的 `LevelRenderer` 注入只更新渲染上下文，并不直接修改 `CameraSystem.activeCamera/customCameraOverlay`；相机代码选择摄像机依赖座位／枪械上下文。用户确认在地面也会触发，空手同样异常，因此不能仅凭“存在取消事件的代码”判定根因。详见 [MTS 事件实现](https://github.com/DonBruce64/MinecraftTransportSimulator/blob/dabd002b675e90ebb55f82ce066d60d387ba5287/mcinterfaceforge1201/src/main/java/mcinterface1201/InterfaceEventsEntityRendering.java#L88)。

### 4.4 光影、ImmediatelyFast 与无关告警

- `latest.log:1175–1176` 明确写出 `enableShaders=false` 和 `Shaders are disabled`。不能归因为某个 shaderpack；关闭光影也不等于移除了 Oculus 的 mixin。
- 首轮读取 `config/immediatelyfast.json:5` 为 `hud_batching=false`，14 行为 `experimental_screen_batching=false`；第二轮用户进一步确认禁用该模组也无明显改变，因此不再优先追查其 HUD 批次。
- `debug.log` 中出现 `iris$skipTranslucentHands` 只证明 mixin 被应用，不证明它在故障帧取消了手部渲染。
- `latest.log:1783–1784` 的 MTS 旧模型转换错误涉及 `engineamci4`、`roller2` 和 lights/treads，并非手／HUD 透明的证据。
- Embeddium 的 tainted / may cause instability 提示是第三方修改告警，不是某模组造成此症状的诊断。
- 这两份日志未找到与该透明现象直接对应的 GL 错误或渲染异常栈。视觉错误可以由合法的错误状态组合产生，未报错不能排除问题。

上述配置读取于调查时；没有历史配置快照，不能保证它们在日志会话的每一刻都未变化。

## 5. 第二轮重点：绘制路径差异与纹理缓存一致性

### 5.1 不能把“方块错、普通物品对”等同于图集损坏

从本机 Forge 1.20.1 mapped sources 核验原版基线路径：

| 绘制内容 | 典型 RenderType | 纹理 |
| --- | --- | --- |
| 空手的手臂 | entitySolid；袖层另用 entityTranslucent | 玩家皮肤 |
| 普通不透明方块物品 | Sheets.cutoutBlockSheet → entityCutout | blocks 图集 |
| 常规非方块物品 | 常见为 translucentCullBlockSheet / translucentItemSheet | 同样可用 blocks 图集 |

出处：`PlayerRenderer.java:184–194`、`Sheets.java:47–49`、`ItemBlockRenderTypes.java:352–374`，来自 `C:/Users/YM/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.2.0_mapped_official_1.20.1/forge-1.20.1-47.2.0_mapped_official_1.20.1-sources.jar`。Forge 模型可提供自己的 RenderType，表格不是所有模组物品的硬性分类。

因此用户的差异更值得按 **entitySolid/entityCutout 与物品透明路径的 shader、采样器、状态切换** 检查；普通物品能绘制正确不能证明所有路径都绑定正确，也不能单凭现象认定图集内容损坏。空手皮肤和方块图集同时异常尤其不适合直接归因于某一个资源包文件。

### 5.2 三层状态的区别

| 状态 | 含义 | 当前恢复情况 |
| --- | --- | --- |
| RenderSystem.shaderTextures[0..11] | 下一次 shader 绘制应使用的逻辑纹理 ID | 已保存恢复 |
| GL 各单元的 GL_TEXTURE_BINDING_2D | GPU 此刻实际绑定的纹理 | 已捕获；部分恢复调用可能被缓存跳过 |
| GlStateManager.TEXTURES[unit].binding | Minecraft 对已绑定纹理的缓存 | 12+ 槽 raw bind 不会同步；0–11 槽恢复依赖它正确 |

在 [MirrorRenderState.java:197](E:/Minecraft/others/Mirror/src/main/java/com/mirror/client/MirrorRenderState.java:197)，代码先恢复 shaderTextures，随后 0–11 槽调用 `RenderSystem.bindTexture`，12+ 槽直接 `GL11C.glBindTexture`。

原版 `GlStateManager._bindTexture` 在缓存等于请求 ID 时不执行 GL 绑定。实际 `mekalus-mc1.20.1-1.8.0.1.jar` 的 `IrisRenderSystem$DSAARB.bindTextureToUnit` 也读取同一缓存：字节码 0–12 比较并提前返回，13–24 才调用 `glBindTextureUnit` 并更新缓存。当前 09:10 日志 1158 行确认 DSA 启用。上游阅读入口：[IrisRenderSystem](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/gl/IrisRenderSystem.java)；本案具体判断以实际 Mekalus JAR 为准。

由此可以构造明确的故障条件：

```text
缓存认为 unit U 已绑定 A，真实 GL 却绑定车辆纹理 B
→ Mirror 或下一次 shader 请求绑定 A
→ 缓存比较相等，跳过真实绑定
→ 绘制仍采样 B
```

但必须说明限制：**目前没有故障帧记录证明低位采样单元确实进入这个条件。** 12+ 槽 raw restore 在恢复值不同于缓存时会造成错配；这本身不会必然污染 0–2 槽。DSA 已启用也不等于普通无光影手部一定经 DSA 绑定。它们是应修正的状态契约缺口及具体候选机制，不是完整运行时定案。

capture 时临时用 raw `glActiveTexture` 查询各槽，然后恢复原 active unit，在无其他调用插入且入口一致时是安全的，不应把查询动作本身误判为必然污染。

### 5.3 ShaderInstance 的反证仍然有效

再次直接读取原版 `ShaderInstance.java:316–370`：`clear()` 不仅清 program 缓存，也会逐采样器执行 `_bindTexture(0)`；`apply()` 则重设采样器 uniform 并绑定其纹理。MTS mode 0 正常执行 clear，故不能声称“MTS 没清纹理”或“下一次 shader 完全不会重新绑定”。要证明故障，应看到该次绑定被缓存错误跳过，或者 samplerMap/uniform/顶点数据在特定 RenderType 下不正确。

## 6. 已确认的非崩溃兼容冲突

| 冲突 | 日志中的实际结果 | 可解释的影响 |
| --- | --- | --- |
| Mirror × Valkyrien Skies | latest.log:1215–1216；Mirror priority 990 的 `mirror$useRecursiveBlockEntitySectionVisibility` 被 VS priority 1000 的 `dontClipTileEntities` 覆盖 | 镜中递归方块实体可见性修正失效 |
| Mirror × Tweakerge | latest.log:1217；`mirror$allowLocalPlayerInReflection` 被 priority 1001 的 `allowRenderingClientPlayerInFreeCameraMode` 覆盖 | 镜中本地玩家专用门控修正失效 |
| ControlCraft × Embeddium | latest.log:1219–1220；ControlCraft 针对已经被 Embeddium 改写的方法无法注入 | 另一项相机／世界渲染兼容异常；未证明与透明症状有关 |

关键原文分别是 `@Redirect conflict. Skipping mirror.mixins.json:LevelRendererMixin` 和 `InvalidInjectionException`，不是“可能有冲突”的泛化提示。

**不要直接把整个 LevelRendererMixin 的优先级提高到 1100。** [当前源码第 22–30 行](E:/Minecraft/others/Mirror/src/main/java/com/mirror/mixin/LevelRendererMixin.java:22) 明确说明 990 是为避免 VS 必需 redirect 被抢占后注入失败而设置。旧文档 `ISSUE12-FIX-FOLLOWUP.md` 所记载的提高优先级方案，不能直接用于当前含 VS 的整合包。

### Tweakerge 关闭自由相机仍然产生影响的原因

核验 `Tweakerge-0.1.5-mc1.20.1.jar`：`MixinWorldRenderer.allowRenderingClientPlayerInFreeCameraMode` 的类优先级为 1001，抢占 `Camera.getEntity()` 对应的第四次调用。配置 `tweakerge.json:738` 当前为 `tweakFreeCamera=false`；这个值只决定它返回什么，不决定该 redirect 是否注册。

```text
正常主相机：原始 camera entity 是玩家 → Tweakerge 原样返回通常没有区别
镜面相机：原始 camera entity 是 Mirror 的 dummy entity
  Mirror 分支应返回 LocalPlayer
  Tweakerge 自由相机关闭时原样返回 dummy entity
  Mirror 分支因注入冲突根本没有机会执行
```

所以“配置关闭，因此 Tweakerge 没影响”是不成立的。禁用模组后 Mirror 的分支重新生效，可以改变镜面内玩家渲染及后续状态顺序，符合用户观察到的现象变化；但尚不能将主视图所有故障都归于这个门控。

其 `MixinGameRenderer.removeHandRendering` 只在自由相机开启时取消手部；`MixinHeldItemRenderer.preventOffhandRendering` 依赖副手禁用配置。当前相应配置均未开启，也未在这些方法中找到主动修改纹理或 shaderColor 的代码。本轮读取时磁盘上仍存在 Tweakerge JAR，但 09:10 启动日志未列出该模组；实验是否加载以对应会话日志和用户操作为准，不能用事后文件存在否定禁用实验。

## 7. 精简修复与验证方案

### A. 先修正纹理恢复契约，保持单变量验证

最小候选改动只落在 `MirrorRenderState.restore()` 的纹理循环：移除 `unit < 12` 的分支，对所有已捕获单元统一恢复真实绑定和逻辑缓存；active unit 也统一处理。12 个 shaderTextures 逻辑槽保持现有恢复，它与 GL 单元数量不是同一概念。

方案示意，尚未应用到源码：

```java
for (int unit = 0; unit < texture2dBindings.length; unit++) {
    int slot = GL13C.GL_TEXTURE0 + unit;
    GL13C.glActiveTexture(slot);                 // 确保真实单元切换
    RenderSystem.activeTexture(slot);           // 同步缓存
    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture2dBindings[unit]);
    RenderSystem.bindTexture(texture2dBindings[unit]); // 同步绑定缓存
}
GL13C.glActiveTexture(activeTexture);
RenderSystem.activeTexture(activeTexture);
```

这里先强制真实 GL 操作，再通过既有 API 同步缓存：即使 API 因缓存相等而跳过，真实绑定也已经正确；若缓存不同，API 会更新它。无需新增依赖、反射字段访问、车辆专用分支或后备路径。代价是每个快照恢复时增加有限数量的绑定调用，应在正确性通过后检查镜面帧耗时。

**这是针对确定状态契约缺口的最小修正，并非承诺它一定解决本次材质错乱。** 在 Tweakerge 禁用的原复现场景中，只比较这个改动；若空手／方块仍错，不继续盲加 GL 状态，而执行 B 中的定点取证。

### B. 精确确认实际采样，避免继续大范围排模组

在 `processPending` 前、镜面恢复后、故障 RenderType 的 `ShaderInstance.apply()` 后（实际 draw 前）限频记录：

- RenderType 和 shader 名称、program ID；
- `Sampler0/1/2` 的 uniform 单元映射及 samplerMap 纹理 ID；
- 对应 GL 单元实际绑定、GlStateManager 缓存、RenderSystem.shaderTextures；
- VAO 的 UV0 attribute 布局及 VBO；只有纹理完全正确时再检查顶点数据；
- 模型形状是否保持正常、错误贴图能否辨认为车辆纹理。

首个关键判据是：**故障 draw 使用的 Sampler0 是否实际指向玩家皮肤／blocks 图集，且与逻辑 ID 一致。** 若不一致就定位错绑发生的位置；若一致而采样外观错误，再看 UV、纹理内容和对应 shader uniform，不能继续称其为绑定错误。

保留 Tweakerge 禁用，测试空手、石头、玻璃、普通平面物品四类。玻璃和石头可以共享图集而走不同透明度路径，能进一步分辨是否是 entitySolid/entityCutout 一侧的问题。必要时在测试实例完全移除 Mekalus/Oculus（连同仅依赖它的组件）对照；“关闭光影”不等于“移除其绑定实现”。这项是归属验证，不是最终解决方案。

验收同时检查车辆仍出现在镜中、空手／方块／普通物品及快捷栏图标和背景正常、背包开合、多镜递归不回归。第二轮用户尚未单独描述快捷栏背景的残余表现，不能在报告中假定它已恢复。编译成功或无 GL 错误不能替代画面验收。

### C. 两个已知 redirect 冲突单独处理

优先修复 Tweakerge 抢占的玩家门控，并与纹理修正分开验收。替换该独占 `@Redirect`，使用能够保留其他模组调用结果的表达式／操作包装注入，仅在镜面 pass 返回真实 LocalPlayer，其余情况沿用原结果。实施前核对项目现有 MixinExtras 的版本、依赖声明和变换后的目标；如果原调用已被 redirect 替换，应针对可共存的门控结果选点，不能假设套一个包装注解就自动消除全部冲突。VS 方块实体门控也按同样原则单独处理；当前未为此新增依赖。

直接移除被替代的旧 redirect，不保留两套路由、不添加优先级回退。若目标调用在其他 mixin 变换后不再适合包装，就选择其后的玩家门控／可见性结果进行定点修改。不得通过重新抢占 VS 的必需注入点来“修好”Mirror。

## 8. 调查边界

第二轮已用用户实测撤销“AR 优先”的建议，明确了 Tweakerge 在功能关闭时仍发生注入冲突的机制，并找出纹理恢复对 GL／缓存一致性保证不足的具体位置。**剩余材质错乱仍缺故障 draw 的采样证据；尚未应用或实测候选修正。** 后续优先顺序是纹理恢复最小修正及定点验证，再修复可共存的玩家门控；不再把卸载 AR/IF 当作当前主要方案。
