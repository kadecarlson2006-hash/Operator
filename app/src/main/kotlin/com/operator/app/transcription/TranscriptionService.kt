package com.operator.app.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.operator.app.MainActivity
import com.operator.app.OperatorApplication
import com.operator.app.R

/**
 * Keeps listening alive when the app is not in front (Milestone 11).
 *
 * Android only permits continuous capture from a foreground service, and from API 34 that service
 * must declare the `microphone` type. It is started from a user action while the app is visible,
 * which is the path Android 14+ still allows; it is never started from the background.
 *
 * The ongoing notification is not just a platform requirement. An assistant that listens without
 * a visible sign of it is exactly what the brief's privacy rules are against, so the notification
 * says plainly that Operator is listening and carries a STOP action that works without opening
 * the app.
 *
 * The service owns no audio itself: [ListenController] in the container does. This exists purely
 * to hold the process in the foreground for the lifetime of a listening session, so that stopping
 * is a single, obvious operation from either the UI or the notification.
 */
class TranscriptionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }
        }

        val container = (application as? OperatorApplication)?.container
        if (container == null) {
            Log.e(TAG, "No container; refusing to run without somewhere to send audio")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        container.listen.start(container.loopback.state.value.selection)
        return START_STICKY
    }

    override fun onDestroy() {
        // Whatever ends the service ends the microphone: the notification going away and capture
        // continuing would be the dishonest combination.
        (application as? OperatorApplication)?.container?.listen?.stop()
        super.onDestroy()
    }

    private fun stopListening() {
        (application as? OperatorApplication)?.container?.listen?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown whenever Operator has the microphone open."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle("Operator is listening")
            // Deliberately no transcript text: the notification is visible on a lock screen.
            .setContentText("Speech is transcribed and kept in memory only.")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "STOP", stop)
            .build()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL_ID = "operator-listening"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.operator.app.action.STOP_LISTENING"

        /** Call only from the foreground, in response to a user action. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TranscriptionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
