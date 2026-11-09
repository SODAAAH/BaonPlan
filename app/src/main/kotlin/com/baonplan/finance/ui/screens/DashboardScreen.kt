package com.baonplan.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.MetricRow
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors

@Composable
fun DashboardScreen(
  state: BaonPlanUiState,
  onQuickAddExpense: () -> Unit,
  onQuickRecordRepayment: () -> Unit,
  onOpenDebts: () -> Unit,
  onOpenDebtDetail: (Long) -> Unit,
  onOpenCalendar: () -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val weekly = state.weeklySummary
  val overview = state.paymentOverview
  val progress = state.repaymentProgress

  val nextPayment = overview.overduePayments.firstOrNull()
    ?: overview.dueTodayPayments.firstOrNull()
    ?: overview.dueTomorrowPayments.firstOrNull()
    ?: overview.nextConfirmedPayment
    ?: overview.nextEstimatedPayment
    ?: overview.unconfirmedOrUnknownDebts.firstOrNull()

  val totalPendingCount = overview.overduePayments.size +
    overview.dueTodayPayments.size +
    overview.dueTomorrowPayments.size +
    overview.upcomingDatedPayments.size +
    overview.unconfirmedOrUnknownDebts.size

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Minimal Greeting
    item {
      Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
          text = ManilaTime.greetingForNow(),
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Here's your money overview.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Section 1: Remaining Debt
    item {
      BaonCard(onClick = onOpenDebts) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          CalmSectionLabel("Remaining debt", modifier = Modifier.padding(0.dp))
          TextButton(
            onClick = onOpenDebts,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
          ) {
            Text("View debts", style = MaterialTheme.typography.labelLarge)
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = MoneyFormatter.formatPeso(state.totalActiveDebtCents),
          style = MaterialTheme.typography.displaySmall,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        val debtNote = when {
          progress.activeLoansCount == 0 && progress.completedLoansCount == 0 ->
            "No debts recorded yet"
          progress.activeLoansCount == 0 ->
            "All tracked debts paid off"
          else -> {
            val pct = (progress.progressFraction * 100).toInt()
            "${progress.activeLoansCount} active · $pct% paid off"
          }
        }
        Text(
          text = debtNote,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Section 2: Next Payment (single most relevant obligation)
    item {
      BaonCard(
        onClick = nextPayment?.let { item -> { onOpenDebtDetail(item.debtId) } }
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          CalmSectionLabel("Next payment", modifier = Modifier.padding(0.dp))
          if (nextPayment != null) {
            TextButton(
              onClick = { onOpenDebtDetail(nextPayment.debtId) },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
              Text("View payment", style = MaterialTheme.typography.labelLarge)
            }
          }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (nextPayment != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
              Text(
                text = nextPayment.debtName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = if (nextPayment.dueDateIso != null) {
                  "Due ${ManilaTime.formatFullDisplay(nextPayment.dueDateIso)}"
                } else {
                  "Due date not confirmed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Text(
              text = MoneyFormatter.formatPeso(nextPayment.amountCents),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          val statusBadge: Pair<String, BadgeTone>? = when {
            nextPayment.urgency == PaymentUrgency.OVERDUE -> "Overdue" to BadgeTone.DANGER
            nextPayment.urgency == PaymentUrgency.DUE_TODAY -> "Due today" to BadgeTone.DANGER
            nextPayment.urgency == PaymentUrgency.DUE_TOMORROW -> "Due tomorrow" to BadgeTone.WARNING
            nextPayment.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION -> "Estimated date" to BadgeTone.WARNING
            nextPayment.dateStatus == DueDateStatus.UNCONFIRMED -> "Unconfirmed date" to BadgeTone.WARNING
            nextPayment.dateStatus == DueDateStatus.UNKNOWN -> "Date unknown" to BadgeTone.NEUTRAL
            else -> null
          }

          if (statusBadge != null || totalPendingCount > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (statusBadge != null) {
                StatusBadge(text = statusBadge.first, tone = statusBadge.second)
              } else {
                Spacer(modifier = Modifier.width(1.dp))
              }
              if (totalPendingCount > 1) {
                TextButton(
                  onClick = onOpenCalendar,
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                  Text(
                    text = "+${totalPendingCount - 1} more upcoming",
                    style = MaterialTheme.typography.labelMedium
                  )
                }
              }
            }
          }
        } else {
          Text(
            text = "No upcoming payments",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "Payments you track will show up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    // Section 3: This Week's Money
    item {
      BaonCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "This week's money",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "${ManilaTime.formatShort(ManilaTime.formatIso(weekly.weekStartDate))}–${ManilaTime.formatShort(ManilaTime.formatIso(weekly.weekEndDate))}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          MetricRow(
            label = "Allowance",
            subtitle = if (weekly.recordedAllowanceCents == 0L) {
              "Plan: ${MoneyFormatter.formatPesoCompact(weekly.conservativePlannedAllowanceCents)}–${MoneyFormatter.formatPesoCompact(weekly.maxPlannedAllowanceCents)}"
            } else null,
            value = MoneyFormatter.formatPeso(weekly.recordedAllowanceCents),
            boldValue = false
          )
          MetricRow(
            label = "Food",
            subtitle = "Budget ${MoneyFormatter.formatPesoCompact(weekly.plannedFoodBudgetCents)}",
            value = MoneyFormatter.formatPeso(weekly.actualFoodExpensesCents),
            boldValue = false
          )
          MetricRow(
            label = "Debt reserved",
            value = MoneyFormatter.formatPeso(weekly.activeDebtReserveCents),
            boldValue = false
          )
          val otherOutflows = weekly.actualOtherExpensesCents +
            weekly.emergencySavingsThisWeekCents +
            weekly.directRepaymentsFromCashCents
          if (otherOutflows > 0L) {
            MetricRow(
              label = "Other & direct payments",
              value = MoneyFormatter.formatPeso(otherOutflows),
              boldValue = false
            )
          }
          CalmDivider()
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Money left",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = MoneyFormatter.formatPeso(weekly.availableSpendingMoneyCents),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.SemiBold,
              color = if (weekly.availableSpendingMoneyCents < 0L) {
                semantic.danger
              } else {
                semantic.positive
              }
            )
          }
        }
      }
    }

    // Section 4: Quick Actions (Strictly 2 primary actions)
    item {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        FilledTonalButton(
          onClick = onQuickAddExpense,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Add expense", style = MaterialTheme.typography.labelLarge)
        }
        Button(
          onClick = onQuickRecordRepayment,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = Icons.Outlined.Payments,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Record payment", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}
