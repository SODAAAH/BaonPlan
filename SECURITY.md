# Security & Privacy Policy

## Privacy & Offline Architecture

BaonPlan is intentionally engineered as a **100% offline** native Android application:

- **No Network Access**: The app manifest declares no `INTERNET` or `ACCESS_NETWORK_STATE` permissions.
- **No Telemetry or Trackers**: Zero third-party analytics, crash-collection, or advertising SDKs are included.
- **No Cloud Backup**: `android:allowBackup="false"` prevents automatic extraction to external cloud backups; backups are explicit local Storage Access Framework (SAF) JSON/CSV exports chosen by the user.
- **OS-Delegated App Lock**: Optional authentication uses Android `BiometricPrompt` and `KeyguardManager` locally without handling, storing, or transmitting raw credentials.

## Supported Versions

Security and financial-integrity fixes are applied to the latest release on the `main` branch (`v1.1.1+`).

## Reporting a Vulnerability or Financial Calculation Bug

If you discover a security concern, backup serialization issue, or financial calculation bug:

1. **Do not** disclose exploitable details or personal financial data publicly before remediation.
2. Open a [GitHub Security Advisory](https://github.com/SODAAAH/BaonPlan/security/advisories/new) on the repository (recommended), or open an issue without sensitive personal records if it is a general UI/calculation bug.
3. Please include the app version (`Settings -> About`), Android API level, steps to reproduce, and expected vs. actual behavior.
