package com.baonplan.finance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.EmptyStateCard
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors

private enum class TxFilter(val label: String) {
  ALL("All"),
  ALLOWANCE("Allowance"),
  EXPENSE("Expenses"),
  REPAYMENT("Payments"),
  RESERVES("Reserves"),
  VOIDED("Voided")
}

@Composable
fun TransactionsScreen(
  state: BaonPlanUiState,
  onSelectTransactionForCorrection: (TransactionEntity) -> Unit,
  modifier: Modifier = Modifier
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf(TxFilter.ALL) }
  val semantic = LocalBaonColors.current

  val filtered = remember(state.transactions, searchQuery, selectedFilter) {
    state.transactions.filter { tx ->
      val matchesFilter = when (selectedFilter) {
        TxFilter.ALL -> !tx.isVoided
        TxFilter.ALLOWANCE -> !tx.isVoided && tx.type == TransactionType.ALLOWANCE
        TxFilter.EXPENSE -> !tx.isVoided && tx.type == TransactionType.EXPENSE
        TxFilter.REPAYMENT -> !tx.isVoided && tx.type == TransactionType.DEBT_REPAYMENT
        TxFilter.RESERVES -> !tx.isVoided && (
          tx.type == TransactionType.DEBT_RESERVE || tx.type == TransactionType.EMERGENCY_SAVINGS
          )
        TxFilter.VOIDED -> tx.isVoided
      }
      val q = searchQuery.trim()
      val matchesQuery = q.isEmpty() ||
        tx.title.contains(q, ignoreCase = true) ||
        tx.category.contains(q, ignoreCase = true) ||
        tx.notes.contains(q, ignoreCase = true)
      matchesFilter && matchesQuery
    }
  }

  val grouped = remember(filtered) {
    filtered.groupBy { it.date }
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        label = { Text("Search history") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        TxFilter.entries.forEach { filter ->
          FilterChip(
            selected = selectedFilter == filter,
            onClick = { selectedFilter = filter },
            label = { Text(filter.label) }
          )
        }
      }
    }

    if (filtered.isNotEmpty()) {
      item {
        val countLabel = "${filtered.size} entr${if (filtered.size == 1) "y" else "ies"} · ${selectedFilter.label}"
        val summaryPair: Pair<String, androidx.compose.ui.graphics.Color> = when (selectedFilter) {
          TxFilter.ALLOWANCE ->
            "+${MoneyFormatter.formatPeso(filtered.sumOf { it.amountCents })}" to semantic.positive
          TxFilter.EXPENSE ->
            "-${MoneyFormatter.formatPeso(filtered.sumOf { it.amountCents })}" to MaterialTheme.colorScheme.onSurface
          TxFilter.REPAYMENT ->
            "-${MoneyFormatter.formatPeso(filtered.sumOf { it.amountCents })}" to MaterialTheme.colorScheme.primary
          TxFilter.RESERVES ->
            MoneyFormatter.formatPeso(filtered.sumOf { it.amountCents }) to MaterialTheme.colorScheme.onSurface
          TxFilter.VOIDED ->
            MoneyFormatter.formatPeso(filtered.sumOf { it.amountCents }) to MaterialTheme.colorScheme.outline
          TxFilter.ALL -> {
            val inflows = filtered.filter { it.type == TransactionType.ALLOWANCE }.sumOf { it.amountCents }
            val outflows = filtered
              .filter { it.type != TransactionType.ALLOWANCE && it.type != TransactionType.ADJUSTMENT }
              .sumOf { it.amountCents }
            "In ${MoneyFormatter.formatPesoCompact(inflows)} · Out ${MoneyFormatter.formatPesoCompact(outflows)}" to MaterialTheme.colorScheme.onSurface
          }
        }
        BaonCard(contentPadding = 12.dp) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = countLabel,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = summaryPair.first,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = summaryPair.second
            )
          }
        }
      }
    }

    if (filtered.isEmpty()) {
      item {
        EmptyStateCard(
          icon = Icons.AutoMirrored.Outlined.ReceiptLong,
          title = "No matching transactions",
          subtitle = "Allowances, expenses, reserves, and debt payments appear here."
        )
      }
    } else {
      grouped.forEach { (dateIso, itemsForDate) ->
        item(key = "grp_$dateIso") {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CalmSectionLabel(ManilaTime.formatDayHeader(dateIso))
            BaonCard(contentPadding = 0.dp) {
              itemsForDate.forEachIndexed { idx, tx ->
                val isPositiveFlow = tx.type == TransactionType.ALLOWANCE
                val amountText = when {
                  tx.isVoided -> MoneyFormatter.formatPeso(tx.amountCents)
                  isPositiveFlow -> "+${MoneyFormatter.formatPeso(tx.amountCents)}"
                  else -> "-${MoneyFormatter.formatPeso(tx.amountCents)}"
                }
                val amountColor = when {
                  tx.isVoided -> MaterialTheme.colorScheme.outline
                  isPositiveFlow -> semantic.positive
                  tx.type == TransactionType.DEBT_REPAYMENT -> MaterialTheme.colorScheme.primary
                  else -> MaterialTheme.colorScheme.onSurface
                }

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(
                      if (!tx.isVoided) {
                        Modifier.clickable { onSelectTransactionForCorrection(tx) }
                      } else Modifier
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                      text = tx.title,
                      style = MaterialTheme.typography.titleMedium,
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      textDecoration = if (tx.isVoided) TextDecoration.LineThrough else TextDecoration.None
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = buildString {
                        append(tx.type.label)
                        if (tx.category.isNotBlank() && tx.category != tx.type.label) {
                          append(" · ${tx.category}")
                        }
                        if (tx.fundedFromReserveCents > 0L) {
                          append(" · From reserve")
                        }
                      },
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    if (tx.isVoided) {
                      Spacer(modifier = Modifier.height(4.dp))
                      StatusBadge(text = "Voided", tone = BadgeTone.DANGER)
                    } else if (tx.isCorrected) {
                      Spacer(modifier = Modifier.height(4.dp))
                      StatusBadge(text = "Edited", tone = BadgeTone.WARNING)
                    }
                  }
                  Text(
                    text = amountText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColor,
                    textDecoration = if (tx.isVoided) TextDecoration.LineThrough else TextDecoration.None
                  )
                }
                if (idx < itemsForDate.lastIndex) {
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
