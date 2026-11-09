package com.baonplan.finance.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.baonplan.finance.domain.util.ManilaTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReminderSchedulingTest {

  private lateinit var context: Context

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
  }

  @Test
  fun computeInitialDelayMinutes_schedulesNextOccurrenceInManilaTimezone() {
    val morning = ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, ManilaTime.ZONE)
    assertEquals(60L, ReminderScheduler.computeInitialDelayMinutes(8, 0, morning))

    val evening = ZonedDateTime.of(2026, 11, 9, 20, 0, 0, 0, ManilaTime.ZONE)
    assertEquals(12L * 60L, ReminderScheduler.computeInitialDelayMinutes(8, 0, evening))
  }

  @Test
  fun schedulePeriodicReminders_enqueuesAndCancelsUniqueWork() {
    val wm = WorkManager.getInstance(context)
    ReminderScheduler.schedulePeriodicReminders(context, enabled = true)
    val activeInfos = wm.getWorkInfosForUniqueWork(ReminderScheduler.PERIODIC_WORK_NAME).get()
    assertTrue(activeInfos.isNotEmpty())
    assertEquals(WorkInfo.State.ENQUEUED, activeInfos.first().state)

    ReminderScheduler.schedulePeriodicReminders(context, enabled = false)
    val cancelledInfos = wm.getWorkInfosForUniqueWork(ReminderScheduler.PERIODIC_WORK_NAME).get()
    assertTrue(cancelledInfos.all { it.state == WorkInfo.State.CANCELLED })
  }

  @Test
  fun reminderWorker_executesCleanly() = runTest {
    val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
    val result = worker.doWork()
    assertEquals(ListenableWorker.Result.success(), result)
  }
}
