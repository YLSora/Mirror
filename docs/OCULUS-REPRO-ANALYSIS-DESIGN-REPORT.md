# Mirror × Oculus 兼容层三类视觉异常 —— 实测复现、根因分析与表现端优化方案

> 范围：Minecraft 1.20.1 · Forge 47.4.20 · Embeddium 0.3.31 · Oculus 1.8.0.1（mekalus）· Mirror 0.1.0
> 结论性质：基于 Rapid Optimization 实测整合包实机复现 + 窗口像素抓取 + 日志/GL 参数采集 + 源码审查。本轮不修改源码，只输出分析与设计。

---

## 0. 复现环境与方法

| 项 | 值 |
|---|---|
| 实测整合包 | `E:\Minecraft\MyMC\MyMC game\.minecraft\versions\Rapid Optimization`（Forge 47.4.20，含 Embeddium 0.3.31 / Oculus 1.8.0.1 / tweakeroo-via-Connector / YSM 等） |
| 硬件 | AMD Ryzen 9 8945HX / NVIDIA RTX 5070 Ti Laptop (OpenGL 4.6) |
| 启动方式 | `run/repro/launch.ps1`（BootstrapLauncher + `--quickPlaySingleplayer 新的世界`），`run/repro/set-shaderpack.ps1` 切换光影 |
| 交互方式 | `run/validation/.../client-window.ps1`（窗口消息发 /setblock /fill /tp，桌面像素采集截图） |
| 镜像配置 | recursionMode=RECURSIVE, maxRecursionDepth=4, recursiveConvergenceReuse=true, reflectionFrameBudgetMs=20, resolutionScale=8 |

场景脚本化搭建：蓝色墙(z=296) + 红墙(z=303) + 石地板 + 镜面(300,101,297)朝南；第二面镜(300,101,303)朝北与之相对形成递归隧道。

---

## 1. 实测数据记录（关键参数）

### 1.1 Sundial（Sundial Alpha Build 2026-06-26.zip）

| 事件 | 观测值 |
|---|---|
| 主世界管线 | 正常创建，Sundial 加载成功 |
| 首镜（1x1 -> 256 分辨率槽）管线创建 | Creating mirror pipeline ... ResolutionBucket[256] 耗时 6971.84 ms（约 7 s），terrain programs 又 385.81 ms |
| 首镜帧窗口 | reflectionPasses=116, avg=65.60 ms, max=7435.99 ms（含 7 s 冻结） |
| 稳态单镜 | reflectionPasses=120, avg=1.2 ms, max=2 ms, temporalAttachmentResets=0, deferredViews=0 |
| 改尺寸（1x1 -> 3x3，256->512 槽） | 在 /fill 成功帧(15:45:26)即刻爆发 GL 错误洪泛 |
| 改尺寸后 GL 错误 | GL_INVALID_VALUE ... the y values exceeds the boundaries of the corresponding image object 累计 16566 次，持续约 110 s |
| 改尺寸后反射耗时 | avg 由 1.2 ms 跳升至 7~13 ms，max 40~47 ms |
| 改尺寸后 temporalAttachmentResets | 过渡帧 = 1（新视图首次使用） |

### 1.2 Complementary（ComplementaryShaders_v4.4_ch.zip，递归）

| 事件 | 观测值 |
|---|---|
| 预热 | Prewarmed mirror pipeline x2 约 3.2 s each，terrain 约 0.5 s each |
| 单镜（无递归）稳态 | reflectionPasses=360, avg=1.1~1.4 ms, deferredViews=0 |
| 加入第二面镜（递归）后 | 深纹理槽 Creating mirror pipeline ... recursionDepth=2 ... 256 约 3.25 s + 0.52 s，maxPendingViews 峰值 9 -> 稳态 3（depth0/1/2） |
| 递归稳态反射耗时 | avg=11~13 ms/Pass（单镜 1.2 ms 的约 10 倍），3 视图/帧合计约 33 ms > 20 ms 预算 |
| 递归预算顺延 | deferredViews=3~10（depth>0 视图被顺延） |
| 递归 GL 错误 | 累计 32111 次：GL_INVALID_OPERATION 16137 + GL_INVALID_VALUE 10758（含 y exceeds 10454 + does not correspond to a valid texture object） |
| temporalAttachmentResets | 仅首用（当前实现为每视图首次使用清一次） |

