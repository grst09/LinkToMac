package com.linktomac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.linktomac.data.H264Utils
import com.linktomac.net.MirrorConfigPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the screen via MediaProjection, encodes it to H.264 with MediaCodec (surface-input
 * mode — frames go straight from the VirtualDisplay to the hardware encoder, no manual YUV
 * conversion), and streams the raw encoded output to [SyncForegroundService]'s connection as
 * binary WebSocket frames. See docs/PROTOCOL.md's Phase 4 section for the wire format.
 *
 * Separate from SyncForegroundService because it needs its own foreground service type
 * (`mediaProjection`, required since Android 10 and strictly enforced since Android 14) and a
 * very different lifecycle — this should only run while mirroring is actually active.
 */
class ScreenMirrorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encodeJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Must start the foreground service before touching MediaProjection — Android 14
        // enforces this ordering for FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) startCapture(resultCode, resultData) else stopCapture("error")
            }
            ACTION_STOP -> stopCapture("requested")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture("requested")
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (running.getAndSet(true)) return

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture("requested")
            }
        }, null)
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val (width, height) = H264Utils.scaledDimensions(metrics.widthPixels, metrics.heightPixels)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        virtualDisplay = projection.createVirtualDisplay(
            "LinkToMacMirror",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        encodeJob = scope.launch { pumpEncoderOutput(codec, width, height) }
    }

    private suspend fun pumpEncoderOutput(codec: MediaCodec, width: Int, height: Int) {
        val bufferInfo = MediaCodec.BufferInfo()
        var configSent = false

        while (running.get()) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                break // codec was released out from under us (stopCapture racing this loop)
            }
            if (outputIndex < 0) continue

            val outputBuffer = codec.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                val data = ByteArray(bufferInfo.size)
                outputBuffer.get(data)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    if (!configSent) {
                        sendConfigIfComplete(data, width, height)?.let { configSent = true }
                    }
                } else {
                    SyncForegroundService.activeConnection()?.sendMirrorFrame(data)
                }
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun sendConfigIfComplete(codecConfig: ByteArray, width: Int, height: Int): Unit? {
        val nals = H264Utils.splitAnnexBNalUnits(codecConfig)
        val sps = nals.firstOrNull { H264Utils.nalType(it) == 7 } ?: return null
        val pps = nals.firstOrNull { H264Utils.nalType(it) == 8 } ?: return null
        SyncForegroundService.activeConnection()?.sendMirrorConfig(
            MirrorConfigPayload(
                width = width,
                height = height,
                fps = TARGET_FPS,
                spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP),
                ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP)
            )
        )
        return Unit
    }

    private fun stopCapture(reason: String) {
        if (!running.getAndSet(false)) return
        encodeJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            encoder?.stop()
        } catch (e: IllegalStateException) {
            // already stopped/released
        }
        encoder?.release()
        encoder = null
        mediaProjection?.stop()
        mediaProjection = null
        SyncForegroundService.activeConnection()?.sendMirrorStopped(reason)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LinkToMac")
            .setContentText("Mirroring your screen to your Mac")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LinkToMac screen mirroring", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "linktomac_mirror"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_START = "com.linktomac.action.MIRROR_START"
        private const val ACTION_STOP = "com.linktomac.action.MIRROR_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val BIT_RATE = 16_000_000
        private const val TARGET_FPS = 30
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        /** Uses `stopService`, not `startForegroundService` — this service's manifest-declared
         *  `foregroundServiceType="mediaProjection"` means any `startForeground()` call requires
         *  a currently-active capture grant. If the process already died (memory pressure, OEM
         *  background kill), starting it fresh just to tell it to stop has no such grant and
         *  crash-loops instead. `stopService` is a no-op if nothing is running, and if the
         *  service *is* running, `onDestroy()` already calls `stopCapture("requested")`. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorService::class.java))
        }
    }
}
