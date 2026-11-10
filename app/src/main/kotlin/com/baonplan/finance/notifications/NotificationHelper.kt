package com.baonplan.finance.notifications

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
import com.baonplan.finance.MainActivity
import com.baonplan.finance.R

object NotificationHelper {
  const val CHANNEL_PAYMENTS = "baonplan_payments"
  const val CHANNEL_ALLOWANCE = "baonplan_allowance"

  const val NOTIFICATION_ID_OVERDUE = 1001
  const val NOTIFICATION_ID_DUE_TODAY = 1002
  const val NOTIFICATION_ID_DUE_TOMORROW = 1003
  const val NOTIFICATION_ID_UPCOMING = 1004
  const val NOTIFICATION_ID_ALLOWANCE = 1005

  fun createChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val paymentsChannel = NotificationChannel(
      CHANNEL_PAYMENTS,
      context.getString(R.string.notification_channel_payments_name),
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      description = context.getString(R.string.notification_channel_payments_desc)
    }

    val allowanceChannel = NotificationChannel(
      CHANNEL_ALLOWANCE,
      context.getString(R.string.notification_channel_allowance_name),
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = context.getString(R.string.notification_channel_allowance_desc)
    }

    manager.createNotificationChannel(paymentsChannel)
    manager.createNotificationChannel(allowanceChannel)
  }

  fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun areNotificationsEnabledInOs(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  fun showNotification(
    context: Context,
    channelId: String,
    notificationId: Int,
    title: String,
    body: String,
    highPriority: Boolean = false
  ): Boolean {
    if (!hasNotificationPermission(context) || !areNotificationsEnabledInOs(context)) {
      return false
    }
    createChannels(context)

    val launchIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      context,
      notificationId,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.drawable.ic_notification_baon)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setPriority(
        if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
      )
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .build()

    return try {
      NotificationManagerCompat.from(context).notify(notificationId, notification)
      true
    } catch (_: SecurityException) {
      false
    }
  }
}
