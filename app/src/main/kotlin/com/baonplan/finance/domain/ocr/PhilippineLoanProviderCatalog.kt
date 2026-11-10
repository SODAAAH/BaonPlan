package com.baonplan.finance.domain.ocr

import java.util.Locale

data class ProviderProfile(
  val id: String,
  val canonicalLender: String,
  val defaultLoanType: String?,
  val detectPatterns: List<Regex>,
  val extraNoisePhrases: List<String> = emptyList(),
  val fieldSynonyms: Map<LoanFieldKey, List<String>> = emptyMap(),
  val preferredNumericDateOrder: NumericDateOrder = NumericDateOrder.AMBIGUOUS,
  val singlePaymentCardHint: Boolean = false
)

/**
 * Adaptable interpretation rules for common Philippine digital lenders and BNPL apps.
 * Enhances field disambiguation and non-loan UI noise filtering without relying on
 * brittle screen pixel assumptions.
 */
object PhilippineLoanProviderCatalog {

  val profiles: List<ProviderProfile> = listOf(
    ProviderProfile(
      id = "shopee_spaylater",
      canonicalLender = "Shopee (SPayLater)",
      defaultLoanType = "BNPL",
      detectPatterns = listOf(Regex("""\bspay\s*later\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf(
        "available limit", "spaylater limit", "total credit limit", "credit limit",
        "remaining limit", "shopee coins", "voucher", "checkout"
      ),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf(
          "current bill", "bill for", "amount to pay", "total bill due", "this month bill", "upcoming bill"
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
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bsloan\b|\bs-?loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available limit", "sloan limit", "credit limit", "max loanable"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current due", "this month payment"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("loan balance", "remaining balance", "total unpaid"),
        LoanFieldKey.SERVICE_FEE to listOf("admin fee", "monthly admin fee")
      )
    ),
    ProviderProfile(
      id = "billease",
      canonicalLender = "BillEase",
      defaultLoanType = "Installment Loan",
      detectPatterns = listOf(Regex("""\bbill\s*ease\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf(
        "available credit", "credit limit", "remaining credit", "spendable limit", "boost limit"
      ),
      fieldSynonyms = mapOf(
        LoanFieldKey.OUTSTANDING_BALANCE to listOf(
          "contract balance", "total remaining balance", "remaining contract balance", "payoff amount"
        ),
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf(
          "due this cycle", "current due", "amount due now", "minimum due"
        ),
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf(
          "installment due", "scheduled installment", "regular installment", "per installment"
        )
      )
    ),
    ProviderProfile(
      id = "gcash_gloan",
      canonicalLender = "GCash (GLoan)",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bg\s*loan\b|\bgloan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("pre-approved", "up to", "gscore", "wallet balance", "available limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current due", "total due now"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining balance", "loan balance", "total unpaid balance"),
        LoanFieldKey.AMOUNT_RECEIVED to listOf("net proceeds", "disbursed to gcash", "credited to gcash"),
        LoanFieldKey.OTHER_FEES to listOf("doc stamp", "documentary stamp", "dst")
      )
    ),
    ProviderProfile(
      id = "gcash_ggives",
      canonicalLender = "GCash (GGives)",
      defaultLoanType = "Installment Loan",
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
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(
        Regex("""\bgcash\b.*\bcash\s+loan\b|\bcash\s+loan\b.*\bgcash\b""", RegexOption.IGNORE_CASE)
      ),
      extraNoisePhrases = listOf("wallet balance", "available limit", "gscore"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("amount to pay", "current bill", "due now"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("remaining balance", "loan balance", "principal balance")
      )
    ),
    ProviderProfile(
      id = "maya_credit",
      canonicalLender = "Maya (Maya Credit)",
      defaultLoanType = "Credit Line",
      detectPatterns = listOf(Regex("""\bmaya\s+(?:easy\s+)?credit\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf(
        "credit limit", "available credit", "maya wallet", "available balance", "remaining credit"
      ),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf(
          "total amount due", "minimum amount due", "minimum due", "current bill"
        ),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf(
          "used credit", "credit used", "outstanding balance", "principal balance"
        ),
        LoanFieldKey.ORIGINAL_PRINCIPAL to listOf("amount availed", "transferred amount")
      ),
      singlePaymentCardHint = true
    ),
    ProviderProfile(
      id = "maya_personal_loan",
      canonicalLender = "Maya (Personal Loan)",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bmaya\s+(?:personal\s+)?loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("maya wallet", "savings balance", "available limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf("monthly amortization", "monthly installment", "amortization"),
        LoanFieldKey.OTHER_FEES to listOf("dst", "documentary stamp", "notarial fee")
      )
    ),
    ProviderProfile(
      id = "atome",
      canonicalLender = "Atome",
      defaultLoanType = "BNPL",
      detectPatterns = listOf(Regex("""\batome\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("atome card limit", "available limit", "spend limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.AMOUNT_CURRENTLY_DUE to listOf("this month due", "upcoming bill", "bill due"),
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("total remaining", "remaining unpaid")
      )
    ),
    ProviderProfile(
      id = "home_credit",
      canonicalLender = "Home Credit",
      defaultLoanType = "Installment Loan",
      detectPatterns = listOf(Regex("""\bhome\s+credit\b|\bhc\s+loan\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("qwarta limit", "available offer", "credit limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.OUTSTANDING_BALANCE to listOf("early payoff", "remaining balance", "outstanding balance"),
        LoanFieldKey.INSTALLMENT_AMOUNT to listOf("monthly payment", "monthly installment")
      ),
      preferredNumericDateOrder = NumericDateOrder.DAY_FIRST
    ),
    ProviderProfile(
      id = "tala",
      canonicalLender = "Tala",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\btala\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("your limit", "tala limit", "credit limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.TOTAL_REPAYMENT to listOf("total repayment", "total amount due back", "repay total")
      )
    ),
    ProviderProfile(
      id = "juanhand",
      canonicalLender = "JuanHand",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bjuan\s*hand\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("credit limit", "max amount", "available limit"),
      fieldSynonyms = mapOf(
        LoanFieldKey.TOTAL_REPAYMENT to listOf("repayment amount", "total payable", "amount to repay")
      )
    ),
    ProviderProfile(
      id = "lazada_lazpaylater",
      canonicalLender = "Lazada (LazPayLater)",
      defaultLoanType = "BNPL",
      detectPatterns = listOf(Regex("""\blaz\s*pay\s*later\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available credit", "credit limit", "lazwallet")
    ),
    ProviderProfile(
      id = "seabank",
      canonicalLender = "SeaBank",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bseabank\b|\bsea\s*bank\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("credit limit", "available credit", "savings balance")
    ),
    ProviderProfile(
      id = "tonik",
      canonicalLender = "Tonik",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\btonik\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("stash balance", "credit limit")
    ),
    ProviderProfile(
      id = "cimb",
      canonicalLender = "CIMB",
      defaultLoanType = "Credit Line",
      detectPatterns = listOf(Regex("""\bcimb\b|\brevict?\s*credit\b|\brevicredit\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("credit limit", "available credit")
    ),
    ProviderProfile(
      id = "cashalo",
      canonicalLender = "Cashalo",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bcashalo\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available limit", "credit limit")
    ),
    ProviderProfile(
      id = "digido",
      canonicalLender = "Digido",
      defaultLoanType = "Cash Loan",
      detectPatterns = listOf(Regex("""\bdigido\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("available limit", "first loan")
    ),
    // Generic parent providers checked AFTER product-specific rules
    ProviderProfile(
      id = "gcash_generic",
      canonicalLender = "GCash",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bgcash\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("wallet balance", "available balance")
    ),
    ProviderProfile(
      id = "maya_generic",
      canonicalLender = "Maya",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bpaymaya\b|\bmaya\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("maya wallet", "available credit", "credit limit")
    ),
    ProviderProfile(
      id = "shopee_generic",
      canonicalLender = "Shopee",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\bshopee\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("shopee pay", "shopeepay balance", "shopee coins")
    ),
    ProviderProfile(
      id = "lazada_generic",
      canonicalLender = "Lazada",
      defaultLoanType = null,
      detectPatterns = listOf(Regex("""\blazada\b""", RegexOption.IGNORE_CASE)),
      extraNoisePhrases = listOf("lazwallet", "available limit")
    )
  )

  fun detectProfile(fullText: String): ProviderProfile? {
    // Handle combination detection first (e.g. "GCash" header on row 1 and "Cash Loan" on row 3)
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

