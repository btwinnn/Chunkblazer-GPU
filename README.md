# ChunkBlazer GPU

Standalone RuneLite GPU plugin extracted from the main ChunkBlazer plugin to keep
that plugin under the RuneLite AI reviewer's 200k-token budget. Provides the
locked-chunk greyscale rendering.

Package: `net.runelite.client.plugins.chunkblazer.gpu`

## Not yet buildable standalone
Needs a build file (pom.xml / build.gradle) added, and depends on two classes
that still live in the main ChunkBlazer plugin:
- `ChunkBlazerConfig` (read by `ChunkBlazerGpuAddon`)
- `ShadingLevel`

Copy or share those when wiring up the build here.
