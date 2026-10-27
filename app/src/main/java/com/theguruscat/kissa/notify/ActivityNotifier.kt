package com.theguruscat.kissa.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.theguruscat.kissa.MainActivity
import com.theguruscat.kissa.R
import com.theguruscat.kissa.data.AccountRepository
import com.theguruscat.kissa.data.Notification
import com.theguruscat.kissa.data.StoriesRepository
import java.util.concurrent.TimeUnit

/**
 * Tells writers, outside the app, when someone likes or comments on their stories. There's no push server:
 * WorkManager checks the relay about every [CHECK_HOURS] hours while signed in, and shows unread notifications
 * it hasn't shown before. Tapping one opens the app's Notifications screen.
 */
class ActivityNotifier(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val account = AccountRepository(applicationContext)
        if (!account.isSignedIn) return Result.success()
        val (list, _) = runCatching { StoriesRepository(account).notifications() }.getOrElse { return Result.retry() }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(LAST_SHOWN, 0L)
        val fresh = list.filter { !it.read && it.id > lastShown }
        if (fresh.isNotEmpty()) {
            show(applicationContext, fresh)
            prefs.edit().putLong(LAST_SHOWN, fresh.maxOf { it.id }).apply()
        }
        return Result.success()
    }

    companion object {
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
        private const val WORK = "activity-notifications"
        private const val CHANNEL = "activity"
        private const val PREFS = "notifier"
        private const val LAST_SHOWN = "last_shown_id"
        private const val CHECK_HOURS = 1L

        /** Starts the periodic check (once signed in); calling it again keeps the existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ActivityNotifier>(CHECK_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stops checking (signed out or account deleted) and forgets what was shown. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            NotificationManagerCompat.from(context).cancelAll()
        }

        /** Everything up to [id] has been seen in the app, so the background check won't show it again. */
        fun markSeen(context: Context, id: Long) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (id > prefs.getLong(LAST_SHOWN, 0L)) prefs.edit().putLong(LAST_SHOWN, id).apply()
            NotificationManagerCompat.from(context).cancelAll()
        }

        private fun show(context: Context, fresh: List<Notification>) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Likes and comments", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When someone likes or comments on your stories"
                },
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val newest = fresh.first()
            val text = if (fresh.size == 1) describe(newest) else "${describe(newest)}, and ${fresh.size - 1} more"
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (fresh.size == 1) newest.storyTitle else "${fresh.size} new on your stories")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(1, notification) }
        }

        fun describe(n: Notification): String =
            if (n.isComment) "u/${n.actor} commented: ${n.comment.orEmpty()}" else "u/${n.actor} liked \"${n.storyTitle}\""
    }
}
