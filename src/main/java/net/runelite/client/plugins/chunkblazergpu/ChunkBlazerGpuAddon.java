package net.runelite.client.plugins.chunkblazergpu;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.chunkblazergpu.runelite.GpuPluginConfig;

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

	private static final String CHUNKBLAZER_GROUP = "chunkblazer";
	private static final String UNLOCKED_CHUNKS_KEY = "unlockedChunks";

	// The GPU plugin renders the extended 184x184 scene, which can span more
	// regions than the old fixed 16-slot list could hold — that overflow is why
	// far locked chunks rendered in colour until you walked closer. Instead of a
	// capped list we upload a per-region locked/unlocked grid covering the whole
	// rendered scene. GRID must match CHUNKBLAZER_GRID in vert.glsl.
	private static final int GRID = 7;
	private final int[] lockedGrid = new int[GRID * GRID];

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

	public void reset()
	{
		isValid = false;
		glProgram = 0;
	}

	public void beforeRender(int glProgram)
	{
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
		}
		else
		{
			glUniform1i(uniUseGray, 1);
			glUniform1i(uniBaseX, vw.getBaseX() * 128);
			glUniform1i(uniBaseY, vw.getBaseY() * 128);

			// Build a per-region locked/unlocked grid covering the whole
			// rendered (extended) scene, straight from the unlocked-config set.
			// Unlike the old approach this does NOT depend on getMapRegions(),
			// so far chunks at the edge of draw distance grey correctly instead
			// of only once you walk into them. The grid origin is one region
			// before the scene base; the >>6 and -1 here must match the
			// gridBase math in vert.glsl, and GRID must match CHUNKBLAZER_GRID.
			int gridBaseRegionX = (vw.getBaseX() >> 6) - 1;
			int gridBaseRegionY = (vw.getBaseY() >> 6) - 1;
			for (int gx = 0; gx < GRID; ++gx)
			{
				for (int gy = 0; gy < GRID; ++gy)
				{
					int regionId = ((gridBaseRegionX + gx) << 8) | (gridBaseRegionY + gy);
					lockedGrid[gx * GRID + gy] = isAccessible(regionId, unlocked) ? 0 : 1;
				}
			}

			glUniform1iv(uniLockedGrid, lockedGrid);
		}

		// Restore the previous state
		if (glProgram != currentProgram)
		{
			glUseProgram(currentProgram);
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
	 * Parse the main plugin's {@code chunkblazer.unlockedChunks} config value
	 * (comma-separated region IDs) into a {@code Set<Integer>}. Read through
	 * ConfigManager so this plugin needs no compile-time dependency on
	 * ChunkBlazer. Returns an empty set if missing or malformed.
	 */
	private Set<Integer> readUnlockedRegionIds()
	{
		String chunkList = configManager.getConfiguration(CHUNKBLAZER_GROUP, UNLOCKED_CHUNKS_KEY);
		if (chunkList == null || chunkList.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>();
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
		return ids;
	}
}
