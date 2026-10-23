/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer.gpu;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import com.chunkblazer.gpu.runelite.GpuPlugin;
import com.chunkblazer.gpu.runelite.GpuPluginConfig;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class ChunkBlazerGpuAddon
{
	@Inject
	private Client client;

	@Inject
	private GpuPluginConfig config;

	// STANDALONE: this plugin has no compile-time dependency on the main
	// ChunkBlazer plugin. The unlocked-region set is read straight from the
	// main plugin's persisted config value ("chunkblazer" group,
	// "unlockedChunks" key) via ConfigManager — the same string
	// ChunkBlazerPlugin.getUnlockedRegionIds() parses. If the main plugin
	// isn't installed the value is null and everything renders locked-grey,
	// which is the honest representation.
	@Inject
	private ConfigManager configManager;

	// Tells the main ChunkBlazer plugin when an unlock sweep starts and
	// finishes (it holds the new task cards until then). RuneLite's
	// plugin-to-plugin channel: no compile-time dependency either way.
	@Inject
	private EventBus eventBus;
	static final String MESSAGE_NAMESPACE = "chunkblazer";
	static final String SWEEP_STARTED = "unlockSweepStarted";
	static final String SWEEP_FINISHED = "unlockSweepFinished";
	// Regions whose sweep is a test ("Play Unlock On Test Chunk").
	private final Set<Integer> testSweeps = new HashSet<>();

	// ---------------------------------------------------------------------
	// SHARED CONTRACT WITH THE MAIN ChunkBlazer PLUGIN (keep in sync!)
	// This plugin ships standalone (no compile-time dep on com.chunkblazer),
	// so the following three things are hand-mirrored from the main plugin and
	// will SILENTLY DRIFT if the main plugin changes them. If greyscale ever
	// stops matching in-game unlocks, re-check all three against ChunkBlazer:
	//   1. Config group/key below  -> ChunkBlazerConfig group + "unlockedChunks"
	//   2. PRIF_CITY_REGIONS (~L50) -> ChunkBlazerPlugin.PRIF_CITY_REGIONS
	//   3. Surface band 39..64 in isAccessible() -> ChunkBlazerPlugin surface rule
	//   4. "tutorialChunks" below -> ChunkBlazerTutorial's practice unlocks
	//   5. "tutorialPreview" below -> ChunkBlazerTutorial's locked view before a mode
	//   6. "tutorialClearArea" below -> ChunkBlazerTutorial.clearMask (start room tiles)
	//   8. "tutorialFence" below -> ChunkBlazerTutorial.setFence/dropFence (flame wall round a square)
	// ---------------------------------------------------------------------
	private static final String CHUNKBLAZER_GROUP = "chunkblazer";
	private static final String UNLOCKED_CHUNKS_KEY = "unlockedChunks";
	// On Tutorial Island, ChunkBlazer's tutorial unlocks the island's chunks as practice:
	// shown unlocked, never saved in unlockedChunks. It keeps them under this key, for as
	// long as the island isn't done (VarPlayerID.TUTORIAL below 1000).
	private static final String TUTORIAL_CHUNKS_KEY = "tutorialChunks";
	private static final int TUTORIAL_ISLAND_DONE = 1000;
	// Set while ChunkBlazer's tutorial shows the world locked before a mode is picked: only
	// its practice chunks count as unlocked.
	private static final String TUTORIAL_PREVIEW_KEY = "tutorialPreview";

	// The GPU plugin renders the extended 184x184 scene, which can span more
	// regions than the old fixed 16-slot list could hold — that overflow is why
	// far locked chunks rendered in colour until you walked closer. Instead of a
	// capped list we upload a per-region locked/unlocked grid covering the whole
	// rendered scene. GRID must match CHUNKBLAZER_GRID in vert.glsl.
	private static final int GRID = 7;
	private final int[] lockedGrid = new int[GRID * GRID];
	// Per grid cell, 1 = a locked chunk the player can afford to unlock now: its walls pulse
	// and colour peeks through its edge (see frag.glsl and wallfrag.glsl).
	private final int[] affordGrid = new int[GRID * GRID];
	// This frame's affordable chunks (ChunkBlazer's "affordableChunks").
	private Set<Integer> affordable = new HashSet<>();
	// Unlocking chunks that were affordable: their colour peek stays while they sweep.
	private final Set<Integer> peekSweeps = new HashSet<>();
	private int uniAffordGrid;
	private final float[] unlockProgressGrid = new float[GRID * GRID];
	private final float[] sweepOriginGrid = new float[GRID * GRID * 2];

	// regionId -> chunk-local (0..1, 0..1) point the Sweep fans out from: the
	// middle of the wall tile nearest the player when the animation started.
	private final Map<Integer, float[]> sweepOrigin = new HashMap<>();

	// ---- Unlock animation state ----
	// regionId -> System.currentTimeMillis() when we saw it become unlocked.
	private final Map<Integer, Long> unlockStartMs = new HashMap<>();
	// Unlocked set from the previous frame, used to spot new unlocks. Null until
	// the first read after login/profile change, so existing unlocks never animate.
	private Set<Integer> lastUnlocked;
	private String lastProfileKey;
	// More new regions than this in one frame is a progress load/server sync,
	// not a player unlocking chunks, so don't animate them.
	private static final int MAX_SIMULTANEOUS_UNLOCK_ANIMS = 4;


	// ---- Chunk walls ----
	// This frame's wanted wall pieces: wallKey -> height (1 = normal; more
	// while surging as the unlock wave passes). Built during the scene pass,
	// then drawn by GpuPlugin (see buildWall).
	private final Map<Long, Float> desiredWalls = new HashMap<>();
	// The opening wave, like ChunkBlazer's original wall: as the sweep reaches
	// a piece it surges up by SURGE over the first SURGE_PART of the band,
	// then drops away.
	private static final float SURGE = 0.4f;
	private static final float SURGE_PART = 0.3f;
	// Only put walls within this many tiles of the player (like ChunkBlazer's own walls).
	private static final int WALL_RANGE = 40;

	// Prifddinas: the city's real regions sit far above the overworld surface
	// band (regionY 94-95), so the free-dungeon coordinate rule would treat them
	// as always-accessible. Must mirror ChunkBlazerPlugin.PRIF_CITY_REGIONS so
	// the shader greys the locked city like the rest of the plugin does.
	private static final Set<Integer> PRIF_CITY_REGIONS = new HashSet<>(Arrays.asList(
		12894, 12895, 13150, 13151));

	private boolean isValid;
	private int glProgram;
	private int uniUseGray;
	private int uniUseHardBorder;
	private int uniGrayAmount;
	private int uniGrayColor;
	private int uniShadingLevel;
	private int uniBaseX;
	private int uniBaseY;
	private int uniLockedGrid;
	private int uniClearOrigin;
	private int uniDoor;
	private int uniDoorShape;
	private int uniClearMask;
	private int uniUnlockProgress;
	private int uniUnlockGlow;
	private int uniUnlockGlowWidth;
	private int uniSweepOrigin;
	private int uniTime;

	// How far flame tongues reach either side of the sweep front, as a
	// fraction of a chunk. MUST match CHUNKBLAZER_SWEEP_JAG in frag.glsl.
	private static final float SWEEP_JAG = 0.06f;

	public void reset()
	{
		isValid = false;
		glProgram = 0;
		unlockStartMs.clear();
		testSweeps.clear();
		sweepOrigin.clear();
		peekSweeps.clear();
		lastUnlocked = null;
		lastProfileKey = null;
		desiredWalls.clear();
	}

	// Wall tile key: plane-independent tile edge. x and y are the world tile of
	// the edge's south/west end; the low bit says which way the edge runs.
	static long wallKey(int x, int y, boolean northSouth)
	{
		return ((long) x << 20) | ((long) y << 1) | (northSouth ? 1 : 0);
	}

	static int wallKeyX(long key)
	{
		return (int) (key >> 20);
	}

	static int wallKeyY(long key)
	{
		return (int) ((key >> 1) & 0x7FFFF);
	}

	static boolean wallKeyNorthSouth(long key)
	{
		return (key & 1) == 1;
	}

	public void beforeRender(int glProgram)
	{
		// Forget last frame's walls; they're rebuilt below if still wanted.
		desiredWalls.clear();

		if (client.getGameState().getState() < GameState.LOADING.getState())
		{
			return;
		}

		if (this.glProgram != glProgram)
		{
			this.glProgram = glProgram;
			uniUseGray = glGetUniformLocation(glProgram, "chunkblazer_useGray");
			uniUseHardBorder = glGetUniformLocation(glProgram, "chunkblazer_useHardBorder");
			uniGrayAmount = glGetUniformLocation(glProgram, "chunkblazer_configGrayAmount");
			uniGrayColor = glGetUniformLocation(glProgram, "chunkblazer_configGrayColor");
			uniShadingLevel = glGetUniformLocation(glProgram, "chunkblazer_shadingLevel");
			uniBaseX = glGetUniformLocation(glProgram, "chunkblazer_baseX");
			uniBaseY = glGetUniformLocation(glProgram, "chunkblazer_baseY");
			uniLockedGrid = glGetUniformLocation(glProgram, "chunkblazer_lockedGrid");
			uniAffordGrid = glGetUniformLocation(glProgram, "chunkblazer_affordGrid");
			uniClearOrigin = glGetUniformLocation(glProgram, "chunkblazer_clearOrigin");
			uniDoor = glGetUniformLocation(glProgram, "chunkblazer_door");
			uniDoorShape = glGetUniformLocation(glProgram, "chunkblazer_doorShape");
			uniClearMask = glGetUniformLocation(glProgram, "chunkblazer_clearMask");
			uniUnlockProgress = glGetUniformLocation(glProgram, "chunkblazer_unlockProgress");
			uniUnlockGlow = glGetUniformLocation(glProgram, "chunkblazer_unlockGlow");
			uniUnlockGlowWidth = glGetUniformLocation(glProgram, "chunkblazer_unlockGlowWidth");
			uniSweepOrigin = glGetUniformLocation(glProgram, "chunkblazer_sweepOrigin");
			uniTime = glGetUniformLocation(glProgram, "chunkblazer_time");
			isValid = uniUseGray != -1;
			checkGLErrors();
		}

		if (isValid)
		{
			updateUniforms();
		}

		checkGLErrors();
	}

	private void updateUniforms()
	{
		var vw = client.getTopLevelWorldView();
		if (vw == null)
		{
			return;
		}

		// Get the currently bound program, so we can restore the state later if needed
		int currentProgram = glGetInteger(GL_CURRENT_PROGRAM);
		if (currentProgram != glProgram)
		{
			glUseProgram(glProgram);
		}

		// Defaults map to slaytostay's out-of-the-box settings (50% gray amount,
		// soft black tint, soft border) so the visual feels familiar.
		Color tint = config.gpuGrayTint();
		glUniform1i(uniUseHardBorder, config.gpuHardBorder() ? 1 : 0);
		glUniform1f(uniGrayAmount, config.gpuGrayAmount() / 255f);
		glUniform1i(uniShadingLevel, config.gpuShadingLevel().ordinal());
		glUniform4f(uniGrayColor,
			tint.getRed()   / 255f,
			tint.getGreen() / 255f,
			tint.getBlue()  / 255f,
			tint.getAlpha() / 255f
		);

		var mapRegions = vw.getMapRegions();

		// Snapshot unlocked-region set once per frame from the config string.
		Set<Integer> unlocked = readUnlockedRegionIds();
		long now = System.currentTimeMillis();
		trackNewUnlocks(unlocked, now);
		uploadUnlockAnimUniforms();

		// Don't grey out instanced areas (raids, GoTR, etc.) when the instance
		// happens to share coordinates with an unlocked region — the shader
		// can't tell the cloned region apart from the original. Mirrors
		// Region Locker's behavior, but with our "unlocked = on the safe list"
		// semantics flipped relative to theirs.
		boolean instanceCoincidesWithUnlockedRegion = false;
		if (vw.isInstance() && mapRegions != null)
		{
			for (int region : mapRegions)
			{
				if (isAccessible(region, unlocked))
				{
					instanceCoincidesWithUnlockedRegion = true;
					break;
				}
			}
		}

		if (!config.useGpuGreyscale() || instanceCoincidesWithUnlockedRegion)
		{
			glUniform1i(uniUseGray, 0);
			liveDoor = null;
			wallMesh = null;
		}
		else
		{
			glUniform1i(uniUseGray, 1);
			glUniform1i(uniBaseX, vw.getBaseX() * 128);
			glUniform1i(uniBaseY, vw.getBaseY() * 128);
			if (uniClearOrigin != -1 && uniClearMask != -1)
			{
				int[] clear = tutorialClearArea();
				glUniform2i(uniClearOrigin, clear[0], clear[1]);
				glUniform1iv(uniClearMask, java.util.Arrays.copyOfRange(clear, 2, 2 + CLEAR_ROWS));
			}

			// Build a per-region locked/unlocked grid covering the whole
			// rendered (extended) scene, straight from the unlocked-config set.
			// Unlike the old approach this does NOT depend on getMapRegions(),
			// so far chunks at the edge of draw distance grey correctly instead
			// of only once you walk into them. The grid origin is one region
			// before the scene base; the >>6 and -1 here must match the
			// gridBase math in vert.glsl, and GRID must match CHUNKBLAZER_GRID.
			Set<Integer> canAfford = new HashSet<>();
			addRegionIds(canAfford, configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, AFFORDABLE_CHUNKS_KEY));
			affordable = canAfford;
			int gridBaseRegionX = (vw.getBaseX() >> 6) - 1;
			int gridBaseRegionY = (vw.getBaseY() >> 6) - 1;
			for (int gx = 0; gx < GRID; ++gx)
			{
				for (int gy = 0; gy < GRID; ++gy)
				{
					int regionId = ((gridBaseRegionX + gx) << 8) | (gridBaseRegionY + gy);
					int i = gx * GRID + gy;
					float progress = unlockProgress(regionId, now);
					unlockProgressGrid[i] = progress;
					float[] origin = sweepOrigin.get(regionId);
					sweepOriginGrid[i * 2] = origin != null ? origin[0] : 0.5f;
					sweepOriginGrid[i * 2 + 1] = origin != null ? origin[1] : 0f;
					// A sweeping chunk is NOT flagged locked here: the vertex grid's
					// soft border would bleed its grey into the neighbouring chunk.
					// The fragment shader greys it per pixel and peels that away.
					lockedGrid[i] = (progress < 0f && !isAccessible(regionId, unlocked)) ? 1 : 0;
					affordGrid[i] = lockedGrid[i] == 1 && canAfford.contains(regionId)
						|| progress >= 0f && peekSweeps.contains(regionId) ? 1 : 0;
				}
			}

			glUniform1iv(uniLockedGrid, lockedGrid);
			if (uniAffordGrid != -1)
			{
				glUniform1iv(uniAffordGrid, affordGrid);
			}
			if (uniUnlockProgress != -1)
			{
				glUniform1fv(uniUnlockProgress, unlockProgressGrid);
			}
			if (uniSweepOrigin != -1)
			{
				glUniform2fv(uniSweepOrigin, sweepOriginGrid);
			}

			// At the border of a chunk the player can afford, the wall morphs round them into a
			// small doorway box, and the grey in it lifts. While that chunk's unlock sweeps, the
			// doorway it started from stays put.
			Door door = sweepDoor != null && unlockStartMs.containsKey(sweepDoor.region) ? sweepDoor : null;
			if (door == null)
			{
				sweepDoor = null;
				float[] at = playerTiles(vw);
				door = at == null ? null : findDoor(at, gridBaseRegionX, gridBaseRegionY);
			}
			liveDoor = door;
			// The wall bends back from the player in a little cone (see Door.bend).
			if (uniDoor != -1 && uniDoorShape != -1)
			{
				if (door == null)
				{
					glUniform4f(uniDoorShape, 0f, 0f, 0f, 1f);
				}
				else
				{
					// Relative to the lock grid's origin, like the shader's own positions.
					int acrossOrigin = (door.northSouth ? gridBaseRegionX : gridBaseRegionY) << 6;
					int alongOrigin = (door.northSouth ? gridBaseRegionY : gridBaseRegionX) << 6;
					glUniform4f(uniDoor, (door.border - acrossOrigin) * 128f, (door.along - alongOrigin) * 128f,
						door.dir, door.northSouth ? 1f : 0f);
					glUniform4f(uniDoorShape, door.open, BEND_DEPTH * 128f, BEND_FLAT * 128f, BEND_RAMP * 128f);
				}
			}

			if (config.gpuFlameWalls() && !vw.isInstance())
			{
				buildWalls(vw, gridBaseRegionX, gridBaseRegionY);
				wallMesh = buildWall(vw, door);
			}
			else
			{
				wallMesh = null;
			}
		}

		// Restore the previous state
		if (glProgram != currentProgram)
		{
			glUseProgram(currentProgram);
		}
	}

	/**
	 * Diff this frame's unlocked set against last frame's and start an
	 * animation for each newly unlocked region. Never animates on the first
	 * read after login or an RS profile switch, or for a large batch (that's a
	 * progress load, not the player buying chunks).
	 */
	private void trackNewUnlocks(Set<Integer> unlocked, long now)
	{
		String profileKey = configManager.getRSProfileKey();
		if (lastUnlocked == null || !Objects.equals(profileKey, lastProfileKey))
		{
			// Login or account switch: cut any sweep short, and say it's over so
			// the main plugin doesn't keep its cards held.
			for (int region : unlockStartMs.keySet())
			{
				post(SWEEP_FINISHED, region, testSweeps.contains(region));
			}
			unlockStartMs.clear();
			testSweeps.clear();
		}
		else if (config.gpuUnlockSweep())
		{
			// New chunks, even if others left the set at the same moment (ChunkBlazer's tutorial
			// swapping its practice chunks for the real ones as Lumbridge unlocks).
			Set<Integer> added = new HashSet<>(unlocked);
			added.removeAll(lastUnlocked);
			if (!added.isEmpty() && added.size() <= MAX_SIMULTANEOUS_UNLOCK_ANIMS)
			{
				for (int region : added)
				{
					startAnimation(region, now, false);
				}
			}
		}
		lastUnlocked = unlocked;
		lastProfileKey = profileKey;

		// Drop finished animations, and say so.
		long duration = config.gpuUnlockDuration();
		for (Iterator<Map.Entry<Integer, Long>> it = unlockStartMs.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Long> e = it.next();
			if (now - e.getValue() >= duration)
			{
				it.remove();
				post(SWEEP_FINISHED, e.getKey(), testSweeps.remove(e.getKey()));
			}
		}
		sweepOrigin.keySet().removeIf(r -> !unlockStartMs.containsKey(r));
		peekSweeps.removeIf(r -> !unlockStartMs.containsKey(r));
	}

	private void post(String name, int regionId, boolean test)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("regionId", regionId);
		data.put("test", test);
		// How long a sweep runs, so ChunkBlazer can deal its cards before it ends.
		data.put("durationMs", (long) config.gpuUnlockDuration());
		eventBus.post(new PluginMessage(MESSAGE_NAMESPACE, name, data));
	}

	private void startAnimation(int regionId, long now, boolean test)
	{
		unlockStartMs.put(regionId, now);
		if (test)
		{
			testSweeps.add(regionId);
		}
		else
		{
			testSweeps.remove(regionId);
		}
		post(SWEEP_STARTED, regionId, test);
		// Unlocking the chunk the player has the doorway open into: the sweep carries on from
		// that doorway instead of the wall and grey snapping back first.
		if (liveDoor != null && liveDoor.region == regionId)
		{
			sweepDoor = liveDoor;
		}
		if (affordable.contains(regionId))
		{
			peekSweeps.add(regionId);
		}
		// The lava spreads from where the player stands inside the chunk; otherwise it rolls in
		// from the wall tile nearest them.
		float[] here = playerChunkLocal(regionId);
		boolean inside = here[0] >= 0 && here[0] <= 1 && here[1] >= 0 && here[1] <= 1;
		sweepOrigin.put(regionId, inside ? here : wallTileNearestPlayer(regionId));
	}

	/** The player's position relative to {@code regionId} (0..1 inside it; can be outside). */
	private float[] playerChunkLocal(int regionId)
	{
		var player = client.getLocalPlayer();
		if (player == null)
		{
			return new float[]{0.5f, 0.5f};
		}
		var at = player.getWorldLocation();
		return new float[]{
			(at.getX() + 0.5f - ((regionId >> 8) << 6)) / 64f,
			(at.getY() + 0.5f - ((regionId & 0xFF) << 6)) / 64f};
	}

	/** Wall piece height as the sweep passes it, opening up: surge, then drop. */
	private static float openingWave(float m)
	{
		if (m < SURGE_PART)
		{
			float t = m / SURGE_PART;
			return 1f + SURGE * (1f - (1f - t) * (1f - t) * (1f - t));
		}
		float t = (m - SURGE_PART) / (1f - SURGE_PART);
		return (1f + SURGE) * (1f - t * t);
	}

	/** Wall piece height as the sweep reaches a still-locked neighbour: rise. */
	private static float rise(float m)
	{
		return 1f - (1f - m) * (1f - m) * (1f - m);
	}

	/** Eased 0..1 progress for a region that is animating, or -1 if it isn't. */
	private float unlockProgress(int regionId, long now)
	{
		Long start = unlockStartMs.get(regionId);
		if (start == null || !config.gpuUnlockSweep())
		{
			return -1f;
		}
		float t = Math.min(1f, Math.max(0f, (now - start) / (float) config.gpuUnlockDuration()));
		// Ease-in-out: slow start so the player sees it begin, slow settle at the end.
		return t * t * (3f - 2f * t);
	}

	private void uploadUnlockAnimUniforms()
	{
		if (uniUnlockGlow == -1)
		{
			return; // shader built without animation support
		}
		Color flame = config.gpuFlameColor();
		glUniform4f(uniUnlockGlow,
			flame.getRed()   / 255f,
			flame.getGreen() / 255f,
			flame.getBlue()  / 255f,
			flame.getAlpha() / 255f
		);
		glUniform1f(uniUnlockGlowWidth, glowWidth());
		if (uniTime != -1)
		{
			// Wrapped every 10 minutes so the float stays precise.
			glUniform1f(uniTime, (System.currentTimeMillis() % 600_000L) / 1000f);
		}
	}

	/** Reveal-front width as a fraction of a chunk (config is in tiles). */
	private float glowWidth()
	{
		return config.gpuUnlockGlowWidth() / 64f;
	}

	// ---------------------------------------------------------------------
	// Reveal maths. MUST mirror chunkblazer_reveal() in
	// frag.glsl, so a wall drops at exactly the moment the colour reaches it.
	// ---------------------------------------------------------------------

	/** 0 = still locked-looking, 1 = revealed, at chunk-local point (lx, ly). */
	private float revealAt(int regionId, float p, float lx, float ly)
	{
		float[] o = sweepOrigin.getOrDefault(regionId, new float[]{0.5f, 0f});
		float order = sweepOrder(o[0], o[1], lx, ly);
		float w = Math.max(glowWidth(), 0.001f);
		// Same front as the shader (without its cosmetic flame tongues).
		float front = p * (1 + w + 2 * SWEEP_JAG) - SWEEP_JAG;
		return Math.max(0f, Math.min(1f, (front - order) / w));
	}

	/**
	 * When (0..1 of the sweep) chunk-local point (lx, ly) is reached, for a
	 * sweep from (ox, oy). MUST match chunkblazer_sweepOrder() in frag.glsl.
	 */
	static float sweepOrder(float ox, float oy, float lx, float ly)
	{
		double far = Math.max(
			Math.max(Math.hypot(ox, oy), Math.hypot(1 - ox, oy)),
			Math.max(Math.hypot(ox, 1 - oy), Math.hypot(1 - ox, 1 - oy)));
		float tx = lx * 64f;
		float ty = ly * 64f;
		float dist = (float) Math.min(1.0, Math.hypot(lx - ox, ly - oy) / far);
		// Lava: lobes, like a flow finding its way over the ground.
		return clamp01(dist + (fbm(tx * 0.09f, ty * 0.09f) - 0.5f) * 0.3f);
	}

	/** Same as GLSL smoothstep. */
	private static float smoothstep(float edge0, float edge1, float x)
	{
		float t = clamp01((x - edge0) / (edge1 - edge0));
		return t * t * (3 - 2 * t);
	}

	private static float clamp01(float v)
	{
		return Math.max(0f, Math.min(1f, v));
	}

	/**
	 * Integer hash of the lattice point containing (x, y), 0..1. Bit-for-bit
	 * the same as chunkblazer_hash() in frag.glsl (Java int multiply wraps
	 * like GLSL uint; >>> is the unsigned shift).
	 */
	static float hash(float x, float y)
	{
		int qx = (int) Math.floor(x) + 65536;
		int qy = (int) Math.floor(y) + 65536;
		int h = qx * 1597334677 ^ qy * (int) 3812015801L;
		h = (h ^ (h >>> 16)) * (int) 2246822519L;
		h ^= h >>> 13;
		return (h & 0xFFFFFF) / 16777216f;
	}

	/** Value noise; same as chunkblazer_noise() in frag.glsl. */
	static float noise(float x, float y)
	{
		float ix = (float) Math.floor(x);
		float iy = (float) Math.floor(y);
		float fx = x - ix;
		float fy = y - iy;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		float a = hash(ix, iy);
		float b = hash(ix + 1, iy);
		float c = hash(ix, iy + 1);
		float d = hash(ix + 1, iy + 1);
		return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
	}

	/** Same as chunkblazer_fbm() in frag.glsl. */
	static float fbm(float x, float y)
	{
		return 0.57f * noise(x, y) + 0.29f * noise(x * 2.1f + 3.7f, y * 2.1f + 3.7f)
			+ 0.14f * noise(x * 4.3f + 9.1f, y * 4.3f + 9.1f);
	}

	/**
	 * Chunk-local (0..1) middle of the wall tile on {@code regionId}'s border
	 * nearest the player. From outside the chunk that's the closest point on
	 * the border; from inside, the closest point on the nearest edge.
	 */
	private float[] wallTileNearestPlayer(int regionId)
	{
		var player = client.getLocalPlayer();
		if (player == null)
		{
			return new float[]{0.5f, 0f};
		}
		var at = player.getWorldLocation();
		float lx = (at.getX() + 0.5f - ((regionId >> 8) << 6)) / 64f;
		float ly = (at.getY() + 0.5f - ((regionId & 0xFF) << 6)) / 64f;
		float cx = Math.max(0f, Math.min(1f, lx));
		float cy = Math.max(0f, Math.min(1f, ly));
		if (cx == lx && cy == ly)
		{
			// Inside: snap to the nearest edge.
			float west = lx;
			float east = 1 - lx;
			float south = ly;
			float north = 1 - ly;
			float min = Math.min(Math.min(west, east), Math.min(south, north));
			if (min == west)
			{
				cx = 0;
			}
			else if (min == east)
			{
				cx = 1;
			}
			else if (min == south)
			{
				cy = 0;
			}
			else
			{
				cy = 1;
			}
		}
		// Centre on that wall tile, so the fan starts from a tile not a corner.
		if (cx == 0 || cx == 1)
		{
			cy = (Math.min(63, (float) Math.floor(cy * 64)) + 0.5f) / 64f;
		}
		else
		{
			cx = (Math.min(63, (float) Math.floor(cx * 64)) + 0.5f) / 64f;
		}
		return new float[]{cx, cy};
	}

	// ---------------------------------------------------------------------
	// Flame walls: a wall on every chunk border between a locked-looking and
	// an unlocked chunk. While a chunk animates, its walls track the reveal:
	// walls to unlocked neighbours drop as the colour reaches them, and walls
	// to still-locked neighbours rise, so the open area grows consistently.
	// ---------------------------------------------------------------------

	private void buildWalls(net.runelite.api.WorldView vw, int gridBaseRegionX, int gridBaseRegionY)
	{
		var player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		var at = player.getWorldLocation();
		int px = at.getX();
		int py = at.getY();

		for (int gx = 0; gx < GRID; ++gx)
		{
			for (int gy = 0; gy < GRID; ++gy)
			{
				int a = gx * GRID + gy;
				// West edge of cell (gx, gy), shared with (gx-1, gy).
				if (gx > 0)
				{
					buildEdge(px, py, a, (gx - 1) * GRID + gy, gridBaseRegionX, gridBaseRegionY,
						(gridBaseRegionX + gx) << 6, (gridBaseRegionY + gy) << 6, true);
				}
				// South edge of cell (gx, gy), shared with (gx, gy-1).
				if (gy > 0)
				{
					buildEdge(px, py, a, gx * GRID + (gy - 1), gridBaseRegionX, gridBaseRegionY,
						(gridBaseRegionX + gx) << 6, (gridBaseRegionY + gy) << 6, false);
				}
			}
		}

		tutorialFence(px, py);
	}

	/** The player's position in world tiles, to a fraction of a tile (edge coordinates), or null. */
	private float[] playerTiles(net.runelite.api.WorldView vw)
	{
		var player = client.getLocalPlayer();
		var local = player == null ? null : player.getLocalLocation();
		if (local == null)
		{
			return null;
		}
		return new float[]{vw.getBaseX() + local.getX() / 128f, vw.getBaseY() + local.getY() / 128f};
	}

	// ---------------------------------------------------------------------
	// The doorway. Stepping through the wall into a chunk the player can afford (ChunkBlazer
	// publishes those as "affordableChunks"), the wall bends back round them in a little cone
	// and the grey in it lifts. Not affordable: the wall stays as it is.
	// ---------------------------------------------------------------------
	private static final String AFFORDABLE_CHUNKS_KEY = "affordableChunks";
	// The bend: a cone one tile back, one straight tile in front of the player and one
	// diagonal tile each side.
	private static final float BEND_DEPTH = 1f; // one tile back
	private static final float BEND_FLAT = 0.5f; // one straight tile in front of the player
	private static final float BEND_RAMP = 1f;   // one diagonal tile each side

	/** A doorway into {@code region}, on the border at {@code border}, centred on {@code along}. */
	private static final class Door
	{
		final int region;
		final boolean northSouth; // the border runs north-south (an x line), else east-west
		final int border;         // that line's x (or y)
		final int dir;            // +1: the chunk lies east/north of it, -1: west/south
		final float along;        // where along the border it's centred (tile edges, fractional)
		final float open;         // 0..1

		Door(int region, boolean northSouth, int border, int dir, float along, float open)
		{
			this.region = region;
			this.northSouth = northSouth;
			this.border = border;
			this.dir = dir;
			this.along = along;
			this.open = open;
		}

		/**
		 * How far (tiles) the wall is bent back into the chunk at {@code a} along the border:
		 * flat across BEND_FLAT either side of the player, then sloping back out over BEND_RAMP.
		 */
		float bend(float a)
		{
			float d = Math.abs(a - along) - BEND_FLAT;
			float profile = d <= 0f ? 1f : Math.max(0f, 1f - d / BEND_RAMP);
			return open * BEND_DEPTH * profile;
		}

	}

	// The doorway shown this frame, and the one an unlock sweep started from.
	private Door liveDoor;
	private Door sweepDoor;

	private Door findDoor(float[] at, int gridBaseRegionX, int gridBaseRegionY)
	{
		int gx = ((int) Math.floor(at[0]) >> 6) - gridBaseRegionX;
		int gy = ((int) Math.floor(at[1]) >> 6) - gridBaseRegionY;
		if (gx < 1 || gy < 1 || gx >= GRID - 1 || gy >= GRID - 1)
		{
			return null;
		}
		if (affordable.isEmpty())
		{
			return null;
		}
		int here = gx * GRID + gy;
		boolean inside = lockedGrid[here] == 1;
		if (!inside && unlockProgressGrid[here] >= 0f)
		{
			return null;
		}
		int x0 = (gridBaseRegionX + gx) << 6;
		int y0 = (gridBaseRegionY + gy) << 6;
		// West, east, south, north: the step to the neighbour and the distance to that side.
		float[][] sides = {
			{-1, 0, at[0] - x0}, {1, 0, x0 + 64 - at[0]}, {0, -1, at[1] - y0}, {0, 1, y0 + 64 - at[1]}};
		Door best = null;
		float bestDist = Float.MAX_VALUE;
		for (float[] side : sides)
		{
			int sx = (int) side[0];
			int sy = (int) side[1];
			int n = (gx + sx) * GRID + gy + sy;
			int lockedCell = inside ? here : n;
			int lockedRegion = ((gridBaseRegionX + lockedCell / GRID) << 8) | (gridBaseRegionY + lockedCell % GRID);
			boolean wall = inside
				? lockedGrid[n] == 0 && unlockProgressGrid[n] < 0f
				: lockedGrid[n] == 1;
			float d = side[2];
			if (!wall || d >= bestDist || d > 4f || !affordable.contains(lockedRegion))
			{
				continue;
			}
			// Only once the player steps through into the chunk: it eases open as they cross the
			// border from the tile beside it, and stays open while they stand in the doorway.
			float open = !inside ? 1f - smoothstep(0f, 0.5f, d) : 1f - smoothstep(BEND_DEPTH, BEND_DEPTH + 0.5f, d);
			if (open <= 0.001f)
			{
				continue;
			}
			boolean northSouth = sx != 0;
			int border = northSouth ? (sx < 0 ? x0 : x0 + 64) : (sy < 0 ? y0 : y0 + 64);
			// The chunk lies on the far side of the border from outside, on this side from in.
			int dir = (northSouth ? sx : sy) * (inside ? -1 : 1);
			// Centred on the player (to the fraction of a tile), kept inside the chunk's side.
			float start = northSouth ? y0 : x0;
			float along = Math.max(start + BEND_FLAT, Math.min(start + 64f - BEND_FLAT, northSouth ? at[1] : at[0]));
			best = new Door(lockedRegion, northSouth, border, dir, along, open);
			bestDist = d;
		}
		return best;
	}

	// ---------------------------------------------------------------------
	// The chunk wall, as ChunkBlazer's original in-game overlay drew it (ChunkWallOverlay), drawn
	// by GpuPlugin (see GpuPlugin.wallHook) so the world in front of it hides it. It stands
	// wherever desiredWalls says, at that height, so it rises, surges and sinks with the unlock
	// sweep, and bends round the doorway.
	// ---------------------------------------------------------------------
	private static final int WALL_STEPS = 4; // steps a tile, so it bends and ripples smoothly
	// ChunkWallOverlay's numbers: three tiles tall, its top rippling by 12%.
	private static final int WALL_HEIGHT = 3 * 128;
	private static final double WALL_FLAME = 0.12;
	private static final Color WALL_DEFAULT_COLOR = new Color(255, 60, 60, 110);

	private volatile float[] wallMesh;
	private float[] wallData = new float[4096];
	private int wallLength;

	float[] getWallMesh()
	{
		return wallMesh;
	}

	/** This frame's wall: see-through panels in the Wall Colour, their tops rippling like flames. */
	private float[] buildWall(net.runelite.api.WorldView vw, Door door)
	{
		if (desiredWalls.isEmpty())
		{
			return null;
		}
		Color c = wallColor();
		wallData[0] = c.getRed() / 255f;
		wallData[1] = c.getGreen() / 255f;
		wallData[2] = c.getBlue() / 255f;
		wallData[3] = c.getAlpha() / 255f;
		wallLength = 4;
		double now = System.currentTimeMillis();
		// Chunk borders run along the ground, so the wall stands on the ground floor even
		// when you're upstairs (the floor you're on hides it as it would any wall below).
		int plane = 0;
		int sizeX = vw.getSizeX() * 128;
		int sizeY = vw.getSizeY() * 128;
		for (Map.Entry<Long, Float> wall : desiredWalls.entrySet())
		{
			long key = wall.getKey();
			float scale = wall.getValue();
			boolean northSouth = wallKeyNorthSouth(key);
			int wx = wallKeyX(key);
			int wy = wallKeyY(key);
			int lx = (wx - vw.getBaseX()) * 128;
			int ly = (wy - vw.getBaseY()) * 128;
			if (lx <= 0 || ly <= 0 || lx + 128 >= sizeX || ly + 128 >= sizeY)
			{
				continue;
			}
			int line = northSouth ? wx : wy;
			int along = northSouth ? wy : wx;
			// Heights at the piece's ends meet its neighbours' halfway, so it dips and rises
			// smoothly with the sweep instead of in steps.
			Float before = desiredWalls.get(northSouth ? wallKey(wx, wy - 1, true) : wallKey(wx - 1, wy, false));
			Float after = desiredWalls.get(northSouth ? wallKey(wx, wy + 1, true) : wallKey(wx + 1, wy, false));
			float[] heights = {
				before == null ? scale : (scale + before) / 2f, scale, after == null ? scale : (scale + after) / 2f};
			// A wall into a chunk the player can afford pulses (see wallfrag.glsl).
			int sideX = northSouth ? wx - 1 : wx;
			int sideY = northSouth ? wy : wy - 1;
			boolean canAfford = affordable.contains(((wx >> 6) << 8) | (wy >> 6))
				|| affordable.contains(((sideX >> 6) << 8) | (sideY >> 6));
			// It brightens towards white as it surges, and a little with its flames.
			float midX = wx + (northSouth ? 0f : 0.5f);
			float midY = wy + (northSouth ? 0.5f : 0f);
			float glow = Math.max(Math.min(1f, Math.max(0f, (scale - 1f) / SURGE)),
				(float) (0.1 * (1 + wallFlame(midX + midY, now))));
			float part = canAfford ? 1f : 0f;
			// On the doorway's border, it bends back from the player (see Door.bend).
			boolean bent = door != null && northSouth == door.northSouth && line == door.border
				&& Math.abs(along + 0.5f - door.along) < BEND_FLAT + BEND_RAMP + 1f;
			for (int k = 0; k < WALL_STEPS; k++)
			{
				float f0 = k / (float) WALL_STEPS;
				float f1 = (k + 1) / (float) WALL_STEPS;
				float b0 = bent ? door.dir * door.bend(along + f0) * 128f : 0f;
				float b1 = bent ? door.dir * door.bend(along + f1) * 128f : 0f;
				float x0 = northSouth ? lx + b0 : lx + f0 * 128f;
				float y0 = northSouth ? ly + f0 * 128f : ly + b0;
				float x1 = northSouth ? lx + b1 : lx + f1 * 128f;
				float y1 = northSouth ? ly + f1 * 128f : ly + b1;
				float g0 = groundHeight(x0, y0, plane, vw);
				float g1 = groundHeight(x1, y1, plane, vw);
				// Where along the world each point is (x + y), for its flame ripple.
				float t0 = g0 - WALL_HEIGHT * wallScale(heights, f0) * (float) (1 + WALL_FLAME * wallFlame(wx + wy + f0, now));
				float t1 = g1 - WALL_HEIGHT * wallScale(heights, f1) * (float) (1 + WALL_FLAME * wallFlame(wx + wy + f1, now));
				float u0 = along + f0;
				float u1 = along + f1;
				wallVertex(x0, g0, y0, 0, u0, part, glow);
				wallVertex(x1, g1, y1, 0, u1, part, glow);
				wallVertex(x1, t1, y1, 1, u1, part, glow);
				wallVertex(x0, g0, y0, 0, u0, part, glow);
				wallVertex(x1, t1, y1, 1, u1, part, glow);
				wallVertex(x0, t0, y0, 1, u0, part, glow);
			}
		}
		return Arrays.copyOf(wallData, wallLength);
	}

	/** -1 to 1: two waves rolling along the wall at different speeds (as ChunkWallOverlay.flame). */
	private static double wallFlame(double along, double now)
	{
		return 0.6 * Math.sin(along * 0.7 + now / 260.0) + 0.4 * Math.sin(along * 1.9 - now / 170.0);
	}

	/** ChunkBlazer's own Wall Colour setting, which the wall is drawn in. */
	private Color wallColor()
	{
		Color c = configManager.getConfiguration(CHUNKBLAZER_GROUP, "chunkWallColor", Color.class);
		return c != null ? c : WALL_DEFAULT_COLOR;
	}

	/** Wall height at fraction {@code f} along a piece, from its start, middle and end heights. */
	private static float wallScale(float[] heights, float f)
	{
		return f <= 0.5f ? heights[0] + (heights[1] - heights[0]) * f * 2f
			: heights[1] + (heights[2] - heights[1]) * (f - 0.5f) * 2f;
	}

	private void wallVertex(float x, float y, float z, float up, float along, float part, float energy)
	{
		if (wallLength + GpuPlugin.WALL_STRIDE > wallData.length)
		{
			wallData = Arrays.copyOf(wallData, wallData.length * 2);
		}
		float[] d = wallData;
		int n = wallLength;
		d[n] = x;
		d[n + 1] = y;
		d[n + 2] = z;
		d[n + 3] = up;
		d[n + 4] = along;
		d[n + 5] = part;
		d[n + 6] = energy;
		wallLength = n + GpuPlugin.WALL_STRIDE;
	}

	/** Ground height at a scene-local point (OSRS heights: more negative is higher). */
	private int groundHeight(float x, float y, int plane, net.runelite.api.WorldView vw)
	{
		return net.runelite.api.Perspective.getTileHeight(client,
			new net.runelite.api.coords.LocalPoint((int) x, (int) y, vw), plane);
	}

	// ChunkBlazer's tutorial fence round a square of tiles (the island's start room, Lumbridge's
	// spawn): "x1,y1,x2,y2", plus ",when,x,y" once it falls in a wave from (x, y).
	private static final String TUTORIAL_FENCE_KEY = "tutorialFence";
	private static final float FENCE_WAVE_MS_PER_TILE = 28f;
	private static final float FENCE_DROP_MS = 700f;

	private void tutorialFence(int px, int py)
	{
		String raw = configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, TUTORIAL_FENCE_KEY);
		if (raw == null)
		{
			return;
		}
		double[] f;
		try
		{
			String[] parts = raw.split(",");
			f = new double[parts.length];
			for (int i = 0; i < parts.length; i++)
			{
				f[i] = Double.parseDouble(parts[i].trim());
			}
		}
		catch (NumberFormatException e)
		{
			return;
		}
		if (f.length < 4)
		{
			return;
		}
		int x1 = (int) f[0];
		int y1 = (int) f[1];
		int x2 = (int) f[2] + 1;
		int y2 = (int) f[3] + 1;
		long now = System.currentTimeMillis();
		for (int x = x1; x < x2; x++)
		{
			fencePiece(f, now, px, py, x, y1, false);
			fencePiece(f, now, px, py, x, y2, false);
		}
		for (int y = y1; y < y2; y++)
		{
			fencePiece(f, now, px, py, x1, y, true);
			fencePiece(f, now, px, py, x2, y, true);
		}
	}

	/** One tile of the fence: standing, or surging and dropping as its wave passes. */
	private void fencePiece(double[] f, long now, int px, int py, int x, int y, boolean northSouth)
	{
		if (Math.abs(x - px) > WALL_RANGE || Math.abs(y - py) > WALL_RANGE)
		{
			return;
		}
		float scale = 1f;
		if (f.length >= 7)
		{
			double mx = northSouth ? x : x + 0.5;
			double my = northSouth ? y + 0.5 : y;
			// Falls with its chunk's unlock sweep, as the front passes it, like the chunk's own
			// walls; without a sweep, in its own wave from where the player stood.
			int region = ((x >> 6) << 8) | (y >> 6);
			float p = unlockProgress(region, now);
			if (p >= 0f)
			{
				scale = openingWave(revealAt(region, p, (float) (mx - ((x >> 6) << 6)) / 64f, (float) (my - ((y >> 6) << 6)) / 64f));
			}
			else
			{
				double t = now - f[4] - Math.hypot(mx - f[5], my - f[6]) * FENCE_WAVE_MS_PER_TILE;
				if (t >= FENCE_DROP_MS)
				{
					return;
				}
				scale = t < 0 ? 1f : openingWave((float) (t / FENCE_DROP_MS));
			}
		}
		if (scale > 0.001f)
		{
			desiredWalls.put(wallKey(x, y, northSouth), scale);
		}
	}

	/**
	 * One 64-tile chunk border between grid cells a and b. If {@code northSouth},
	 * the border runs north from (x0, y0); otherwise it runs east.
	 */
	private void buildEdge(int px, int py,
		int a, int b, int gridBaseRegionX, int gridBaseRegionY, int x0, int y0, boolean northSouth)
	{
		float pa = unlockProgressGrid[a];
		float pb = unlockProgressGrid[b];
		boolean animA = pa >= 0f;
		boolean animB = pb >= 0f;
		if (animA && animB)
		{
			return;
		}
		boolean lockedA = lockedGrid[a] == 1;
		boolean lockedB = lockedGrid[b] == 1;
		if (!animA && !animB && lockedA == lockedB)
		{
			return; // same state both sides: no wall
		}

		// Quick reject: whole border out of range of the player.
		int midX = northSouth ? x0 : x0 + 32;
		int midY = northSouth ? y0 + 32 : y0;
		if (Math.abs(midX - px) > WALL_RANGE + 32 || Math.abs(midY - py) > WALL_RANGE + 32)
		{
			return;
		}

		// The animating side, if any, and whether the far side is still locked
		// (then the wall rises with the reveal instead of dropping).
		int animCell = animA ? a : (animB ? b : -1);
		float p = animA ? pa : pb;
		boolean otherLocked = animA ? lockedB : lockedA;
		int animRegion = -1;
		int animX0 = 0;
		int animY0 = 0;
		if (animCell >= 0)
		{
			int cgx = animCell / GRID;
			int cgy = animCell % GRID;
			animRegion = ((gridBaseRegionX + cgx) << 8) | (gridBaseRegionY + cgy);
			animX0 = (gridBaseRegionX + cgx) << 6;
			animY0 = (gridBaseRegionY + cgy) << 6;
		}

		for (int i = 0; i < 64; i++)
		{
			int ax = northSouth ? x0 : x0 + i;
			int ay = northSouth ? y0 + i : y0;
			if (Math.abs(ax - px) > WALL_RANGE || Math.abs(ay - py) > WALL_RANGE)
			{
				continue;
			}

			float scale = 1f;
			if (animCell >= 0)
			{
				float mx = northSouth ? ax : ax + 0.5f;
				float my = northSouth ? ay + 0.5f : ay;
				float m = revealAt(animRegion, p, (mx - animX0) / 64f, (my - animY0) / 64f);
				scale = otherLocked ? rise(m) : openingWave(m);
			}
			if (scale > 0.001f)
			{
				desiredWalls.put(wallKey(ax, ay, northSouth), scale);
			}
		}
	}

	private void checkGLErrors()
	{
		int error;
		while ((error = glGetError()) != GL_NO_ERROR)
		{
			log.error("glGetError: {}", error);
		}
	}

	/**
	 * A region renders full-colour (not greyed) if it's unlocked, or is an
	 * off-map dungeon by the coordinate rule: regionY outside the overworld
	 * surface band 39..64 — EXCEPT the Prifddinas city regions, which live in
	 * instance coordinates but are a real lockable surface city. Mirrors
	 * ChunkBlazerPlugin.isFreeRegion / isRegionUnlocked so the shader and the
	 * main plugin agree. (Free chunks need no special case: since they became
	 * unlock-on-demand they appear in unlockedChunks once unlocked, and are
	 * correctly grey while still locked.)
	 */
	private boolean isAccessible(int regionId, Set<Integer> unlocked)
	{
		if (!PRIF_CITY_REGIONS.contains(regionId))
		{
			int regionY = regionId & 0xFF;
			if (regionY < 39 || regionY > 64)
			{
				return true;
			}
		}
		return unlocked.contains(regionId);
	}

	/**
	 * Parse the main plugin's {@code chunkblazer.unlockedChunks} value (comma-separated
	 * region IDs) into a {@code Set<Integer>}. Read through ConfigManager so this plugin
	 * needs no compile-time dependency on ChunkBlazer. Returns an empty set if missing or
	 * malformed.
	 *
	 * <p>ChunkBlazer stores per-account progress in the RSProfile store (so multiple
	 * accounts on one RuneLite profile stay independent), so this reads
	 * {@code getRSProfileConfiguration}, not the profile-global {@code getConfiguration}.
	 * A null RS profile (not logged in) yields an empty set, same as no unlocks.
	 */
	private Set<Integer> readUnlockedRegionIds()
	{
		Set<Integer> ids = new HashSet<>();
		if (isTutorialPreview())
		{
			addRegionIds(ids, configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, TUTORIAL_CHUNKS_KEY));
			return ids;
		}
		addRegionIds(ids, configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, UNLOCKED_CHUNKS_KEY));
		if (client.getVarpValue(VarPlayerID.TUTORIAL) < TUTORIAL_ISLAND_DONE)
		{
			addRegionIds(ids, configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, TUTORIAL_CHUNKS_KEY));
		}
		return ids;
	}

	// The tiles ChunkBlazer's tutorial keeps clear of grey (the room a new account starts in):
	// its corner tile x,y then 32 rows of 32 bits, bit x of row y for tile corner + (x, y).
	// Parsed again only when the setting changes. CLEAR_ROWS must match the shader's.
	private static final String TUTORIAL_CLEAR_KEY = "tutorialClearArea";
	private static final int CLEAR_ROWS = 32;
	private static final int[] NO_CLEAR_AREA = new int[2 + CLEAR_ROWS];
	private String clearAreaRaw;
	private int[] clearArea = NO_CLEAR_AREA;

	private int[] tutorialClearArea()
	{
		String raw = configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, TUTORIAL_CLEAR_KEY);
		if (!Objects.equals(raw, clearAreaRaw))
		{
			clearAreaRaw = raw;
			clearArea = NO_CLEAR_AREA;
			try
			{
				String[] parts = raw == null ? new String[0] : raw.split(",");
				if (parts.length == 2 + CLEAR_ROWS)
				{
					int[] parsed = new int[parts.length];
					for (int i = 0; i < parts.length; i++)
					{
						parsed[i] = Integer.parseInt(parts[i].trim());
					}
					clearArea = parsed;
				}
			}
			catch (NumberFormatException ignored)
			{
				// Unreadable: nothing kept clear.
			}
		}
		return clearArea;
	}

	private boolean isTutorialPreview()
	{
		return configManager.getRSProfileConfiguration(CHUNKBLAZER_GROUP, TUTORIAL_PREVIEW_KEY) != null;
	}

	/** Add a comma-separated list of region ids to {@code ids} (null or empty: none). */
	private static void addRegionIds(Set<Integer> ids, String chunkList)
	{
		if (chunkList == null || chunkList.isEmpty())
		{
			return;
		}
		for (String token : chunkList.split(","))
		{
			String trimmed = token.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			try
			{
				ids.add(Integer.parseInt(trimmed));
			}
			catch (NumberFormatException ignored)
			{
				// silently skip malformed entries — same behavior as
				// ChunkBlazerPlugin.getUnlockedRegionIds()
			}
		}
	}
}
