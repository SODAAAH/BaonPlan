package com.baonplan.finance.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.ocr.ImageScanPreprocessor
import com.baonplan.finance.data.ocr.PdfDocumentProcessor
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.InterestCalculationMethod
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.PrepaymentRuleType
import com.baonplan.finance.domain.model.ProjectionReliability
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ScheduleCompletenessStatus
import com.baonplan.finance.domain.ocr.PersistedLoanAnalysisRecord
import com.baonplan.finance.domain.ocr.RepaymentScheduleAnalyzer
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import kotlinx.serialization.json.Json
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
import java.io.File

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
  onCorrectTransaction: (TransactionEntity) -> Unit,
  attachments: List<DebtAttachmentEntity> = emptyList(),
  resolveAttachmentFile: (String) -> File? = { null }
) {
  val semantic = LocalBaonColors.current
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var previewAttachment by remember { mutableStateOf<Pair<DebtAttachmentEntity, File>?>(null) }
  val resolvedAttachments = remember(attachments) {
    attachments.mapNotNull { att ->
      resolveAttachmentFile(att.fileName)?.let { file -> att to file }
    }
  }

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
    val analysisRecord = remember(debt.loanAnalysisJson) {
      debt.loanAnalysisJson?.let { raw ->
        runCatching {
          Json { ignoreUnknownKeys = true }.decodeFromString(PersistedLoanAnalysisRecord.serializer(), raw)
        }.getOrNull()
      }
    }
    val availableVersions = remember(installments, debt.scheduleVersion) {
      val fromRows = installments.map { it.scheduleVersion }.distinct().sorted()
      if (fromRows.isNotEmpty()) fromRows else listOf(debt.scheduleVersion)
    }
    var selectedScheduleVersion by remember(debt.id, debt.scheduleVersion) {
      androidx.compose.runtime.mutableIntStateOf(availableVersions.maxOrNull() ?: debt.scheduleVersion)
    }
    val visibleInstallments = remember(installments, selectedScheduleVersion, availableVersions) {
      if (availableVersions.size > 1) {
        installments.filter { it.scheduleVersion == selectedScheduleVersion }
      } else {
        installments
      }
    }

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
            val typeSub = if (debt.productClassification != LoanProductClassification.UNKNOWN) {
              debt.productClassification.label
            } else {
              debt.category
            }
            Text(
              text = "${debt.lender} · $typeSub",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f).padding(end = 8.dp)
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
          if (debt.parentDebtId != null && !debt.obligationRole.countsInTotalDebt) {
            Spacer(modifier = Modifier.height(6.dp))
            StatusBadge(
              text = "Linked to parent account · excluded from total debt",
              tone = BadgeTone.NEUTRAL
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

      // 2b. Repayment Analysis & Payoff Timeline
      item {
        RepaymentAnalysisSection(
          debt = debt,
          installments = visibleInstallments,
          analysisRecord = analysisRecord
        )
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
            if (debt.creditLimitCents != null || debt.availableCreditCents != null ||
              debt.statementBalanceCents != null || debt.minimumDueCents != null
            ) {
              CalmDivider()
              debt.creditLimitCents?.let {
                MetricRow(label = "Credit limit", value = MoneyFormatter.formatPeso(it), boldValue = false)
              }
              debt.availableCreditCents?.let {
                MetricRow(label = "Available credit", value = MoneyFormatter.formatPeso(it), boldValue = false)
              }
              debt.statementBalanceCents?.let {
                MetricRow(label = "Statement balance", value = MoneyFormatter.formatPeso(it), boldValue = false)
              }
              debt.minimumDueCents?.let {
                MetricRow(label = "Minimum due", value = MoneyFormatter.formatPeso(it), boldValue = false)
              }
            }
            if (debt.notes.isNotBlank()) {
              CalmDivider()
              Text(
                text = debt.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            if (resolvedAttachments.isNotEmpty()) {
              CalmDivider()
              CalmSectionLabel(
                text = "Attached screenshots (${resolvedAttachments.size})",
                modifier = Modifier.padding(0.dp)
              )
              LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(
                  items = resolvedAttachments,
                  key = { (att, _) -> att.id }
                ) { (att, file) ->
                  AttachmentThumbTile(
                    attachment = att,
                    file = file,
                    onClick = { previewAttachment = att to file }
                  )
                }
              }
            }
          }
        }
      }

      // 4. Payment Schedule
      item {
        val canManageSchedule = debt.status == DebtStatus.ACTIVE && debt.remainingBalanceCents > 0L
        val isIrregular = debt.frequency == RepaymentFrequency.IRREGULAR
        SectionHeader(
          title = "Payment schedule (${visibleInstallments.size})",
          actionLabel = if (canManageSchedule) "Update" else null,
          onAction = if (canManageSchedule) {
            { onGenerateSchedule(debt) }
          } else null
        )
        if (availableVersions.size > 1) {
          Spacer(modifier = Modifier.height(6.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            availableVersions.forEach { ver ->
              val isCurrentVer = ver == (availableVersions.maxOrNull() ?: debt.scheduleVersion)
              FilterChip(
                selected = selectedScheduleVersion == ver,
                onClick = { selectedScheduleVersion = ver },
                label = {
                  Text(
                    text = if (isCurrentVer) "Schedule v$ver (Current)" else "Schedule v$ver (Original)",
                    style = MaterialTheme.typography.labelSmall
                  )
                }
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (isIrregular && visibleInstallments.isNotEmpty()) {
          Text(
            text = "Irregular payment dates preserved as imported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
          )
        }
        if (visibleInstallments.isEmpty()) {
          BaonCard {
            Text(
              text = if (isIrregular) {
                "No irregular schedule rows saved yet. Tap Update if you switch to a regular payment interval."
              } else {
                "No schedule created yet. BaonPlan never guesses missing dates — tap Update once you know the due date and frequency."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          BaonCard(contentPadding = 0.dp) {
            visibleInstallments.forEachIndexed { idx, inst ->
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
                  val componentParts = listOfNotNull(
                    inst.principalComponentCents?.let { "Principal ${MoneyFormatter.formatPeso(it)}" },
                    inst.interestComponentCents?.let { "Interest ${MoneyFormatter.formatPeso(it)}" },
                    inst.serviceFeeComponentCents?.let { "Fee ${MoneyFormatter.formatPeso(it)}" }
                  )
                  if (componentParts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = componentParts.joinToString(" · "),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DueDateStatusBadge(status = inst.dateStatus)
                    StatusBadge(
                      text = inst.rawStatusLabel?.takeIf { it.isNotBlank() } ?: inst.status.label,
                      tone = when (inst.status) {
                        InstallmentStatus.PAID -> BadgeTone.POSITIVE
                        InstallmentStatus.OVERDUE -> BadgeTone.DANGER
                        InstallmentStatus.PARTIAL, InstallmentStatus.DISPUTED -> BadgeTone.WARNING
                        else -> BadgeTone.NEUTRAL
                      }
                    )
                  }
                  inst.evidenceSourceLabel?.takeIf { it.isNotBlank() }?.let { srcLabel ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = srcLabel,
                      style = MaterialTheme.typography.labelSmall,
                      color = semantic.muted
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
                    inst.status.isPayableInstallment
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
              if (idx < visibleInstallments.lastIndex) {
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

  previewAttachment?.let { (att, file) ->
    AttachmentPreviewDialog(
      attachment = att,
      file = file,
      onDismiss = { previewAttachment = null }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepaymentAnalysisSection(
  debt: DebtEntity,
  installments: List<InstallmentEntity>,
  analysisRecord: PersistedLoanAnalysisRecord?
) {
  val semantic = LocalBaonColors.current
  val timeline = analysisRecord?.payoffTimeline
  val terms = analysisRecord?.contractualTerms
  val isRevolving = debt.productClassification == LoanProductClassification.REVOLVING_CREDIT ||
    debt.scheduleCompletenessStatus == ScheduleCompletenessStatus.REVOLVING_NO_FIXED_PAYOFF
  val isSettled = debt.status == DebtStatus.PAID || debt.remainingBalanceCents <= 0L

  val totalCount = debt.originalInstallmentsCount
    ?: timeline?.totalInstallmentsCount
    ?: installments.size.takeIf { it > 0 && debt.scheduleCompletenessStatus == ScheduleCompletenessStatus.COMPLETE_SCHEDULE }
  val paidCount = installments.count { it.status == InstallmentStatus.PAID }.takeIf { it > 0 }
    ?: debt.paidInstallmentsCount
    ?: timeline?.paidInstallmentsCount
  val remainingCount = installments.count { it.status.isPayableInstallment }.takeIf { it > 0 }
    ?: debt.remainingInstallmentsCount.takeIf { it > 0 }
    ?: timeline?.remainingInstallmentsCount

  val contractualMaturity = debt.contractualMaturityDateIso ?: timeline?.contractualMaturityDateIso
  val currentScheduledPayoff = timeline?.currentScheduledPayoffDateIso
    ?: installments.lastOrNull { !it.dueDate.isNullOrBlank() }?.dueDate
  val projectedPayoff = debt.projectedPayoffDateIso ?: timeline?.projectedPayoffDateIso
  val actualPaidDate = debt.actualFullyPaidDateIso ?: timeline?.actualFullyPaidDateIso

  val hypotheticalScenarios = remember(debt, terms) {
    if (!isSettled && !isRevolving && debt.remainingBalanceCents > 0L) {
      RepaymentScheduleAnalyzer.computeHypotheticalScenarios(
        remainingBalanceCents = debt.remainingBalanceCents,
        installmentAmountCents = debt.installmentAmountCents,
        remainingInstallmentsCount = remainingCount ?: 1,
        nextDueDateIso = debt.nextDueDate,
        frequency = debt.frequency,
        customIntervalDays = debt.customIntervalDays,
        contractualTerms = terms ?: com.baonplan.finance.domain.ocr.ContractualTermsSummary()
      )
    } else {
      emptyList()
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SectionHeader(title = "Repayment analysis")
    BaonCard {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Payoff timeline & schedule status",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
          )
          StatusBadge(
            text = debt.scheduleCompletenessStatus.label,
            tone = when (debt.scheduleCompletenessStatus) {
              ScheduleCompletenessStatus.COMPLETE_SCHEDULE -> BadgeTone.POSITIVE
              ScheduleCompletenessStatus.PARTIAL_SCHEDULE -> BadgeTone.PRIMARY
              ScheduleCompletenessStatus.REVOLVING_NO_FIXED_PAYOFF -> BadgeTone.NEUTRAL
              else -> BadgeTone.WARNING
            }
          )
        }

        if (!isRevolving && totalCount != null && totalCount > 0 && paidCount != null) {
          val fraction = (paidCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "Installment progress",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "$paidCount of $totalCount paid" + (remainingCount?.let { " · $it left" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
            LinearProgressIndicator(
              progress = { fraction },
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
            )
          }
        }

        debt.applicationProvider?.takeIf { it.isNotBlank() && it != debt.lender }?.let { app ->
          MetricRow(label = "App / platform", value = app, boldValue = false)
        }
        debt.lendingCompany?.takeIf { it.isNotBlank() }?.let { company ->
          MetricRow(label = "Lending / financing company", value = company, boldValue = false)
        }
        debt.actualCreditor?.takeIf { it.isNotBlank() && it != debt.lendingCompany }?.let { creditor ->
          MetricRow(label = "Actual creditor", value = creditor, boldValue = false)
        }
        debt.loanReferenceId?.takeIf { it.isNotBlank() }?.let { ref ->
          MetricRow(label = "Reference / contract ID", value = ref, boldValue = false)
        }

        (timeline?.originalLoanDateIso ?: debt.loanBookingDateIso)?.let { origIso ->
          MetricRow(label = "Original loan date", value = ManilaTime.formatDisplay(origIso), boldValue = false)
        }
        timeline?.firstInstallmentDateIso?.let { firstIso ->
          MetricRow(label = "First installment", value = ManilaTime.formatDisplay(firstIso), boldValue = false)
        }
        if (isSettled) {
          actualPaidDate?.let { paidIso ->
            MetricRow(
              label = "Actual fully paid date",
              value = ManilaTime.formatDisplay(paidIso),
              valueColor = semantic.positive
            )
          }
        } else if (!isRevolving) {
          contractualMaturity?.let { matIso ->
            MetricRow(
              label = "Contractual maturity",
              value = ManilaTime.formatDisplay(matIso),
              boldValue = false
            )
          }
          currentScheduledPayoff?.takeIf { it != contractualMaturity }?.let { curIso ->
            MetricRow(
              label = "Current scheduled payoff",
              value = ManilaTime.formatDisplay(curIso),
              boldValue = false
            )
          }
          projectedPayoff?.let { projIso ->
            MetricRow(
              label = if (debt.projectionReliability == ProjectionReliability.ESTIMATED_FROM_PARTIAL) {
                "Projected full-payment date (est.)"
              } else {
                "Projected full-payment date"
              },
              value = ManilaTime.formatDisplay(projIso),
              valueColor = MaterialTheme.colorScheme.primary
            )
          }
        }

        val explanation = timeline?.missingInformationExplanation
          ?: if (isRevolving) {
            "Revolving credit lines do not have a fixed full-payment date because balances change with new usage and minimum payments."
          } else if (!isSettled && projectedPayoff == null) {
            "Upload a complete repayment schedule or confirm next payment date and interval to determine the expected full-payment date."
          } else {
            null
          }
        explanation?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        if (terms != null) {
          val rateSummary = listOfNotNull(
            terms.annualInterestRatePercent?.let { "$it% p.a." },
            terms.monthlyInterestRatePercent?.let { "$it% monthly" },
            terms.dailyInterestRatePercent?.let { "$it% daily" },
            terms.effectiveInterestRatePercent?.let { "EIR $it%" }
          ).joinToString(" · ")
          if (rateSummary.isNotBlank() ||
            terms.interestCalculationMethod != InterestCalculationMethod.UNKNOWN ||
            terms.prepaymentRule != PrepaymentRuleType.UNKNOWN ||
            !analysisRecord?.analyzedDocuments.isNullOrEmpty()
          ) {
            CalmDivider()
            ExpandableSection(
              title = "Contractual terms & source evidence",
              subtitle = "Interest rules, prepayment terms & document provenance",
              initiallyExpanded = false
            ) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rateSummary.isNotBlank()) {
                  MetricRow(label = "Interest rate", value = rateSummary, boldValue = false)
                }
                if (terms.interestCalculationMethod != InterestCalculationMethod.UNKNOWN) {
                  MetricRow(label = "Calculation method", value = terms.interestCalculationMethod.label, boldValue = false)
                }
                if (terms.prepaymentRule != PrepaymentRuleType.UNKNOWN) {
                  MetricRow(label = "Prepayment rule", value = terms.prepaymentRule.label, boldValue = false)
                }
                terms.latePaymentFeeRule?.let { lateRule ->
                  MetricRow(label = "Late fee rule", value = lateRule, boldValue = false)
                }
                analysisRecord?.analyzedDocuments.orEmpty().forEach { doc ->
                  Text(
                    text = "• ${doc.displayTitle} (${doc.documentKind.label})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                analysisRecord?.evidenceTraces.orEmpty().forEach { trace ->
                  Text(
                    text = "• ${trace.fieldLabel}: ${trace.resolvedValueDisplay} (${trace.sourceDocumentLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    color = semantic.muted
                  )
                }
              }
            }
          }
        }

        if (hypotheticalScenarios.isNotEmpty()) {
          CalmDivider()
          ExpandableSection(
            title = "What-if payoff explorer",
            subtitle = "Hypothetical simulations only — stored loan values are unchanged",
            initiallyExpanded = false
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              hypotheticalScenarios.forEach { scenario ->
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                  ) {
                    Text(
                      text = scenario.scenarioTitle,
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                      text = scenario.explanation,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
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
private fun AttachmentThumbTile(
  attachment: DebtAttachmentEntity,
  file: File,
  onClick: () -> Unit
) {
  val semantic = LocalBaonColors.current
  val isPdf = file.extension.equals("pdf", ignoreCase = true)
  val thumb by produceState<ImageBitmap?>(
    initialValue = null,
    key1 = file.absolutePath
  ) {
    value = if (isPdf) {
      PdfDocumentProcessor.renderPdfPageThumbnail(file, pageIndex = 0)?.asImageBitmap()
    } else {
      ImageScanPreprocessor.decodeThumbnail(file)?.asImageBitmap()
    }
  }

  Surface(
    modifier = Modifier
      .width(86.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.divider)
  ) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(96.dp)
          .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
      ) {
        val img = thumb
        if (img != null) {
          Image(
            bitmap = img,
            contentDescription = attachment.displayLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
          )
        }
      }
      Text(
        text = attachment.displayLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
      )
    }
  }
}

@Composable
private fun AttachmentPreviewDialog(
  attachment: DebtAttachmentEntity,
  file: File,
  onDismiss: () -> Unit
) {
  val isPdf = file.extension.equals("pdf", ignoreCase = true)
  val fullBitmap by produceState<ImageBitmap?>(
    initialValue = null,
    key1 = file.absolutePath
  ) {
    value = if (isPdf) {
      PdfDocumentProcessor.renderPdfPageThumbnail(file, pageIndex = 0)?.asImageBitmap()
    } else {
      ImageScanPreprocessor.decodeAttachmentPreview(file)?.asImageBitmap()
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = attachment.displayLabel,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Close preview")
          }
        }
        CalmDivider()
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(12.dp),
          contentAlignment = Alignment.Center
        ) {
          val img = fullBitmap
          if (img != null) {
            Image(
              bitmap = img,
              contentDescription = attachment.displayLabel,
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
          } else {
            CircularProgressIndicator()
          }
        }
      }
    }
  }
}
