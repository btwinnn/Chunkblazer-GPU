uniform bool chunkblazer_useHardBorder;
uniform vec4 chunkblazer_configGrayColor;
uniform float chunkblazer_configGrayAmount;
uniform int chunkblazer_shadingLevel; // 0 = LIGHT, 1 = HEAVY, 2 = SILHOUETTE (matches ShadingLevel.ordinal())

in float chunkblazer_grayAmount;
in float chunkblazer_clearAmount;
in vec3 chunkblazer_gridPos;

// At the wall of a chunk the player can afford, the grey lifts in a doorway in front of them
// that glides along as they walk: flat for a stretch either side of them, then sloping back
// out to the border (a box, for walls made of game models). In lock-grid units, as
// chunkblazer_gridPos: door = (the border's x or z, the player's place along it, which side
// the chunk is: +1/-1, 1 if the border runs north-south); shape = (how open 0..1, depth, flat
// half-width, slope length).
uniform vec4 chunkblazer_door;
uniform vec4 chunkblazer_doorShape;

float chunkblazer_doorAt() {
  float open = chunkblazer_doorShape.x;
  if (open <= 0.0) {
    return 0.0;
  }
  bool northSouth = chunkblazer_door.w > 0.5;
  float across = northSouth ? chunkblazer_gridPos.x : chunkblazer_gridPos.z;
  float along = northSouth ? chunkblazer_gridPos.z : chunkblazer_gridPos.x;
  float into = (across - chunkblazer_door.x) * chunkblazer_door.z;
  float d = abs(along - chunkblazer_door.y) - chunkblazer_doorShape.z;
  float profile = d <= 0.0 ? 1.0 : clamp(1.0 - d / chunkblazer_doorShape.w, 0.0, 1.0);
  float depth = open * chunkblazer_doorShape.y * profile;
  // Soft over a quarter tile at the far edge.
  return (into > -64.0 && depth > 0.0) ? 1.0 - smoothstep(depth - 16.0, depth + 16.0, into) : 0.0;
}

// ---- Unlock sweep -----------------------------------------------------------
// Must match GRID in ChunkBlazerGpuAddon / CHUNKBLAZER_GRID in vert.glsl.
#define CHUNKBLAZER_ANIM_GRID 7
#define CHUNKBLAZER_REGION_UNITS 8192.0 // 64 tiles * 128 units

uniform int chunkblazer_useGray;
// The same locked grid as the vertex shader's (1 = locked), so grey stops exactly at the
// chunk border instead of bleeding over the last open tile.
uniform int chunkblazer_lockedGrid[CHUNKBLAZER_ANIM_GRID * CHUNKBLAZER_ANIM_GRID];

// 1 = a locked chunk the player can afford to unlock now (or one that was, while it sweeps):
// colour peeks through its edge beside open chunks, a glimpse of what's past the wall.
uniform int chunkblazer_affordGrid[CHUNKBLAZER_ANIM_GRID * CHUNKBLAZER_ANIM_GRID];
#define CHUNKBLAZER_PEEK_TILES 2.5

bool chunkblazer_openCell(int x, int y) {
  if (x < 0 || y < 0 || x >= CHUNKBLAZER_ANIM_GRID || y >= CHUNKBLAZER_ANIM_GRID) {
    return false;
  }
  int i = x * CHUNKBLAZER_ANIM_GRID + y;
  return chunkblazer_lockedGrid[i] == 0 && chunkblazer_affordGrid[i] == 0;
}

// 0..1: how much colour shows through here, fading in over the first few tiles past the wall.
float chunkblazer_peekAt() {
  vec2 cellF = floor(chunkblazer_gridPos.xz / CHUNKBLAZER_REGION_UNITS);
  ivec2 c = ivec2(cellF);
  if (c.x < 0 || c.y < 0 || c.x >= CHUNKBLAZER_ANIM_GRID || c.y >= CHUNKBLAZER_ANIM_GRID
    || chunkblazer_affordGrid[c.x * CHUNKBLAZER_ANIM_GRID + c.y] == 0) {
    return 0.0;
  }
  vec2 lt = (chunkblazer_gridPos.xz / CHUNKBLAZER_REGION_UNITS - cellF) * 64.0;
  float d = 1e6;
  if (chunkblazer_openCell(c.x - 1, c.y)) d = min(d, lt.x);
  if (chunkblazer_openCell(c.x + 1, c.y)) d = min(d, 64.0 - lt.x);
  if (chunkblazer_openCell(c.x, c.y - 1)) d = min(d, lt.y);
  if (chunkblazer_openCell(c.x, c.y + 1)) d = min(d, 64.0 - lt.y);
  return 1.0 - smoothstep(0.0, CHUNKBLAZER_PEEK_TILES, d);
}

