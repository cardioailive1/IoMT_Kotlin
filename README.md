# IoMT CardioAI — Android (Kotlin)

Native Android port of the iOS CardioAI app, connecting to the **same Render-hosted backend** — no backend behavior changes except one new endpoint (see below). Built with Kotlin + Jetpack Compose, matching the iOS app's architecture and feature set 1:1 where Android's platform APIs allow.

## Important — deployment reality check

**Android apps cannot be "deployed on Render.com."** Render hosts backends and web services, not native mobile apps. To get this app onto a device:
- **For internal/team testing**: build a debug APK in Android Studio (`Build > Build APK(s)`) and side-load it, or use Android Studio's "Run" to install directly on a connected device/emulator
- **For wider distribution**: publish to the Google Play Store (Internal Testing track is the closest equivalent to TestFlight), which requires a Google Play Developer account ($25 one-time fee) and app signing setup — neither of which exists yet for this project

## One backend change was required

Your backend previously had **no way for a patient to get an account without Apple Sign-In** — `/auth/apple` is Apple-only, `/auth/signup` explicitly rejects `role: patient`, and Android has no equivalent to Apple Sign-In. Without a fix, Android patients would be permanently locked out.

Added: `POST /auth/google`, mirroring `/auth/apple`'s exact auto-provisioning pattern (using Google's ID token verification instead of Apple's JWKS). See `migrations/007_add_google_signin.sql` and the `db.py`/`iomt_cardioai_production.py` changes delivered alongside this Android project.

**Run migration 007 before Android patients can sign in:**
```bash
psql "<your DATABASE_URL>" -f migrations/007_add_google_signin.sql
```

## What's ported, and how each iOS concept maps to Android

| iOS | Android | Notes |
|---|---|---|
| Keychain | EncryptedSharedPreferences (`SecureStorage.kt`) | AES-256, Android Keystore-backed |
| `APIClient.swift` | `ApiClient.kt` | Same detailed decode-error-message fix ported — errors name the actual failure, not a generic message |
| `AuthService.swift` | `AuthService.kt` | Email/password identical; **Apple Sign-In → Google Sign-In** via Credential Manager (Android's current recommended API) |
| `HealthKitService.swift` | `HealthConnectService.kt` | Reads heart rate from Health Connect (Wear OS, Samsung Health, etc.) — Android has no live observer API equivalent to `HKObserverQuery`, so this polls every 5 minutes |
| `GoogleHealthService.swift` (Fitbit) | `GoogleHealthService.kt` | Same Google Health API, same OAuth2 PKCE flow — **Custom Tabs** instead of `ASWebAuthenticationSession` |
| `DevicePairingService.swift` (BLE) | `DevicePairingService.kt` | Native Android `BluetoothLeScanner`/`BluetoothGatt` APIs; same standard GATT service UUIDs, same `restrictToHealthServices` testing flag |
| `KnownBLEDevices.swift` | `KnownBLEDevices.kt` | Identical catalog |
| `BridgeClient.swift` (WebSocket) | `BridgeClient.kt` | OkHttp WebSocket; same HMAC-SHA256 handshake, same patient-facing-vs-technical label split |
| `RootView.swift` | `CardioAINavHost.kt` | Same fix already applied — sign-in never gates behind HMAC provisioning |
| `SettingsView.swift` | `SettingsScreen.kt` | Same patient-role gating — Role/Patient ID/Backend Connection/Security details hidden for patients, visible for clinical staff |
| `DashboardView.swift` | `DashboardScreen.kt` | Same soft-label fix for the bridge status chip |

## Required setup before this builds and runs

### 1. Google Sign-In (patient auth)
- Create an OAuth 2.0 **Web** Client ID in Google Cloud Console (used for backend token verification)
- Create an OAuth 2.0 **Android** Client ID (uses your app's SHA-1 signing fingerprint + package name — no secret needed)
- Set `GOOGLE_OAUTH_CLIENT_ID` on your Render backend to the **Web** client ID (for `/auth/google`'s audience check)
- Set `googleWebClientId` in `AppContainer.kt` to the same **Web** client ID

### 2. Google Health API (Fitbit integration)
Same setup as documented in the iOS `GoogleHealthService.swift` — Google Cloud project, Health API enabled, OAuth client (this time type **"Android"**, not "iOS"), Restricted-scope test users while in development. Set `clientId` in `GoogleHealthService.kt`.

### 3. App icons
No launcher icon files are included — Android Studio's "New Project" wizard or the Image Asset tool can generate the required `mipmap` densities; without them the build will fail on the `android:icon` manifest reference.

### 4. Backend URL
Same as iOS — entered at runtime in the sign-in screen, stored via `SecureStorage`. No hardcoded backend URL.

## What has NOT been verified

This environment has no Android SDK, Gradle, or Kotlin compiler available — every file was checked for structural correctness (brace/paren balance, cross-referenced type signatures, consistent imports) with the same rigor applied throughout this project's iOS work, but **none of it has been compiled**. Before relying on this:
1. Open in Android Studio, let Gradle sync, and resolve any dependency version conflicts it flags
2. Build and run on a real device — BLE and Health Connect both behave unreliably on emulators
3. Test each auth path (email/password login, Google Sign-In, signup) against your actual backend

## What's simplified compared to iOS, honestly

- **HealthConnectService polls every 5 minutes**, not a live push — Health Connect doesn't expose a third-party observer API the way HealthKit does
- **No dedicated ViewModel layer** — screens read directly from the container's services via `collectAsState()`. This matches the app's current scale; if it grows, introducing ViewModels per screen would be the natural next refactor
- **BLE device pairing UI is simpler** than iOS's — no RSSI-sorted list animations, just a flat list; functionally equivalent
