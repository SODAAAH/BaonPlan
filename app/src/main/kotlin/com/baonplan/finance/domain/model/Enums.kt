package com.baonplan.finance.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RepaymentFrequency(val label: String) {
  WEEKLY("Weekly"),
  EVERY_14_DAYS("Every 14 days"),
  MONTHLY("Monthly"),
  ONE_TIME("One-time"),
  CUSTOM_DAYS("Custom interval"),
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
  PARTIAL("Partial"),
  PAID("Paid"),
  SKIPPED("Skipped")
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
