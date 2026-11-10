package com.baonplan.finance.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.CalmSegmentedToggle
import com.baonplan.finance.ui.components.EmptyStateCard
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors
import java.time.LocalDate

@Composable
fun DebtsScreen(
  state: BaonPlanUiState,
  onOpenDebtDetail: (Long) -> Unit,
  onAddDebt: () -> Unit,
  onScanDebt: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var selectedFilter by remember { mutableStateOf(DebtStatus.ACTIVE) }

  val filteredDebts = remember(state.debts, selectedFilter) {
    state.debts.filter { it.status == selectedFilter }
  }

  val activeDebts = remember(state.debts) { state.debts.filter { it.status == DebtStatus.ACTIVE } }
  val activeCount = activeDebts.size
  val paidCount = state.debts.count { it.status == DebtStatus.PAID }
  val archivedCount = state.debts.count { it.status == DebtStatus.ARCHIVED }

  val installmentActiveCount = activeDebts.count { it.frequency != RepaymentFrequency.ONE_TIME }
  val oneTimeActiveCount = activeDebts.count { it.frequency == RepaymentFrequency.ONE_TIME }

  val summarySubtitle = when {
    activeCount == 0 && paidCount == 0 && archivedCount == 0 -> "No debts added yet"
    activeCount == 0 && paidCount > 0 && archivedCount > 0 -> "$paidCount paid off · $archivedCount archived"
    activeCount == 0 && paidCount > 0 -> "$paidCount paid off"
    activeCount == 0 -> "$archivedCount archived"
    oneTimeActiveCount > 0 && installmentActiveCount > 0 ->
      "$installmentActiveCount installment · $oneTimeActiveCount one-time"
    oneTimeActiveCount > 0 -> "$oneTimeActiveCount one-time / BNPL"
    else -> "$installmentActiveCount active loan${if (installmentActiveCount == 1) "" else "s"}"
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Header
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Your debts",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onScanDebt,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
          ) {
            Icon(
              imageVector = Icons.Outlined.DocumentScanner,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Scan debt", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          FilledTonalButton(
            onClick = onAddDebt,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
          ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add debt", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }

    // Single Concise Summary Block
    item {
      BaonCard {
        CalmSectionLabel("Remaining debt", modifier = Modifier.padding(0.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = MoneyFormatter.formatPeso(state.totalActiveDebtCents),
          style = MaterialTheme.typography.displaySmall,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = summarySubtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Segmented Filter
    item {
      CalmSegmentedToggle(
        options = listOf(
          DebtStatus.ACTIVE to "Active ($activeCount)",
          DebtStatus.PAID to "Paid ($paidCount)",
          DebtStatus.ARCHIVED to "Archived ($archivedCount)"
        ),
        selected = selectedFilter,
        onSelect = { selectedFilter = it }
      )
    }

    // Clean Vertical List with Dividers
    if (filteredDebts.isEmpty()) {
      item {
        EmptyStateCard(
          icon = Icons.Outlined.AccountBalance,
          title = "No ${selectedFilter.label.lowercase()} debts",
          subtitle = if (selectedFilter == DebtStatus.ACTIVE) {
            "Add your loans or BNPL balances to keep track of due dates and repayments."
          } else {
            "Debts appear here once paid off or archived."
          },
          actionLabel = if (selectedFilter == DebtStatus.ACTIVE) "Add debt" else null,
          onAction = if (selectedFilter == DebtStatus.ACTIVE) onAddDebt else null,
          secondaryActionLabel = if (selectedFilter == DebtStatus.ACTIVE) "Scan debt" else null,
          onSecondaryAction = if (selectedFilter == DebtStatus.ACTIVE) onScanDebt else null
        )
      }
    } else {
      item {
        BaonCard(contentPadding = 0.dp) {
          filteredDebts.forEachIndexed { index, debt ->
            DebtRow(
              debt = debt,
              today = state.today,
              onClick = { onOpenDebtDetail(debt.id) }
            )
            if (index < filteredDebts.lastIndex) {
              CalmDivider()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DebtRow(
  debt: DebtEntity,
  today: LocalDate,
  onClick: () -> Unit
) {
  val semantic = LocalBaonColors.current
  val subtitleText = when {
    debt.status == DebtStatus.PAID -> "Paid in full"
    debt.status == DebtStatus.ARCHIVED -> "Archived"
    debt.nextDueDate != null -> {
      val countdown = ManilaTime.formatDaysUntilDue(debt.nextDueDate, today)
      val base = "${debt.frequency.label} · Due ${ManilaTime.formatShort(debt.nextDueDate)}"
      if (countdown != null) "$base · $countdown" else base
    }
    else -> "${debt.lender} · ${debt.frequency.label}"
  }

  // Strictly at most ONE concise status tag per row
  val statusHint: Pair<String, BadgeTone>? = when {
    debt.status == DebtStatus.PAID -> "Paid" to BadgeTone.POSITIVE
    debt.parentDebtId != null && !debt.obligationRole.countsInTotalDebt ->
      "Linked · excluded from total" to BadgeTone.NEUTRAL
    debt.status == DebtStatus.ACTIVE && debt.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION ->
      "Estimated date" to BadgeTone.WARNING
    debt.status == DebtStatus.ACTIVE && debt.dateStatus == DueDateStatus.UNCONFIRMED ->
      "Unconfirmed" to BadgeTone.WARNING
    debt.status == DebtStatus.ACTIVE && debt.dateStatus == DueDateStatus.UNKNOWN ->
      "Date unknown" to BadgeTone.NEUTRAL
    else -> null
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp, vertical = 15.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(
        text = debt.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitleText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (statusHint != null) {
        Spacer(modifier = Modifier.height(6.dp))
        StatusBadge(text = statusHint.first, tone = statusHint.second)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = MoneyFormatter.formatPeso(debt.remainingBalanceCents),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (debt.status == DebtStatus.PAID) semantic.positive else MaterialTheme.colorScheme.onSurface
      )
      Spacer(modifier = Modifier.width(6.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = "View details",
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}
