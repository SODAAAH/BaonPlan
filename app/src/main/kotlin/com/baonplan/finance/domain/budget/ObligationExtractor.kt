package com.baonplan.finance.domain.budget

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import java.time.LocalDate

object ObligationExtractor {

  /**
   * Resolves a friendly provider label preferring actual lender / application provider
   * while falling back safely to [DebtEntity.lender].
   */
  fun providerDisplayName(debt: DebtEntity): String =
    debt.applicationProvider?.takeIf { it.isNotBlank() }
      ?: debt.lender.takeIf { it.isNotBlank() }
      ?: debt.lendingCompany?.takeIf { it.isNotBlank() }
      ?: "Loan"

  /**
   * Deterministically extracts all payable, non-excluded obligations across active debts that count
   * toward total debt, respecting [DebtEntity.scheduleVersion], revolving credit minimum dues,
   * partial payments, pending statuses, and unknown/unconfirmed amounts or dates.
   */
  fun extractPayableObligations(
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    today: LocalDate
  ): List<BudgetDebtObligationItem> {
    val activeDebts = debts.filter {
      it.status == DebtStatus.ACTIVE &&
        it.remainingBalanceCents > 0L &&
        FinancialCalculator.countsTowardTotalDebt(it)
    }

    val results = mutableListOf<BudgetDebtObligationItem>()

    for (debt in activeDebts) {
      val effectiveInsts = FinancialCalculator.effectiveInstallmentsForDebt(debt, installments)
      val provider = providerDisplayName(debt)

      if (effectiveInsts.isNotEmpty()) {
        val payable = effectiveInsts.filter { it.status.isPayableInstallment }
        for (inst in payable) {
          val isAmountKnown = inst.amountDueCents > 0L
          val unpaid = if (isAmountKnown) {
            (inst.amountDueCents - inst.paidAmountCents).coerceAtLeast(0L)
          } else {
            0L
          }
          if (isAmountKnown && unpaid <= 0L) continue

          val parsedDue = ManilaTime.parseDate(inst.dueDate)
          val isDated = parsedDue != null &&
            (inst.dateStatus == DueDateStatus.CONFIRMED ||
              inst.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
          val isOverdue = parsedDue != null && isDated &&
            (parsedDue.isBefore(today) || inst.status == InstallmentStatus.OVERDUE)

          results.add(
            BudgetDebtObligationItem(
              debtId = debt.id,
              installmentId = inst.id,
              debtName = debt.name,
              providerName = provider,
              classification = debt.productClassification,
              dueDate = if (isDated) parsedDue else null,
              dueDateIso = if (parsedDue != null && isDated) ManilaTime.formatIso(parsedDue) else inst.dueDate,
              dateStatus = inst.dateStatus,
              installmentStatus = inst.status,
              amountDueCents = if (isAmountKnown) inst.amountDueCents else 0L,
              paidAmountCents = inst.paidAmountCents.coerceAtLeast(0L),
              unpaidAmountCents = unpaid,
              isAmountKnown = isAmountKnown,
              isOverdue = isOverdue,
              isPendingVerification = inst.status == InstallmentStatus.PENDING
            )
          )
        }
      } else {
        // No scheduled rows for active scheduleVersion: derive honestly from debt fields without
        // inventing installment schedules or misusing credit limits as payment due.
        val rawAmountDue: Long? = when (debt.productClassification) {
          LoanProductClassification.REVOLVING_CREDIT -> {
            debt.minimumDueCents?.takeIf { it > 0L }
              ?: debt.amountCurrentlyDueCents?.takeIf { it > 0L }
              ?: debt.installmentAmountCents.takeIf { it > 0L }
          }
          else -> {
            debt.installmentAmountCents.takeIf { it > 0L }?.let { minOf(it, debt.remainingBalanceCents) }
              ?: debt.amountCurrentlyDueCents?.takeIf { it > 0L }?.let { minOf(it, debt.remainingBalanceCents) }
              ?: if (
                debt.frequency == RepaymentFrequency.ONE_TIME ||
                debt.remainingInstallmentsCount <= 1
              ) {
                debt.remainingBalanceCents.takeIf { it > 0L }
              } else {
                null
              }
          }
        }

        val isAmountKnown = rawAmountDue != null && rawAmountDue > 0L
        val amountDue = rawAmountDue ?: 0L
        val parsedDue = ManilaTime.parseDate(debt.nextDueDate)
        val isDated = parsedDue != null &&
          (debt.dateStatus == DueDateStatus.CONFIRMED ||
            debt.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
        val isOverdue = parsedDue != null && isDated && parsedDue.isBefore(today)

        results.add(
          BudgetDebtObligationItem(
            debtId = debt.id,
            installmentId = null,
            debtName = debt.name,
            providerName = provider,
            classification = debt.productClassification,
            dueDate = if (isDated) parsedDue else null,
            dueDateIso = if (parsedDue != null && isDated) ManilaTime.formatIso(parsedDue) else debt.nextDueDate,
            dateStatus = debt.dateStatus,
            installmentStatus = if (isOverdue) InstallmentStatus.OVERDUE else InstallmentStatus.UPCOMING,
            amountDueCents = amountDue,
            paidAmountCents = 0L,
            unpaidAmountCents = amountDue,
            isAmountKnown = isAmountKnown,
            isOverdue = isOverdue,
            isPendingVerification = false
          )
        )
      }
    }

    return results.sortedWith(
      compareBy<BudgetDebtObligationItem> { it.dueDate ?: LocalDate.MAX }
        .thenBy { it.debtName.lowercase() }
        .thenBy { it.debtId }
        .thenBy { it.installmentId ?: 0L }
    )
  }
}
