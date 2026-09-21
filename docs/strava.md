# Recording workouts and uploading to Strava

Grupetto records indoor rides on the tablet. A hosted server and a second recording device are not required. Start the overlay, then tap **Start workout**. **Finish workout** offers Save & finish, Keep riding, and Discard. Finishing also ends zone enforcement and releases media. **End enforcement** only disables penalties; it does not stop recording.

## Inactivity and recovery

**Workouts & Strava** contains the shared inactivity setting for recordings and zone sessions: Off, 15, **25 (default)**, 30, 60, or 120 minutes. Existing installations receive the new 25-minute default. Auto-ended rides exclude trailing inactivity; breaks followed by further pedaling remain in the ride. The 25-minute default precedes the inspected tablet's 30-minute screen timeout and the existing 30-minute cadence restart watchdog. Screen-off is not a guarantee that the tablet continues executing: expiration is checked again after waking or restarting.

Samples are saved once a second. If the process is interrupted, the recorded portion survives. When you reopen the app, Resume or Finish the interrupted recording; if its inactivity deadline already passed, it is finalized automatically. Missing readings and sleep gaps are not filled with invented data. No-pedaling sessions stay local and cannot be uploaded.

## Personal Strava setup

1. Sign in at https://www.strava.com/settings/api and create your own API application. Set **Authorization Callback Domain** to **127.0.0.1**. Check that the application has active developer access in Strava's dashboard.
2. Open **Workouts & Strava → Strava connection**. Enter your own client ID and client secret. These are application credentials, not your Strava password.
3. Tap **Connect Strava** and grant permission to upload activities in the browser. The app listens for the reply on a temporary port accessible only on the tablet. The browser shows a completion page with a return-to-Grupetto link.
4. If the browser cannot complete the return, use **Copy authorization link**, authorize in a browser, and paste its complete final URL into the app. On another device the loopback page is expected not to load; copy its address. Complete this within ten minutes. A rejected, expired, or already-used response requires starting Connect again.

No embedded WebView receives your password. The app encrypts your personal client secret and tokens with a Keystore-protected key and excludes them from Android backup/transfer. Tokens refresh automatically. Clearing app data or reinstalling may require connecting again. Disconnect first to change credentials or accounts, and finish an active recording before doing so. Disconnect removes local credentials and cancels scheduled uploads; to revoke the application itself, remove it in Strava's application settings.

This is a personal-credentials integration. Do not ship a shared client secret in an APK or commit one to the repository. A public integration should use a backend to protect its shared application secret.

## Uploads and history

Automatic upload is enabled by default for rides recorded while connected. You can turn it off and use **Workout history → Upload / Retry**. Rides recorded before connection require an explicit upload action. Workouts already associated with another athlete require reconnecting that account.

Uploads include power, cadence, heart rate when available, and distance estimated from power. They have no fabricated GPS route. Resistance is kept locally and summarized in the description. Strava's activity visibility follows your Strava defaults. A connection does not require permission to read your other rides.

WorkManager waits for connectivity and retries transient errors with backoff; Android may defer work during sleep. Strava processing is polled until an activity ID is returned. Rate-limit reset times are honored. A submission interrupted before its upload ID was saved is marked **check Strava before retrying**: inspect your activities before explicitly retrying. External IDs are not assumed to be an idempotency guarantee.

History retains the newest **30 uploaded rides and every unsynced ride**. Export TCX saves an independent copy using Android's file picker. Delete locally removes local samples, not the Strava activity. Keep exported copies of rides you want to archive beyond retention. Queued/processing rides cannot be deleted while an upload may still be in flight.

## Development and verification

Use JDK 17 and the checked-in Gradle 8.13 wrapper. Kotlin 1.9's kapt processor is incompatible with the previous Gradle 9 wrapper; Room uses kapt here without changing the Kotlin/Compose versions or minimum Android SDK.

```
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleDebugAndroidTest
```

Device checks should use the `.dev` build, which installs separately from the stable app. Instrumentation tests use isolated database/credential filenames. Verify the browser callback, real short ride upload and charts, offline retry, screen-off at the configured timeout, and BLE on/off. Authentication against Strava and a real activity upload require the owner's credentials and authorization; never place them in tests or logs.

References: [Strava authentication](https://developers.strava.com/docs/authentication/), [upload formats](https://developers.strava.com/docs/uploads/), [native OAuth](https://www.rfc-editor.org/rfc/rfc8252), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent), [Pacelet](https://github.com/LiamCordelle/pacelet), [stravacli](https://github.com/dlenski/stravacli).
