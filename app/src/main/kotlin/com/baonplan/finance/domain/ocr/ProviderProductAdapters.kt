package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
import java.util.Locale

data class ProviderAdapterResult(
  val providerProfile: ProviderProfile?,
  val productClassification: ExtractedField<LoanProductClassification>,
  val applicationProvider: ExtractedField<String>,
  val actualCreditor: ExtractedField<String>,
  val obligationRole: ObligationRole = ObligationRole.STANDALONE,
  val isRestructuredOrFlexi: Boolean = false,
  val hasDisclosureStatement: Boolean = false,
  val adapterNotes: List<String> = emptyList()
)

/**
 * Structured product-family interpretation adapters for Philippine digital lending,
 * BNPL, credit-line, and installment providers. Distinguishes product types within
 * the same app (e.g. GLoan vs GGives, BillEase Cash vs Installment vs Credit Line,
 * Maya Personal Loan vs Easy Credit, Shopee SPayLater vs SLoan, Atome Cash Swak
 * vs Atome Card, CIMB REVI Credit vs Converted Term Loan, Skyro Product/Cash/Flexi).
 */
object ProviderProductAdapters {

  private val disclosureRegex = Regex(
    """\b(?:disclosure\s+statement|truth\s+in\s+lending|loan\s+disclosure|amortization\s+schedule|loan\s+agreement)\b""",
    RegexOption.IGNORE_CASE
  )

  private val restructuredFlexiRegex = Regex(
    """\b(?:flexi|restructur(?:ed|ing)|re-?schedul(?:ed|ing)|modified\s+(?:schedule|terms)|new\s+schedule|payment\s+arrangement)\b""",
    RegexOption.IGNORE_CASE
  )

  private val convertedInstallmentRegex = Regex(
    """\b(?:convert(?:ed)?\s+to\s+installment|installment\s+conversion|converted\s+term\s+loan|convert\s+balance|split\s+into\s+\d+\s*(?:mos|months)|easy\s+installment\s+plan)\b""",
    RegexOption.IGNORE_CASE
  )

  private val billingStatementRegex = Regex(
    """\b(?:monthly\s+statement|billing\s+statement|statement\s+of\s+account|soa\b|statement\s+balance|statement\s+date|billing\s+cycle|statement\s+period)\b""",
    RegexOption.IGNORE_CASE
  )

  private val revolvingCueRegex = Regex(
    """\b(?:credit\s+line|credit\s+limit|available\s+credit|used\s+credit|revolving|minimum\s+(?:amount\s+)?due|reusable\s+limit|spendable\s+limit)\b""",
    RegexOption.IGNORE_CASE
  )

