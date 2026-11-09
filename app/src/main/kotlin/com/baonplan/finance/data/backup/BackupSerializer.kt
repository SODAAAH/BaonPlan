package com.baonplan.finance.data.backup

import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Serializable
data class BaonPlanBackupPayload(
  val schemaVersion: Int = 2,
  val appId: String = "com.baonplan.finance",
  val currency: String = "PHP",
  val timezone: String = "Asia/Manila",
  val exportedAtIso: String = Instant.now().toString(),
  val settings: UserSettingsEntity,
  val debts: List<DebtEntity>,
  val installments: List<InstallmentEntity>,
  val transactions: List<TransactionEntity>,
  val allowanceRecords: List<AllowanceRecordEntity>,
  val expenses: List<ExpenseEntity>,
  val debtReserves: List<DebtReserveEntity>,
  val debtAttachments: List<DebtAttachmentEntity> = emptyList()
)

object BackupSerializer {
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun encodeBackupJson(payload: BaonPlanBackupPayload): String =
    json.encodeToString(BaonPlanBackupPayload.serializer(), payload)

  fun decodeBackupJson(raw: String): Result<BaonPlanBackupPayload> = runCatching {
    val payload = json.decodeFromString(BaonPlanBackupPayload.serializer(), raw)
    require(payload.schemaVersion >= 1) { "Unsupported backup schema version: ${payload.schemaVersion}" }
    require(payload.appId == "com.baonplan.finance") { "Backup file does not belong to BaonPlan." }
    payload
  }

  fun encodeTransactionsCsv(transactions: List<TransactionEntity>): String {
    val sb = StringBuilder()
    sb.appendLine(
      "id,date,type,title,amount_php,amount_centavos,category,debt_id,installment_id,funded_from_reserve_php,is_voided,is_corrected,notes"
    )
    for (tx in transactions) {
      val amountPhp = BigDecimal(tx.amountCents).divide(BigDecimal(100), 2, RoundingMode.UNNECESSARY).toPlainString()
      val reservePhp = BigDecimal(tx.fundedFromReserveCents).divide(BigDecimal(100), 2, RoundingMode.UNNECESSARY).toPlainString()
      val row = listOf(
        tx.id.toString(),
        csvCell(tx.date),
        csvCell(tx.type.name),
        csvCell(tx.title),
        amountPhp,
        tx.amountCents.toString(),
        csvCell(tx.category),
        tx.debtId?.toString() ?: "",
        tx.installmentId?.toString() ?: "",
        reservePhp,
        tx.isVoided.toString(),
        tx.isCorrected.toString(),
        csvCell(tx.notes)
      ).joinToString(",")
      sb.appendLine(row)
    }
    return sb.toString()
  }

  private fun csvCell(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.contains(',') || escaped.contains('"') || escaped.contains('\n') || escaped.contains('\r')) {
      "\"$escaped\""
    } else {
      escaped
    }
  }
}
