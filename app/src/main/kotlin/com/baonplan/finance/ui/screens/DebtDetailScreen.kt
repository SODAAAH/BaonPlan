package com.baonplan.finance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.DueDateStatusBadge
import com.baonplan.finance.ui.components.ExpandableSection
import com.baonplan.finance.ui.components.MetricRow
import com.baonplan.finance.ui.components.SectionHeader
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebtDetailScreen(
  debt: DebtEntity?,
  installments: List<InstallmentEntity>,
  transactions: List<TransactionEntity>,
  onBack: () -> Unit,
  onEditDebt: (DebtEntity) -> Unit,
  onRecordPayment: (DebtEntity) -> Unit,
  onConfirmDate: (DebtEntity) -> Unit,
  onGenerateSchedule: (DebtEntity) -> Unit,
  onMarkInstallmentPaid: (InstallmentEntity) -> Unit,
  onArchiveToggle: (DebtEntity) -> Unit,
  onDeleteDebt: (DebtEntity) -> Unit,
  onCorrectTransaction: (TransactionEntity) -> Unit
) {
  val semantic = LocalBaonColors.current
  var showDeleteConfirm by remember { mutableStateOf(false) }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0),
    topBar = {
      TopAppBar(
        windowInsets = WindowInsets(0),
        title = {
          Text(
            text = debt?.name ?: "Debt details",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (debt != null) {
            IconButton(onClick = { onEditDebt(debt) }) {
              Icon(Icons.Outlined.Edit, contentDescription = "Edit debt")
            }
            IconButton(onClick = { onArchiveToggle(debt) }) {
              Icon(
                imageVector = if (debt.status == DebtStatus.ARCHIVED) {
                  Icons.Outlined.Unarchive
                } else {
                  Icons.Outlined.Archive
                },
                contentDescription = if (debt.status == DebtStatus.ARCHIVED) "Restore debt" else "Archive debt"
              )
            }
          }
        }
      )
    }
  ) { padding ->
    if (debt == null) {
      Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text("Debt not found.")
      }
      return@Scaffold
    }

    val breakdown = FinancialCalculator.calculateLoanCostBreakdown(debt)

    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 1. Clean Balance & Next Installment Summary
      item {
        BaonCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "${debt.lender} · ${debt.category}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusBadge(
              text = debt.status.label,
              tone = when (debt.status) {
                DebtStatus.PAID -> BadgeTone.POSITIVE
                DebtStatus.ARCHIVED -> BadgeTone.NEUTRAL
                DebtStatus.ACTIVE -> BadgeTone.PRIMARY
              }
            )
          }
          Spacer(modifier = Modifier.height(10.dp))
          CalmSectionLabel("Balance remaining", modifier = Modifier.padding(0.dp))
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = MoneyFormatter.formatPeso(debt.remainingBalanceCents),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
          )

          CalmDivider(modifier = Modifier.padding(vertical = 14.dp))

          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val isSettled = debt.status == DebtStatus.PAID || debt.remainingBalanceCents <= 0L
            if (!isSettled) {
              val installmentLabel = if (debt.installmentAmountCents > 0L) {
                "${MoneyFormatter.formatPeso(debt.installmentAmountCents)} · ${debt.remainingInstallmentsCount} left"
              } else {
                "Not specified"
              }
              MetricRow(label = "Next installment", value = installmentLabel, boldValue = false)
            }
            MetricRow(label = "Frequency", value = debt.frequency.label, boldValue = false)
            if (!isSettled) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Next due date",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(
                    text = ManilaTime.formatDisplay(debt.nextDueDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  if (debt.status == DebtStatus.ACTIVE) {
                    DueDateStatusBadge(status = debt.dateStatus)
                  }
                }
              }
            } else {
              MetricRow(
                label = "Status",
                value = if (debt.lastKnownPaidDate != null) {
                  "Paid in full · ${ManilaTime.formatDisplay(debt.lastKnownPaidDate)}"
                } else {
                  "Paid in full"
                },
                valueColor = semantic.positive,
                boldValue = false
              )
            }
          }
        }
      }

      // 2. Clear Actions
      item {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (debt.status == DebtStatus.ACTIVE && debt.remainingBalanceCents > 0L) {
            Button(
              onClick = { onRecordPayment(debt) },
              shape = RoundedCornerShape(12.dp)
            ) {
              Text("Record payment")
            }
          }
          if (debt.status == DebtStatus.ACTIVE && debt.dateStatus != DueDateStatus.CONFIRMED) {
            FilledTonalButton(
              onClick = { onConfirmDate(debt) },
              shape = RoundedCornerShape(12.dp)
            ) {
              Text("Confirm date")
            }
          }
          OutlinedButton(
            onClick = { onEditDebt(debt) },
            shape = RoundedCornerShape(12.dp)
          ) {
            Text("Edit debt")
          }
          if (debt.status == DebtStatus.ACTIVE && debt.remainingBalanceCents > 0L) {
            OutlinedButton(
              onClick = { onGenerateSchedule(debt) },
              shape = RoundedCornerShape(12.dp)
            ) {
              Text("View schedule")
            }
          }
        }
      }

      // 3. Expandable "Loan details" (all 8 cost breakdown lines preserved)
      item {
        ExpandableSection(
          title = "Loan details",
          subtitle = "Original amount, fees, interest, and tracked payments"
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricRow(
              label = "Original principal",
              value = breakdown.originalPrincipalCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered",
              boldValue = false
            )
            MetricRow(
              label = "Amount received",
              value = breakdown.amountReceivedCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered",
              boldValue = false
            )
            MetricRow(
              label = "Interest",
              value = breakdown.interestCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered",
              boldValue = false
            )
            MetricRow(
              label = "Service fees",
              value = breakdown.serviceFeeCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered",
              boldValue = false
            )
            MetricRow(
              label = "Processing fees",
              value = breakdown.processingFeeCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered",
              boldValue = false
            )
            CalmDivider()
            MetricRow(
              label = "Total scheduled repayment",
              value = breakdown.totalScheduledRepaymentCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not entered"
            )
            MetricRow(
              label = "Amount already paid",
              subtitle = buildString {
                if (breakdown.isPriorPaidKnown && breakdown.priorPaidCents > 0L) {
                  append("Prior ${MoneyFormatter.formatPeso(breakdown.priorPaidCents)} + ")
                }
                append("In app ${MoneyFormatter.formatPeso(breakdown.trackedPaidCents)}")
              },
              value = MoneyFormatter.formatPeso(breakdown.totalKnownPaidCents),
              valueColor = semantic.positive
            )
            MetricRow(
              label = "Remaining balance",
              value = MoneyFormatter.formatPeso(breakdown.remainingBalanceCents)
            )
            if (debt.notes.isNotBlank()) {
              CalmDivider()
              Text(
                text = debt.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }

      // 4. Payment Schedule
      item {
        val canManageSchedule = debt.status == DebtStatus.ACTIVE && debt.remainingBalanceCents > 0L
        SectionHeader(
          title = "Payment schedule (${installments.size})",
          actionLabel = if (canManageSchedule) "Update" else null,
          onAction = if (canManageSchedule) {
            { onGenerateSchedule(debt) }
          } else null
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (installments.isEmpty()) {
          BaonCard {
            Text(
              text = "No schedule created yet. BaonPlan never guesses missing dates — tap Update once you know the due date and frequency.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          BaonCard(contentPadding = 0.dp) {
            installments.forEachIndexed { idx, inst ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                  Text(
                    text = "Payment #${inst.installmentNumber} · ${ManilaTime.formatDisplay(inst.dueDate)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DueDateStatusBadge(status = inst.dateStatus)
                    StatusBadge(
                      text = inst.status.label,
                      tone = when (inst.status) {
                        InstallmentStatus.PAID -> BadgeTone.POSITIVE
                        InstallmentStatus.PARTIAL -> BadgeTone.WARNING
                        else -> BadgeTone.NEUTRAL
                      }
                    )
                  }
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(
                    text = MoneyFormatter.formatPeso(inst.amountDueCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                  )
                  if (inst.paidAmountCents > 0L && inst.status != InstallmentStatus.PAID) {
                    Text(
                      text = "Paid ${MoneyFormatter.formatPeso(inst.paidAmountCents)}",
                      style = MaterialTheme.typography.labelSmall,
                      color = semantic.positive
                    )
                  }
                  if (debt.status == DebtStatus.ACTIVE &&
                    debt.remainingBalanceCents > 0L &&
                    inst.status != InstallmentStatus.PAID
                  ) {
                    TextButton(
                      onClick = { onMarkInstallmentPaid(inst) },
                      contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                      Text("Mark paid")
                    }
                  } else if (inst.status == InstallmentStatus.PAID) {
                    Text(
                      text = ManilaTime.formatShort(inst.paidDate),
                      style = MaterialTheme.typography.labelSmall,
                      color = semantic.positive
                    )
                  }
                }
              }
              if (idx < installments.lastIndex) {
                CalmDivider()
              }
            }
          }
        }
      }

      // 5. Recorded Payments
      item {
        SectionHeader(title = "Recorded payments (${transactions.size})")
        Spacer(modifier = Modifier.height(6.dp))
        if (transactions.isEmpty()) {
          BaonCard {
            Text(
              text = "No payments recorded yet for this debt." +
                (debt.lastKnownPaidDate?.let { " Last known paid before adding: ${ManilaTime.formatDisplay(it)}." } ?: ""),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          BaonCard(contentPadding = 0.dp) {
            transactions.forEachIndexed { idx, tx ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .then(
                    if (!tx.isVoided) Modifier.clickable { onCorrectTransaction(tx) } else Modifier
                  )
                  .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                  Text(tx.title, style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = ManilaTime.formatDisplay(tx.date) +
                      if (tx.fundedFromReserveCents > 0L) " · From debt reserve" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  if (tx.isVoided) {
                    Spacer(modifier = Modifier.height(4.dp))
                    StatusBadge(text = "Voided", tone = BadgeTone.DANGER)
                  } else if (tx.isCorrected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    StatusBadge(text = "Edited", tone = BadgeTone.WARNING)
                  }
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(
                    text = MoneyFormatter.formatPeso(tx.amountCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tx.isVoided) MaterialTheme.colorScheme.outline else semantic.positive
                  )
                  if (!tx.isVoided) {
                    Text(
                      text = "Tap to edit",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary
                    )
                  }
                }
              }
              if (idx < transactions.lastIndex) {
                CalmDivider()
              }
            }
          }
        }
      }

      // 6. Permanent Delete Option
      item {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
          horizontalArrangement = Arrangement.Center
        ) {
          TextButton(
            onClick = { showDeleteConfirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
          ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Delete debt")
          }
        }
      }
    }
  }

  if (showDeleteConfirm && debt != null) {
    val activeTxCount = transactions.count { !it.isVoided }
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Delete ${debt.name}?") },
      text = {
        Text(
          buildString {
            append("This permanently removes ${debt.name} and its payment schedule from your device.")
            if (activeTxCount > 0) {
              append("\n\n$activeTxCount linked payment record(s) will be voided, and any debt reserve used for them will be restored.")
            }
          }
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showDeleteConfirm = false
            onDeleteDebt(debt)
            onBack()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
      }
    )
  }
}
