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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * Lifts dark scenes, the way a game engine's auto exposure does.
 *
 * Every few frames the picture is shrunk to a 32 x 18 grid of brightness values and read back
 * (about 2 KB). The average brightness of that grid (a log average, so one bright lamp doesn't
 * hide a dark room) sets how much to lift, and the lift eases in and out over about a second so
 * cuts don't make the picture pump. The lift is a soft curve on brightness only: shadows and
 * mid-tones come up, white stays white and colours keep their hue.
 *
 * Black bars and fades to black are left out of the average, so letterboxed films aren't
 * over-lifted and a black screen stays black. HDR pictures pass through untouched.
 */

@UnstableApi
internal class AutoBrightnessEffect : GlEffect {
    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram =
        AutoBrightnessShaderProgram(useHdr)
}

@UnstableApi
private class AutoBrightnessShaderProgram(private val useHdr: Boolean) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ useHdr,
    /* texturePoolCapacity = */ 1,
) {
    private val liftProgram = GlProgram(VERTEX, LIFT_FRAGMENT)
    private val meterProgram = GlProgram(VERTEX, METER_FRAGMENT)
    private val meterPixels = ByteBuffer.allocateDirect(METER_WIDTH * METER_HEIGHT * 4).order(ByteOrder.nativeOrder())
    private val boundFramebuffer = IntArray(1)

    private var meterTexture = -1
    private var meterFbo = -1
    private var width = 1
    private var height = 1
    private var frames = 0
    private var gain = 1f
    private var targetGain = 1f
    private var lastPresentationUs = Long.MIN_VALUE

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        val identity = GlUtil.create4x4IdentityMatrix()
        for (program in listOf(liftProgram, meterProgram)) {
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            program.setFloatsUniform("uTransformationMatrix", identity)
            program.setFloatsUniform("uTexTransformationMatrix", identity)
        }
        // Each grid cell averages four taps spread across it, a quarter cell in from each side.
        meterProgram.setFloatUniform("uCellX", 0.25f / METER_WIDTH)
        meterProgram.setFloatUniform("uCellY", 0.25f / METER_HEIGHT)
        if (!useHdr && meterTexture < 0) {
            try {
                meterTexture = GlUtil.createTexture(METER_WIDTH, METER_HEIGHT, /* useHighPrecisionColorComponents = */ false)
                meterFbo = GlUtil.createFboForTexture(meterTexture)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (!useHdr && meterFbo >= 0) {
                if (frames++ % METER_EVERY_FRAMES == 0) meter(inputTexId)
                ease(presentationTimeUs)
            }
            liftProgram.use()
            liftProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            liftProgram.setFloatUniform("uGain", gain)
            liftProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Draws the brightness grid, reads it back and works out the lift it asks for. */
    private fun meter(inputTexId: Int) {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFramebuffer, 0)
        GlUtil.focusFramebufferUsingCurrentContext(meterFbo, METER_WIDTH, METER_HEIGHT)
        meterProgram.use()
        meterProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        meterProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        meterPixels.clear()
        GLES20.glReadPixels(0, 0, METER_WIDTH, METER_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, meterPixels)
        GlUtil.checkGlError()
        // Back to this frame's output, which the base class had focused.
        GlUtil.focusFramebufferUsingCurrentContext(boundFramebuffer[0], width, height)

        var logSum = 0.0
        var counted = 0
        for (i in 0 until METER_WIDTH * METER_HEIGHT) {
            val luma = (meterPixels.get(i * 4).toInt() and 0xFF) / 255.0
            if (luma < BLACK_LEVEL) continue
            logSum += ln(luma)
            counted++
        }
        targetGain = if (counted < METER_WIDTH * METER_HEIGHT * MIN_PICTURE_SHARE) {
            1f
        } else {
            val average = exp(logSum / counted)
            // Square root: a scene half as bright as the target gets about 1.4x, not 2x.
            sqrt(TARGET_LUMA / average).toFloat().coerceIn(1f, MAX_GAIN)
        }
    }

    /** Moves the lift toward the target at a rate set by the video's own clock. */
    private fun ease(presentationTimeUs: Long) {
        val last = lastPresentationUs
        lastPresentationUs = presentationTimeUs
        if (last == Long.MIN_VALUE) {
            gain = targetGain
            return
        }
        // A seek jumps the clock; treat it as one frame rather than a long gap or going back.
        val stepSeconds = ((presentationTimeUs - last) / 1_000_000f).let { if (it in 0f..MAX_STEP_SECONDS) it else FRAME_SECONDS }
        val blend = 1f - exp(-stepSeconds / EASE_SECONDS)
        gain += (targetGain - gain) * blend
    }

    override fun flush() {
        super.flush()
        // After a seek, judge the new spot afresh instead of easing from the old one.
        frames = 0
        lastPresentationUs = Long.MIN_VALUE
    }

    override fun release() {
        super.release()
        try {
            liftProgram.delete()
            meterProgram.delete()
            if (meterFbo >= 0) GlUtil.deleteFbo(meterFbo)
            if (meterTexture >= 0) GlUtil.deleteTexture(meterTexture)
            meterFbo = -1
            meterTexture = -1
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val METER_WIDTH = 32
        const val METER_HEIGHT = 18
        const val METER_EVERY_FRAMES = 8
        /** Picture brightness (gamma-encoded, 0..1) below which a cell counts as black. */
        const val BLACK_LEVEL = 0.03
        /** Fewer non-black cells than this share and the frame is left alone. */
        const val MIN_PICTURE_SHARE = 0.1
        /** Log-average brightness a well-lit scene sits around; brighter scenes get no lift. */
        const val TARGET_LUMA = 0.32
        const val MAX_GAIN = 1.6f
        const val EASE_SECONDS = 0.8f
        const val MAX_STEP_SECONDS = 0.5f
        const val FRAME_SECONDS = 1f / 30f
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

/** One grid cell's brightness, from four taps across it, in the red channel. */
private const val METER_FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uCellX;
uniform float uCellY;
varying vec2 vTexSamplingCoord;

float luma(vec2 p) {
  return dot(texture2D(uTexSampler, p).rgb, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
  vec2 p = vTexSamplingCoord;
  float y = 0.25 * (luma(p + vec2(-uCellX, -uCellY)) + luma(p + vec2(uCellX, -uCellY))
      + luma(p + vec2(-uCellX, uCellY)) + luma(p + vec2(uCellX, uCellY)));
  gl_FragColor = vec4(y, y, y, 1.0);
}
"""

/**
 * Lifts brightness along y' = g*y / (1 + (g - 1)*y): the slope at black is g, white stays at 1,
 * and the colour is scaled by y'/y so hue and saturation stay put.
 */
private const val LIFT_FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uGain;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
  if (uGain <= 1.001) {
    gl_FragColor = color;
    return;
  }
  float y = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
  if (y <= 0.0001) {
    gl_FragColor = color;
    return;
  }
  float lifted = uGain * y / (1.0 + (uGain - 1.0) * y);
  gl_FragColor = vec4(clamp(color.rgb * (lifted / y), 0.0, 1.0), color.a);
}
"""