### 1.3 递归抖动量化（Complementary，玩家静止、bobView=false）

| 场景 | meanAbsDiff | changedPct(>12) | strongPct(>60) |
|---|---|---|---|
| 单镜（无递归）相邻两帧 | 0.05 | 0.02 % | 0.00 % |
| 递归（双镜相对）相邻两帧 | 5.91 | 3.01 % | 1.61 % |

结论：递归开启后，静止画面的帧间不稳定度由 0.02% 放大到 3.01%，约 150 倍。这是远处场景抖动/闪烁的量化信号（与历史文档 iterationRP 静止 changedPct 约 2.6% 相互印证，说明异常与具体光影名无关，凡重度 TAA/时域累积的光影都受影响）。

---

## 2. 三类异常的根本原因

共同底层事实：三支光影包均能加载、递归视图（depth0/1/2）稳定建立、无崩溃；异常不是状态泄漏/黑屏级崩溃，而是时域累积一致性 + 分辨率槽位切换 + 管线生命周期三个表现端问题。其中 2.3 与 2.2 直接由 GL 状态损坏触发，2.1 由时域历史不一致 + GL 损坏放大共同触发。

### 2.1 问题1：Complementary / Sildur / IterationRP 递归反射远处抖动、闪烁

触发光影共性：三者重度使用 TAA + 屏幕空间（SSR/时域降噪），重投影误差随距离放大 -> 异常集中在远处。

根因（按权重排序）：

1. （主因）递归视图错误累积时域历史。OculusMirrorTemporalStateMixin 当前只在每视图首次使用清一次历史（mirror$knownViews.add(viewId)），此后递归视图（depth>0）持续做 TAA 时域累积。但递归视图的相机是主眼经 1..N 次平面反射得到，其运动被反射放大；depth>=2 又是镜面自身相机、镜面扁平的收敛近似，其内容视差与真实链式视差不一致。两者叠加后，TAA 用上一帧历史重投影当前帧时，远处像素出现亚像素级错位 -> 抖动/闪烁。实测该累积是 3.01% 帧间不稳定的主要来源。

2. （放大）递归 GL 状态损坏污染时域附件。递归建立时（创建 depth=2 深纹理管线 + 256/512 槽位切换）触发 32111 次 GL 错误（invalid-op / invalid-value / y-exceeds / invalid texture object）。这些错误作用于 Oculus 的 gbuffer/历史缓冲，使 TAA 的历史纹理在部分帧失效或错位，进一步放大抖动。

3. （放大）预算顺延破坏逐帧连续假设。递归视图成本约 11~13 ms/Pass，3 视图约 33 ms > 20 ms 预算 -> deferredViews=3~10。被顺延的视图其 MirrorViewHistory.commit 未执行，下一帧 previousOr 返回的是 >=2 帧前的历史，TAA 仍按 1 帧前重投影 -> 跳变。当前 MirrorViewHistory 无帧号连续性校验。

4. （辅助）投影稳定器周期重稳定。MirrorProjectionStabilizer（EXPANSION_HEADROOM=1.10 / SHRINK_THRESHOLD=0.70）在包络变化超阈值时一次性改投影与 UV crop，改变当帧 TAA 用旧投影重投影，产生单帧跳变；掠射/远距时被放大。

### 2.2 问题2：Sundial 首镜不渲染实体和区块

