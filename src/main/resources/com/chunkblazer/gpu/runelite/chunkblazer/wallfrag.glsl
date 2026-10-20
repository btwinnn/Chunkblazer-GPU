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
#version 330

// ChunkBlazer's chunk wall, as its original in-game overlay drew it: a see-through panel in
// the Wall Colour, brightened towards white by its glow, solid at the ground and fading to
// clear at the top, with a stronger line along the ground (about a pixel) so the exact
// border is clear. A wall into a chunk the player can afford has a bright band running along it.
in float vUp;
in float vAlong;
flat in float vPart;
flat in float vEnergy;

uniform float time;  // seconds
uniform vec4 color;  // the Wall Colour

out vec4 FragColor;

// 0..1: the bright band, passing each spot every few seconds, to show the wall can be crossed.
float wallPulse() {
  float phase = fract(vAlong / 24.0 - time / 3.0);
  float tiles = min(phase, 1.0 - phase) * 24.0;
  return 1.0 - smoothstep(0.0, 2.5, tiles);
}

void main() {
  float pulse = vPart > 0.5 ? wallPulse() : 0.0;
  float k = clamp(vEnergy + pulse * 0.8, 0.0, 1.0) * 0.6;
  vec3 rgb = color.rgb + (vec3(1.0) - color.rgb) * k;
  float a = min(1.0, color.a * (1.0 + k));
  float line = 1.0 - step(fwidth(vUp) * 1.5, vUp);
  FragColor = vec4(rgb, line > 0.5 ? min(1.0, a * 2.0) : a * (1.0 - vUp));
}
