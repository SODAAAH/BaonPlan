package com.baonplan.finance.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.PriorWeekCarryOverCandidate
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ScenarioPlan
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.ExpandableSection
import com.baonplan.finance.ui.components.MetricRow
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowanceBudgetScreen(
  state: BaonPlanUiState,
  onRecordAllowanceQuick: (Long) -> Unit,
  onOpenRecordAllowanceDialog: () -> Unit,
  onDeleteAllowance: (Long) -> Unit,
  onQuickSchoolMealExpense: () -> Unit,
  onQuickPresetExpense: (ExpensePreset) -> Unit,
  onDeleteExpense: (Long) -> Unit,
  onOpenAddExpenseDialog: () -> Unit,
  onOpenAdjustBudget: () -> Unit,
  onOpenCreateReserveDialog: (ReserveType) -> Unit,
  onReleaseReserve: (Long) -> Unit,
  onOpenUseEmergencySavings: () -> Unit,
  onCarryOverPriorWeek: (PriorWeekCarryOverCandidate) -> Unit,
  onSweepPriorWeekToReserve: (PriorWeekCarryOverCandidate, ReserveType) -> Unit,
  onDismissCarryOver: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val weekly = state.weeklySummary
  val settings = state.settings
  val activeReserves = remember(state.reserves) {
    state.reserves.filter { it.status == ReserveStatus.RESERVED }
  }
  val weekExpenses = remember(state.expenses, state.today) {
    val weekStart = ManilaTime.weekStart(state.today)
    state.expenses.filter { ManilaTime.isInWeek(it.date, weekStart) }
  }
  val secondaryQuickPreset = remember(state.preferences.expensePresets) {
    state.preferences.expensePresets.firstOrNull { it.category != ExpenseCategory.SCHOOL_FOOD }
  }

  val initialAllowPesos = (settings.conservativeAllowanceCents / 100L).toFloat()
  val allowMin = remember(initialAllowPesos) {
    minOf(200f, floor(initialAllowPesos / 50f) * 50f).coerceAtLeast(0f)
  }
  val allowMax = remember(initialAllowPesos) {
    maxOf(1000f, ceil(initialAllowPesos / 50f) * 50f + 200f)
  }
  val allowSteps = remember(allowMin, allowMax) {
    val stepCount = ((allowMax - allowMin) / 50f).roundToInt()
    if (stepCount in 2..40) stepCount - 1 else 19
  }
  var customAllowancePesos by remember(settings.conservativeAllowanceCents, allowMin, allowMax) {
    mutableFloatStateOf(initialAllowPesos.coerceIn(allowMin, allowMax))
  }

  val initialDebtPesos = (settings.weeklyDebtReserveTargetCents / 100L).toFloat()
  val debtMax = remember(initialDebtPesos) {
    maxOf(500f, ceil(initialDebtPesos / 25f) * 25f + 100f)
  }
  val debtSteps = remember(debtMax) {
    val stepCount = (debtMax / 25f).roundToInt()
    if (stepCount in 2..40) stepCount - 1 else 19
  }
  var customDebtReservePesos by remember(settings.weeklyDebtReserveTargetCents, debtMax) {
    mutableFloatStateOf(initialDebtPesos.coerceIn(0f, debtMax))
  }

  val initialEmergPesos = (settings.weeklyEmergencySavingsTargetCents / 100L).toFloat()
  val emergMax = remember(initialEmergPesos) {
    maxOf(300f, ceil(initialEmergPesos / 25f) * 25f + 50f)
  }
  val emergSteps = remember(emergMax) {
    val stepCount = (emergMax / 25f).roundToInt()
    if (stepCount in 2..40) stepCount - 1 else 19
  }
  var customEmergencyPesos by remember(settings.weeklyEmergencySavingsTargetCents, emergMax) {
    mutableFloatStateOf(initialEmergPesos.coerceIn(0f, emergMax))
  }

  val customScenario = FinancialCalculator.calculateScenarioPlan(
    scenarioName = "Custom Plan",
    allowanceCents = customAllowancePesos.roundToLong() * 100L,
    foodBudgetCents = settings.weeklyFoodBudgetCents,
    otherExpensesCents = settings.weeklyOtherExpensesBudgetCents,
    plannedDebtReserveCents = customDebtReservePesos.roundToLong() * 100L,
    emergencySavingsCents = customEmergencyPesos.roundToLong() * 100L
  )

  val foodProgress = if (weekly.plannedFoodBudgetCents > 0L) {
    (weekly.actualFoodExpensesCents.toFloat() / weekly.plannedFoodBudgetCents.toFloat()).coerceIn(0f, 1f)
  } else 0f

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Header
    item {
      Column {
        Text(
          text = "Budget",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "How much you can safely spend this week",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Optional Prior-Week Leftover Carry-Over / Sweep
    state.priorWeekCarryOver?.let { carryOver ->
      item {
        BaonCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "Unspent from last week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "${MoneyFormatter.formatPeso(carryOver.leftoverCents)} left from ${ManilaTime.formatShort(ManilaTime.formatIso(carryOver.weekStartDate))}–${ManilaTime.formatShort(ManilaTime.formatIso(carryOver.weekEndDate))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            IconButton(
              onClick = { onDismissCarryOver(carryOver.weekStartIso) },
              modifier = Modifier.size(32.dp)
            ) {
              Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            FilledTonalButton(
              onClick = { onCarryOverPriorWeek(carryOver) },
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
              Text("Carry to this week")
            }
            OutlinedButton(
              onClick = { onSweepPriorWeekToReserve(carryOver, ReserveType.EMERGENCY_SAVINGS) },
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
              Text("Save emergency")
            }
            OutlinedButton(
              onClick = { onSweepPriorWeekToReserve(carryOver, ReserveType.DEBT_REPAYMENT_RESERVE) },
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
              Text("Debt reserve")
            }
          }
        }
      }
    }

    // 1. Money Left Focus Card + 3 Clear Actions
    item {
      BaonCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          CalmSectionLabel("Money left this week", modifier = Modifier.padding(0.dp))
          Text(
            text = "${ManilaTime.formatShort(ManilaTime.formatIso(weekly.weekStartDate))}–${ManilaTime.formatShort(ManilaTime.formatIso(weekly.weekEndDate))}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = MoneyFormatter.formatPeso(weekly.availableSpendingMoneyCents),
          style = MaterialTheme.typography.displaySmall,
          color = if (weekly.availableSpendingMoneyCents < 0L) semantic.danger else semantic.positive
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = if (weekly.recordedAllowanceCents == 0L) {
            "Record your weekly allowance to track real cash left."
          } else {
            "After food, reserves, and expenses recorded this week."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(
            onClick = onOpenAddExpenseDialog,
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Record expense")
          }
          FilledTonalButton(
            onClick = onOpenRecordAllowanceDialog,
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Record allowance")
          }
          OutlinedButton(
            onClick = onOpenAdjustBudget,
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Adjust budget")
          }
        }
      }
    }

    // 2. Weekly Allowance
    item {
      BaonCard {
        MetricRow(
          label = "Weekly allowance",
          subtitle = "Target range ${MoneyFormatter.formatPesoCompact(settings.minWeeklyAllowanceCents)}–${MoneyFormatter.formatPesoCompact(settings.maxWeeklyAllowanceCents)}",
          value = MoneyFormatter.formatPeso(weekly.recordedAllowanceCents)
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilledTonalButton(
            onClick = { onRecordAllowanceQuick(settings.minWeeklyAllowanceCents) },
            shape = RoundedCornerShape(10.dp)
          ) {
            Text("+ ${MoneyFormatter.formatPesoCompact(settings.minWeeklyAllowanceCents)}")
          }
          if (settings.maxWeeklyAllowanceCents != settings.minWeeklyAllowanceCents) {
            OutlinedButton(
              onClick = { onRecordAllowanceQuick(settings.maxWeeklyAllowanceCents) },
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+ ${MoneyFormatter.formatPesoCompact(settings.maxWeeklyAllowanceCents)}")
            }
          }
        }
      }
    }

    // 3. Food Budget
    item {
      BaonCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Food budget", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "${settings.schoolDaysPerWeek} school days × ${MoneyFormatter.formatPesoCompact(settings.foodPerSchoolDayCents)}/day · ${weekly.schoolDaysLoggedThisWeek}/${settings.schoolDaysPerWeek} days logged",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Text(
            text = "${MoneyFormatter.formatPeso(weekly.actualFoodExpensesCents)} / ${MoneyFormatter.formatPesoCompact(weekly.plannedFoodBudgetCents)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (weekly.actualFoodExpensesCents > weekly.plannedFoodBudgetCents) semantic.warning else MaterialTheme.colorScheme.onSurface
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { foodProgress },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
          color = if (weekly.actualFoodExpensesCents > weekly.plannedFoodBudgetCents) semantic.warning else semantic.positive,
          trackColor = MaterialTheme.colorScheme.surfaceContainer,
          strokeCap = StrokeCap.Round
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Remaining: ${MoneyFormatter.formatPeso(weekly.remainingFoodBudgetCents)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
              onClick = onQuickSchoolMealExpense,
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
              Text("+ ${MoneyFormatter.formatPesoCompact(settings.foodPerSchoolDayCents)} meal")
            }
            if (secondaryQuickPreset != null) {
              TextButton(
                onClick = { onQuickPresetExpense(secondaryQuickPreset) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
              ) {
                Text("+ ${MoneyFormatter.formatPesoCompact(secondaryQuickPreset.amountCents)} ${secondaryQuickPreset.label.lowercase()}")
              }
            }
          }
        }
        if (weekly.actualOtherExpensesCents > 0L) {
          CalmDivider(modifier = Modifier.padding(vertical = 8.dp))
          MetricRow(
            label = "Other expenses this week",
            subtitle = if (weekly.emergencyFundedExpensesThisWeekCents > 0L) {
              "${MoneyFormatter.formatPesoCompact(weekly.emergencyFundedExpensesThisWeekCents)} paid from emergency savings"
            } else null,
            value = MoneyFormatter.formatPeso(weekly.actualOtherExpensesCents),
            boldValue = false
          )
        }
      }
    }

    // 4. Debt Reserve & Emergency Savings
    item {
      BaonCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text("Debt reserve", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "Reserved cash — not counted as paid until you confirm a payment.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Text(
            text = MoneyFormatter.formatPeso(state.activeDebtReserveTotalCents),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(
            onClick = { onOpenCreateReserveDialog(ReserveType.DEBT_REPAYMENT_RESERVE) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
          ) {
            Text("Set aside for debt")
          }
          TextButton(
            onClick = { onOpenCreateReserveDialog(ReserveType.EMERGENCY_SAVINGS) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
          ) {
            Text("+ Emergency savings")
          }
        }
        if (state.emergencySavingsTotalCents > 0L || weekly.emergencySavingsThisWeekCents > 0L) {
          CalmDivider(modifier = Modifier.padding(vertical = 8.dp))
          val goalCents = state.preferences.emergencySavingsGoalCents
          val goalProgress = if (goalCents > 0L) {
            (state.emergencySavingsTotalCents.toFloat() / goalCents.toFloat()).coerceIn(0f, 1f)
          } else 0f

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Emergency savings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              if (goalCents > 0L) {
                Text(
                  text = "Goal ${MoneyFormatter.formatPesoCompact(goalCents)} (${(goalProgress * 100).toInt()}%)",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
            Text(
              text = MoneyFormatter.formatPeso(state.emergencySavingsTotalCents),
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
              color = semantic.positive
            )
          }
          if (goalCents > 0L) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
              progress = { goalProgress },
              modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
              color = semantic.positive,
              trackColor = MaterialTheme.colorScheme.surfaceContainer,
              strokeCap = StrokeCap.Round
            )
          }
          if (state.emergencySavingsTotalCents > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
              TextButton(
                onClick = onOpenUseEmergencySavings,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
              ) {
                Text("Use emergency savings")
              }
            }
          }
        }
      }
    }

    // 5. Progressive Disclosure: ₱400 vs ₱500 Comparison & Simulator
    item {
      ExpandableSection(
        title = "Weekly scenarios & simulator",
        subtitle = "Compare ${MoneyFormatter.formatPesoCompact(settings.minWeeklyAllowanceCents)} vs ${MoneyFormatter.formatPesoCompact(settings.maxWeeklyAllowanceCents)} week"
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Text(
            text = "Food (${MoneyFormatter.formatPesoCompact(settings.weeklyFoodBudgetCents)}), savings, and a personal buffer are always protected before allocating money to debt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          ScenarioSummaryBlock(plan = state.scenario400)
          CalmDivider()
          ScenarioSummaryBlock(plan = state.scenario500)
          CalmDivider()
          Text("Try a custom week", style = MaterialTheme.typography.titleSmall)
          Column {
            Text(
              text = "Allowance: ₱${customAllowancePesos.roundToLong()}",
              style = MaterialTheme.typography.labelLarge
            )
            Slider(
              value = customAllowancePesos,
              onValueChange = { customAllowancePesos = it },
              valueRange = allowMin..allowMax,
              steps = allowSteps
            )
            Text(
              text = "Debt reserve: ₱${customDebtReservePesos.roundToLong()} (Safe max ${MoneyFormatter.formatPeso(customScenario.recommendedSafeDebtReserveCents)})",
              style = MaterialTheme.typography.labelLarge
            )
            Slider(
              value = customDebtReservePesos,
              onValueChange = { customDebtReservePesos = it },
              valueRange = 0f..debtMax,
              steps = debtSteps
            )
            Text(
              text = "Emergency savings: ₱${customEmergencyPesos.roundToLong()}",
              style = MaterialTheme.typography.labelLarge
            )
            Slider(
              value = customEmergencyPesos,
              onValueChange = { customEmergencyPesos = it },
              valueRange = 0f..emergMax,
              steps = emergSteps
            )
            MetricRow(
              label = "Remaining after food",
              value = MoneyFormatter.formatPeso(customScenario.remainingAfterFoodCents),
              boldValue = false
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetricRow(
              label = "Money left",
              value = MoneyFormatter.formatPeso(customScenario.remainingSpendingMoneyCents),
              valueColor = if (customScenario.isDeficit) semantic.danger else semantic.positive
            )
          }
        }
      }
    }

    // 6. Progressive Disclosure: This Week's Expenses, Allowances & Active Reserves
    item {
      val shownAllowances = state.allowances.take(12)
      ExpandableSection(
        title = "Entries this week & reserves (${activeReserves.size + weekExpenses.size + shownAllowances.size})",
        subtitle = "Review or remove expenses, reserves, and allowances"
      ) {
        if (shownAllowances.isEmpty() && activeReserves.isEmpty() && weekExpenses.isEmpty()) {
          Text(
            text = "No allowance, expense, or active reserve entries yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (activeReserves.isNotEmpty()) {
              CalmSectionLabel("Active reserves", modifier = Modifier.padding(0.dp))
              activeReserves.forEachIndexed { idx, res ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(res.reserveType.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      text = "Week of ${ManilaTime.formatDisplay(res.weekStartDate)} · ${MoneyFormatter.formatPeso(res.remainingReservedCents)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  TextButton(onClick = { onReleaseReserve(res.id) }) {
                    Text("Release")
                  }
                }
                if (idx < activeReserves.lastIndex || weekExpenses.isNotEmpty() || shownAllowances.isNotEmpty()) {
                  CalmDivider()
                }
              }
            }

            if (weekExpenses.isNotEmpty()) {
              CalmSectionLabel("Expenses this week", modifier = Modifier.padding(0.dp))
              weekExpenses.forEachIndexed { idx, expense ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(expense.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      text = buildString {
                        append("${ManilaTime.formatDisplay(expense.date)} · ${expense.category.label}")
                        if (expense.isSchoolDay) append(" · School day")
                      },
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      text = "-${MoneyFormatter.formatPeso(expense.amountCents)}",
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.SemiBold,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { onDeleteExpense(expense.id) }) {
                      Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete expense",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                }
                if (idx < weekExpenses.lastIndex || shownAllowances.isNotEmpty()) {
                  CalmDivider()
                }
              }
            }

            if (shownAllowances.isNotEmpty()) {
              CalmSectionLabel("Allowance history", modifier = Modifier.padding(0.dp))
              shownAllowances.forEachIndexed { idx, record ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(record.sourceLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      text = ManilaTime.formatDisplay(record.receivedDate),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      text = MoneyFormatter.formatPeso(record.amountCents),
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.SemiBold,
                      color = semantic.positive
                    )
                    IconButton(onClick = { onDeleteAllowance(record.id) }) {
                      Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete allowance",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                }
                if (idx < shownAllowances.lastIndex) {
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
private fun ScenarioSummaryBlock(plan: ScenarioPlan) {
  val semantic = LocalBaonColors.current
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(plan.scenarioName, style = MaterialTheme.typography.titleSmall)
      StatusBadge(
        text = if (plan.isDeficit) "Deficit" else "Safe",
        tone = if (plan.isDeficit) BadgeTone.DANGER else BadgeTone.POSITIVE
      )
    }
    MetricRow(label = "Allowance", value = MoneyFormatter.formatPeso(plan.allowanceCents), boldValue = false)
    MetricRow(label = "Food budget", value = "-${MoneyFormatter.formatPeso(plan.foodBudgetCents)}", boldValue = false)
    MetricRow(label = "Remaining after food", value = MoneyFormatter.formatPeso(plan.remainingAfterFoodCents), boldValue = false)
    MetricRow(label = "Other & savings", value = "-${MoneyFormatter.formatPeso(plan.otherExpensesCents + plan.emergencySavingsCents)}", boldValue = false)
    MetricRow(
      label = "Planned debt reserve",
      subtitle = "Safe max: ${MoneyFormatter.formatPeso(plan.recommendedSafeDebtReserveCents)}",
      value = "-${MoneyFormatter.formatPeso(plan.plannedDebtReserveCents)}",
      boldValue = false
    )
    MetricRow(
      label = "Money left",
      value = MoneyFormatter.formatPeso(plan.remainingSpendingMoneyCents),
      valueColor = if (plan.isDeficit) semantic.danger else semantic.positive
    )
  }
}