  fun classify(
    rows: List<NormalizedOcrRow>,
    rawFullText: String,
    screenshotIndex: Int
  ): ProviderAdapterResult {
    val combinedText = buildString {
      if (rawFullText.isNotBlank()) appendLine(rawFullText)
      rows.forEach { appendLine(it.text) }
    }
    val lower = combinedText.lowercase(Locale.US)
    val profile = PhilippineLoanProviderCatalog.detectProfile(combinedText)

    val hasDisclosure = disclosureRegex.containsMatchIn(combinedText)
    val isFlexiOrRestructured = restructuredFlexiRegex.containsMatchIn(combinedText)
    val isConverted = convertedInstallmentRegex.containsMatchIn(combinedText)
    val isBillingStatement = billingStatementRegex.containsMatchIn(combinedText)
    val hasRevolvingCues = revolvingCueRegex.containsMatchIn(combinedText)

    val notes = mutableListOf<String>()
    if (hasDisclosure) {
      notes.add("Disclosure Statement detected — contractual repayment terms take priority.")
    }
    if (isFlexiOrRestructured) {
      notes.add("Restructured / Flexi schedule detected — newer schedule terms supersede prior schedule.")
    }

    val productField: ExtractedField<LoanProductClassification> = when {
      isFlexiOrRestructured -> ExtractedField.extracted(
        value = LoanProductClassification.FLEXIBLE_OR_RESTRUCTURED_LOAN,
        sourceSnippet = findMatchingSnippet(rows, restructuredFlexiRegex),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Restructured or Flexi schedule terms detected in screenshot #${screenshotIndex + 1}."
      )

      isConverted -> ExtractedField.extracted(
        value = LoanProductClassification.CREDIT_BILL_INSTALLMENT,
        sourceSnippet = findMatchingSnippet(rows, convertedInstallmentRegex),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Converted installment plan detected in screenshot #${screenshotIndex + 1}."
      )

      profile?.id == "gcash_ggives" -> ExtractedField.extracted(
        value = LoanProductClassification.BNPL_INSTALLMENT,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "GGives merchant purchase installment financing detected."
      )

      profile?.id == "gcash_gloan" || profile?.id == "gcash_cash_loan" -> ExtractedField.extracted(
        value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "${profile.canonicalLender} individual cash loan detected."
      )

      profile?.id == "billease" -> classifyBillEase(rows, lower, hasRevolvingCues, isBillingStatement, screenshotIndex)

      profile?.id == "maya_credit" -> ExtractedField.extracted(
        value = LoanProductClassification.REVOLVING_CREDIT,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Maya Easy Credit revolving credit line detected."
      )

      profile?.id == "maya_personal_loan" -> ExtractedField.extracted(
        value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Maya Personal Loan fixed-term installment loan detected."
      )

      profile?.id == "shopee_spaylater" -> {
        val classification = if (isBillingStatement && !lower.contains("order #") && !lower.contains("order id")) {
          LoanProductClassification.BNPL_PAY_LATER
        } else {
          LoanProductClassification.BNPL_INSTALLMENT
        }
        ExtractedField.extracted(
          value = classification,
          sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
          screenshotIndex = screenshotIndex,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = if (classification == LoanProductClassification.BNPL_PAY_LATER) {
            "SPayLater monthly billing statement detected."
          } else {
            "SPayLater BNPL merchant purchase plan detected."
          }
        )
      }

      profile?.id == "shopee_sloan" -> ExtractedField.extracted(
        value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Shopee SLoan cash loan detected (kept distinct from SPayLater)."
      )

      profile?.id == "atome" -> classifyAtome(rows, lower, hasRevolvingCues, isBillingStatement, screenshotIndex)

      profile?.id == "cimb" -> classifyCimb(rows, lower, hasRevolvingCues, isBillingStatement, screenshotIndex)

      profile?.id == "home_credit" -> {
        val isMerchant = Regex("""\b(?:merchant|store|item|gadget|appliance|phone|down\s*payment|product\s+loan)\b""").containsMatchIn(lower)
        val cls = if (isMerchant) LoanProductClassification.BNPL_INSTALLMENT else LoanProductClassification.FIXED_INSTALLMENT_LOAN
        ExtractedField.extracted(
          value = cls,
          sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
          screenshotIndex = screenshotIndex,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Home Credit ${cls.label.lowercase(Locale.US)} detected."
        )
      }

      profile?.id == "skyro" -> classifySkyro(rows, lower, hasRevolvingCues, screenshotIndex)

      profile?.id == "tala" -> classifyTala(rows, lower, screenshotIndex)

      profile?.defaultProductClassification != null -> ExtractedField.extracted(
        value = profile.defaultProductClassification,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.first()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Matched ${profile.canonicalLender} product profile."
      )

      hasRevolvingCues -> ExtractedField.estimated(
        value = LoanProductClassification.REVOLVING_CREDIT,
        sourceSnippet = findMatchingSnippet(rows, revolvingCueRegex),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Credit limit / minimum due cues indicate a revolving credit line."
      )

      isBillingStatement -> ExtractedField.estimated(
        value = LoanProductClassification.BNPL_PAY_LATER,
        sourceSnippet = findMatchingSnippet(rows, billingStatementRegex),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Statement balance / billing cycle cues indicate a periodic statement."
      )

      else -> ExtractedField.notDetected(
        reason = "Product classification not explicitly stated — confirm below if needed."
      )
    }

    val role = when {
      isConverted || productField.value == LoanProductClassification.CREDIT_BILL_INSTALLMENT ->
        ObligationRole.CONVERTED_INSTALLMENT
      isBillingStatement && (
        productField.value == LoanProductClassification.BNPL_PAY_LATER ||
          (productField.value == LoanProductClassification.BNPL_INSTALLMENT && lower.contains("statement"))
        ) -> ObligationRole.BILLING_STATEMENT
      else -> ObligationRole.STANDALONE
    }

    val appProvider = profile?.applicationProviderName?.let { appName ->
      ExtractedField.extracted(
        value = appName,
        sourceSnippet = findMatchingSnippet(rows, profile.detectPatterns.firstOrNull()),
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Application platform identified as $appName."
      )
    } ?: ExtractedField.notDetected()

    val creditor = resolveActualCreditor(rows, profile, screenshotIndex)

    return ProviderAdapterResult(
      providerProfile = profile,
      productClassification = productField,
      applicationProvider = appProvider,
      actualCreditor = creditor,
      obligationRole = role,
      isRestructuredOrFlexi = isFlexiOrRestructured,
      hasDisclosureStatement = hasDisclosure,
      adapterNotes = notes
    )
  }

