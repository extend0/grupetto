# Recording workouts and uploading to Strava

Grupetto records indoor rides on the tablet. A hosted server and a second recording device are not required. Start the overlay, then tap **Start workout**. **Finish workout** offers Save & finish, Keep riding, and Discard. Finishing also ends zone enforcement and releases media. **End enforcement** only disables penalties; it does not stop recording.

## Inactivity and recovery

**Workouts & Strava** contains the shared inactivity setting for recordings and zone sessions: Off, 15, **25 (default)**, 30, 60, or 120 minutes. Existing installations receive the new 25-minute default. Auto-ended rides exclude trailing inactivity; breaks followed by further pedaling remain in the ride. The 25-minute default precedes the inspected tablet's 30-minute screen timeout and the existing 30-minute cadence restart watchdog. Screen-off is not a guarantee that the tablet continues executing: expiration is checked again after waking or restarting.

Samples are saved once a second. If the process is interrupted, the recorded portion survives. When you reopen the app, Resume or Finish the interrupted recording; if its inactivity deadline already passed, it is finalized automatically. Missing readings and sleep gaps are not filled with invented data. No-pedaling sessions stay local and cannot be uploaded.

## Connect through your upload server

The private upload server owns Strava authorization and token refresh. Grupetto stores only a revocable device credential and your privately supplied server address; no Strava client secret belongs on the tablet or in this repository.

1. Sign into your existing upload server website as the rider.
2. Choose **Enable workout uploads** and authorize activity reading and uploading in Strava.
3. Choose **Create tablet pairing link**. Copy the link into **Workouts & Strava → Strava connection** and tap **Connect to upload server** within ten minutes.
4. Check the member and athlete shown on the tablet before recording.

One rider is connected at a time. Finish the active recording before changing connections. Existing workouts retain their rider and server. The link contains a one-time secret: do not put it in Git, chat, screenshots, or logs. It stays out of URL query strings. The tablet encrypts its device credential with Android Keystore and excludes it from backup. Pairing expires after 90 days; create a new link to reconnect. No browser login must stay open during a ride.

Disconnect tablet revokes the device credential when online without disconnecting Strava from goal tracking. If offline, revoke it on the upload server website. Jobs already received by the upload server continue; local recordings remain on the tablet.

## Migration from direct Strava upload

The first launch removes the old local Strava credentials. Connect to upload server to resume uploading. The database upgrade retains recordings and samples, and marks interrupted direct uploads for explicit review. Already uploaded rides are never resubmitted automatically. Before retrying a review item, check Strava for a completed activity.

## Uploads and history

Automatic upload is enabled by default for rides recorded while connected. You can turn it off and use **Workout history → Upload / Retry**. Rides recorded before connection require an explicit upload action. Workouts already associated with another athlete require reconnecting that account.

Uploads include power, cadence, heart rate when available, and distance estimated from power. They have no fabricated GPS route. Resistance is kept locally and summarized in the description. Strava's activity visibility follows your Strava defaults. The upload server also uses activity reading permission for household goal tracking.

WorkManager waits for connectivity and sends TCX to the upload server with a stable delivery ID. The upload server persists the file and job before acknowledging receipt, then uploads and polls Strava independently of the tablet. TCX files are deleted after success or expire after seven days; the cloud is not a workout archive. Rate limits and temporary outages are retried. Files larger than 8 MiB must be exported locally. A submission interrupted before its upload ID was saved is marked **check Strava before retrying**: inspect your activities before explicitly retrying. External IDs are not assumed to be an idempotency guarantee.

History retains the newest **30 uploaded rides and every unsynced ride**. Export TCX saves an independent copy using Android's file picker. Delete locally removes local samples, not the Strava activity. Keep exported copies of rides you want to archive beyond retention. Queued/processing rides cannot be deleted while an upload may still be in flight.

## Development and verification

Run `python3 scripts/check-private-data.py` before sharing changes. It checks tracked and commit-eligible untracked files for private deployment addresses and credential patterns. Enable the staged-content check with `git config core.hooksPath .githooks`; CI runs the same guard. Keep local credentials in ignored configuration files and use `example.invalid` for sample server addresses.

Use JDK 17 and the checked-in Gradle 8.13 wrapper. Kotlin 1.9's kapt processor is incompatible with the previous Gradle 9 wrapper; Room uses kapt here without changing the Kotlin/Compose versions or minimum Android SDK.

```
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleDebugAndroidTest
```

Device checks should use the `.dev` build, which installs separately from the stable app. Instrumentation tests use isolated database/credential filenames. Verify the browser callback, real short ride upload and charts, offline retry, screen-off at the configured timeout, and BLE on/off. Authentication against Strava and a real activity upload require the rider's upload server pairing and Strava authorization; never place them in tests or logs.

References: [Strava authentication](https://developers.strava.com/docs/authentication/), [upload formats](https://developers.strava.com/docs/uploads/), [native OAuth](https://www.rfc-editor.org/rfc/rfc8252), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent), [Pacelet](https://github.com/LiamCordelle/pacelet), [stravacli](https://github.com/dlenski/stravacli).
