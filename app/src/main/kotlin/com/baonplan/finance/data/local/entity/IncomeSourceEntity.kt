package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.TwiceMonthlyPattern
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "income_sources")
data class IncomeSourceEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val name: String,
  val frequency: IncomeFrequency = IncomeFrequency.WEEKLY,
  val expectedAmountCents: Long,
  val isVariableAmount: Boolean = false,
  val startDateIso: String,
  val endDateIso: String? = null,
  val customIntervalDays: Int? = null,
  val customIntervalMonths: Int? = null,
  val twiceMonthlyPattern: TwiceMonthlyPattern = TwiceMonthlyPattern.FIRST_AND_FIFTEENTH,
  val twiceMonthlyDay1: Int? = null,
  val twiceMonthlyDay2: Int? = null,
  val customDatesCsv: String = "",
  val anchorDayOfMonth: Int? = null,
  val isActive: Boolean = true,
  val isPaused: Boolean = false,
  val pausedFromDateIso: String? = null,
  val effectiveFromDateIso: String? = null,
  val isPrimary: Boolean = false,
  val notes: String = "",
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