  private fun classifyBillEase(
    rows: List<NormalizedOcrRow>,
    lower: String,
    hasRevolvingCues: Boolean,
    isBillingStatement: Boolean,
    idx: Int
  ): ExtractedField<LoanProductClassification> {
    val snippet = findMatchingSnippet(rows, Regex("""\bbill\s*ease\b""", RegexOption.IGNORE_CASE))
    return when {
      Regex("""\bcash\s+loan\b|\bdisburs|\bnet\s+proceeds\b""").containsMatchIn(lower) ->
        ExtractedField.extracted(
          value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "BillEase Cash Loan transaction detected."
        )
      hasRevolvingCues || isBillingStatement ->
        ExtractedField.extracted(
          value = LoanProductClassification.REVOLVING_CREDIT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "BillEase Credit Line / statement cues detected — statement & minimum due kept separate."
        )
      Regex("""\bmerchant\b|\bshop\b|\bdown\s*payment\b|\border\b""").containsMatchIn(lower) ->
        ExtractedField.extracted(
          value = LoanProductClassification.BNPL_INSTALLMENT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "BillEase merchant installment plan detected."
        )
      else ->
        ExtractedField.extracted(
          value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.MEDIUM,
          verificationReason = "BillEase installment loan detected (confirm if Credit Line instead)."
        )
    }
  }

  private fun classifyAtome(
    rows: List<NormalizedOcrRow>,
    lower: String,
    hasRevolvingCues: Boolean,
    isBillingStatement: Boolean,
    idx: Int
  ): ExtractedField<LoanProductClassification> {
    val snippet = findMatchingSnippet(rows, Regex("""\batome\b""", RegexOption.IGNORE_CASE))
    return when {
      Regex("""\bcash\s+swak\b|\batome\s+cash\b""").containsMatchIn(lower) -> {
        val isMultiInstallment = Regex("""\b(?:2|3|4|6|9|12)\s*(?:installments?|months?|mos\.?)\b""").containsMatchIn(lower)
        val cls = if (isMultiInstallment) LoanProductClassification.FIXED_INSTALLMENT_LOAN else LoanProductClassification.SHORT_TERM_LUMP_SUM_LOAN
        ExtractedField.extracted(
          value = cls,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Atome Cash / Cash Swak short-term cash facility detected."
        )
      }
      Regex("""\batome\s+card\b""").containsMatchIn(lower) || hasRevolvingCues || isBillingStatement ->
        ExtractedField.extracted(
          value = LoanProductClassification.REVOLVING_CREDIT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Atome Card revolving statement/credit line detected."
        )
      else ->
        ExtractedField.extracted(
          value = LoanProductClassification.BNPL_INSTALLMENT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Atome BNPL purchase installment plan detected."
        )
    }
  }

