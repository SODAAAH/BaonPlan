package com.baonplan.finance.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class LoanProductClassification(val label: String, val defaultCategory: String) {
  FIXED_INSTALLMENT_LOAN("Fixed installment loan", "Installment Loan"),
  BNPL_INSTALLMENT("BNPL installment", "BNPL"),
  BNPL_PAY_LATER("BNPL pay later", "BNPL"),
  REVOLVING_CREDIT("Revolving credit line", "Credit Line"),
  SHORT_TERM_LUMP_SUM_LOAN("Short-term lump sum", "Cash Loan"),
  CREDIT_BILL_INSTALLMENT("Credit bill installment", "Card Installment"),
  FLEXIBLE_OR_RESTRUCTURED_LOAN("Flexible / restructured loan", "Flexible Loan"),
  OTHER("Other", "Other"),
  UNKNOWN("Unknown", "Loan")
}

@Serializable
enum class ObligationRole(val label: String, val countsInTotalDebt: Boolean) {
  STANDALONE("Standalone obligation", true),
  UNDERLYING_OBLIGATION("Underlying purchase / loan", true),
  BILLING_STATEMENT("Billing statement (linked)", false),
  CONVERTED_INSTALLMENT("Converted installment plan (linked)", false)
}

@Serializable
enum class RepaymentFrequency(val label: String) {
  DAILY("Daily"),
  WEEKLY("Weekly"),
  EVERY_14_DAYS("Every 14 days"),
  TWICE_MONTHLY("Twice monthly"),
  MONTHLY("Monthly"),
  QUARTERLY("Quarterly"),
  ONE_TIME("Lump sum"),
  CUSTOM_DAYS("Custom interval"),
  IRREGULAR("Custom / irregular"),
  UNKNOWN("Unknown")
}

@Serializable
enum class DueDateStatus(val label: String) {
  CONFIRMED("Confirmed"),
  ESTIMATED_REQUIRES_CONFIRMATION("Estimated"),
  UNCONFIRMED("Unconfirmed"),
  UNKNOWN("Unknown")
}

@Serializable
enum class DebtStatus(val label: String) {
  ACTIVE("Active"),
  PAID("Paid"),
  ARCHIVED("Archived")
}

@Serializable
enum class InstallmentStatus(val label: String) {
  UPCOMING("Upcoming"),
  UNPAID("Unpaid"),
  PENDING("Pending"),
  PARTIAL("Partial"),
  PAID("Paid"),
  OVERDUE("Overdue"),
  SKIPPED("Skipped"),
  REFUNDED("Refunded"),
  REVERSED("Reversed"),
  DISPUTED("Disputed"),
  UNKNOWN("Unknown");

  val isPayableInstallment: Boolean
    get() = this == UPCOMING ||
      this == UNPAID ||
      this == PENDING ||
      this == PARTIAL ||
      this == OVERDUE ||
      this == UNKNOWN
}

@Serializable
enum class TransactionType(val label: String) {
  ALLOWANCE("Allowance"),
  EXPENSE("Expense"),
  DEBT_REPAYMENT("Debt Repayment"),
  DEBT_RESERVE("Debt Reserve"),
  EMERGENCY_SAVINGS("Emergency Savings"),
  ADJUSTMENT("Adjustment")
}

@Serializable
enum class ExpenseCategory(val label: String) {
  SCHOOL_FOOD("School Food"),
  TRANSPORT("Transport"),
  SCHOOL_SUPPLIES("School Supplies"),
  PERSONAL("Personal"),
  EMERGENCY("Emergency"),
  OTHER("Other")
}

@Serializable
enum class ReserveType(val label: String) {
  DEBT_REPAYMENT_RESERVE("Debt Reserve"),
  EMERGENCY_SAVINGS("Emergency Savings")
}

@Serializable
enum class ReserveStatus(val label: String) {
  RESERVED("Reserved"),
  APPLIED("Applied to Payment"),
  RELEASED("Released")
}

@Serializable
enum class ThemeMode(val label: String) {
  SYSTEM("System default"),
  LIGHT("Light mode"),
  DARK("Dark mode")
}

enum class PaymentUrgency(val label: String) {
  OVERDUE("Overdue"),
  DUE_TODAY("Due Today"),
  DUE_TOMORROW("Due Tomorrow"),
  UPCOMING("Upcoming"),
  UNCONFIRMED("Unconfirmed"),
  UNKNOWN("Unknown")
}
