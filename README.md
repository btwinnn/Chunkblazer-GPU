# ChunkBlazer GPU

An optional GPU renderer for [ChunkBlazer](https://github.com/btwinnn/Chunkblazer)
that draws locked chunks in greyscale, shipped as a separate plugin.

- **Package:** `com.chunkblazer.gpu`
- **Standalone:** builds on its own (depends only on `runelite-client`).
- **Config group:** `chunkblazergpu` (kept separate from the core GPU plugin).
- Not compatible with 117 HD. Declares `conflicts = "GPU"` (core GPU plugin).

## Credits / derivation

Derived from two BSD-2-Clause projects — see [`LICENSE`](LICENSE):

- [RuneLite GPU plugin](https://github.com/runelite/runelite) — Adam and the RuneLite contributors
- [Region Locker GPU](https://github.com/slaytostay/region-locker) — slaytostay

## Build

```
./gradlew build
```

`runelite-client` is `compileOnly` and provides LWJGL/Guava/Guice transitively,
so this plugin ships **zero new runtime dependencies**. Run the RuneLite ruleset
before submitting:

```
./gradlew checkstyleMain
```

## Dev client

`com.chunkblazer.gpu` is outside RuneLite's core plugin scan
(`net.runelite.client.plugins`), so the dev client loads it via
`ExternalPluginManager.loadBuiltin` — the same package-agnostic path the Hub uses.
The ChunkBlazer `run-chunkblazer.bat` copies this repo in and loads both plugins
through `com.chunkblazer.DevLauncher`.
