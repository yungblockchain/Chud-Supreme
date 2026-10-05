@file:OptIn(UnstableApi::class)

package com.m3u.data.service.internal

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/*
 * Anime4K v3.2 "Original", one pass, so it can run inside the player.
 *
 * The maths (the luma weights, the polynomial, refine strength 0.5, and the
 * final blend along the gradient) is bloc97's, under the MIT licence:
 *
 * Copyright (c) 2019-2021 bloc97
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@UnstableApi
internal class Anime4KEffect : GlEffect {
    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram =
        Anime4KShaderProgram(useHdr)
}

@UnstableApi
private class Anime4KShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ useHdr,
    /* texturePoolCapacity = */ 1,
) {
    private val glProgram = GlProgram(VERTEX, FRAGMENT)
    private var texelX = 1f / 1920f
    private var texelY = 1f / 1080f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelX = 1f / inputWidth.coerceAtLeast(1)
        texelY = 1f / inputHeight.coerceAtLeast(1)
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        glProgram.setFloatUniform("uTexelX", texelX)
        glProgram.setFloatUniform("uTexelY", texelY)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uTexelX", texelX)
            glProgram.setFloatUniform("uTexelY", texelY)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

private const val VERTEX = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

/** bloc97 Anime4K-v3.2-Upscale-Original, collapsed into the one pass the player can run. */
private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uTexelX;
uniform float uTexelY;
varying vec2 vTexSamplingCoord;

float luma(vec4 rgba) {
  return dot(rgba, vec4(0.299, 0.587, 0.114, 0.0));
}

float power_function(float x) {
  float x2 = x * x;
  float x3 = x2 * x;
  float x4 = x2 * x2;
  float x5 = x2 * x3;
  return 11.68129591 * x5 - 42.46906057 * x4 + 60.28286266 * x3
      - 41.84451327 * x2 + 14.05517353 * x - 1.081521930;
}

void main() {
  vec2 d = vec2(uTexelX, uTexelY);
  vec2 p = vTexSamplingCoord;
  float tl = luma(texture2D(uTexSampler, p + vec2(-d.x, -d.y)));
  float tc = luma(texture2D(uTexSampler, p + vec2(0.0, -d.y)));
  float tr = luma(texture2D(uTexSampler, p + vec2(d.x, -d.y)));
  float ml = luma(texture2D(uTexSampler, p + vec2(-d.x, 0.0)));
  float mc = luma(texture2D(uTexSampler, p));
  float mr = luma(texture2D(uTexSampler, p + vec2(d.x, 0.0)));
  float bl = luma(texture2D(uTexSampler, p + vec2(-d.x, d.y)));
  float bc = luma(texture2D(uTexSampler, p + vec2(0.0, d.y)));
  float br = luma(texture2D(uTexSampler, p + vec2(d.x, d.y)));

  float xgrad = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
  float ygrad = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
  float sobel = clamp(sqrt(xgrad * xgrad + ygrad * ygrad), 0.0, 1.0);
  float dval = clamp(power_function(sobel) * 0.5, 0.0, 1.0);
  vec4 center = texture2D(uTexSampler, p);
  if (dval < 0.1) {
    gl_FragColor = center;
    return;
  }
  float norm = sqrt(xgrad * xgrad + ygrad * ygrad);
  if (norm <= 0.001) {
    gl_FragColor = center;
    return;
  }
  float dx = xgrad / norm;
  float dy = ygrad / norm;
  float xpos = -sign(dx);
  float ypos = -sign(dy);
  vec4 xval = texture2D(uTexSampler, p + vec2(d.x * xpos, 0.0));
  vec4 yval = texture2D(uTexSampler, p + vec2(0.0, d.y * ypos));
  float xyratio = abs(dx) / (abs(dx) + abs(dy));
  vec4 avg = xyratio * xval + (1.0 - xyratio) * yval;
  gl_FragColor = avg * dval + center * (1.0 - dval);
}
"""
