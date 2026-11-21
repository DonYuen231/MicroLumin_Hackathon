package app.recall.phone.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.recall.phone.MainActivity
import app.recall.phone.core.FollowUp
import app.recall.phone.core.followUpKey
import app.recall.phone.core.followUpTitle

/** Tells the user when a person is saved or a follow-up is due. It never sends anything. */
class Notifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Saved people", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(FOLLOW_UP_CHANNEL, "Follow-ups", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun profileSaved(personId: String, name: String, updated: Boolean) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(savedMessage(name, updated))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        // One notification per person, so a newer update replaces the older one.
        manager.notify(personId.hashCode(), notification)
    }

    fun followUpDue(personId: String, name: String, followUp: FollowUp) {
        val id = followUpKey(personId, followUp.text).hashCode()
        val title = followUpTitle(name, followUp.text)
        val draft = draftIntent(followUp)?.let {
            PendingIntent.getActivity(
                appContext,
                id,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = NotificationCompat.Builder(appContext, FOLLOW_UP_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(followUp.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(followUp.reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(draft ?: openApp())
            .setAutoCancel(true)
        draft?.let { builder.addAction(android.R.drawable.ic_menu_edit, "Open draft", it) }
        manager.notify(id, builder.build())
    }

    /** Opens a compose screen with the text filled in. The user decides whether to send it. */
    private fun draftIntent(followUp: FollowUp): Intent? {
        if (followUp.draft.isBlank()) return null
        val compose = when (followUp.channel) {
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_SUBJECT, "Following up")
                .putExtra(Intent.EXTRA_TEXT, followUp.draft)
            "message" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                .putExtra("sms_body", followUp.draft)
            else -> return null
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (compose.resolveActivity(appContext.packageManager) != null) return compose
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, followUp.draft)
        return Intent.createChooser(share, "Open draft").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

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
        const val FOLLOW_UP_CHANNEL = "follow_ups"
    }
}
