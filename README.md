# BaonPlan

A **100% offline native Android personal finance application** built with **Kotlin, Jetpack Compose, Material 3, Room, DataStore, and WorkManager** for Filipino college students managing weekly *baon* allowance, school food budgets, installment loans, SPayLater balances, payment reminders, and emergency savings.

- **Version**: `1.1.1` (`versionCode 3`)
- **Application ID**: `com.baonplan.finance`
- **Minimum Android Version**: Android 8.0 (API 26)
- **Target / Compile SDK**: Android 16 (API 36)
- **Appearance**: System default, Light mode, and Dark mode
- **Currency & Timezone**: Philippine Peso (`PHP` / `₱`) · `Asia/Manila`
- **Download**: [Latest GitHub Release (`BaonPlan-v1.1.1-release.apk`)](https://github.com/SODAAAH/BaonPlan/releases/latest)
- **License**: [MIT License](LICENSE)

---

## About & Financial Safeguards

- **100% Native & Offline**: Does not declare `INTERNET` permission, never runs a WebView/PWA wrapper, disables automatic cloud backup (`android:allowBackup="false"`), never transmits financial records externally, and never collects account passwords or OTPs. Optional **App lock** delegates exclusively to the Android OS biometric or device-PIN prompt on your device.
- **Exact Integer Centavo Arithmetic**: All balances, installments, allowances, reserves, and expenses use `Long` centavos and `BigDecimal` parsing (`HALF_UP`) via `MoneyFormatter`—zero floating-point currency math.
- **No Fabricated Repayment Schedules**: Loans with `Unknown` repayment frequency or `Unconfirmed` due dates remain clearly labeled and excluded from automated installment generation until confirmed by the user.
- **Cash-Safety Planning**: Weekly scenario planning and 7-day dues readiness protect school food (`₱50`/school day default), emergency savings, miscellaneous needs, and a minimum personal cash buffer before recommending a safe debt reserve.
- **Reserves Never Masquerade as Payments**: Setting aside money in a Debt Reserve deducts from available weekly spending money without reducing active loan balances until an actual repayment transaction is recorded.
- **Double-Count Prevention & Audit Trail**: Marking installments paid checks for reserve usage explicitly and stays idempotent; editing debt metadata preserves existing installment schedules; and editing or voiding transactions atomically updates linked loans, installments, due dates, budgets, and reserves while preserving voided history.

---

## Screens & Features

BaonPlan is organized into **4 calm primary tabs** (`Home`, `Budget`, `Debts`, `More`) with focused secondary screens:

| Area | Capabilities |
| :--- | :--- |
| **Home** | Calm money overview displaying *Remaining debt*, single most relevant *Next payment*, a *Next 7 days due* readiness check, *This week's money* (*Allowance*, *Food*, *Debt reserved*, *Other & direct payments*, highlighted *Money left*), and two context-aware quick actions (*Add expense* + *Record baon* / *Add debt* / *Record payment*). |
| **Budget** | Weekly spending clarity with one-tap `₱400` / `₱500` & custom allowance logging, `+ ₱50 meal` & customizable quick expense preset (`+ ₱20 commute`), school-day progress counter (`x/y days logged`), reviewable/deletable *Entries this week & reserves*, *Prior-week leftover* carry-over/sweep prompt, *Emergency savings* goal progress bar + partial withdrawals, *Debt reserve* pool, and collapsible `₱400` vs `₱500` scenario simulator sliders. |
| **Debts & Debt Detail** | Single non-double-counted *Remaining debt* summary, *Active* / *Paid* / *Archived* filter, clean debt rows, full payment/schedule/date confirmation actions (with explicit reserve choice on installment payment), editable payment history, confirmation-gated *Delete debt*, and expandable *Loan details* showing the 8-line cost breakdown (*Original principal*, *Amount received*, *Interest*, *Service fees*, *Processing fees*, *Total scheduled repayment*, *Amount already paid*, *Remaining balance*). |
| **More — Payment Calendar** | 7-day dues readiness summary banner, *Upcoming* prioritized due list, and interactive *Calendar* monthly grid in `Asia/Manila` highlighting overdue, due today, due tomorrow, estimated dates, weekly *baon* check-in days, and unconfirmed items. |
| **More — Transactions & Reports** | Searchable, filterable, date-grouped ledger with an active filter/search inflow-outflow subtotal pill and tap-to-correct/void support, plus native Compose `Canvas` debt breakdown charts, confirmed & estimated debt-free payoff projection, *Repayment focus* advisor (*Due soonest* vs. *Smallest balance first*), 4-week history comparison chart, and CSV export. |
| **More — Settings, Privacy & OS Integration** | Immediate *System default* / *Light mode* / *Dark mode* theme switching, WorkManager reminders with custom *Reminder time* and configurable *Advance notice* (`1`/`2`/`3`/`7` days), customizable quick expense presets & *Emergency goal* target, optional biometric/device-PIN *App lock*, local SAF JSON backup/restore with *Last backup* status, two-step *Clear all data*, launcher shortcuts (*Add expense*, *Record payment*), and minimalist Home Screen Widget. |

---

## Clean Start & Default Planner Settings

BaonPlan starts with an empty loan and transaction ledger (**₱0.00** remaining debt) and editable planner defaults in `DefaultSettingsProvider`:

- **Weekly Allowance Range**: `₱400` min / `₱500` max / `₱400` conservative planning baseline.
- **School Food Baseline**: `2` school days/week × `₱50`/day = **`₱100.00` weekly food budget**.
- **Weekly Buffer Targets**: `₱50.00` other expenses buffer · `₱50.00` emergency savings · `₱150.00` planned debt reserve · `₱500.00` starter emergency savings goal · customizable quick expense presets (all editable under *More → Budget defaults*).
- **Manual Ledger Control**: Add loans, payment schedules, allowances, expenses, and reserves directly with no preloaded personal records.

---

## Architecture

- **Pattern**: Single-activity MVVM + Unidirectional Data Flow (`StateFlow<BaonPlanUiState>` + `SharedFlow<String>` in `BaonPlanViewModel`, backed by `BaonPlanRepository`).
- **UI**: Jetpack Compose Material 3 with Jetpack Navigation 3, adaptive bottom `NavigationBar` (`< 600dp`) and `NavigationRail` (`>= 600dp`), and modal bottom sheets across Light & Dark themes.
- **Storage, Security & OS Integration**: Room SQLite (`baonplan.db`, schema v1 across 7 entities: `debts`, `installments`, `transactions`, `allowance_records`, `expenses`, `debt_reserves`, `user_settings`), DataStore Preferences, WorkManager + `BOOT_COMPLETED` scheduling, OS Biometric/Keyguard `AppLockHelper`, launcher `shortcuts.xml`, and `BaonWidgetProvider` (`RemoteViews`).

```text
app/src/main/kotlin/com/baonplan/finance/
├── BaonPlanApp.kt                     # App initialization, channels & reminder scheduling
├── MainActivity.kt                    # Single edge-to-edge Compose activity, shortcuts & lock gate
├── domain/
│   ├── FinancialCalculator.kt         # Exact Long centavo math, readiness, payoff & trend rules
│   ├── model/                         # Domain enums & summary models
│   └── util/                          # MoneyFormatter (BigDecimal/Long), ManilaTime
├── data/
│   ├── backup/BackupSerializer.kt     # Versioned JSON backup & escaped CSV serializer
│   ├── local/
│   │   ├── BaonPlanDatabase.kt        # Room database definition (v1)
│   │   ├── DefaultSettingsProvider.kt # Clean-start defaults (zero preloaded debts)
│   │   ├── SettingsPreferencesDataStore.kt
│   │   ├── dao/                       # Room DAOs
│   │   └── entity/                    # Room entities
│   └── repository/BaonPlanRepository.kt
├── notifications/                     # ReminderWorker, ReminderScheduler, NotificationHelper, BootCompletedReceiver
├── security/                          # Offline BiometricPrompt / Keyguard AppLockHelper
├── widget/                            # Home-screen RemoteViews summary widget provider
└── ui/
    ├── BaonPlanRoot.kt                # Navigation 3 host, SAF launchers & modal orchestration
    ├── BaonPlanViewModel.kt           # Combined Room + DataStore reactive state
    ├── components/                    # Calm Material 3 components, cards, toggles & bottom sheets
    ├── navigation/                    # @Serializable NavKey routes
    ├── screens/                       # Home, Budget, Debts, DebtDetail, More, Calendar, Transactions, Reports & Settings subscreens
    └── theme/                         # Warm-white Light & Charcoal-Slate Dark Material 3 themes
```

---

## Open Source & Contributing

BaonPlan is open-source software licensed under the [MIT License](LICENSE).

- Read [CONTRIBUTING.md](CONTRIBUTING.md) for local build commands, architecture guidelines, and pull request conventions.
- Read [SECURITY.md](SECURITY.md) for offline privacy guarantees and reporting issues.
