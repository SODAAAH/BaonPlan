package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.LoanProductClassification
import java.util.Locale

data class ProviderProfile(
  val id: String,
  val canonicalLender: String,
  val applicationProviderName: String? = null,
  val defaultActualCreditor: String? = null,
  val defaultLoanType: String?,
  val defaultProductClassification: LoanProductClassification? = null,
  val detectPatterns: List<Regex>,
  val extraNoisePhrases: List<String> = emptyList(),
  val fieldSynonyms: Map<LoanFieldKey, List<String>> = emptyMap(),
  val preferredNumericDateOrder: NumericDateOrder = NumericDateOrder.AMBIGUOUS,
  val singlePaymentCardHint: Boolean = false
)

/**
 * Adaptable interpretation rules for common Philippine digital lenders, credit lines,
 * and BNPL apps. Enhances field disambiguation, product classification, and non-loan
 * UI noise filtering without relying on brittle screen pixel assumptions.
 */
object PhilippineLoanProviderCatalog {

  val profiles: List<ProviderProfile> = listOf(
    ProviderProfile(
      id = "shopee_spaylater",
      canonicalLender = "Shopee (SPayLater)",
      applicationProviderName = "Shopee",
      defaultActualCreditor = "SeaMoney (Credit) Finance Philippines, Inc.",
      defaultLoanType = "BNPL",
      defaultProductClassification = LoanProductClassification.BNPL_INSTALLMENT,
      detectPatterns = listOf(Regex("""\bspay\s*later\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf(
        "spaylater limit", "total credit limit", "remaining limit",
        "shopee coins", "voucher", "checkout"
      ),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf(
          "current bill", "bill for", "amount to pay", "total bill due", "this month bill", "upcoming bill"
        ),
        LoanFieldKey.STATEMENT_BALANCE to listOf(
          "statement balance", "billed amount", "monthly bill"
        ),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf(
          "total unpaid", "unpaid total", "remaining unpaid", "outstanding unpaid"
        ),
        LoanFieldKey.ORIGINAL_PRINCIPAL to listOf(
          "order total", "purchase amount", "financed amount"
        )
      )
    ),
    ProviderProfile(
      id = "shopee_sloan",
      canonicalLender = "Shopee (SLoan)",
      applicationProviderName = "Shopee",
      defaultActualCreditor = "SeaMoney (Credit) Finance Philippines, Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bsloan\b|\bs-?loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("sloan limit", "max loanable"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current due", "this month payment"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("loan balance", "remaining balance", "total unpaid"),
        LoanFieldKey.SERVICE_FEE to listOf("admin fee", "monthly admin fee")
      )
    ),
    ProviderProfile(
      id = "billease",
      canonicalLender = "BillEase",
      applicationProviderName = "BillEase",
      defaultActualCreditor = "First Digital Finance Corporation",
      defaultLoanType = "Installment Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bbill\s*ease\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("boost limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.OUTSTANDING_BALANCE to listOf(
          "contract balance", "total remaining balance", "remaining contract balance", "payoff amount"
        ),
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf(
          "due this cycle", "current due", "amount due now"
        ),
        LoanFieldKey.MINIMUM_DUE to listOf("minimum amount due", "minimum due"),
        LoanFieldKey.STATEMENT_BALANCE to listOf("statement balance"),
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf(
          "installment due", "scheduled installment", "regular installment", "per installment"
        )
      )
    ),
    ProviderProfile(
      id = "gcash_gloan",
      canonicalLender = "GCash (GLoan)",
      applicationProviderName = "GCash",
      defaultActualCreditor = "Fuse Lending Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bg\s*loan\b|\bgloan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("pre-approved", "up to", "gscore", "wallet balance"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current due", "total due now", "total billed amount", "billed amount"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining balance", "loan balance", "total unpaid balance"),
        LoanFieldKey.AMOUNT_RECEIVED to listOf("net proceeds", "disbursed to gcash", "credited to gcash"),
        LoanFieldKey.OTHER_FEES to listOf("doc stamp", "documentary stamp", "dst")
      )
    ),
    ProviderProfile(
      id = "gcash_ggives",
      canonicalLender = "GCash (GGives)",
      applicationProviderName = "GCash",
      defaultActualCreditor = "Fuse Lending Inc.",
      defaultLoanType = "Installment Loan",
      defaultProductClassification = LoanProductClassification.BNPL_INSTALLMENT,
      detectPatterns = listOf(Regex("""\bg\s*gives\b|\bggives\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("gscore", "available balance", "wallet balance", "ggives limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount due now", "current payment due"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining loan balance", "remaining balance")
      )
    ),
    ProviderProfile(
      id = "gcash_cash_loan",
      canonicalLender = "GCash (Cash Loan)",
      applicationProviderName = "GCash",
      defaultActualCreditor = "Fuse Lending Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(
        Regex("""\bgcash\b.*\bcash\s+loan\b|\bcash\s+loan\b.*\bgcash\b""", RegexOption.IGNORE_CASE)
      ),
      extraNoisePhrases = listOf("wallet balance", "gscore"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current bill", "due now"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining balance", "loan balance", "principal balance")
      )
    ),
    ProviderProfile(
      id = "maya_credit",
      canonicalLender = "Maya (Easy Credit)",
      applicationProviderName = "Maya",
      defaultActualCreditor = "Maya Bank, Inc.",
      defaultLoanType = "Credit Line",
      defaultProductClassification = LoanProductClassification.REVOLVING_CREDIT,
      detectPatterns = listOf(Regex("""\bmaya\s+(?:easy\s+)?credit\b|\beasy\s+credit\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("maya wallet", "available balance"),
      fieldSynonyms = mapOf(
        LoanFieldKey.STATEMENT_BALANCE to listOf("statement balance", "total amount due", "current bill"),
        LoanFieldKey.MINIMUM_DUE to listOf("minimum amount due", "minimum due"),
        LoanFieldKey.USED_CREDIT to listOf("used credit", "credit used"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("outstanding balance", "principal balance"),
        LoanFieldKey.ORIGINAL_PRINCIPAL to listOf("amount availed", "transferred amount", "principal availed"),
        LoanFieldKey.OTHER_FEES to listOf("doc stamp", "documentary stamp", "dst")
      ),
      singlePaymentCardHint = true
    ),
    ProviderProfile(
      id = "maya_personal_loan",
      canonicalLender = "Maya (Personal Loan)",
      applicationProviderName = "Maya",
      defaultActualCreditor = "Maya Bank, Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bmaya\s+(?:personal\s+)?loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("maya wallet", "savings balance"),
      fieldSynonyms = mapOf(
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf("monthly amortization", "monthly installment", "amortization"),
        LoanFieldKey.OTHER_FEES to listOf("dst", "documentary stamp", "notarial fee")
      )
    ),
    ProviderProfile(
      id = "atome",
      canonicalLender = "Atome",
      applicationProviderName = "Atome",
      defaultLoanType = "BNPL",
      defaultProductClassification = LoanProductClassification.BNPL_INSTALLMENT,
      detectPatterns = listOf(Regex("""\batome\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("spend limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("this month due", "upcoming bill", "bill due"),
        LoanFieldKey.STATEMENT_BALANCE to listOf("statement balance"),
        LoanFieldKey.MINIMUM_DUE to listOf("minimum due", "minimum amount due"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("total remaining", "remaining unpaid")
      )
    ),
    ProviderProfile(
      id = "home_credit",
      canonicalLender = "Home Credit",
      applicationProviderName = "Home Credit",
      defaultActualCreditor = "HC Consumer Finance Philippines, Inc.",
      defaultLoanType = "Installment Loan",
      defaultProductClassification = LoanProductClassification.BNPL_INSTALLMENT,
      detectPatterns = listOf(Regex("""\bhome\s+credit\b|\bhc\s+loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("qwarta limit", "available offer"),
      fieldSynonyms = mapOf(
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("early payoff", "remaining balance", "outstanding balance"),
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf("monthly payment", "monthly installment")
      ),
      preferredNumericDateOrder = NumericDateOrder.DAY_FIRST
    ),
    ProviderProfile(
      id = "skyro",
      canonicalLender = "Skyro",
      applicationProviderName = "Skyro",
      defaultActualCreditor = "Advanced Finance Solutions, Inc.",
      defaultLoanType = "Installment Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bskyro\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("skyro offer", "pre-qualified"),
      fieldSynonyms = mapOf(
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining balance", "outstanding balance", "payoff amount"),
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf("monthly payment", "monthly installment", "installment amount"),
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount due", "current due", "minimum due")
      )
    ),
    ProviderProfile(
      id = "tala",
      canonicalLender = "Tala",
      applicationProviderName = "Tala",
      defaultActualCreditor = "Tala Financing Philippines Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.SHORT_TERM_LUMP_SUM_LOAN,
      detectPatterns = listOf(Regex("""\btala\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("your limit", "tala limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.TOTAL_REPAYMENT to listOf("total repayment", "total amount due back", "repay total")
      )
    ),
    ProviderProfile(
      id = "juanhand",
      canonicalLender = "JuanHand",
      applicationProviderName = "JuanHand",
      defaultActualCreditor = "WeFund Lending Corp.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bjuan\s*hand\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("max amount"),
      fieldSynonyms = mapOf(
        LoanFieldKey.TOTAL_REPAYMENT to listOf("repayment amount", "total payable", "amount to repay")
      )
    ),
    ProviderProfile(
      id = "lazada_lazpaylater",
      canonicalLender = "Lazada (LazPayLater)",
      applicationProviderName = "Lazada",
      defaultLoanType = "BNPL",
      defaultProductClassification = LoanProductClassification.BNPL_INSTALLMENT,
      detectPatterns = listOf(Regex("""\blaz\s*pay\s*later\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("lazwallet")
    ),
    ProviderProfile(
      id = "seabank",
      canonicalLender = "SeaBank",
      applicationProviderName = "SeaBank",
      defaultActualCreditor = "SeaBank Philippines Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bseabank\b|\bsea\s*bank\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("savings balance")
    ),
    ProviderProfile(
      id = "tonik",
      canonicalLender = "Tonik",
      applicationProviderName = "Tonik",
      defaultActualCreditor = "Tonik Digital Bank, Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\btonik\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("stash balance")
    ),
    ProviderProfile(
      id = "cimb",
      canonicalLender = "CIMB",
      applicationProviderName = "CIMB",
      defaultActualCreditor = "CIMB Bank Philippines Inc.",
      defaultLoanType = "Credit Line",
      defaultProductClassification = LoanProductClassification.REVOLVING_CREDIT,
      detectPatterns = listOf(Regex("""\bcimb\b|\brevict?\s*credit\b|\brevicredit\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("fast plus", "upsave", "gsave"),
      fieldSynonyms = mapOf(
        LoanFieldKey.STATEMENT_BALANCE to listOf("statement balance", "total statement balance"),
        LoanFieldKey.MINIMUM_DUE to listOf("minimum amount due", "minimum due"),
        LoanFieldKey.USED_CREDIT to listOf("used credit", "utilized limit")
      )
    ),
    ProviderProfile(
      id = "cashalo",
      canonicalLender = "Cashalo",
      applicationProviderName = "Cashalo",
      defaultActualCreditor = "Paloo Financing Inc.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
      detectPatterns = listOf(Regex("""\bcashalo\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available limit")
    ),
    ProviderProfile(
      id = "digido",
      canonicalLender = "Digido",
      applicationProviderName = "Digido",
      defaultActualCreditor = "Digido Finance Corp.",
      defaultLoanType = "Cash Loan",
      defaultProductClassification = LoanProductClassification.SHORT_TERM_LUMP_SUM_LOAN,
      detectPatterns = listOf(Regex("""\bdigido\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available limit", "first loan")
    ),
    // Generic parent providers checked AFTER product-specific rules
    ProviderProfile(
      id = "gcash_generic",
      canonicalLender = "GCash",
      applicationProviderName = "GCash",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bgcash\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("wallet balance", "available balance")
    ),
    ProviderProfile(
      id = "maya_generic",
      canonicalLender = "Maya",
      applicationProviderName = "Maya",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bpaymaya\b|\bmaya\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("maya wallet")
    ),
    ProviderProfile(
      id = "shopee_generic",
      canonicalLender = "Shopee",
      applicationProviderName = "Shopee",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bshopee\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("shopee pay", "shopeepay balance", "shopee coins")
    ),
    ProviderProfile(
      id = "lazada_generic",
      canonicalLender = "Lazada",
      applicationProviderName = "Lazada",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\blazada\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("lazwallet")
    )
  )

  fun detectProfile(fullText: String): ProviderProfile? {
    val lower = fullText.lowercase(Locale.US)
    if (lower.contains("gcash") && Regex("""\bcash\s+loan\b""").containsMatchIn(lower) &&
      !Regex("""\bg\s*loan\b|\bgloan\b|\bg\s*gives\b|\bggives\b""").containsMatchIn(lower)
    ) {
      return byId("gcash_cash_loan")
    }
    return profiles.firstOrNull { profile ->
      profile.detectPatterns.any { it.containsMatchIn(fullText) }
    }
  }

  fun byId(id: String?): ProviderProfile? =
    if (id.isNullOrBlank()) null else profiles.firstOrNull { it.id == id }
}
