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

@Serializable
enum class SourceDocumentKind(val label: String, val contractualRank: Int, val paymentStatusRank: Int) {
  LOAN_AGREEMENT("Loan agreement", contractualRank = 1, paymentStatusRank = 5),
  DISCLOSURE_STATEMENT("Disclosure statement", contractualRank = 1, paymentStatusRank = 5),
  AMORTIZATION_SCHEDULE("Amortization schedule", contractualRank = 2, paymentStatusRank = 3),
  RESTRUCTURING_NOTICE("Revised / restructuring notice", contractualRank = 1, paymentStatusRank = 3),
  BILLING_STATEMENT("Billing / loan statement", contractualRank = 4, paymentStatusRank = 1),
  PAYMENT_RECEIPT("Payment receipt / confirmation", contractualRank = 6, paymentStatusRank = 2),
  APP_SCHEDULE_SCREENSHOT("App schedule screenshot", contractualRank = 5, paymentStatusRank = 4),
  APP_SUMMARY_SCREENSHOT("App summary screenshot", contractualRank = 5, paymentStatusRank = 4),
  UNKNOWN_DOCUMENT("Uploaded document", contractualRank = 7, paymentStatusRank = 6)
}

@Serializable
enum class ScheduleCompletenessStatus(val label: String) {
  COMPLETE_SCHEDULE("Complete schedule"),
  COMPLETE_CONTRACTUAL("Complete contractual schedule"),
  RECONSTRUCTED_MULTI_SOURCE("Reconstructed from multiple sources"),
  PARTIAL_SCHEDULE("Partial schedule"),
  PARTIALLY_RECONSTRUCTED("Partially reconstructed"),
  SUMMARY_ONLY_NO_SCHEDULE("Summary only"),
  PROJECTION_ONLY("Projection only"),
  REVOLVING_NO_FIXED_PAYOFF("Revolving (no fixed payoff)"),
  NEEDS_USER_REVIEW("Needs user review")
}

@Serializable
enum class ProjectionReliability(val label: String) {
  CONFIRMED_BY_SCHEDULE("Confirmed by schedule"),
  HIGH("High reliability"),
  MODERATE("Moderate reliability"),
  ESTIMATED_FROM_PARTIAL("Estimated from partial schedule"),
  LOW("Low reliability"),
  NOT_APPLICABLE_REVOLVING("Not applicable (revolving)"),
  INSUFFICIENT_DATA("Insufficient schedule data")
}

@Serializable
enum class InterestCalculationMethod(val label: String) {
  FIXED_AMORTIZED("Declining balance / amortized"),
  REDUCING_BALANCE("Reducing / declining balance"),
  EQUAL_PRINCIPAL("Equal principal"),
  FLAT_RATE("Flat / add-on interest"),
  FLAT_ADD_ON("Flat / add-on interest"),
  DAILY_SIMPLE("Daily simple interest"),
  DAILY_INTEREST("Daily simple interest"),
  LUMP_SUM_FEE_BASED("Lump-sum fee-based"),
  INTEREST_ONLY_BALLOON("Interest-only / balloon"),
  ZERO_INTEREST("0% interest"),
  UNKNOWN("Not specified")
}

@Serializable
enum class PrepaymentRuleType(val label: String) {
  REBATE_UNEARNED_INTEREST("Unearned interest rebate"),
  NO_PENALTY_REMAINING_PRINCIPAL("No prepayment penalty"),
  NO_EARLY_SETTLEMENT_REBATE("Full scheduled amount due"),
  FULL_CONTRACT_INTEREST_DUE("Full scheduled interest due"),
  PREPAYMENT_PENALTY("Prepayment fee applies"),
  EARLY_TERMINATION_FEE("Early termination fee applies"),
  UNKNOWN("Not specified in contract")
}
