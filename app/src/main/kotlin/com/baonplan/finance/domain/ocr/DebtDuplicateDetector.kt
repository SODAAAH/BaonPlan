package com.baonplan.finance.domain.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.model.DebtStatus
import java.util.Locale
import kotlin.math.abs

/**
 * Pure Kotlin duplicate detector comparing a candidate import draft against existing Room records.
 * Avoids false positives for distinct loans from the same lender (e.g., two separate Shopee orders).
 */
object DebtDuplicateDetector {

  private val stopWords = setOf(
    "loan", "installment", "order", "plan", "cash", "credit", "bnpl", "pay", "manual", "entry"
  )

  fun findPotentialDuplicates(
    draft: DebtImportDraft,
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>
  ): List<DuplicateDebtMatch> {
    val targetBalance = draft.outstandingBalanceCents.value ?: draft.remainingInstallmentTotalCents.value
    val targetPrincipal = draft.originalPrincipalCents.value
    val targetInstallment = draft.installmentAmountCents.value
    val targetNextDate = draft.nextDueDateIso.value
    val targetScheduleDates = draft.installmentRows.mapNotNull { it.dueDateIso }.toSet()

    return findPotentialDuplicates(
      lender = draft.lender.value,
      loanName = draft.loanName.value,
      loanReferenceId = draft.loanReferenceId.value,
      remainingBalanceCents = targetBalance,
      originalPrincipalCents = targetPrincipal,
      installmentAmountCents = targetInstallment,
      nextDueDateIso = targetNextDate,
      scheduleDates = targetScheduleDates,
      existingDebts = existingDebts,
      existingInstallments = existingInstallments
    )
  }

  fun findPotentialDuplicates(
    lender: String?,
    loanName: String?,
    loanReferenceId: String?,
    remainingBalanceCents: Long?,
    originalPrincipalCents: Long?,
    installmentAmountCents: Long?,
    nextDueDateIso: String?,
    scheduleDates: Set<String> = emptySet(),
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>
  ): List<DuplicateDebtMatch> {
    if (existingDebts.isEmpty()) return emptyList()
    val installmentsByDebt = existingInstallments.groupBy { it.debtId }
    val matches = mutableListOf<DuplicateDebtMatch>()

    val normLender = normalize(lender.orEmpty())
    val normNameTokens = tokenize(loanName.orEmpty())
    val normRef = normalize(loanReferenceId.orEmpty())

    for (existing in existingDebts) {
      if (existing.status == DebtStatus.ARCHIVED) continue

      var score = 0
      val reasons = mutableListOf<String>()
      var nonLenderFinancialOrDateSignals = 0

      val exLender = normalize(existing.lender)
      val lenderMatch = normLender.isNotEmpty() && exLender.isNotEmpty() &&
        (normLender == exLender || normLender.contains(exLender) || exLender.contains(normLender))
      if (lenderMatch) {
        score += 2
        reasons.add("Same lender (${existing.lender})")
      }

      if (normRef.length >= 3) {
        val exNameAndNotes = normalize("${existing.name} ${existing.notes}")
        if (exNameAndNotes.contains(normRef)) {
          score += 5
          nonLenderFinancialOrDateSignals++
          reasons.add("Matches reference #$loanReferenceId")
        }
      }

      val exNameTokens = tokenize(existing.name)
      val nameJaccard = if (normNameTokens.isNotEmpty() && exNameTokens.isNotEmpty()) {
        val inter = normNameTokens.intersect(exNameTokens).size
        val union = normNameTokens.union(exNameTokens).size
        inter.toFloat() / union.toFloat()
      } else 0f

      if (nameJaccard >= 0.6f) {
        score += 3
        nonLenderFinancialOrDateSignals++
        reasons.add("Similar loan name (\"${existing.name}\")")
      }

      val exDates = buildSet {
        existing.nextDueDate?.let { add(it) }
        installmentsByDebt[existing.id].orEmpty().mapNotNull { it.dueDate }.forEach { add(it) }
      }

      val balanceMatch = remainingBalanceCents != null &&
        abs(remainingBalanceCents - existing.remainingBalanceCents) <= 100L &&
        !(remainingBalanceCents == 0L && existing.status != DebtStatus.PAID)
      if (balanceMatch) {
        score += 3
        nonLenderFinancialOrDateSignals++
        reasons.add("Matching remaining balance")
      }

      val principalMatch = originalPrincipalCents != null &&
        existing.originalPrincipalCents != null &&
        abs(originalPrincipalCents - existing.originalPrincipalCents) <= 100L
      if (principalMatch) {
        score += 2
        nonLenderFinancialOrDateSignals++
        reasons.add("Same original principal")
      }

      val installmentMatch = installmentAmountCents != null &&
        installmentAmountCents > 0L &&
        existing.installmentAmountCents > 0L &&
        abs(installmentAmountCents - existing.installmentAmountCents) <= 100L
      if (installmentMatch) {
        score += 2
        nonLenderFinancialOrDateSignals++
        reasons.add("Same per-payment amount")
      }

      val exactNextDateMatch = nextDueDateIso != null && existing.nextDueDate == nextDueDateIso
      val scheduleOverlapCount = if (scheduleDates.isNotEmpty()) exDates.intersect(scheduleDates).size else 0
      if (exactNextDateMatch || scheduleOverlapCount > 0) {
        score += 2
        nonLenderFinancialOrDateSignals++
        reasons.add(
          if (exactNextDateMatch) "Same next due date" else "$scheduleOverlapCount matching schedule date(s)"
        )
      }

      // Skip already-paid debts unless nearly identical historical loan
      if (existing.status == DebtStatus.PAID && !principalMatch && nameJaccard < 0.7f && score < 7) {
        continue
      }

      // Same lender alone (or weak single match) MUST NOT trigger duplicate warning for genuinely distinct loans
      if (nonLenderFinancialOrDateSignals >= 2 || (nonLenderFinancialOrDateSignals >= 1 && score >= 5)) {
        val confidence = if (score >= 6) DuplicateConfidence.HIGH else DuplicateConfidence.POSSIBLE
        matches.add(
          DuplicateDebtMatch(
            existingDebtId = existing.id,
            debtName = existing.name,
            lender = existing.lender,
            remainingBalanceCents = existing.remainingBalanceCents,
            nextDueDate = existing.nextDueDate,
            confidence = confidence,
            reasons = reasons
          )
        )
      }
    }

    return matches.sortedByDescending { if (it.confidence == DuplicateConfidence.HIGH) 2 else 1 }
  }

  private fun normalize(s: String): String =
    s.lowercase(Locale.US).replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

  private fun tokenize(s: String): Set<String> =
    normalize(s).split(' ').filter { it.length > 1 && it !in stopWords }.toSet()
}
