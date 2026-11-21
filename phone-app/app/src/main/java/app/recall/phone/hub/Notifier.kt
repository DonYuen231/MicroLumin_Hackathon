package app.recall.phone.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.recall.phone.MainActivity

/** Tells the user when automatic sync saves or updates a person. */
class Notifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Saved people", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun profileSaved(personId: String, name: String, updated: Boolean) {
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(savedMessage(name, updated))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per person, so a newer update replaces the older one.
        manager.notify(personId.hashCode(), notification)
    }

    private fun savedMessage(name: String, updated: Boolean): String {
        val known = name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)
        return when {
            updated && known -> "Updated $name"
            updated -> "Updated a saved person"
            known -> "Saved $name"
            else -> "Saved a new person"
        }
    }

    private companion object {
        const val CHANNEL_ID = "people"
    }
}
