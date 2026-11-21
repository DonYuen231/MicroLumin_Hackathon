package app.recall.phone.hub

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
import androidx.core.app.ServiceCompat
import app.recall.phone.MainActivity
import app.recall.phone.RecallApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the glasses server alive while the phone is in a pocket. */
class HubService : Service() {
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        val container = (application as RecallApp).container
        container.server.start()
        syncJob = container.scope.launch {
            while (isActive) {
                try {
                    container.captures.syncInBackground()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed check must never stop the hub. The next check tries again.
                    Log.w(TAG, "Automatic sync failed: ${e.javaClass.simpleName}")
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        syncJob?.cancel()
        (application as RecallApp).container.server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Glasses hub", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Recall is ready")
            .setContentText("Listening for your glasses")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hub"
        private const val NOTIFICATION_ID = 1
        private const val SYNC_INTERVAL_MS = 30_000L
        private const val TAG = "RecallSync"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HubService::class.java))
        }
    }
}
