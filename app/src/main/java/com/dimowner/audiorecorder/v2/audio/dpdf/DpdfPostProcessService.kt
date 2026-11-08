/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Foreground service: DPDFNet offline enhance after recording stops, then chains to DecodeService.
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.DecodeService
import com.dimowner.audiorecorder.v2.app.HomeActivity
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.RecordsDataSource
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class DpdfPostProcessService : Service() {

    companion object {
        private const val CHANNEL_NAME = "DPDF"
        private const val CHANNEL_ID = "com.dimowner.audiorecorder.DpdfPost.Notification"
        private const val NOTIF_ID = 108
        const val ACTION_START = "ACTION_START_DPDF_POST"
        const val EXTRA_RECORD_ID = "key_dpdf_post_record_id"
        const val EXTRA_PATH = "key_dpdf_post_path"
        const val EXTRA_DURATION = "key_dpdf_post_duration"

        fun start(
            context: Context,
            recordId: Long,
            path: String,
            durationMills: Long,
        ) {
            val intent = Intent(context, DpdfPostProcessService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_DURATION, durationMills)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start DpdfPostProcessService")
                // Fall through to waveform decode so the record is still usable.
                DecodeService.startNotificationV2(context, recordId, path, durationMills)
            }
        }
    }

    @Inject
    lateinit var prefs: PrefsV2

    @Inject
    lateinit var dpdfController: DpdfNoiseReductionController

    @Inject
    lateinit var recordsDataSource: RecordsDataSource

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val job = SupervisorJob()
    private val scope by lazy { CoroutineScope(ioDispatcher + job) }
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        val path = intent.getStringExtra(EXTRA_PATH)
        val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
        if (path.isNullOrBlank() || recordId < 0) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        scope.launch {
            try {
                if (prefs.dpdfPostProcessEnabled) {
                    val enhancer = dpdfController.obtainConfiguredEnhancer()
                    if (enhancer != null) {
                        val ok = DpdfFileEnhancer.enhanceInPlace(File(path), enhancer)
                        if (ok) {
                            try {
                                val record = recordsDataSource.getRecord(recordId)
                                if (record != null) {
                                    val size = File(path).length()
                                    recordsDataSource.updateRecord(record.copy(size = size))
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "DPDF post: could not update record size")
                            }
                        } else {
                            Timber.w("DPDF post-process returned false for %s", path)
                        }
                    } else {
                        Timber.e("DPDF post-process: model unavailable")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "DPDF post-process crashed")
            } finally {
                DecodeService.startNotificationV2(
                    applicationContext,
                    recordId,
                    path,
                    duration,
                )
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    @SuppressLint("WrongConstant")
    private fun startForegroundNotification() {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val chan = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                )
                chan.lightColor = Color.BLUE
                chan.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                chan.setSound(null, null)
                chan.enableLights(false)
                chan.enableVibration(false)
                nm.createNotificationChannel(chan)
            }
        }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIF_ID, buildNotification())
            } else {
                startForeground(NOTIF_ID, buildNotification(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            isForegroundStarted = true
        } catch (e: Exception) {
            isForegroundStarted = false
            Timber.e(e, "DpdfPostProcessService: startForeground not allowed")
        }
    }

    private fun buildNotification(): Notification {
        val flags = AppConstants.PENDING_INTENT_FLAGS
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.dpdf_post_processing))
            .setSmallIcon(R.drawable.ic_loop)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSound(null)
            .setDefaults(0)
            .build()
    }

    private fun stopSelfSafely() {
        if (isForegroundStarted) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundStarted = false
        }
        stopSelf()
    }
}
