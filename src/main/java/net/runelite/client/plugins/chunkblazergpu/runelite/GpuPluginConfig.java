/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.chunkblazergpu.runelite;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import net.runelite.client.plugins.chunkblazergpu.ShadingLevel;
import net.runelite.client.plugins.chunkblazergpu.runelite.config.AntiAliasingMode;
import net.runelite.client.plugins.chunkblazergpu.runelite.config.ColorBlindMode;
import net.runelite.client.plugins.chunkblazergpu.runelite.config.UIScalingMode;

import static net.runelite.client.plugins.chunkblazergpu.runelite.GpuPlugin.MAX_DISTANCE;
import static net.runelite.client.plugins.chunkblazergpu.runelite.GpuPlugin.MAX_FOG_DEPTH;

@ConfigGroup(GpuPluginConfig.GROUP)
public interface GpuPluginConfig extends Config
{
	String GROUP = "chunkblazergpu";

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Locked-chunk greyscale (ChunkBlazer). Moved here from the main
	// plugin's ChunkBlazerConfig so this plugin stands alone.
	// ------------------------------------------------------------------

	@ConfigSection(
		name = "Locked-Chunk Greyscale",
		description = "How chunks you haven't unlocked in ChunkBlazer are shaded",
		position = 15,
		closedByDefault = false
	)
	String greyscaleSection = "greyscale";

	@ConfigItem(
		keyName = "useGpuGreyscale",
		name = "Render Locked Chunks",
		description = "Apply a greyscale wash to chunks you haven't unlocked in ChunkBlazer.",
		section = greyscaleSection,
		position = 0
	)
	default boolean useGpuGreyscale()
	{
		return true;
	}

	@ConfigItem(
		keyName = "gpuHardBorder",
		name = "Hard Border",
		description = "Use a hard cut-off at the chunk boundary instead of a soft transition",
		section = greyscaleSection,
		position = 1
	)
	default boolean gpuHardBorder()
	{
		return false;
	}

	@Range(min = 0, max = 255)
	@ConfigItem(
		keyName = "gpuGrayAmount",
		name = "Grey Amount",
		description = "How desaturated locked chunks appear (0 = full color, 255 = fully grey)",
		section = greyscaleSection,
		position = 2
	)
	@Units(Units.PERCENT)
	default int gpuGrayAmount()
	{
		return 150;
	}

	@Alpha
	@ConfigItem(
		keyName = "gpuGrayTint",
		name = "Grey Tint",
		description = "Soft-light tint colour blended into the desaturated pixels (alpha controls blend strength)",
		section = greyscaleSection,
		position = 3
	)
	default Color gpuGrayTint()
	{
		return new Color(0, 0, 0, 64);
	}

	@ConfigItem(
		keyName = "gpuShadingLevel",
		name = "Shading Style",
		description = "How locked chunks are shaded. Light = desaturated wash, Heavy = dark desaturated, "
			+ "Silhouette = near-black with only terrain relief (hills, cliffs, mountains) showing.",
		section = greyscaleSection,
		position = 4
	)
	default ShadingLevel gpuShadingLevel()
	{
		return ShadingLevel.LIGHT;
	}
}
