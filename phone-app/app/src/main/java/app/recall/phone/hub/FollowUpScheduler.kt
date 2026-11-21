package app.recall.phone.hub

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.recall.phone.RecallApp
import app.recall.phone.core.Profile
import app.recall.phone.core.followUpDelayMs
import app.recall.phone.core.followUpKey
import app.recall.phone.core.sameFollowUp
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val KEY_PERSON = "person_id"
private const val KEY_TEXT = "text"

class FollowUpScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)

    // A finished job no longer blocks a new one, so this remembers what was already scheduled.
    private val scheduled = context.getSharedPreferences("follow_ups", Context.MODE_PRIVATE)

    fun schedule(personId: String, profile: Profile, metAt: Instant, demoSpeed: Boolean) {
        val now = Instant.now()
        for (followUp in profile.followUps) {
            val hours = followUp.dueInHours ?: continue
            val key = followUpKey(personId, followUp.text)
            if (scheduled.getBoolean(key, false)) continue
            val request = OneTimeWorkRequestBuilder<FollowUpWorker>()
                .setInitialDelay(
                    followUpDelayMs(hours, metAt, now, demoSpeed),
                    TimeUnit.MILLISECONDS,
                )
                .setInputData(workDataOf(KEY_PERSON to personId, KEY_TEXT to followUp.text))
                .build()
            work.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
            scheduled.edit().putBoolean(key, true).apply()
        }
    }
}

/** Reads the profile when it fires, so a deleted person or finished follow-up stays silent. */
class FollowUpWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val personId = inputData.getString(KEY_PERSON) ?: return Result.success()
        val text = inputData.getString(KEY_TEXT) ?: return Result.success()
        val container = (applicationContext as RecallApp).container
        val person = container.people.person(personId) ?: return Result.success()
        val followUp = person.profile.followUps.firstOrNull { sameFollowUp(it.text, text) }
            ?: return Result.success()
        container.notifier.followUpDue(personId, person.profile.name, followUp)
        return Result.success()
    }
}
