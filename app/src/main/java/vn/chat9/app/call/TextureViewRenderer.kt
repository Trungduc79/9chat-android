package vn.chat9.app.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebRTC video renderer backed by a [TextureView] instead of the stock
 * [org.webrtc.SurfaceViewRenderer] (SurfaceView). TextureView participates
 * in the normal Android view compositing pipeline, so it can be clipped,
 * rounded, alpha-blended, and transformed like any other view — which the
 * SurfaceView renderer cannot (foot-gun #14).
 *
 * Used for the local PIP during a video call — the PIP has rounded corners
 * and would otherwise render as a hard-edged rectangle on top of the chrome.
 *
 * Remote full-screen video stays on [org.webrtc.SurfaceViewRenderer]; it
 * has no clipping requirement and benefits from the faster dedicated surface.
 */
class TextureViewRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), VideoSink, TextureView.SurfaceTextureListener {

    private val eglRenderer = EglRenderer("TextureViewRenderer")
    private var sharedContext: EglBase.Context? = null
    private var rendererSurface: Surface? = null
    private var eglInitialized = false
    private var mirror: Boolean = false

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(sharedContext: EglBase.Context) {
        this.sharedContext = sharedContext
    }

    fun release() {
        if (eglInitialized) {
            try { eglRenderer.release() } catch (_: Exception) {}
            eglInitialized = false
        }
        rendererSurface?.release()
        rendererSurface = null
    }

    fun setScalingType(scalingType: RendererCommon.ScalingType) {
        // TextureView's default (fill bounds) matches SCALE_ASPECT_FILL
        // visually for our PIP aspect ratio.
    }

    fun setMirror(mirror: Boolean) {
        this.mirror = mirror
        scaleX = if (mirror) -1f else 1f
    }

    override fun onFrame(frame: VideoFrame) {
        if (eglInitialized) eglRenderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val ctx = sharedContext ?: return
        rendererSurface = Surface(surfaceTexture)
        if (!eglInitialized) {
            try {
                eglRenderer.init(ctx, EglBase.CONFIG_PLAIN, GlRectDrawer())
                eglInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "EglRenderer init failed", e)
                return
            }
        }
        try {
            eglRenderer.createEglSurface(rendererSurface!!)
        } catch (e: Exception) {
            Log.e(TAG, "createEglSurface failed", e)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (eglInitialized) {
            val countdown = CountDownLatch(1)
            eglRenderer.releaseEglSurface { countdown.countDown() }
            try { countdown.await(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        rendererSurface?.release()
        rendererSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    companion object {
        private const val TAG = "TextureViewRenderer"
    }
}
