package com.baonplan.finance.data.local

import androidx.room.TypeConverter
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType

class RoomConverters {
  @TypeConverter fun fromRepaymentFrequency(v: RepaymentFrequency): String = v.name
  @TypeConverter fun toRepaymentFrequency(v: String): RepaymentFrequency = RepaymentFrequency.valueOf(v)

  @TypeConverter fun fromDueDateStatus(v: DueDateStatus): String = v.name
  @TypeConverter fun toDueDateStatus(v: String): DueDateStatus = DueDateStatus.valueOf(v)

  @TypeConverter fun fromDebtStatus(v: DebtStatus): String = v.name
  @TypeConverter fun toDebtStatus(v: String): DebtStatus = DebtStatus.valueOf(v)

  @TypeConverter fun fromInstallmentStatus(v: InstallmentStatus): String = v.name
  @TypeConverter fun toInstallmentStatus(v: String): InstallmentStatus = InstallmentStatus.valueOf(v)

  @TypeConverter fun fromTransactionType(v: TransactionType): String = v.name
  @TypeConverter fun toTransactionType(v: String): TransactionType = TransactionType.valueOf(v)

  @TypeConverter fun fromExpenseCategory(v: ExpenseCategory): String = v.name
  @TypeConverter fun toExpenseCategory(v: String): ExpenseCategory = ExpenseCategory.valueOf(v)

  @TypeConverter fun fromReserveType(v: ReserveType): String = v.name
  @TypeConverter fun toReserveType(v: String): ReserveType = ReserveType.valueOf(v)

  @TypeConverter fun fromReserveStatus(v: ReserveStatus): String = v.name
  @TypeConverter fun toReserveStatus(v: String): ReserveStatus = ReserveStatus.valueOf(v)
}
