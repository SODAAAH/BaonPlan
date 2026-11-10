package com.baonplan.finance.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.budget.BudgetDebtObligationItem
import com.baonplan.finance.domain.budget.PaymentDueGroup
import com.baonplan.finance.domain.budget.ResolvedIncomeOccurrence
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.DuesCoverageStatus
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
  state: BaonPlanUiState,
  onQuickAddExpense: () -> Unit,
  onQuickRecordRepayment: () -> Unit,
  onQuickRecordAllowance: () -> Unit,
  onQuickAddDebt: () -> Unit,
  onOpenDebts: () -> Unit,
  onOpenDebtDetail: (Long) -> Unit,
  onStepBudgetPeriod: (Int) -> Unit,
  onResetBudgetPeriodToCurrent: () -> Unit,
  onOpenSetupBudget: () -> Unit,
  onOpenIncomeSources: () -> Unit,
  onOpenConfirmOccurrence: (ResolvedIncomeOccurrence) -> Unit,
  onOpenBudgetSettings: () -> Unit,
  onOpenPaymentBreakdown: (PaymentDueGroup?) -> Unit,
  onQuickReserveAmount: (debtId: Long, debtName: String, amountCents: Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val weekly = state.weeklySummary
  val overview = state.budgetOverview
  val grouping = state.paymentGrouping
  val progress = state.repaymentProgress
  val readiness = state.duesReadiness

  var showDebtObligationsBreakdown by remember { mutableStateOf(false) }
  var showOverdueBreakdown by remember { mutableStateOf(false) }

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
          progress.activeLoansCount == 0 && progress.completedLoansCount == 0 && progress.archivedLoansCount == 0 ->
            "No debts recorded yet"
          progress.activeLoansCount == 0 && progress.completedLoansCount > 0 && progress.archivedLoansCount > 0 ->
            "All tracked debts paid off · ${progress.archivedLoansCount} archived"
          progress.activeLoansCount == 0 && progress.completedLoansCount > 0 ->
            "All tracked debts paid off"
          progress.activeLoansCount == 0 ->
            "No active debts · ${progress.archivedLoansCount} archived"
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

    // Section 2: Intelligent Grouped Next Payment
    item {
      val overdueGroup = grouping.overdueGroup
      val nextGroup = grouping.nextGroup
      val followingGroup = grouping.followingGroups.firstOrNull()
      val tapPrimaryGroup = nextGroup ?: overdueGroup

      BaonCard(
        onClick = if (grouping.hasAnyActionablePayments) {
          { onOpenPaymentBreakdown(tapPrimaryGroup) }
        } else null
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          CalmSectionLabel("Next payment", modifier = Modifier.padding(0.dp))
          if (grouping.hasAnyActionablePayments) {
            TextButton(
              onClick = { onOpenPaymentBreakdown(tapPrimaryGroup) },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
              Text("View breakdown", style = MaterialTheme.typography.labelLarge)
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Separated Overdue Section (stays visible even if there are no future groups)
        if (overdueGroup != null) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = semantic.dangerContainer,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onOpenPaymentBreakdown(overdueGroup) }
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  StatusBadge("Overdue", BadgeTone.DANGER)
                  Text(
                    text = overdueGroup.countdownLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = semantic.onDangerContainer
                  )
                }
                Text(
                  text = if (overdueGroup.knownTotalCents > 0L) {
                    MoneyFormatter.formatPeso(overdueGroup.knownTotalCents) +
                      if (overdueGroup.hasIncompleteAmounts) "+" else ""
                  } else {
                    "Amount unavailable"
                  },
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = semantic.onDangerContainer
                )
              }
              val overdueSummaryLine = if (overdueGroup.items.size == 1) {
                val single = overdueGroup.items.first()
                "${single.debtName} · ${single.providerName} · Due ${ManilaTime.formatDisplay(single.dueDateIso)}"
              } else {
                "${overdueGroup.totalPaymentsCount} overdue payments · ${overdueGroup.distinctLoansCount} ${if (overdueGroup.distinctLoansCount == 1) "loan" else "loans"}"
              }
              Text(
                text = overdueSummaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = semantic.onDangerContainer
              )
            }
          }
          if (nextGroup != null || grouping.undatedOrUnconfirmedPayments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
          }
        }

        // Upcoming Grouped Next Payment
        if (nextGroup != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
              Text(
                text = if (nextGroup.isSingleDate) {
                  nextGroup.headlineLabel
                } else {
                  "Total due ${nextGroup.dateRangeLabel}"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(2.dp))
              val subText = if (nextGroup.isMultiItemGroup) {
                "${nextGroup.totalPaymentsCount} payments · ${nextGroup.distinctLoansCount} ${if (nextGroup.distinctLoansCount == 1) "loan" else "loans"} · ${nextGroup.countdownLabel}"
              } else {
                val single = nextGroup.items.first()
                "${single.debtName} (${single.providerName}) · ${nextGroup.countdownLabel}"
              }
              Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Column(horizontalAlignment = Alignment.End) {
              val amountDisplay = if (nextGroup.knownTotalCents > 0L) {
                MoneyFormatter.formatPeso(nextGroup.knownTotalCents) +
                  if (nextGroup.hasIncompleteAmounts) "+" else ""
              } else {
                "Amount unavailable"
              }
              Text(
                text = amountDisplay,
                style = if (nextGroup.knownTotalCents > 0L) {
                  MaterialTheme.typography.headlineSmall
                } else {
                  MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = if (nextGroup.knownTotalCents == 0L && nextGroup.hasIncompleteAmounts) {
                  semantic.warning
                } else {
                  MaterialTheme.colorScheme.onSurface
                }
              )
            }
          }

          if (nextGroup.isMultiItemGroup) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              nextGroup.items.take(3).forEach { item ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = "${item.debtName} · ${item.providerName} · ${ManilaTime.formatShort(item.dueDateIso)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                  )
                  Text(
                    text = item.unpaidAmountCents?.let { MoneyFormatter.formatPesoCompact(it) } ?: "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (item.unpaidAmountCents == null) semantic.warning else MaterialTheme.colorScheme.onSurface
                  )
                }
              }
              if (nextGroup.items.size > 3) {
                Text(
                  text = "+${nextGroup.items.size - 3} more in breakdown",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }
            }
          }

          if (nextGroup.hasIncompleteAmounts || nextGroup.hasEstimatedDates || nextGroup.hasPendingPayments) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              if (nextGroup.hasPendingPayments) {
                StatusBadge("Includes pending", BadgeTone.WARNING)
              }
              if (nextGroup.hasEstimatedDates) {
                StatusBadge("Estimated date", BadgeTone.WARNING)
              }
              if (nextGroup.hasIncompleteAmounts) {
                StatusBadge("Total incomplete", BadgeTone.WARNING)
              }
            }
          }

          if (followingGroup != null) {
            CalmDivider(modifier = Modifier.padding(vertical = 10.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPaymentBreakdown(followingGroup) },
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = "Following payment · ${followingGroup.dateRangeLabel}",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "${followingGroup.totalPaymentsCount} ${if (followingGroup.totalPaymentsCount == 1) "payment" else "payments"} · ${followingGroup.distinctLoansCount} ${if (followingGroup.distinctLoansCount == 1) "loan" else "loans"}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Text(
                text = if (followingGroup.knownTotalCents > 0L) {
                  MoneyFormatter.formatPesoCompact(followingGroup.knownTotalCents) +
                    if (followingGroup.hasIncompleteAmounts) "+" else ""
                } else {
                  "Unavailable"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        } else if (overdueGroup == null && grouping.undatedOrUnconfirmedPayments.isEmpty()) {
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

        if (grouping.undatedOrUnconfirmedPayments.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "${grouping.undatedOrUnconfirmedPayments.size} ${if (grouping.undatedOrUnconfirmedPayments.size == 1) "loan needs" else "loans need"} due date confirmation",
            style = MaterialTheme.typography.labelMedium,
            color = semantic.warning
          )
        }

        if (readiness.status != DuesCoverageStatus.NO_DUES && overview.isConfigured) {
          CalmDivider(modifier = Modifier.padding(vertical = 10.dp))
          val (coverageText, coverageColor) = when (readiness.status) {
            DuesCoverageStatus.COVERED_BY_RESERVE -> "Covered by reserve" to semantic.positive
            DuesCoverageStatus.COVERED_WITH_CASH -> "Covered with cash left" to semantic.positive
            DuesCoverageStatus.SHORTFALL ->
              "${MoneyFormatter.formatPesoCompact(readiness.unmetShortfallCents)} short" to semantic.warning
            DuesCoverageStatus.NO_DUES -> "" to MaterialTheme.colorScheme.onSurfaceVariant
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Next 7 days due: ${MoneyFormatter.formatPesoCompact(readiness.dueNext7DaysCents)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = coverageText,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = coverageColor
            )
          }
        }
      }
    }

    // Section 3: Adaptive Budget Overview (Replaces "This week's money")
    item {
      BaonCard {
        if (!overview.isConfigured) {
          CalmSectionLabel("Budget overview", modifier = Modifier.padding(0.dp))
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Set up your allowance to see how much money is available after your loan payments.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (overview.periodDebtTotalCents > 0L || overview.overdueDebtTotalCents > 0L || state.allowances.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              if (state.allowances.isNotEmpty()) {
                val totalLegacyIn = MoneyFormatter.sumCentsExact(state.allowances.map { it.amountCents })
                MetricRow(
                  label = "Recorded allowance history",
                  value = MoneyFormatter.formatPeso(totalLegacyIn),
                  boldValue = false
                )
              }
              if (overview.periodDebtTotalCents > 0L) {
                MetricRow(
                  label = "Upcoming loan dues in period",
                  value = MoneyFormatter.formatPeso(overview.periodDebtTotalCents),
                  boldValue = false
                )
              }
              if (overview.overdueDebtTotalCents > 0L) {
                MetricRow(
                  label = "Overdue loan dues",
                  value = MoneyFormatter.formatPeso(overview.overdueDebtTotalCents),
                  valueColor = semantic.danger,
                  boldValue = false
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(14.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Button(
              onClick = onOpenSetupBudget,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              Text("Set up budget")
            }
            if (state.allowances.isNotEmpty() || state.expenses.isNotEmpty()) {
              OutlinedButton(
                onClick = onOpenBudgetSettings,
                shape = RoundedCornerShape(12.dp)
              ) {
                Text("Settings")
              }
            }
          }
        } else {
          // Configured Adaptive Cash-Flow Budget Overview
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                CalmSectionLabel("Budget overview", modifier = Modifier.padding(0.dp))
                StatusBadge(overview.periodWindow.frequencyLabel, BadgeTone.NEUTRAL)
              }
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = overview.periodWindow.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (overview.periodOffset != 0) {
                TextButton(
                  onClick = onResetBudgetPeriodToCurrent,
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                  Text("Current", style = MaterialTheme.typography.labelMedium)
                }
              }
              IconButton(
                onClick = { onStepBudgetPeriod(-1) },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                  contentDescription = "Previous period"
                )
              }
              IconButton(
                onClick = { onStepBudgetPeriod(1) },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                  contentDescription = "Next period"
                )
              }
            }
          }

          // Pending receipt prompt (when an allowance due today or earlier isn't confirmed yet)
          val firstPendingReceipt = overview.occurrencesInPeriod.firstOrNull { occ ->
            occ.status == IncomeOccurrenceStatus.EXPECTED && !occ.expectedDate.isAfter(state.today)
          }
          if (firstPendingReceipt != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = semantic.warningContainer,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenConfirmOccurrence(firstPendingReceipt) }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(
                    text = "Confirm allowance receipt",
                    style = MaterialTheme.typography.labelLarge,
                    color = semantic.onWarningContainer
                  )
                  Text(
                    text = "${firstPendingReceipt.sourceName} · ${MoneyFormatter.formatPeso(firstPendingReceipt.expectedAmountCents)} scheduled ${ManilaTime.formatShort(ManilaTime.formatIso(firstPendingReceipt.expectedDate))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.onWarningContainer
                  )
                }
                Text(
                  text = "Confirm",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                  color = semantic.onWarningContainer
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          // Hero Spendable / Projected Remaining Number
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = MoneyFormatter.formatPeso(overview.projectedRemainingCents),
                style = MaterialTheme.typography.displaySmall,
                color = if (overview.isProjectedDeficit) semantic.danger else semantic.positive
              )
              val primarySub = when {
                overview.pendingExpectedIncomeCents > 0L && state.budgetProfile.includeExpectedIncomeInProjection ->
                  "Projected remaining (expected allowance included)"
                overview.categoryAllocations.isNotEmpty() || overview.plannedFutureReservesThisCycleCents > 0L ->
                  "Remaining after debt, spending & plans"
                else -> "Available after debt obligations"
              }
              Text(
                text = primarySub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            if (overview.isProjectedDeficit || overview.isActualDeficit) {
              StatusBadge(
                text = if (overview.isActualDeficit) "Cash deficit" else "Projected shortfall",
                tone = BadgeTone.DANGER
              )
            }
          }

          // Honest Actual vs Expected Distinction whenever unreceived expected income exists or cash differs
          if (overview.pendingExpectedIncomeCents > 0L ||
            overview.actualRemainingCents != overview.projectedRemainingCents
          ) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Actual cash on hand now",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = MoneyFormatter.formatPeso(overview.actualRemainingCents),
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                  color = if (overview.isActualDeficit) semantic.danger else MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (overview.rolloverEnabled && overview.openingCarryoverCents != 0L) {
              MetricRow(
                label = "Carried over from prior cash",
                value = if (overview.openingCarryoverCents > 0L) {
                  "+${MoneyFormatter.formatPeso(overview.openingCarryoverCents)}"
                } else {
                  MoneyFormatter.formatPeso(overview.openingCarryoverCents)
                },
                valueColor = if (overview.openingCarryoverCents > 0L) semantic.positive else semantic.danger,
                boldValue = false
              )
            }

            val nextIncomeSubtitle = buildString {
              append("Received: ${MoneyFormatter.formatPesoCompact(overview.actualReceivedIncomeCents)}")
              if (overview.pendingExpectedIncomeCents > 0L) {
                append(" · Pending: ${MoneyFormatter.formatPesoCompact(overview.pendingExpectedIncomeCents)}")
              }
              if (overview.nextExpectedIncomeDate != null && overview.nextExpectedIncomeAmountCents != null) {
                append("\nNext expected: ${ManilaTime.formatShort(ManilaTime.formatIso(overview.nextExpectedIncomeDate))} (${MoneyFormatter.formatPesoCompact(overview.nextExpectedIncomeAmountCents)})")
              }
            }
            MetricRow(
              label = if (overview.pendingExpectedIncomeCents > 0L) "Expected allowance" else "Allowance",
              subtitle = nextIncomeSubtitle,
              value = MoneyFormatter.formatPeso(overview.expectedIncomeCents),
              boldValue = false
            )

            // Period Loan Payments Row (Expandable)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable(enabled = overview.periodDebtItems.isNotEmpty()) {
                    showDebtObligationsBreakdown = !showDebtObligationsBreakdown
                  },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    Text(
                      text = "Loan payments due",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (overview.periodDebtItems.isNotEmpty()) {
                      Icon(
                        imageVector = if (showDebtObligationsBreakdown) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                  val debtSub = buildString {
                    if (overview.periodDebtItems.isEmpty()) {
                      append("No payments due this period")
                    } else {
                      append("${overview.periodDebtItems.size} ${if (overview.periodDebtItems.size == 1) "payment" else "payments"} due")
                      if (overview.debtCoveredByExistingReserveCents > 0L) {
                        append(" · ${MoneyFormatter.formatPesoCompact(overview.debtCoveredByExistingReserveCents)} covered by reserve")
                      }
                      if (overview.hasUnknownAmountObligations) {
                        append(" · Includes unknown amount")
                      }
                    }
                  }
                  Text(
                    text = debtSub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overview.hasUnknownAmountObligations) semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Text(
                  text = if (overview.periodDebtTotalCents > 0L) {
                    "-${MoneyFormatter.formatPeso(overview.periodDebtTotalCents)}"
                  } else {
                    MoneyFormatter.formatPeso(0L)
                  },
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }

              AnimatedVisibility(visible = showDebtObligationsBreakdown && overview.periodDebtItems.isNotEmpty()) {
                DebtObligationsMiniList(
                  items = overview.periodDebtItems,
                  onOpenDebtDetail = onOpenDebtDetail
                )
              }
            }

            // Separated Overdue Obligations Row (Expandable)
            if (overview.overdueDebtItems.isNotEmpty()) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showOverdueBreakdown = !showOverdueBreakdown },
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = semantic.danger,
                        modifier = Modifier.size(15.dp)
                      )
                      Text(
                        text = "Overdue from prior periods (${overview.overdueDebtItems.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.danger
                      )
                      Icon(
                        imageVector = if (showOverdueBreakdown) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = semantic.danger
                      )
                    }
                    Text(
                      text = if (state.budgetProfile.includeOverdueInBudget) {
                        "Deducted from available cash until settled"
                      } else {
                        "Excluded from spendable math per settings"
                      },
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Text(
                    text = "-${MoneyFormatter.formatPeso(overview.overdueDebtTotalCents)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = semantic.danger
                  )
                }

                AnimatedVisibility(visible = showOverdueBreakdown) {
                  DebtObligationsMiniList(
                    items = overview.overdueDebtItems,
                    onOpenDebtDetail = onOpenDebtDetail
                  )
                }
              }
            }

            // Recorded Spending & Cash Repayments
            val spendingSubtitle = buildList {
              if (overview.directCashRepaymentsCents > 0L) {
                add("${MoneyFormatter.formatPesoCompact(overview.directCashRepaymentsCents)} direct loan payments")
              }
              if (overview.emergencyFundedExpensesCents > 0L) {
                add("${MoneyFormatter.formatPesoCompact(overview.emergencyFundedExpensesCents)} from emergency savings")
              }
            }.joinToString(" · ").takeIf { it.isNotBlank() }

            MetricRow(
              label = "Recorded expenses",
              subtitle = spendingSubtitle,
              value = "-${MoneyFormatter.formatPeso(overview.actualExpensesCents)}",
              boldValue = false
            )

            // Optional Reserve Ahead Plans
            if (state.budgetProfile.reserveAheadEnabled && overview.upcomingReservePlans.isNotEmpty()) {
              CalmDivider()
              Text(
                text = "Upcoming payment reserves",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              overview.upcomingReservePlans.take(3).forEach { plan ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                      text = "${plan.debtName} · Due ${ManilaTime.formatShort(plan.dueDateIso)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                      text = "Set aside ${MoneyFormatter.formatPesoCompact(plan.recommendedThisCycleCents)}/cycle (${plan.remainingCyclesBeforeDue} left)",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  TextButton(
                    onClick = {
                      onQuickReserveAmount(
                        plan.debtId,
                        plan.debtName,
                        plan.recommendedThisCycleCents
                      )
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                  ) {
                    Text("Set aside")
                  }
                }
              }
            }

            // Optional Category & Savings Allocations (ONLY shown if configured)
            if (overview.categoryAllocations.isNotEmpty()) {
              CalmDivider()
              Text(
                text = "Planned categories & savings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              overview.categoryAllocations.forEach { cat ->
                val ratio = if (cat.allocatedCents > 0L) {
                  (cat.actualSpentCents.toFloat() / cat.allocatedCents.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = cat.label,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                      text = "${MoneyFormatter.formatPesoCompact(cat.actualSpentCents)} / ${MoneyFormatter.formatPesoCompact(cat.allocatedCents)} · ${if (cat.isOverspent) "Over" else "${MoneyFormatter.formatPesoCompact(cat.remainingAllocationCents)} left"}",
                      style = MaterialTheme.typography.labelSmall,
                      color = if (cat.isOverspent) semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(4.dp)
                      .clip(RoundedCornerShape(2.dp)),
                    color = if (cat.isOverspent) semantic.warning else semantic.positive,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                    strokeCap = StrokeCap.Round
                  )
                }
              }
            }

            if (overview.categoryAllocations.isNotEmpty() || overview.plannedFutureReservesThisCycleCents > 0L) {
              CalmDivider()
              MetricRow(
                label = "Available after debt (before categories)",
                value = MoneyFormatter.formatPeso(overview.availableAfterDebtCents),
                valueColor = if (overview.availableAfterDebtCents < 0L) semantic.danger else semantic.positive
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          CalmDivider()
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            TextButton(
              onClick = onOpenIncomeSources,
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text("Income & receipts (${overview.activeSourcesCount})")
            }
            TextButton(
              onClick = onOpenBudgetSettings,
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text("Budget settings")
            }
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
        val rightActionLabel = when {
          state.totalActiveDebtCents > 0L -> "Record payment"
          weekly.recordedAllowanceCents == 0L && overview.actualReceivedIncomeCents == 0L -> "Record baon"
          else -> "Add debt"
        }
        val rightActionIcon = when {
          state.totalActiveDebtCents > 0L -> Icons.Outlined.Payments
          weekly.recordedAllowanceCents == 0L && overview.actualReceivedIncomeCents == 0L -> Icons.Outlined.AccountBalanceWallet
          else -> Icons.Outlined.Add
        }
        val onRightAction = when {
          state.totalActiveDebtCents > 0L -> onQuickRecordRepayment
          weekly.recordedAllowanceCents == 0L && overview.actualReceivedIncomeCents == 0L -> onQuickRecordAllowance
          else -> onQuickAddDebt
        }
        Button(
          onClick = onRightAction,
          shape = RoundedCornerShape(14.dp),
          contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = rightActionIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(rightActionLabel, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun DebtObligationsMiniList(
  items: List<BudgetDebtObligationItem>,
  onOpenDebtDetail: (Long) -> Unit
) {
  val semantic = LocalBaonColors.current
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items.forEachIndexed { idx, item ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDebtDetail(item.debtId) }
            .padding(vertical = 2.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
              text = item.debtName,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            val dateText = item.dueDateIso?.let { ManilaTime.formatDisplay(it) } ?: "Unconfirmed date"
            Text(
              text = "${item.providerName} · $dateText" +
                if (item.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " · Est." else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Text(
            text = if (item.isAmountKnown) {
              MoneyFormatter.formatPeso(item.unpaidAmountCents)
            } else {
              "Amount unavailable"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (!item.isAmountKnown) semantic.warning else MaterialTheme.colorScheme.onSurface
          )
        }
        if (idx < items.lastIndex) {
          CalmDivider()
        }
      }
    }
  }
}
