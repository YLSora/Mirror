# Mirror

Mirror is a functional Minecraft 1.20.1 Forge mod that forked from Vista.
Add a placeable mirror with real-time reflection mechanisms.

Mirror是模组Vista中的“镜子”向1.20.1Forge的功能移植模组。
添加了镜子方块及其实时渲染机制。

## Description

该模组目前已经移植了Vista模组中的镜子主要功能，
包括镜面反射、连接镜像、远景LOD、Recrusive、Oculus兼容等。
合成配方需要使用远古守卫者掉落的Crystalline。
此外，还可以开启末影人的机制联动。
配置文件目录：`config/mirror-common.toml`。

## Authors and source

Original authors: MehVahdJukaar, Plantkillable
    <https://github.com/MehVahdJukaar/cameramod>
Projected by: MinazukiSora, gpt 5.6 sol high.
    <https://github.com/YLSora/Mirror>

## Requirements

- Minecraft 1.20.1
- Forge >= 47.2.0
- Oculus >= 1.8.0 for shader compatibility

Build with Java 17:

```text
gradlew clean build
gradlew runClient
gradlew runServer
```

The observation feature checks the player's ray against the mirror surface,
reflects that ray, and applies vanilla Enderman freeze/anger behavior to nearby
Endermen.

See [NOTICE.md](NOTICE.md) and [LICENSE.md](LICENSE.md) before redistributing.
