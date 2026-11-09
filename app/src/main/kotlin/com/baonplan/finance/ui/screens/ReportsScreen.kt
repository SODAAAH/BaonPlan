package com.baonplan.finance.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.InfoCallout
import com.baonplan.finance.ui.components.MetricRow
import com.baonplan.finance.ui.components.SectionHeader
import com.baonplan.finance.ui.theme.LocalBaonColors
import java.util.Locale
import androidx.compose.foundation.layout.padding

@Composable
fun ReportsScreen(
  state: BaonPlanUiState,
  onExportCsv: () -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val palette = semantic.chartPalette
  val activeDebts = state.debts.filter { it.status == DebtStatus.ACTIVE && it.remainingBalanceCents > 0L }
  val totalActive = state.totalActiveDebtCents
  val progress = state.repaymentProgress
  val weekly = state.weeklySummary

  val installmentsByDebt = state.installments.groupBy { it.debtId }
  val unconfirmedOrUnknown = activeDebts.filter { debt ->
    val unpaidRows = installmentsByDebt[debt.id].orEmpty()
      .filter { it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL }
    val allUnpaidRowsDated = unpaidRows.isNotEmpty() && unpaidRows.all {
      ManilaTime.parseDate(it.dueDate) != null &&
        (it.dateStatus == DueDateStatus.CONFIRMED || it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
    }
    debt.frequency == RepaymentFrequency.UNKNOWN ||
      (unpaidRows.isNotEmpty() && !allUnpaidRowsDated) ||
      (unpaidRows.isEmpty() && (
        debt.frequency == RepaymentFrequency.IRREGULAR ||
          debt.dateStatus == DueDateStatus.UNCONFIRMED ||
          debt.dateStatus == DueDateStatus.UNKNOWN
        ))
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // 1. Debt Composition
    item {
      SectionHeader(
        title = "Debt breakdown",
        actionLabel = "Export CSV",
        onAction = onExportCsv
      )
      Spacer(modifier = Modifier.height(8.dp))
      BaonCard {
        Text(
          text = "Remaining debt: ${MoneyFormatter.formatPeso(totalActive)}",
          style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (totalActive > 0L && activeDebts.isNotEmpty()) {
          Canvas(
            modifier = Modifier
              .fillMaxWidth()
              .height(14.dp)
              .clip(RoundedCornerShape(7.dp))
          ) {
            var xOffset = 0f
            activeDebts.forEachIndexed { idx, debt ->
              val share = debt.remainingBalanceCents.toFloat() / totalActive.toFloat()
              val w = size.width * share
              if (w > 0f) {
                drawRect(
                  color = palette[idx % palette.size],
                  topLeft = Offset(xOffset, 0f),
                  size = Size(w, size.height)
                )
                xOffset += w
              }
            }
          }
          Spacer(modifier = Modifier.height(14.dp))
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            activeDebts.forEachIndexed { idx, debt ->
              val pct = (debt.remainingBalanceCents.toDouble() * 100.0 / totalActive.toDouble())
              val shareColor = palette[idx % palette.size]
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(shareColor))
                  Spacer(modifier = Modifier.width(8.dp))
                  Column {
                    Text(debt.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                      text = "${String.format(Locale.US, "%.1f", pct)}% · ${debt.frequency.label}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
                Text(
                  text = MoneyFormatter.formatPeso(debt.remainingBalanceCents),
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        } else if (state.debts.isEmpty()) {
          Text(
            text = "No debts recorded yet. Add a debt to see your breakdown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          Text(
            text = "Zero remaining debt!",
            style = MaterialTheme.typography.bodyMedium,
            color = semantic.positive
          )
        }
      }
    }

    // 2. Repayment Progress
    item {
      SectionHeader(title = "Repayment progress")
      Spacer(modifier = Modifier.height(8.dp))
      BaonCard {
        MetricRow(
          label = "Completed debts",
          subtitle = "${progress.completedLoansCount} paid off",
          value = MoneyFormatter.formatPeso(progress.completedLoansOriginalTotalCents),
          valueColor = semantic.positive
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
          label = "Paid on active debts",
          value = MoneyFormatter.formatPeso(progress.trackedRepaidOnActiveCents),
          boldValue = false
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
          label = "Known progress",
          value = "${(progress.progressFraction * 100).toInt()}%"
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { progress.progressFraction },
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
          color = semantic.positive,
          trackColor = MaterialTheme.colorScheme.surfaceContainer,
          strokeCap = StrokeCap.Round
        )
        val payoff = state.payoffInsights
        if (payoff.includedDebtsCount > 0 && payoff.estimatedDebtFreeDateIso != null) {
          Spacer(modifier = Modifier.height(12.dp))
          MetricRow(
            label = "Est. debt-free (scheduled)",
            subtitle = buildString {
              payoff.estimatedWeeksRemaining?.let { weeks ->
                append("~${weeks}w remaining")
              }
              if (payoff.includesEstimatedDates) {
                if (isNotEmpty()) append(" · ")
                append("Includes estimated dates")
              }
            },
            value = ManilaTime.formatDisplay(payoff.estimatedDebtFreeDateIso),
            boldValue = false
          )
        }
        if (unconfirmedOrUnknown.isNotEmpty()) {
          Spacer(modifier = Modifier.height(12.dp))
          InfoCallout(
            icon = Icons.Outlined.Info,
            title = "Payoff timeline excludes ${unconfirmedOrUnknown.size} debt(s)",
            body = "Debts with unknown or unconfirmed schedules (${unconfirmedOrUnknown.joinToString { it.name }}) are left out of automated payoff projections.",
            tone = BadgeTone.WARNING
          )
        }
      }
    }

    // 2b. Repayment Priority Advisor
    val payoffAdvisor = state.payoffInsights
    if (activeDebts.isNotEmpty() && (payoffAdvisor.soonestDueDebt != null || payoffAdvisor.quickestWinDebt != null)) {
      item {
        SectionHeader(title = "Repayment focus")
        Spacer(modifier = Modifier.height(8.dp))
        BaonCard {
          payoffAdvisor.soonestDueDebt?.let { soonest ->
            MetricRow(
              label = "Due soonest",
              subtitle = "${soonest.debtName} · ${soonest.dueDateIso?.let { ManilaTime.formatDisplay(it) } ?: soonest.dateStatus.label}",
              value = MoneyFormatter.formatPeso(soonest.amountCents)
            )
          }
          payoffAdvisor.quickestWinDebt?.let { quickest ->
            val sameDebt = payoffAdvisor.soonestDueDebt?.debtId == quickest.debtId
            if (payoffAdvisor.soonestDueDebt != null) {
              CalmDivider(modifier = Modifier.padding(vertical = 10.dp))
            }
            MetricRow(
              label = if (sameDebt) "Smallest balance (same loan)" else "Smallest balance first",
              subtitle = "${quickest.name} · ${quickest.frequency.label}",
              value = MoneyFormatter.formatPeso(quickest.remainingBalanceCents),
              valueColor = semantic.positive
            )
            if (!sameDebt) {
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Putting any extra ₱50–₱100 reserve toward ${quickest.name} finishes a recurring bill faster and frees up weekly baon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }

    // 3. Weekly Money Breakdown
    item {
      SectionHeader(title = "This week's allocation")
      Spacer(modifier = Modifier.height(8.dp))
      BaonCard {
        val bars = listOf(
          Triple("Allowance", weekly.recordedAllowanceCents, palette.getOrElse(0) { semantic.positive }),
          Triple("Food", weekly.actualFoodExpensesCents, palette.getOrElse(1) { MaterialTheme.colorScheme.primary }),
          Triple("Other expenses", weekly.actualOtherExpensesCents, palette.getOrElse(4) { semantic.muted }),
          Triple("Debt reserve", weekly.activeDebtReserveCents, palette.getOrElse(2) { MaterialTheme.colorScheme.secondary }),
          Triple("Paid this week", weekly.repaymentsPaidThisWeekCents, semantic.positive),
          Triple("Emergency savings", state.emergencySavingsTotalCents, palette.getOrElse(3) { semantic.warning })
        )
        val maxBar = (bars.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(10_000L)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          bars.forEach { (label, cents, color) ->
            val ratio = (cents.toFloat() / maxBar.toFloat()).coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MoneyFormatter.formatPeso(cents), style = MaterialTheme.typography.labelLarge)
              }
              Canvas(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(8.dp)
              ) {
                drawRoundRect(
                  color = color.copy(alpha = 0.16f),
                  cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                if (ratio > 0f) {
                  drawRoundRect(
                    color = color,
                    size = Size(size.width * ratio, size.height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                  )
                }
              }
            }
          }
        }
      }
    }

    // 3b. Last 4 Weeks Comparison
    val trends = state.weeklyTrends
    if (trends.any { it.allowanceCents > 0L || it.expensesCents > 0L || it.repaymentsCents > 0L }) {
      item {
        SectionHeader(title = "Last 4 weeks")
        Spacer(modifier = Modifier.height(8.dp))
        BaonCard {
          val maxWeekCents = trends
            .maxOf { maxOf(it.allowanceCents, it.expensesCents, it.repaymentsCents) }
            .coerceAtLeast(10_000L)
          val barAllowanceColor = palette.getOrElse(0) { semantic.positive }
          val barExpenseColor = palette.getOrElse(1) { MaterialTheme.colorScheme.primary }
          val barPaidColor = semantic.positive

          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            trends.forEachIndexed { idx, point ->
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = point.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = "${ManilaTime.formatShort(ManilaTime.formatIso(point.weekStartDate))}–${ManilaTime.formatShort(ManilaTime.formatIso(point.weekEndDate))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Text(
                  text = "In ${MoneyFormatter.formatPesoCompact(point.allowanceCents)} · Spent ${MoneyFormatter.formatPesoCompact(point.expensesCents)} · Paid ${MoneyFormatter.formatPesoCompact(point.repaymentsCents)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Canvas(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                ) {
                  val barH = 3.5.dp.toPx()
                  val gap = 1.5.dp.toPx()
                  val items = listOf(
                    point.allowanceCents to barAllowanceColor,
                    point.expensesCents to barExpenseColor,
                    point.repaymentsCents to barPaidColor
                  )
                  items.forEachIndexed { barIdx, (cents, color) ->
                    val y = barIdx * (barH + gap)
                    drawRoundRect(
                      color = color.copy(alpha = 0.14f),
                      topLeft = Offset(0f, y),
                      size = Size(size.width, barH),
                      cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                    val ratio = (cents.toFloat() / maxWeekCents.toFloat()).coerceIn(0f, 1f)
                    if (ratio > 0f) {
                      drawRoundRect(
                        color = color,
                        topLeft = Offset(0f, y),
                        size = Size(size.width * ratio, barH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                      )
                    }
                  }
                }
              }
              if (idx < trends.lastIndex) {
                CalmDivider()
              }
            }
          }
        }
      }
    }

    // 4. CSV Export
    item {
      BaonCard {
        Text("Export transaction history", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Save your ${state.transactions.size} local transaction entries as a CSV file.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
          onClick = onExportCsv,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Outlined.Download, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Export CSV")
        }
      }
    }
  }
}
