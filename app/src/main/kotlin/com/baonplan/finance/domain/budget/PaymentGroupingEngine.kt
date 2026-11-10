package com.baonplan.finance.domain.budget

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object PaymentGroupingEngine {

  const val DEFAULT_GROUP_WINDOW_DAYS: Long = 3L

  private val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
  private val dayOnlyFormatter = DateTimeFormatter.ofPattern("d", Locale.US)
  private val fullShortFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

  /**
   * Builds grouped upcoming payments sorted by due date with non-chained window grouping
   * (`[anchor, anchor.plusDays(windowDays)]`), clean overdue separation, incomplete amount honesty,
   * and compact preview groups.
   */
  fun buildGroupedPayments(
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    today: LocalDate,
    windowDays: Long = DEFAULT_GROUP_WINDOW_DAYS
  ): PaymentGroupingOverview {
    val obligations = ObligationExtractor.extractPayableObligations(
      debts = debts,
      installments = installments,
      today = today
    )

    val allItems = obligations.map { ob ->
      GroupedPaymentItem(
        debtId = ob.debtId,
        installmentId = ob.installmentId,
        debtName = ob.debtName,
        providerName = ob.providerName,
        classification = ob.classification,
        dueDate = ob.dueDate,
        dueDateIso = ob.dueDateIso,
        dateStatus = ob.dateStatus,
        installmentStatus = ob.installmentStatus,
        unpaidAmountCents = if (ob.isAmountKnown) ob.unpaidAmountCents else null,
        totalDueCents = if (ob.isAmountKnown) ob.amountDueCents else null,
        paidAmountCents = ob.paidAmountCents,
        isAmountKnown = ob.isAmountKnown,
        isEstimatedDate = ob.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
        isPending = ob.isPendingVerification,
        isOverdue = ob.isOverdue
      )
    }

    val undated = allItems.filter { it.dueDate == null }
    val dated = allItems.filter { it.dueDate != null }

    val overdueItems = dated
      .filter { it.isOverdue }
      .sortedWith(
        compareBy<GroupedPaymentItem> { it.dueDate }
          .thenBy { it.debtName.lowercase() }
          .thenBy { it.debtId }
          .thenBy { it.installmentId ?: 0L }
      )

    val upcomingDatedItems = dated
      .filter { !it.isOverdue && !it.dueDate!!.isBefore(today) }
      .sortedWith(
        compareBy<GroupedPaymentItem> { it.dueDate }
          .thenBy { it.debtName.lowercase() }
          .thenBy { it.debtId }
          .thenBy { it.installmentId ?: 0L }
      )

    val overdueGroup = if (overdueItems.isNotEmpty()) {
      createGroup(items = overdueItems, isOverdue = true, today = today)
    } else {
      null
    }

    val upcomingGroups = groupSortedUpcomingItems(
      sortedItems = upcomingDatedItems,
      today = today,
      windowDays = windowDays.coerceAtLeast(0L)
    )

    val nextGroup = upcomingGroups.firstOrNull()
    val followingGroups = if (upcomingGroups.size > 1) upcomingGroups.drop(1) else emptyList()

    return PaymentGroupingOverview(
      overdueGroup = overdueGroup,
      nextGroup = nextGroup,
      followingGroups = followingGroups,
      undatedOrUnconfirmedPayments = undated,
      hasAnyActionablePayments = overdueGroup != null || nextGroup != null || undated.isNotEmpty()
    )
  }

  /**
   * Deterministic non-chained 3-day grouping anchored to the first due date in each group:
   * items join the current group if `item.dueDate <= groupAnchor.plusDays(windowDays)`.
   */
  private fun groupSortedUpcomingItems(
    sortedItems: List<GroupedPaymentItem>,
    today: LocalDate,
    windowDays: Long
  ): List<PaymentDueGroup> {
    if (sortedItems.isEmpty()) return emptyList()

    val groups = mutableListOf<PaymentDueGroup>()
    var currentItems = mutableListOf<GroupedPaymentItem>()
    var groupAnchor: LocalDate? = null

    for (item in sortedItems) {
      val date = item.dueDate ?: continue
      val anchor = groupAnchor
      if (anchor == null) {
        groupAnchor = date
        currentItems.add(item)
      } else {
        val cutoff = anchor.plusDays(windowDays)
        if (!date.isAfter(cutoff)) {
          currentItems.add(item)
        } else {
          groups.add(createGroup(currentItems, isOverdue = false, today = today))
          currentItems = mutableListOf(item)
          groupAnchor = date
        }
      }
    }
    if (currentItems.isNotEmpty()) {
      groups.add(createGroup(currentItems, isOverdue = false, today = today))
    }
    return groups
  }

  private fun createGroup(
    items: List<GroupedPaymentItem>,
    isOverdue: Boolean,
    today: LocalDate
  ): PaymentDueGroup {
    val dates = items.mapNotNull { it.dueDate }.sorted()
    val startDate = dates.first()
    val endDate = dates.last()
    val isSingleDate = startDate == endDate
    val rangeLabel = formatDateRange(startDate, endDate)

    val countdownLabel = if (isOverdue) {
      val oldestOverdueDays = ChronoUnit.DAYS.between(startDate, today).coerceAtLeast(1L)
      if (oldestOverdueDays == 1L) "Overdue by 1 day" else "Overdue by $oldestOverdueDays days"
    } else if (isSingleDate) {
      val delta = ChronoUnit.DAYS.between(today, startDate)
      when {
        delta <= 0L -> "Due today"
        delta == 1L -> "Due tomorrow"
        else -> "Due in $delta days"
      }
    } else {
      val startDelta = ChronoUnit.DAYS.between(today, startDate)
      when {
        startDelta <= 0L -> "Due today – ${endDate.format(monthDayFormatter)}"
        startDelta == 1L -> "Starts tomorrow"
        else -> "Due in $startDelta days"
      }
    }

    val headlineLabel = when {
      isOverdue -> if (isSingleDate) "Overdue · $rangeLabel" else "Overdue · $rangeLabel"
      isSingleDate -> when (ChronoUnit.DAYS.between(today, startDate)) {
        0L -> "Due today"
        1L -> "Due tomorrow"
        else -> "Due $rangeLabel"
      }
      else -> "Due $rangeLabel"
    }

    val knownTotal = MoneyFormatter.sumCentsExact(
      items.mapNotNull { it.unpaidAmountCents }
    )
    val distinctLoans = items.map { it.debtId }.distinct().size

    return PaymentDueGroup(
      startDate = startDate,
      endDate = endDate,
      isSingleDate = isSingleDate,
      isOverdueGroup = isOverdue,
      dateRangeLabel = rangeLabel,
      headlineLabel = headlineLabel,
      countdownLabel = countdownLabel,
      items = items,
      knownTotalCents = knownTotal,
      totalPaymentsCount = items.size,
      distinctLoansCount = distinctLoans,
      hasIncompleteAmounts = items.any { !it.isAmountKnown },
      hasEstimatedDates = items.any { it.isEstimatedDate },
      hasPendingPayments = items.any { it.isPending },
      primaryDebtId = items.first().debtId
    )
  }

  fun formatDateRange(startDate: LocalDate, endDate: LocalDate): String {
    if (startDate == endDate) {
      return startDate.format(monthDayFormatter)
    }
    return when {
      startDate.year == endDate.year && startDate.month == endDate.month ->
        "${startDate.format(monthDayFormatter)}–${endDate.format(dayOnlyFormatter)}"
      startDate.year == endDate.year ->
        "${startDate.format(monthDayFormatter)}–${endDate.format(monthDayFormatter)}"
      else ->
        "${startDate.format(fullShortFormatter)}–${endDate.format(fullShortFormatter)}"
    }
  }
}
