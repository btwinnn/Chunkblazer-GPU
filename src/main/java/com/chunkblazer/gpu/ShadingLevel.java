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

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Visual style the ChunkBlazer GPU plugin applies to locked chunks.
 *
 * <p>The enum {@code ordinal()} is passed verbatim into the shader uniform
 * {@code chunkblazer_shadingLevel} (see chunkblazer/frag.glsl), so the
 * declaration order here is load-bearing: 0 = LIGHT, 1 = HEAVY, 2 = SILHOUETTE.
 * If you reorder these, update the branches in chunkblazer/frag.glsl to match.
 */
@Getter
@AllArgsConstructor
public enum ShadingLevel
{
	/** Partial desaturation + soft-light tint — the original subtle wash. */
	LIGHT("Light wash"),
	/** Full desaturation, darkened and tinted — clearly "off limits". */
	HEAVY("Heavy shade"),
	/** Near-black; only baked terrain relief (hills, cliffs, mountains) shows. */
	SILHOUETTE("Silhouette");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