1. （主因）新分辨率槽的惰性管线创建阻塞渲染线程。首次出现的分辨率槽（实测 256 槽）没有预热/已认领管线时，OculusPipelineManagerMixin.mirror$prepareSlotPipeline 在反射 pass 内同步 new IrisRenderingPipeline(programs)（Sundial 下约 7 s 自定义 uniform 解析 + shader 编译），渲染线程被整帧冻结；随后 terrain programs 又 0.39 s。冻结期镜像无内容。

2. （主因）预热只覆盖 2 个槽，且分辨率无关预热在首帧才 resize。预热管线在 shader 加载期构造，但 RenderTargets（gbuffer）尺寸仍是主世界尺寸，直到首个 beginLevelRendering 才 resize 到镜面槽尺寸。首镜即冷 resize + 冷 terrain program 惰性创建同时发生，实体/区块的 Embeddium terrain 程序尚未 READY，首个有效帧前镜像缺地形与实体。

3. （辅助）首用视图的时域全清。mirror$requestFullClear() 在首用触发，Sundial 这类路径追踪/时域累积光影从 0 采样起步，首若干帧内容（尤其被时域降噪的实体与区块间接光照）不完整。

### 2.3 问题3：Sundial 改尺寸变黑

1. （核心）分辨率槽位切换导致 Oculus gbuffer 尺寸错配 -> y exceeds 洪泛。镜面尺寸变化使 MirrorTextureKey 维度改变（1x1=112 -> 3x3=368，经 1.333x 补偿后桶 256->512）。MirrorTextureManager.getOrCreate 立即 oldTexture.close()（销毁旧 surface target）并新建纹理/槽位，pipeline 由 256 槽切到 512 槽，Oculus 的 RenderTargets（gbuffer/深度/历史缓冲）随之 resize。该 resize 与旧尺寸残留的 viewport/scissor/历史复制不同步，产生 GL_INVALID_VALUE: the y values exceeds the boundaries of the corresponding image object（16566 次）。Sundial 是路径追踪后端，GBuffer/历史一旦损坏无法自愈 -> 镜像变黑且持续约 110 s。

2. （核心）MirrorRenderTargetTexture 在构造期捕获纹理 id，存在陈旧 id 窗口。构造时 this.id = target.getColorTextureId()；对尚未绑定的 TextureTarget，该 id 为 0/无效，直到首次 compose() 后 refreshId() 才校正。改尺寸新建纹理的这段窗口内，若表面被采样即产生 invalid texture object 类错误（递归场景实测出现同类错误 1 万余次）。

3. （辅助）旧 capture 槽与旧 pipeline 不随视图销毁。MirrorCapturePool 的 256 槽、mirror$mirrorPipelines 的 256 pipeline 在尺寸切换后仍驻留，仅 MirrorReflectionTexture.close() 释放了 surface target。资源身份不对称，为 resize 错配埋下隐患。

---

## 3. 优化方案设计（底层源码 · 光影无关 · 非侵入）

设计原则：落到镜面底层源码；长效稳定、不依赖具体光影名；不改变现有渲染机制/性能端优化/既有 Oculus 兼容层主体结构；不引入新问题。

### 3.1 P0 —— 递归视图逐帧冻结时域累积（直接消除问题1主因）

落点：OculusMirrorTemporalStateMixin（+ MirrorPassContext.recursionDepth()）

在 mirror$resetNewViewTemporalAttachments 中区分直接视图与递归视图：
- recursionDepth() > 0（递归/深收敛视图）：每帧 ((OculusRenderTargetsAccess) renderTargets).mirror$requestFullClear()，等效于递归层不做 TAA 时域累积。
- recursionDepth() == 0（直接视图）：保持现有每视图首次使用清一次逻辑不变，直接镜面仍保留 TAA。

改动仅是对现有注入点增加一个 recursionDepth 分支，不新增 mixin、不改递归几何/收敛近似/分辨率衰减/预算机制。