float chunkblazer_lockedHere() {
  ivec2 cell = ivec2(floor(chunkblazer_gridPos.xz / CHUNKBLAZER_REGION_UNITS));
  if (cell.x < 0 || cell.y < 0 || cell.x >= CHUNKBLAZER_ANIM_GRID || cell.y >= CHUNKBLAZER_ANIM_GRID) {
    return 0.0;
  }
  return float(chunkblazer_lockedGrid[cell.x * CHUNKBLAZER_ANIM_GRID + cell.y]);
}
// Per grid cell: < 0 = not animating, 0..1 = eased sweep progress. Sweeping
// cells are NOT flagged locked in the vertex grid (that made the soft border
// bleed grey into the neighbouring chunk); the grey inside them is applied
// per pixel here instead.
uniform float chunkblazer_unlockProgress[CHUNKBLAZER_ANIM_GRID * CHUNKBLAZER_ANIM_GRID];
// Per grid cell, the chunk-local point the sweep fans out from: the middle of
// the wall tile nearest the player when the unlock began, or the player's own
// position when standing inside the chunk.
uniform vec2 chunkblazer_sweepOrigin[CHUNKBLAZER_ANIM_GRID * CHUNKBLAZER_ANIM_GRID];
uniform vec4 chunkblazer_unlockGlow;       // flame colour + strength in alpha
uniform float chunkblazer_unlockGlowWidth; // sweep-front width, as a fraction of a chunk
uniform float chunkblazer_time;            // seconds, drives the flicker

// How far (fraction of a chunk) cosmetic flame tongues reach either side of
// the front. MUST match SWEEP_JAG in ChunkBlazerGpuAddon.
#define CHUNKBLAZER_SWEEP_JAG 0.06

// Integer hash of a lattice point. A fract(p * bigConstant) float hash falls
// apart once p gets into the thousands (the clock gets there in minutes) and
// turns the flames into big blocky glitches; integer maths stays exact.
// MUST match ChunkBlazerGpuAddon.hash().
float chunkblazer_hash(vec2 p) {
  uvec2 q = uvec2(ivec2(floor(p)) + 65536);
  uint h = q.x * 1597334677u ^ q.y * 3812015801u;
  h = (h ^ (h >> 16u)) * 2246822519u;
  h ^= h >> 13u;
  return float(h & 0xFFFFFFu) / 16777216.0;
}

// Value noise and fbm. MUST match ChunkBlazerGpuAddon.noise()/fbm().
float chunkblazer_noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(chunkblazer_hash(i), chunkblazer_hash(i + vec2(1.0, 0.0)), f.x),
             mix(chunkblazer_hash(i + vec2(0.0, 1.0)), chunkblazer_hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float chunkblazer_fbm(vec2 p) {
  return 0.57 * chunkblazer_noise(p) + 0.29 * chunkblazer_noise(p * 2.1 + 3.7)
       + 0.14 * chunkblazer_noise(p * 4.3 + 9.1);
}

// When (0..1 of the sweep) each point is reached. `local` is the point in its
// chunk (0..1), `o` the sweep origin. Time-free, so the flame walls can drop
// in exact step. MUST match ChunkBlazerGpuAddon.sweepOrder().
float chunkblazer_sweepOrder(vec2 local, vec2 o) {
  float far = max(
    max(length(o), length(vec2(1.0 - o.x, o.y))),
    max(length(vec2(o.x, 1.0 - o.y)), length(1.0 - o)));
  vec2 lt = local * 64.0; // chunk-local tiles
  float dist = clamp(distance(local, o) / far, 0.0, 1.0);
  // Lava: lobes, like a flow finding its way over the ground.
  return clamp(dist + (chunkblazer_fbm(lt * 0.09) - 0.5) * 0.3, 0.0, 1.0);
}

// Is this pixel in a sweeping chunk? If so, returns true with `m` = how
// revealed it is (0 = still grey, 1 = full colour) and `lt` = its
// chunk-local tile position.
bool chunkblazer_sweepAt(out float m, out vec2 lt) {
  m = 1.0;
  lt = vec2(0.0);
  if (chunkblazer_useGray == 0) {
    return false;
  }
  vec2 cellF = floor(chunkblazer_gridPos.xz / CHUNKBLAZER_REGION_UNITS);
  ivec2 cell = ivec2(cellF);
  if (cell.x < 0 || cell.y < 0 || cell.x >= CHUNKBLAZER_ANIM_GRID || cell.y >= CHUNKBLAZER_ANIM_GRID) {
    return false;
  }
  int cellIdx = cell.x * CHUNKBLAZER_ANIM_GRID + cell.y;
  float p = chunkblazer_unlockProgress[cellIdx];
  if (p < 0.0) {
    return false;
  }

  vec2 local = chunkblazer_gridPos.xz / CHUNKBLAZER_REGION_UNITS - cellF;
  lt = local * 64.0;
  float order = chunkblazer_sweepOrder(local, chunkblazer_sweepOrigin[cellIdx]);

  // The front starts JAG before the first point and ends JAG + w past the
  // last, so the band fully enters and fully leaves. MUST match revealAt().
  float w = max(chunkblazer_unlockGlowWidth, 0.001);
  float front = p * (1.0 + w + 2.0 * CHUNKBLAZER_SWEEP_JAG) - CHUNKBLAZER_SWEEP_JAG;
  m = clamp((front - order) / w, 0.0, 1.0);
  return true;
}

// Fire colour by intensity: dark red embers -> the configured flame colour
// -> orange -> yellow-white tips.
vec3 chunkblazer_firePalette(float i) {
  vec3 base = chunkblazer_unlockGlow.rgb;
  vec3 dark = base * 0.45;
  vec3 hot = vec3(1.0, 0.55, 0.08);
  vec3 tip = vec3(1.0, 0.88, 0.5);
  if (i < 0.4) return mix(dark, base, i / 0.4);
  if (i < 0.8) return mix(base, hot, (i - 0.4) / 0.4);
  return mix(hot, tip, (i - 0.8) / 0.2);
}

// Draw the burning band for pixel at reveal `m` (only called for 0 < m < 1).
void chunkblazer_sweepFx(inout vec3 col, float m, vec2 lt) {
  float a = chunkblazer_unlockGlow.a;
  float t = chunkblazer_time;
  vec2 q = floor(lt * 4.0) / 4.0; // quarter-tile blocks: chunky, OSRS-ish

  // Glowing molten edge, cooling into dark crust with glowing cracks, which then crumbles
  // back to the real ground.
  float lead = smoothstep(0.0, 0.08, m);
  float heat = 1.0 - smoothstep(0.0, 0.6, m);
  float crust = smoothstep(0.12, 0.45, m) * (1.0 - smoothstep(0.7, 1.0, m));
  float crack = 1.0 - smoothstep(0.0, 0.07, abs(chunkblazer_noise(q * 0.7) - 0.5));
  float pulse = 0.8 + 0.2 * chunkblazer_noise(q * 0.5 + vec2(t * 1.5, 0.0));
  col = mix(col, vec3(0.10, 0.06, 0.05), 0.85 * crust);
  float glow = lead * max(heat * (1.0 - 0.8 * crust), crack * crust * 0.9) * pulse;
  col = mix(col, chunkblazer_firePalette(clamp(0.3 + heat * 0.45, 0.0, 0.75)), clamp(glow * 1.2, 0.0, 1.0) * a);
}

float chunkblazer_blendSoftLight(float base, float blend) {
  return blend < 0.5 ?
    2.0 * base * blend + base * base * (1.0 - 2.0 * blend) :
    sqrt(base) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend);
}