  private fun classifyCimb(
    rows: List<NormalizedOcrRow>,
    lower: String,
    hasRevolvingCues: Boolean,
    isBillingStatement: Boolean,
    idx: Int
  ): ExtractedField<LoanProductClassification> {
    val snippet = findMatchingSnippet(rows, Regex("""\bcimb\b|\brevicredit\b|\brevict?\s*credit\b""", RegexOption.IGNORE_CASE))
    return when {
      Regex("""\bpersonal\s+loan\b|\bterm\s+loan\b|\bmonthly\s+installment\b""").containsMatchIn(lower) && !hasRevolvingCues ->
        ExtractedField.extracted(
          value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "CIMB fixed-term installment loan detected."
        )
      else ->
        ExtractedField.extracted(
          value = LoanProductClassification.REVOLVING_CREDIT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = if (hasRevolvingCues || isBillingStatement) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
          verificationReason = "CIMB REVI Credit revolving credit line detected."
        )
    }
  }

  private fun classifySkyro(
    rows: List<NormalizedOcrRow>,
    lower: String,
    hasRevolvingCues: Boolean,
    idx: Int
  ): ExtractedField<LoanProductClassification> {
    val snippet = findMatchingSnippet(rows, Regex("""\bskyro\b""", RegexOption.IGNORE_CASE))
    return when {
      Regex("""\bskyro\s*credit\b""").containsMatchIn(lower) || hasRevolvingCues ->
        ExtractedField.extracted(
          value = LoanProductClassification.REVOLVING_CREDIT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "SkyroCredit revolving credit line detected."
        )
      Regex("""\bproduct\s+loan\b|\bmerchant\b|\bstore\b|\bitem\b|\bappliance\b|\bphone\b""").containsMatchIn(lower) ->
        ExtractedField.extracted(
          value = LoanProductClassification.BNPL_INSTALLMENT,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Skyro Product Loan merchant installment detected."
        )
      else ->
        ExtractedField.extracted(
          value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
          sourceSnippet = snippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Skyro Cash Loan detected."
        )
    }
  }

  private fun classifyTala(
    rows: List<NormalizedOcrRow>,
    lower: String,
    idx: Int
  ): ExtractedField<LoanProductClassification> {
    val snippet = findMatchingSnippet(rows, Regex("""\btala\b""", RegexOption.IGNORE_CASE))
    val isMultiInstallment = Regex("""\b(?:installments?|weekly|bi-?weekly|2\s+payments?|3\s+payments?|4\s+payments?)\b""").containsMatchIn(lower)
    return if (isMultiInstallment) {
      ExtractedField.extracted(
        value = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
        sourceSnippet = snippet,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Tala multi-installment repayment plan detected."
      )
    } else {
      ExtractedField.extracted(
        value = LoanProductClassification.SHORT_TERM_LUMP_SUM_LOAN,
        sourceSnippet = snippet,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Tala short-term cash loan detected."
      )
    }
  }

  private fun resolveActualCreditor(
    rows: List<NormalizedOcrRow>,
    profile: ProviderProfile?,
    idx: Int
  ): ExtractedField<String> {
    val creditorLabelRegex = Regex(
      """\b(?:creditor|lending\s+partner|financed\s+by|underwritten\s+by|powered\s+by)\b""",
      RegexOption.IGNORE_CASE
    )
    for (row in rows) {
      if (creditorLabelRegex.containsMatchIn(row.text)) {
        val after = row.columns.drop(1).joinToString(" ").trim()
          .ifBlank { row.text.substringAfter(':', "").trim() }
        if (after.length in 3..60) {
          return ExtractedField.extracted(
            value = after,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Underlying creditor read from disclosure/partner line."
          )
        }
      }
    }
    val knownCreditor = profile?.defaultActualCreditor
    return if (!knownCreditor.isNullOrBlank()) {
      ExtractedField.estimated(
        value = knownCreditor,
        sourceSnippet = profile.canonicalLender,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Known lending entity for ${profile.canonicalLender}."
      )
    } else {
      ExtractedField.notDetected()
    }
  }

  private fun findMatchingSnippet(rows: List<NormalizedOcrRow>, regex: Regex?): String? {
    if (regex == null) return rows.firstOrNull()?.text
    return rows.firstOrNull { regex.containsMatchIn(it.text) }?.text ?: rows.firstOrNull()?.text
  }
}