风险控制：递归层失去 TAA（轻微锯齿），远轻于抖动；直接视图 TAA 不受影响；无性能端负面（甚至略减 TAA 开销）。这同时消解 2.1-2、2.1-3 中历史被 GL 损坏/顺延过期对递归层的放大作用。

### 3.2 P1 —— 时域历史连续帧校验（消除问题1的顺延跳变，防御直接视图）

落点：MirrorViewHistory

commit 记录单调递增的帧序号；previousOr 仅在上一 commit 帧号 == 当前帧号 - 1 时返回历史，否则返回 current（重投影置零）。覆盖预算顺延/视图重建导致的跳帧场景，避免用过期历史做重投影。

风险控制：只改一个包内私有类的历史语义，不影响矩阵/投影数学。直接视图本就逐帧渲染，校验恒为连续，零行为差异；递归视图在 3.1 落地后历史已不参与，此校验为纯防御。

### 3.3 P1 —— 分辨率槽位切换的优雅过渡（消除问题2/3的 GL 洪泛主因）

落点：MirrorTextureManager.getOrCreate + MirrorReflectionTexture + MirrorRenderTargetTexture

1. 延迟销毁旧纹理（防 use-after-free / 尺寸错配）。当同视图、尺寸变大且 reuseForChangedLayout 返回 false 时，不再立即 oldTexture.close()；将旧纹理放入退役集，待新纹理首个 rendered==true（首次 compose 成功）之后再 close()。新纹理渲染期间旧 surface target 保持有效，Oculus 新旧槽位（256/512）pipeline 与各自 capture target 稳定共存，消除 resize 期间旧尺寸残留引用。

2. 消除陈旧纹理 id 窗口。MirrorRenderTargetTexture 不再在构造期缓存 id；改为在每次被纹理管理器绑定采样前，从 target.getColorTextureId() 惰性读取（并在 MirrorReflectionTexture.render() 的 compose 之后保持 surfaceTexture.refreshId()，把表面 drawFace 仅在 hasRendered() 后提交这一既有约束显式覆盖退役/新建过渡期）。确保任何被采样的 id 恒有效。

3. 槽位切换前后强制一次干净 resize（可选加固）。在 MirrorReflectionTexture.render() 首次进入新槽位前，若检测到 surfaceTarget/capture 槽位维度变化，先对该 slot 的 RenderTargets 走一次 fullClearRequired + 显式 bind/resize 边界，再开始世界渲染，避免渲染中途 resize。

风险控制：三项都不改反射几何、捕获槽位算法、分辨率分级、预算机制；只把销毁时机和 id 时效从立即/构造期改为新帧稳定后/惰性。不新增第二阴影图、不重建管线。

### 3.4 P2 —— 首镜/新槽管线异步预热兜底（消除问题2的 7 s 冻结）

落点：OculusPipelineManagerMixin（预热数量/时机）

1. 预热数量由固定 2 提升为覆盖常见分辨率槽（256/512/1024 三槽，或按 maxConnectedSize x resolutionScale 预估上界，上限受 maxRecursiveViews 与显存约束），并在预热阶段就完成 terrain programs 与 RenderTargets 到目标槽尺寸的 resize，使首镜命中预热槽即零惰性成本。
2. 若镜面尺寸变化引入预热未覆盖的新槽，在新纹理首次提交前提前、非渲染线程内（或复用现有 heavyBuildBudget 单帧限制）构造该槽 pipeline，避免反射 pass 内同步 7 s 编译。

风险控制：仅增加预热槽数与预热时机；预热失败仍走现有惰性构造 + quarantine 路径（不回退）。显存占用与预热槽数成正比，需用 maxConnectedSize/分辨率上界做硬顶。

### 3.5 P2 —— 深收敛纹理采样与时域一致性（防御问题1的收敛近似视差）

落点：MirrorTextureManager.getDeepTexture 采样语义（配合 3.1）

