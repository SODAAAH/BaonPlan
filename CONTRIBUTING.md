# Contributing to BaonPlan

Thank you for your interest in contributing to **BaonPlan**! BaonPlan is an open-source, 100% offline native Android personal finance app designed to stay calm, honest, and dependable for Filipino college students.

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Core Design & Engineering Principles

Please keep every change aligned with these non-negotiable rules:

1. **100% Offline & Privacy-First**:
   - Never add `INTERNET` / `ACCESS_NETWORK_STATE` / `CAMERA` / `READ_MEDIA_IMAGES` permissions, analytics, crash-reporting SDKs, ads, cloud sync, external AI APIs, or WebViews; keep `datatransport` network services stripped via `AndroidManifest.xml`.
   - Keep `android:allowBackup="false"`, ensure all financial data stays local in Room SQLite, DataStore Preferences, and private `filesDir/debt_attachments/`, and never log OCR text, file URIs, lender names, or peso amounts.
2. **Exact Integer Centavo Arithmetic**:
   - Store and calculate all money amounts in `Long` centavos (never `Float` or `Double` for currency math).
   - Parse user inputs via `MoneyFormatter.parsePesoToCents` (`BigDecimal`) and format outputs with `MoneyFormatter`.
3. **Honest Date & Balance Tracking**:
   - Never invent payment due dates for loans marked `Unknown`, `Irregular`, or `Unconfirmed`, and keep `"Balance"` distinct from `"Remaining Installment Total"` in OCR extraction under `domain/ocr/` (keep `domain/ocr/` pure JVM-testable Kotlin).
   - Keep debt reserves isolated from paid-off balances until an actual repayment is recorded.
   - Ensure transaction edits and voids update linked debts, installments, budgets, and reserves atomically inside Room transactions.
4. **Calm Native UI/UX**:
   - Follow Material 3 with the warm-white Light and charcoal-slate Dark color system (never hardcode theme colors inside screens).
   - Keep the 4 primary tabs (`Home`, `Budget`, `Debts`, `More`) uncluttered with plain, beginner-friendly labels.

## Prerequisites

- **JDK 17** (Gradle toolchain targets JVM 17)
- **Android SDK** with Platform `36`, Build-Tools `36.0.0`, and Android Gradle Plugin `9.0+` compatible tooling (or Android Studio)
- Never commit `local.properties`, `keystore.properties`, `*.jks`, or `*.keystore`

## Building & Running Checks Locally

On macOS/Linux use `./gradlew`; on Windows PowerShell/CMD use `.\gradlew.bat`.

```bash
# Run unit & Robolectric persistence tests
./gradlew testDebugUnitTest

# Run Android Lint checks
./gradlew lintDebug

# Build debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
```

### Signed Release Builds (Optional)

Copy [`keystore.properties.example`](keystore.properties.example) to `keystore.properties` (gitignored), point it at your local `.jks` keystore, and run:

```bash
./gradlew assembleRelease
```

### Continuous Integration

Every push and pull request targeting `main` runs [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml), which verifies `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.

## Pull Request Checklist

- Keep PRs focused on a single bug fix or cohesive feature.
- Verify unit tests (`testDebugUnitTest`) and lint (`lintDebug`) pass cleanly.
- If modifying Room entities or migrations, preserve backward compatibility and check generated schemas under `app/schemas/`.
- Test changes in both Light and Dark themes.
