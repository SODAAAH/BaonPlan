# BaonPlan

A **100% offline native Android personal finance application** built with **Kotlin, Jetpack Compose, Material 3, Room, DataStore, and WorkManager** for Filipino college students managing weekly *baon* allowance, school food budgets, installment loans, SPayLater balances, payment reminders, and emergency savings.

- **Application ID**: `com.baonplan.finance`
- **Minimum Android Version**: Android 8.0 (API 26)
- **Target / Compile SDK**: Android 16 (API 36)
- **Appearance**: System default, Light mode, and Dark mode
- **Currency & Timezone**: Philippine Peso (`PHP` / `₱`) · `Asia/Manila`
- **Download**: [Latest GitHub Release (`BaonPlan-v1.0.0-release.apk`)](https://github.com/SODAAAH/BaonPlan/releases/latest)

---

## About & Financial Safeguards

- **100% Native & Offline**: Does not declare `INTERNET` permission, never runs a WebView/PWA wrapper, disables automatic cloud backup (`android:allowBackup="false"`), never transmits financial records externally, and never asks for passwords, PINs, or OTPs.
- **Exact Integer Centavo Arithmetic**: All balances, installments, allowances, reserves, and expenses use `Long` centavos and `BigDecimal` parsing (`HALF_UP`) via `MoneyFormatter`—zero floating-point currency math.
- **No Fabricated Repayment Schedules**: Loans with `Unknown` repayment frequency or `Unconfirmed` due dates remain clearly labeled and excluded from automated installment generation until confirmed by the user.
- **Cash-Safety Planning**: Weekly scenario planning protects school food (`₱50`/school day default), emergency savings, miscellaneous needs, and a minimum personal cash buffer before recommending a safe debt reserve.
- **Reserves Never Masquerade as Payments**: Setting aside money in a Debt Reserve deducts from available weekly spending money without reducing active loan balances until an actual repayment transaction is recorded.
- **Double-Count Prevention & Audit Trail**: Marking installments paid is idempotent, and editing or voiding mistaken transactions atomically updates linked loans, installments, budgets, and reserves while preserving voided history.

---

## Screens & Features

BaonPlan is organized into **4 calm primary tabs** (`Home`, `Budget`, `Debts`, `More`) with focused secondary screens:

| Area | Capabilities |
| :--- | :--- |
| **Home** | Calm money overview displaying *Remaining debt*, single most relevant *Next payment*, *This week's money* (*Allowance*, *Food*, *Debt reserved*, highlighted *Money left*), and two quick actions (*Add expense*, *Record payment*). |
| **Budget** | Weekly spending clarity with one-tap `₱400` / `₱500` & custom allowance logging, `₱50` school food quick-logging, *Debt reserve* vs *Emergency savings* pools, and collapsible `₱400` vs `₱500` scenario comparison & cash-buffer simulator sliders. |
| **Debts & Debt Detail** | Single non-double-counted *Remaining debt* summary, *Active* / *Paid* / *Archived* filter, clean debt rows, full payment/schedule/date confirmation actions, editable payment history, and expandable *Loan details* showing the full 8-line cost breakdown (*Original principal*, *Amount received*, *Interest*, *Service fees*, *Processing fees*, *Total scheduled repayment*, *Amount already paid*, *Remaining balance*). |
| **More — Payment Calendar** | *Upcoming* prioritized due list and interactive *Calendar* monthly grid in `Asia/Manila` highlighting overdue, due today, due tomorrow, estimated dates, weekly *baon* check-in days, and unconfirmed items. |
| **More — Transactions & Reports** | Searchable, filterable, date-grouped ledger with tap-to-correct/void support, plus native Compose `Canvas` debt breakdown charts, repayment progress, weekly allocation bars, and CSV export. |
| **More — Settings & Backup** | Immediate *System default* / *Light mode* / *Dark mode* theme switching (saved in DataStore), WorkManager reminders + `POST_NOTIFICATIONS` controls, editable budget defaults, local SAF JSON backup/restore, CSV export, and two-step *Clear all data*. |

---

## Clean Start & Default Planner Settings

BaonPlan starts with an empty loan and transaction ledger (**₱0.00** remaining debt) and editable planner defaults in `DefaultSettingsProvider`:

- **Weekly Allowance Range**: `₱400` min / `₱500` max / `₱400` conservative planning baseline.
- **School Food Baseline**: `2` school days/week × `₱50`/day = **`₱100.00` weekly food budget**.
- **Weekly Buffer Targets**: `₱50.00` other expenses buffer · `₱50.00` emergency savings · `₱150.00` planned debt reserve (all configurable under *More → Budget defaults*).
- **Manual Ledger Control**: Add loans, payment schedules, allowances, expenses, and reserves directly with no preloaded personal records.

---

## Architecture

- **Pattern**: Single-activity MVVM + Unidirectional Data Flow (`StateFlow<BaonPlanUiState>` + `SharedFlow<String>` in `BaonPlanViewModel`, backed by `BaonPlanRepository`).
- **UI**: Jetpack Compose Material 3 with Jetpack Navigation 3, adaptive bottom `NavigationBar` (`< 600dp`) and `NavigationRail` (`>= 600dp`), and modal bottom sheets across Light & Dark themes.
- **Storage & Background**: Room SQLite (`baonplan.db`, schema v1 across 7 entities: `debts`, `installments`, `transactions`, `allowance_records`, `expenses`, `debt_reserves`, `user_settings`), DataStore Preferences, and WorkManager + `BOOT_COMPLETED` scheduling.

```text
app/src/main/kotlin/com/baonplan/finance/
├── BaonPlanApp.kt                     # App initialization, channels & reminder scheduling
├── MainActivity.kt                    # Single edge-to-edge Compose activity
├── domain/
│   ├── FinancialCalculator.kt         # Exact Long centavo math & schedule rules
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
└── ui/
    ├── BaonPlanRoot.kt                # Navigation 3 host, SAF launchers & modal orchestration
    ├── BaonPlanViewModel.kt           # Combined Room + DataStore reactive state
    ├── components/                    # Calm Material 3 components, cards, toggles & bottom sheets
    ├── navigation/                    # @Serializable NavKey routes
    ├── screens/                       # Home, Budget, Debts, DebtDetail, More, Calendar, Transactions, Reports & Settings subscreens
    └── theme/                         # Warm-white Light & Charcoal-Slate Dark Material 3 themes
```
