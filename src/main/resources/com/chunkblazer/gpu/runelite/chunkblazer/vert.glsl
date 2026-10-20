// Grid covering the rendered (extended, up to 184x184) scene. The GPU plugin
// draws more regions than a small fixed list could hold, which is why far
// locked chunks used to stay in colour until you walked closer. CHUNKBLAZER_GRID
// must match GRID in ChunkBlazerGpuAddon. 7 comfortably covers the ~4-5 regions
// per axis an extended scene can span.
#define CHUNKBLAZER_GRID 7
uniform int chunkblazer_useGray;
uniform int chunkblazer_baseX;
uniform int chunkblazer_baseY;
uniform int chunkblazer_lockedGrid[CHUNKBLAZER_GRID * CHUNKBLAZER_GRID];
// Tiles kept clear of grey: ChunkBlazer's tutorial keeps the room a new account starts in
// clear, moulded to it. Bit x of row y is world tile clearOrigin + (x, y). All zero: none.
#define CHUNKBLAZER_CLEAR_ROWS 32
uniform ivec2 chunkblazer_clearOrigin;
uniform int chunkblazer_clearMask[CHUNKBLAZER_CLEAR_ROWS];

bool chunkblazer_clearTile(int tx, int ty) {
  int x = tx - chunkblazer_clearOrigin.x;
  int y = ty - chunkblazer_clearOrigin.y;
  if (x < 0 || y < 0 || x >= 32 || y >= CHUNKBLAZER_CLEAR_ROWS) {
    return false;
  }
  return ((chunkblazer_clearMask[y] >> x) & 1) != 0;
}

out float chunkblazer_grayAmount;
// 1.0 inside the tutorial's clear area: an unlock sweep leaves it alone too.
out float chunkblazer_clearAmount;
// Position relative to the lock grid's origin, in 1/128-tile units:
// .x = east, .y = height above ground (up is positive), .z = north.
// Kept grid-relative (max ~57k) so it stays precise as a float.
out vec3 chunkblazer_gridPos;

// Locked flag (1.0 / 0.0) for the region containing world-unit position (wx, wy).
// gridBaseX/Y are the grid's origin region; positions outside the grid return
// 0.0 (never greyed — fails safe).
float chunkblazer_lockedAt(int wx, int wy, int gridBaseX, int gridBaseY) {
  int gx = (wx >> 13) - gridBaseX;
  int gy = (wy >> 13) - gridBaseY;
  if (gx < 0 || gy < 0 || gx >= CHUNKBLAZER_GRID || gy >= CHUNKBLAZER_GRID) {
    return 0.0;
  }
  return float(chunkblazer_lockedGrid[gx * CHUNKBLAZER_GRID + gy]);
}

void chunkblazer_vert(vec3 vertex) {
  // Sub-tile offsets so a vertex sitting exactly on a region boundary still
  // counts as locked when an adjacent region is locked (avoids thin seams).
  const ivec2 offsets[5] = ivec2[](
    ivec2(0, 0),
    ivec2(-1, -1),
    ivec2(-1, 1),
    ivec2(1, -1),
    ivec2(1, 1)
  );

  // World position in 1/128-tile units; >>13 converts to region coords
  // (7 bits units->tiles, 6 bits tiles->region).
  int wx = int(vertex.x) + chunkblazer_baseX;
  int wy = int(vertex.z) + chunkblazer_baseY;

  // Grid origin: one region before the scene base. Must match the addon's
  // gridBaseRegion = (getBaseX() >> 6) - 1.
  int gridBaseX = (chunkblazer_baseX >> 13) - 1;
  int gridBaseY = (chunkblazer_baseY >> 13) - 1;

  float locked = 0.0;
  for (int i = 0; i < 5; ++i) {
    locked = max(locked, chunkblazer_lockedAt(wx + offsets[i].x, wy + offsets[i].y, gridBaseX, gridBaseY));
  }

  // A vertex on a tile's edge or corner counts for every tile it touches, so the clear
  // room's walls stay clear and its edge fades over one tile outside.
  chunkblazer_clearAmount = 0.0;
  int tx = wx >> 7;
  int ty = wy >> 7;
  int tx0 = (wx & 127) == 0 ? tx - 1 : tx;
  int ty0 = (wy & 127) == 0 ? ty - 1 : ty;
  if (chunkblazer_clearTile(tx, ty) || chunkblazer_clearTile(tx0, ty) || chunkblazer_clearTile(tx, ty0)
    || chunkblazer_clearTile(tx0, ty0)) {
    locked = 0.0;
    chunkblazer_clearAmount = 1.0;
  }


  chunkblazer_grayAmount = float(chunkblazer_useGray) * locked;
  // OSRS scene Y grows downward, so negate it for "height".
  chunkblazer_gridPos = vec3(wx - (gridBaseX << 13), -vertex.y, wy - (gridBaseY << 13));
}