vec3 chunkblazer_blendSoftLight(vec3 base, vec3 blend, float opacity) {
  blend = vec3(
    chunkblazer_blendSoftLight(base.r, blend.r),
    chunkblazer_blendSoftLight(base.g, blend.g),
    chunkblazer_blendSoftLight(base.b, blend.b)
  );
  return mix(base, blend, opacity);
}

void chunkblazer_frag(inout vec4 color) {
  float finalGrayAmount = chunkblazer_grayAmount * chunkblazer_lockedHere();
  if (chunkblazer_useHardBorder && finalGrayAmount > 0.0)
    finalGrayAmount = 1.0;

  // Jagex bakes directional terrain lighting into face colours, so the pixel's
  // luminance already encodes the relief of slopes, hills, cliffs and mountains.
  // Every level below is built on that single luminance value.
  float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
  vec3 shaded;

  if (chunkblazer_shadingLevel == 2) {
    // SILHOUETTE: crush luminance with a gamma curve so flat, evenly-lit ground
    // collapses toward black while brighter lit slopes survive as faint
    // outlines. No hue is kept — only the shape of the terrain reads through.
    float relief = pow(clamp(lum, 0.0, 1.0), 3.0) * 0.65;
    shaded = vec3(relief);
    // Allow the configured tint to wash the silhouette (e.g. a cold blue night),
    // at half strength so it stays dark.
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a * 0.5);
  } else if (chunkblazer_shadingLevel == 1) {
    // HEAVY: fully desaturate and darken — clearly off-limits but still legible.
    shaded = vec3(lum * 0.45);
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a);
  } else {
    // LIGHT (default): partial desaturation governed by the Grey Amount slider,
    // then a soft-light tint. Identical to the original wash.
    shaded = mix(color.rgb, vec3(lum), chunkblazer_configGrayAmount);
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a);
  }

  // Grey from the vertex grid (locked chunks, with their soft border), and
  // inside a sweeping chunk, grey that the sweep peels away pixel by pixel.
  float m;
  vec2 lt;
  bool sweeping = chunkblazer_sweepAt(m, lt);
  if (sweeping) {
    // Not over the tutorial's clear area, which was never grey.
    finalGrayAmount = max(finalGrayAmount, (1.0 - m) * (1.0 - chunkblazer_clearAmount));
  }
  // Nor in the doorway, which an unlock sweep starts from as it was.
  float door = chunkblazer_doorAt();
  finalGrayAmount *= 1.0 - door;
  // And it thins at the edge of a chunk the player can afford.
  finalGrayAmount *= 1.0 - 0.85 * chunkblazer_peekAt();
  color.rgb = mix(color.rgb, shaded, finalGrayAmount);

  if (sweeping && m > 0.0 && m < 1.0 && chunkblazer_clearAmount < 0.5 && door < 0.5) {
    chunkblazer_sweepFx(color.rgb, m, lt);
  }
}
