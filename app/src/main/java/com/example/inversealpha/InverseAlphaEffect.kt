package com.example.inversealpha

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** 逐幀反向 Alpha 混合：V = (C - α·I) / (1 - α·A)，圖片外區域原樣保留。 */
class InverseAlphaEffect(
    val overlay: Bitmap,
    val x: Int, val y: Int, val w: Int, val h: Int,
    val alpha: Float
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        InverseAlphaProgram(this, useHdr)
}

private class InverseAlphaProgram(
    private val fx: InverseAlphaEffect,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val vertex = """
        attribute vec4 aFramePosition;
        varying vec2 vTexSamplingCoord;
        void main() {
            gl_Position = aFramePosition;
            vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
        }
    """.trimIndent()

    // Bitmap 為預乘 Alpha：o.rgb = A·I，所以 α·I·A_img = uAlpha * o.rgb
    private val fragment = """
        precision highp float;
        uniform sampler2D uTexSampler;
        uniform sampler2D uOverlay;
        uniform vec2 uVideoSize;
        uniform vec4 uRect;
        uniform float uAlpha;
        varying vec2 vTexSamplingCoord;
        void main() {
            vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
            vec2 p = vec2(vTexSamplingCoord.x * uVideoSize.x,
                          (1.0 - vTexSamplingCoord.y) * uVideoSize.y);
            vec2 uv = (p - uRect.xy) / uRect.zw;
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                gl_FragColor = c; return;
            }
            vec4 o = texture2D(uOverlay, uv);
            float a = o.a * uAlpha;
            if (1.0 - a < 0.002) { gl_FragColor = c; return; }
            vec3 v = (c.rgb - uAlpha * o.rgb) / (1.0 - a);
            gl_FragColor = vec4(clamp(v, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val program: GlProgram
    private var overlayTex = 0
    private var vw = 1f
    private var vh = 1f

    init {
        try {
            program = GlProgram(vertex, fragment)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        vw = inputWidth.toFloat(); vh = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (overlayTex == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                overlayTex = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, fx.overlay, 0)
            }
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uOverlay", overlayTex, 1)
            program.setFloatsUniform("uVideoSize", floatArrayOf(vw, vh))
            program.setFloatsUniform("uRect",
                floatArrayOf(fx.x.toFloat(), fx.y.toFloat(), fx.w.toFloat(), fx.h.toFloat()))
            program.setFloatUniform("uAlpha", fx.alpha)
            program.setBufferAttribute("aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(), 4)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (_: GlUtil.GlException) {}
        if (overlayTex != 0) GLES20.glDeleteTextures(1, intArrayOf(overlayTex), 0)
    }
}
