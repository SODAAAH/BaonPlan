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
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllowanceBudgetScreen(
  state: BaonPlanUiState,
  onRecordAllowanceQuick: (Long) -> Unit,
  onOpenRecordAllowanceDialog: () -> Unit,
  onDeleteAllowance: (Long) -> Unit,
  onQuickSchoolMealExpense: () -> Unit,
  onOpenAddExpenseDialog: () -> Unit,
  onOpenAdjustBudget: () -> Unit,
  onOpenCreateReserveDialog: (ReserveType) -> Unit,
  onReleaseReserve: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val weekly = state.weeklySummary
  val settings = state.settings
  val activeReserves = remember(state.reserves) {
    state.reserves.filter { it.status == ReserveStatus.RESERVED }
  }

  var customAllowancePesos by remember(settings.conservativeAllowanceCents) {
    mutableFloatStateOf((settings.conservativeAllowanceCents / 100L).toFloat())
  }
  var customDebtReservePesos by remember(settings.weeklyDebtReserveTargetCents) {
    mutableFloatStateOf((settings.weeklyDebtReserveTargetCents / 100L).toFloat())
  }
  var customEmergencyPesos by remember(settings.weeklyEmergencySavingsTargetCents) {
    mutableFloatStateOf((settings.weeklyEmergencySavingsTargetCents / 100L).toFloat())
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
          OutlinedButton(
            onClick = { onRecordAllowanceQuick(settings.maxWeeklyAllowanceCents) },
            shape = RoundedCornerShape(10.dp)
          ) {
            Text("+ ${MoneyFormatter.formatPesoCompact(settings.maxWeeklyAllowanceCents)}")
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
              text = "${settings.schoolDaysPerWeek} school days × ${MoneyFormatter.formatPesoCompact(settings.foodPerSchoolDayCents)}/day",
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
          TextButton(
            onClick = onQuickSchoolMealExpense,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("+ ${MoneyFormatter.formatPesoCompact(settings.foodPerSchoolDayCents)} meal")
          }
        }
        if (weekly.actualOtherExpensesCents > 0L) {
          CalmDivider(modifier = Modifier.padding(vertical = 8.dp))
          MetricRow(
            label = "Other expenses this week",
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
          MetricRow(
            label = "Emergency savings",
            value = MoneyFormatter.formatPeso(state.emergencySavingsTotalCents),
            valueColor = semantic.positive,
            boldValue = false
          )
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
              valueRange = 200f..1000f,
              steps = 15
            )
            Text(
              text = "Debt reserve: ₱${customDebtReservePesos.roundToLong()} (Safe max ${MoneyFormatter.formatPeso(customScenario.recommendedSafeDebtReserveCents)})",
              style = MaterialTheme.typography.labelLarge
            )
            Slider(
              value = customDebtReservePesos,
              onValueChange = { customDebtReservePesos = it },
              valueRange = 0f..500f,
              steps = 19
            )
            Text(
              text = "Emergency savings: ₱${customEmergencyPesos.roundToLong()}",
              style = MaterialTheme.typography.labelLarge
            )
            Slider(
              value = customEmergencyPesos,
              onValueChange = { customEmergencyPesos = it },
              valueRange = 0f..300f,
              steps = 11
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

    // 6. Progressive Disclosure: Recorded Allowance & Reserve Entries
    item {
      ExpandableSection(
        title = "Allowance & reserve entries (${state.allowances.size + activeReserves.size})",
        subtitle = "Review logged allowances or release reserved money"
      ) {
        if (state.allowances.isEmpty() && activeReserves.isEmpty()) {
          Text(
            text = "No allowance or active reserve entries yet.",
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
                if (idx < activeReserves.lastIndex || state.allowances.isNotEmpty()) {
                  CalmDivider()
                }
              }
            }

            if (state.allowances.isNotEmpty()) {
              CalmSectionLabel("Allowance history", modifier = Modifier.padding(0.dp))
              state.allowances.take(12).forEachIndexed { idx, record ->
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
                if (idx < state.allowances.take(12).lastIndex) {
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
