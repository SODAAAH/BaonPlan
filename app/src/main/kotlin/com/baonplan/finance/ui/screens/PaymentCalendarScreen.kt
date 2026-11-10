package com.baonplan.finance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.DuesCoverageStatus
import com.baonplan.finance.domain.model.NextPaymentItem
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.CalmSegmentedToggle
import com.baonplan.finance.ui.components.DueDateStatusBadge
import com.baonplan.finance.ui.components.EmptyStateCard
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.components.UrgencyBadge
import com.baonplan.finance.ui.theme.LocalBaonColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private enum class CalendarViewMode {
  UPCOMING,
  CALENDAR
}

@Composable
fun PaymentCalendarScreen(
  state: BaonPlanUiState,
  onOpenDebtDetail: (Long) -> Unit,
  onConfirmDebtDate: (DebtEntity) -> Unit,
  onRecordPaymentForDebt: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val overview = state.paymentOverview
  var viewMode by remember { mutableStateOf(CalendarViewMode.UPCOMING) }
  var currentMonth by remember(state.today) { mutableStateOf(YearMonth.from(state.today)) }
  var selectedDate by remember(state.today) { mutableStateOf<LocalDate?>(state.today) }

  val allDatedPayments = remember(overview) {
    overview.overduePayments +
      overview.dueTodayPayments +
      overview.dueTomorrowPayments +
      overview.upcomingDatedPayments
  }
  val paymentsByDate = remember(allDatedPayments) {
    allDatedPayments.groupBy { it.dueDateIso }
  }
  val paidPayments = remember(state.transactions) {
    state.transactions
      .filter { !it.isVoided && it.type == TransactionType.DEBT_REPAYMENT }
      .take(10)
  }

  val selectedDateIso = selectedDate?.let { ManilaTime.formatIso(it) }
  val paymentsOnSelectedDate = selectedDateIso?.let { paymentsByDate[it] }.orEmpty()

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      CalmSegmentedToggle(
        options = listOf(
          CalendarViewMode.UPCOMING to "Upcoming",
          CalendarViewMode.CALENDAR to "Calendar"
        ),
        selected = viewMode,
        onSelect = { viewMode = it }
      )
    }

    if (viewMode == CalendarViewMode.CALENDAR) {
      item {
        BaonCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
              Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = ManilaTime.formatMonthYear(currentMonth),
                style = MaterialTheme.typography.titleMedium
              )
              if (currentMonth != YearMonth.from(state.today)) {
                TextButton(
                  onClick = {
                    currentMonth = YearMonth.from(state.today)
                    selectedDate = state.today
                  },
                  contentPadding = PaddingValues(0.dp)
                ) {
                  Text("Today", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
              Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next month")
            }
          }

          Spacer(modifier = Modifier.height(6.dp))
          Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
              Text(
                text = day,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
              )
            }
          }
          Spacer(modifier = Modifier.height(6.dp))

          val firstDay = currentMonth.atDay(1)
          val leadingEmpty = firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value
          val daysInMonth = currentMonth.lengthOfMonth()
          val totalCells = ((leadingEmpty + daysInMonth + 6) / 7) * 7

          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0 until (totalCells / 7)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                for (col in 0 until 7) {
                  val cellIndex = row * 7 + col
                  val dayNum = cellIndex - leadingEmpty + 1
                  if (dayNum in 1..daysInMonth) {
                    val date = currentMonth.atDay(dayNum)
                    val dateIso = ManilaTime.formatIso(date)
                    val dayPayments = paymentsByDate[dateIso].orEmpty()
                    val isToday = date == state.today
                    val isSelected = date == selectedDate
                    val isAllowanceDay = date.dayOfWeek.value == state.settings.allowanceReminderDayOfWeek

                    val dotColor: Color? = when {
                      dayPayments.any {
                        it.urgency == PaymentUrgency.OVERDUE || it.urgency == PaymentUrgency.DUE_TODAY
                      } -> semantic.danger
                      dayPayments.any {
                        it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
                      } -> semantic.warning
                      dayPayments.isNotEmpty() -> semantic.positive
                      isAllowanceDay -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                      else -> null
                    }

                    Box(
                      modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                          when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isToday -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                          }
                        )
                        .then(
                          if (isToday && !isSelected) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                          } else {
                            Modifier
                          }
                        )
                        .clickable { selectedDate = date },
                      contentAlignment = Alignment.Center
                    ) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                          text = dayNum.toString(),
                          style = MaterialTheme.typography.bodySmall,
                          fontWeight = if (isToday || isSelected || dayPayments.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                          color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                          }
                        )
                        if (dotColor != null) {
                          Spacer(modifier = Modifier.height(2.dp))
                          Box(
                            modifier = Modifier
                              .size(5.dp)
                              .clip(CircleShape)
                              .background(if (isSelected) MaterialTheme.colorScheme.onPrimary else dotColor)
                          )
                        }
                      }
                    }
                  } else {
                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            CalendarLegendDot(color = semantic.positive, label = "Confirmed")
            CalendarLegendDot(color = semantic.warning, label = "Estimated")
            CalendarLegendDot(color = semantic.danger, label = "Due/Overdue")
            CalendarLegendDot(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), label = "Baon day")
          }
        }
      }

      if (selectedDate != null) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel(ManilaTime.formatDisplay(selectedDate!!))
            if (paymentsOnSelectedDate.isEmpty()) {
              BaonCard {
                val isBaon = selectedDate!!.dayOfWeek.value == state.settings.allowanceReminderDayOfWeek
                Text(
                  text = if (isBaon) {
                    "Weekly allowance check-in day — no scheduled debt payments."
                  } else {
                    "No confirmed or estimated payments on this day."
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            } else {
              PaymentGroupCard(
                items = paymentsOnSelectedDate,
                debts = state.debts,
                today = state.today,
                onOpenDebtDetail = onOpenDebtDetail,
                onRecordPayment = onRecordPaymentForDebt,
                onConfirmDebtDate = onConfirmDebtDate
              )
            }
          }
        }
      }
    } else {
      val dues = state.duesReadiness
      if (dues.status != DuesCoverageStatus.NO_DUES) {
        item {
          BaonCard {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Top
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                CalmSectionLabel("Next 7 days due", modifier = Modifier.padding(0.dp))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = MoneyFormatter.formatPeso(dues.dueNext7DaysCents),
                  style = MaterialTheme.typography.headlineSmall,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }
              val (badgeLabel, badgeTone) = when (dues.status) {
                DuesCoverageStatus.COVERED_BY_RESERVE -> "Covered by reserve" to BadgeTone.POSITIVE
                DuesCoverageStatus.COVERED_WITH_CASH -> "Covered with cash" to BadgeTone.POSITIVE
                DuesCoverageStatus.SHORTFALL -> "${MoneyFormatter.formatPesoCompact(dues.unmetShortfallCents)} short" to BadgeTone.WARNING
                DuesCoverageStatus.NO_DUES -> "" to BadgeTone.NEUTRAL
              }
              StatusBadge(text = badgeLabel, tone = badgeTone)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = buildString {
                append("${dues.dueNext7DaysCount} payment${if (dues.dueNext7DaysCount == 1) "" else "s"}")
                append(" · Reserve ${MoneyFormatter.formatPesoCompact(dues.activeDebtReserveCents)}")
                append(" · Cash left ${MoneyFormatter.formatPesoCompact(dues.availableCashCents)}")
                if (dues.overdueCents > 0L) {
                  append(" · Overdue ${MoneyFormatter.formatPesoCompact(dues.overdueCents)}")
                }
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      val hasAnyUpcomingOrUnconfirmed = overview.overduePayments.isNotEmpty() ||
        overview.dueTodayPayments.isNotEmpty() ||
        overview.dueTomorrowPayments.isNotEmpty() ||
        overview.upcomingDatedPayments.isNotEmpty() ||
        overview.unconfirmedOrUnknownDebts.isNotEmpty()

      if (!hasAnyUpcomingOrUnconfirmed && paidPayments.isEmpty()) {
        item {
          EmptyStateCard(
            icon = Icons.Outlined.EventAvailable,
            title = "No scheduled payments",
            subtitle = "Confirmed, estimated, and unconfirmed due dates will appear here once you add debts."
          )
        }
      }

      if (overview.overduePayments.isNotEmpty()) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel("Overdue")
            PaymentGroupCard(
              items = overview.overduePayments,
              debts = state.debts,
              today = state.today,
              onOpenDebtDetail = onOpenDebtDetail,
              onRecordPayment = onRecordPaymentForDebt,
              onConfirmDebtDate = onConfirmDebtDate
            )
          }
        }
      }

      val dueSoon = overview.dueTodayPayments + overview.dueTomorrowPayments
      if (dueSoon.isNotEmpty()) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel("Due today & tomorrow")
            PaymentGroupCard(
              items = dueSoon,
              debts = state.debts,
              today = state.today,
              onOpenDebtDetail = onOpenDebtDetail,
              onRecordPayment = onRecordPaymentForDebt,
              onConfirmDebtDate = onConfirmDebtDate
            )
          }
        }
      }

      if (overview.upcomingDatedPayments.isNotEmpty()) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel("Upcoming")
            PaymentGroupCard(
              items = overview.upcomingDatedPayments,
              debts = state.debts,
              today = state.today,
              onOpenDebtDetail = onOpenDebtDetail,
              onRecordPayment = onRecordPaymentForDebt,
              onConfirmDebtDate = onConfirmDebtDate
            )
          }
        }
      }

      if (overview.unconfirmedOrUnknownDebts.isNotEmpty()) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel("Unconfirmed or unknown dates")
            PaymentGroupCard(
              items = overview.unconfirmedOrUnknownDebts,
              debts = state.debts,
              today = state.today,
              onOpenDebtDetail = onOpenDebtDetail,
              onRecordPayment = onRecordPaymentForDebt,
              onConfirmDebtDate = onConfirmDebtDate
            )
          }
        }
      }

      if (paidPayments.isNotEmpty()) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CalmSectionLabel("Paid recently")
            BaonCard(contentPadding = 0.dp) {
              paidPayments.forEachIndexed { idx, tx ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(
                      if (tx.debtId != null) {
                        Modifier.clickable { onOpenDebtDetail(tx.debtId) }
                      } else Modifier
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                      text = tx.title,
                      style = MaterialTheme.typography.titleSmall,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = "Paid ${ManilaTime.formatDisplay(tx.date)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Text(
                    text = MoneyFormatter.formatPeso(tx.amountCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = semantic.positive
                  )
                }
                if (idx < paidPayments.lastIndex) {
                  CalmDivider()
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CalendarLegendDot(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun PaymentGroupCard(
  items: List<NextPaymentItem>,
  debts: List<DebtEntity>,
  today: LocalDate,
  onOpenDebtDetail: (Long) -> Unit,
  onRecordPayment: (Long) -> Unit,
  onConfirmDebtDate: (DebtEntity) -> Unit
) {
  BaonCard(contentPadding = 0.dp) {
    items.forEachIndexed { idx, item ->
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onOpenDebtDetail(item.debtId) }
          .padding(horizontal = 18.dp, vertical = 14.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
              text = item.debtName,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val countdown = ManilaTime.formatDaysUntilDue(item.dueDateIso, today)
            Text(
              text = buildString {
                append("${ManilaTime.formatDisplay(item.dueDateIso)} · ${item.frequency.label}")
                if (countdown != null &&
                  item.urgency != PaymentUrgency.DUE_TODAY &&
                  item.urgency != PaymentUrgency.DUE_TOMORROW
                ) {
                  append(" · $countdown")
                }
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val showDateBadge = item.dateStatus != DueDateStatus.CONFIRMED
            val showUrgencyBadge = item.urgency == PaymentUrgency.OVERDUE ||
              item.urgency == PaymentUrgency.DUE_TODAY ||
              item.urgency == PaymentUrgency.DUE_TOMORROW
            if (showDateBadge || showUrgencyBadge) {
              Spacer(modifier = Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showDateBadge) {
                  DueDateStatusBadge(status = item.dateStatus)
                }
                if (showUrgencyBadge) {
                  UrgencyBadge(urgency = item.urgency)
                }
              }
            }
          }
          Text(
            text = MoneyFormatter.formatPeso(item.amountCents),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { onRecordPayment(item.debtId) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text("Pay")
          }
          if (item.dateStatus != DueDateStatus.CONFIRMED) {
            Button(
              onClick = { debts.firstOrNull { it.id == item.debtId }?.let(onConfirmDebtDate) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Text("Confirm date")
            }
          }
        }
      }
      if (idx < items.lastIndex) {
        CalmDivider()
      }
    }
  }
}