depth>=2 深纹理本就是镜面自身相机、扁平的近似，其运动/视差与真实链不同。3.1 落地后深纹理（depth=2 属递归）已逐帧清历史，不再被 TAA 放大；此处仅补充：深纹理合成时使用最近邻/无时域采样，进一步避免近似视差被累积为游动。

风险控制：只影响 depth>=2 的亚像素级深层内容采样滤波，不改变直接视图与 depth1 的清晰度。

### 3.6 不做的事（守住既有边界）

- 不按光影名做白/黑名单（违反光影无关）。
- 不重建第二张阴影图、不改捕获槽位/分辨率分级、不动 MirrorCapturePool/MirrorTextureManager 的递归收敛与预算主体逻辑。
- 不移除 deferred 呈现主路径（递归中子镜面在父级 tone-mapping 后再合成的语义保留）。
- 不改反射几何、投影数学、frustum/区块剔除或 Embeddium 深度 pass 隔离。

---

## 4. 量化总结

| # | 异常 | 触发光影 | 根因（底层） | 方案要点 | 预期量化效果 |
|---|---|---|---|---|---|
| 1 | 递归反射远处抖动/闪烁 | Complementary / Sildur / IterationRP | 递归视图错误累积 TAA 历史 + GL 状态损坏污染时域附件 + 预算顺延过期历史 + 收敛近似视差 | 递归视图逐帧清历史(3.1) + 连续帧校验(3.2) + 深纹理同冻结(3.5) | 递归区帧间 changedPct 由 3.01% -> 0.1% 以下（对照单镜基线 0.02%）；直接视图 TAA 不变；消除 deferredViews 导致的跳变 |
| 2 | Sundial 首镜不渲染实体/区块 | Sundial | 新分辨率槽惰性管线创建同步阻塞约 7 s + 预热只覆盖 2 槽且首帧才 resize + 首用全清 | 槽位优雅过渡(3.3) + 新槽异步预热/预热扩容(3.4) | 首镜管线创建阻塞 7 s -> 命中预热槽约 0 ms；实体/区块首帧即可见 |
| 3 | Sundial 改尺寸变黑 | Sundial | 分辨率槽切换触发 Oculus gbuffer resize 错配 -> y exceeds 洪泛 16566 次 + 陈旧纹理 id | 延迟销毁旧纹理 + 惰性 id + 干净 resize 边界(3.3) | 改尺寸 GL 错误洪泛 16566 -> 0；镜像不再变黑 |

实测基线（回归对照）：
- Sundial 单镜稳态 reflectionPasses=120, avg=1.2 ms, GL错误=0, deferredViews=0；
- Sundial 首镜 256 槽 Creating mirror pipeline = 6971.84 ms，改尺寸后 y-exceeds=16566；
- Complementary 递归 avg=11~13 ms/Pass, deferredViews=3~10, GL错误=32111，递归静止 changedPct=3.01%，单镜静止 changedPct=0.02%。

方案落地后的验收应复核：1 递归区 changedPct 收敛至 0.1% 以下且不劣化直接视图 TAA；2 首镜无 7 s 阻塞、实体区块首帧可见；3 改尺寸 GL 错误归零、镜像不黑；且上述基线（单镜 avg、deferredViews=0、无 quarantine）不劣化。

### 附：复现产物

- 启动/切换/交互脚本：run/repro/launch.ps1、run/repro/set-shaderpack.ps1、run/validation/2026-08-25-real-client/client-window.ps1、framediff.ps1
- 运行日志：run/repro/logs/sundial-repro.stdout.log、run/repro/logs/comp-recursive.stdout.log
- 窗口抓取：run/repro/captures/sundial-mirror1.png（首镜）、sundial-3x3.png（改尺寸后）、comp-frame1/2/3.png（递归）、comp-norec1/2.png（无递归基线）
- 游戏侧日志：<版本目录>/logs/latest.log
