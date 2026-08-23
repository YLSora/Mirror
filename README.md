# Mirror

Standalone Minecraft 1.20.1 Forge mirror mod. The technical mod ID is `mirror`
and the display name is `Mirror`.

The current implementation contains the first playable layer plus direct reflection
MVP: the mirror block, near/far placement, rectangular connections, persistent
master dimensions, crafting, Crystalline material, Elder Guardian loot, an
off-screen reflection target, mirror material shader, first-frame fade, distance
LOD, SHARED texture reuse, and chain-isolated RECURSIVE rendering with hard depth
and resolution limits. Server-side Enderman observation is enabled by default
and can be disabled independently with `enderman.enableObservation` in
`config/mirror-common.toml`.

Build with Java 17:

```text
gradlew clean build
gradlew runClient
gradlew runServer
```

The observation feature checks the player's ray against the mirror surface,
reflects that ray, and applies vanilla Enderman freeze/anger behavior to nearby
Endermen. It runs only on the server and adds no Moonlight runtime dependency.

The original Vista sources remain outside the Gradle source set for reference;
the published JAR is built only from `src/main` and does not provide any `vista`
ID or migration path.

See [NOTICE.md](NOTICE.md) and [LICENSE.md](LICENSE.md) before redistributing.
