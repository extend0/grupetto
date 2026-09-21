package com.spop.poverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spop.poverlay.ConfigurationRepository
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.MainActivity
import com.spop.poverlay.BuildConfig
import com.spop.poverlay.R
import com.spop.poverlay.media.MediaPenaltyController
import com.spop.poverlay.overlay.penalty.PenaltyCurtain
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.zone.ForegroundAppMonitor
import com.spop.poverlay.zone.ZoneEnforcementCoordinator
import com.spop.poverlay.zone.ZonePersistence
import com.spop.poverlay.zone.ZoneRuntime

import com.spop.poverlay.sensor.CadenceWatchdog
import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsG700CrossTrainer
import com.spop.poverlay.util.IsRunningOnPeloton
import com.spop.poverlay.util.LifecycleEnabledService
import com.spop.poverlay.util.disableAnimations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import timber.log.Timber
import kotlin.math.roundToInt


class OverlayService : LifecycleEnabledService() {
    companion object {
        const val ActionMinimizeOverlay = "com.spop.poverlay.action.MINIMIZE_OVERLAY"
        const val ActionRestoreOverlay = "com.spop.poverlay.action.RESTORE_OVERLAY"
        const val ActionReleasePenalty = "com.spop.poverlay.action.RELEASE_PENALTY"
        private const val ActionDebugSetHeartRate = "com.spop.poverlay.debug.SET_HR"
        private const val DebugHeartRateExtra = "bpm"
        private const val DefaultOverlayFlags = (LayoutParams.FLAG_NOT_TOUCH_MODAL
                or LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        /**
         * The penalty curtain covers the whole screen and must swallow taps aimed at the video
         * underneath, so FLAG_NOT_TOUCHABLE is deliberately absent. It stays NOT_FOCUSABLE so it
         * never steals key events or breaks the host app's back button.
         */
        private const val CurtainFlags = (LayoutParams.FLAG_NOT_FOCUSABLE
                or LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or LayoutParams.FLAG_KEEP_SCREEN_ON)


        private const val OverlayServiceId = 2032
        private const val OverlayNotificationChannelId = "grupetto_overlay_service"
        private const val WakeLockTimeoutMs = 15 * 60 * 1000L
        private const val WakeLockRenewIntervalMs = 10 * 60 * 1000L

        val OverlayHeightDp = 110.dp


        //The percentage up or down a vertical drag must go before the overlay is relocated
        //Defined relative to the height of the screen
        const val VerticalMoveDragThreshold = .5f

        // Replace with DeadSensorInterface to simulate a dead sensor
        val EmulatorSensorInterface by lazy { DummySensorInterface() }

        private val mutableIsRunning = MutableStateFlow(false)
        val isRunning = mutableIsRunning.asStateFlow()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefreshJob: Job? = null
    private var overlayView: View? = null
    private var curtainView: View? = null
    private var debugHeartRateReceiver: BroadcastReceiver? = null
    private var zoneCoordinator: ZoneEnforcementCoordinator? = null
    private var windowManager: WindowManager? = null
    private var sensorViewModel: OverlaySensorViewModel? = null
    private var minimizedStateBeforeConfiguration: Boolean? = null
    private val bleServer by lazy { (application as GrupettoApplication).bleServer }

    override fun onCreate() {
        super.onCreate()
        mutableIsRunning.value = true
        syncBackgroundExecutionGuards()
        // The config screen normally starts this, but the overlay can be launched without it
        // ever opening (boot, or the cadence watchdog restart). start() is idempotent.
        HeartRateManager.start(applicationContext)
        // Keeps the staleness watchdog running for as long as the overlay lives, so a silently
        // dead strap blanks the heart rate instead of freezing it at its last value.
        HeartRateManager.setManaging(true, HeartRateManager.OwnerOverlay)
        val notification = prepareNotification(NotificationManagerCompat.from(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OverlayServiceId, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                OverlayServiceId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(OverlayServiceId, notification)
        }
        registerDebugHeartRateReceiver()
        buildDialog()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("overlay service received intent")
        when (intent?.action) {
            ActionMinimizeOverlay -> {
                sensorViewModel?.let { viewModel ->
                    if (minimizedStateBeforeConfiguration == null) {
                        minimizedStateBeforeConfiguration = viewModel.isMinimized.value
                    }
                    viewModel.minimizeOverlay()
                }
            }
            ActionReleasePenalty -> {
                zoneCoordinator?.releaseByUser()
            }
            ActionRestoreOverlay -> {
                minimizedStateBeforeConfiguration?.let { previousState ->
                    sensorViewModel?.setMinimized(previousState)
                    minimizedStateBeforeConfiguration = null
                }
            }
        }
        syncBackgroundExecutionGuards()
        return START_STICKY
    }

    override fun onDestroy() {
        mutableIsRunning.value = false
        (application as com.spop.poverlay.GrupettoApplication).recorder.detach()
        // Stop before tearing down the views: this hands back any media we paused.
        zoneCoordinator?.stop()
        zoneCoordinator = null
        HeartRateManager.setManaging(false, HeartRateManager.OwnerOverlay)
        debugHeartRateReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugHeartRateReceiver = null
        removeOverlayViews()
        releaseWakeLock()
        sensorViewModel = null
        super.onDestroy()
    }

    private fun buildDialog() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val screenSize = Size(
            resources.displayMetrics.widthPixels.toFloat(),
            resources.displayMetrics.heightPixels.toFloat()
        )

        val sensorInterface = if (IsRunningOnPeloton) {
            if (IsG700CrossTrainer || IsBikePlus) {
                PelotonBikePlusSensorInterface(this).also {
                    lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) {
                            it.stop()
                        }
                    })
                }
            } else {
                PelotonBikeSensorInterfaceV1New(this).also {
                    lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) {
                            it.stop()
                        }
                    })
                }
            }

        } else {
            EmulatorSensorInterface
        }

        val configurationRepository = ConfigurationRepository(applicationContext, this)

        val timerViewModel = OverlayTimerViewModel(
            application,
            configurationRepository,
            sensorInterface.power
        )

        val sensorViewModel = OverlaySensorViewModel(
            application,
            sensorInterface,
            DeadSensorDetector(sensorInterface, this.coroutineContext),
            timerViewModel
        )
        this.sensorViewModel = sensorViewModel
        // Wire up timer to auto-start/pause based on movement

        val dialogViewModel = OverlayDialogViewModel(screenSize)

        val coordinator = ZoneEnforcementCoordinator(
            scope = lifecycleScope,
            configFlow = configurationRepository.zoneEnforcementConfig,
            isMovingFlow = sensorViewModel.isMoving,
            powerFlow = sensorInterface.power,
            cadenceFlow = sensorInterface.cadence,
            onWorkoutEnded = sensorViewModel::resetWorkout,
            onMovementChanged = sensorViewModel::setMoving,
            media = MediaPenaltyController(applicationContext),
            persistence = ZonePersistence(applicationContext),
            foreground = ForegroundAppMonitor(applicationContext),
            onShowCurtain = ::showPenaltyCurtain,
            onHideCurtain = ::hidePenaltyCurtain,
        )
        zoneCoordinator = coordinator
        coordinator.start()
        val recordingApp = application as com.spop.poverlay.GrupettoApplication
        recordingApp.recorder.attach(sensorInterface,
            start = { coordinator.resetForRecording(false) },
            end = { coordinator.resetForRecording(true) })
        lifecycleScope.launch {
            recordingApp.recorder.moving.collect { syncBackgroundExecutionGuards() }
        }

        // Initialize and start watchdog (always enabled)
        val watchdogThreshold = 30.minutes
        val watchdog = CadenceWatchdog(sensorInterface, this.coroutineContext, watchdogThreshold)
        watchdog.start()
        
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                watchdog.stop()
            }
        })
        
        // Handle watchdog restart trigger
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchdog.restartTriggered.collect {
                    Timber.w(
                        "Watchdog triggered restart - no cadence detected for ${watchdogThreshold.inWholeMinutes} minutes"
                    )
                    restartToOverlay()
                }
            }
        }

        val layoutFlag = overlayWindowType()


        val overlayParams = LayoutParams(
            200,
            LayoutParams.WRAP_CONTENT,
            layoutFlag,
            DefaultOverlayFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            disableAnimations()
        }

        overlayView = ComposeView(this).apply {
            lifecycleViaService()
            setViewCompositionStrategy(
                ViewCompositionStrategy
                    .DisposeOnLifecycleDestroyed(this@OverlayService)
            )
            setContent {
                Overlay(
                    sensorViewModel,
                    timerViewModel,
                    OverlayHeightDp,
                    dialogViewModel.dialogLocation.collectAsState(),
                    dialogViewModel::processHorizontalDrag,
                    dialogViewModel::processVerticalDrag,
                    dialogViewModel::onOverlayLayout,
                    dialogViewModel::onTimerOverlayLayout
                )
            }
            alpha = 0.9f
            isFocusable = false
            clipToPadding = false
            clipChildren = false
            clipToOutline = false
        }
        val overlay = overlayView!!
        wm.addView(overlay, overlayParams)

        //Subscribe to Dialog view model and update views
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(
                    dialogViewModel.dialogOrigin,
                    dialogViewModel.dialogGravity,
                    dialogViewModel.dialogSizeParams,
                    dialogViewModel.minimizedDialogSizeParams,
                    sensorViewModel.isMinimized,
                ) { origin, gravity, expandedSize, stripSize, minimized ->
                    overlayParams.x = origin.x.roundToInt()
                    overlayParams.y = origin.y.roundToInt()
                    overlayParams.flags = DefaultOverlayFlags
                    overlayParams.gravity = gravity
                    overlayParams.width = if (minimized) stripSize.first
                        else maxOf(expandedSize.first, stripSize.first)
                    overlayParams.height = LayoutParams.WRAP_CONTENT
                    // The visible strip owns its touches in both modes. Hidden metric cards
                    // are removed from layout instead of covered by an invisible window.
                    val currentOverlay = overlayView ?: return@combine
                    disableClipOnParents(currentOverlay)
                    wm.updateViewLayout(currentOverlay, overlayParams)
                }.collect {}
            }
        }
    }

    private fun disableClipOnParents(v: View) {
        if (v.parent == null) {
            return
        }
        if (v is ViewGroup) {
            v.clipChildren = false
        }
        if (v.parent is View) {
            disableClipOnParents(v.parent as View)
        }
    }

    private fun restartToOverlay() {
        Timber.i("Restarting Grupetto to overlay due to watchdog trigger")
        
        // Stop the current service
        stopSelf()
        
        // Start the overlay service again
        val restartIntent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, restartIntent)
        
        // Exit the process to ensure clean restart
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Runtime.getRuntime().exit(0)
        }, 500)
    }

    private fun prepareNotification(notificationManager: NotificationManagerCompat): Notification {
        val channelId = OverlayNotificationChannelId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(channelId) == null
        ) {
            val name: CharSequence = getString(R.string.overlay_notification)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance)
            channel.enableVibration(false)
            notificationManager.createNotificationChannel(channel)
        }
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            intentFlags
        )

        val notificationBuilder: NotificationCompat.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationCompat.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                NotificationCompat.Builder(this)
            }

        notificationBuilder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        // The escape hatch that does not depend on the curtain drawing. If addView ever fails,
        // this is the only way left to get the media back without force-quitting the app - which
        // is what makes it safe for a penalty to otherwise hold indefinitely.
        val releaseIntent = Intent(this, OverlayService::class.java).apply {
            action = ActionReleasePenalty
        }
        val releasePendingIntent = PendingIntent.getService(
            this,
            1,
            releaseIntent,
            intentFlags
        )
        notificationBuilder.addAction(
            0,
            getString(R.string.end_enforcement_action),
            releasePendingIntent
        )
        val finishIntent = Intent(this, MainActivity::class.java).putExtra("finishWorkout", true)
        notificationBuilder.addAction(0, "Finish workout", PendingIntent.getActivity(this, 7, finishIntent, intentFlags))
        notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return notificationBuilder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Grupetto:OverlayWakelock").apply {
            setReferenceCounted(false)
            acquire(WakeLockTimeoutMs)
        }
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(WakeLockRenewIntervalMs)
                wakeLock?.let {
                    if (it.isHeld) {
                        it.acquire(WakeLockTimeoutMs)
                    }
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun syncBackgroundExecutionGuards() {
        val bleEnabled = isBleTxEnabled()
        val dirConEnabled = isDirConEnabled()
        val shouldRunBle = bleEnabled && hasBleRuntimePermissions()

        if (shouldRunBle) {
            bleServer.setDirConTransportEnabled(dirConEnabled)
            bleServer.start()
        } else {
            bleServer.stop()
            bleServer.setDirConTransportEnabled(dirConEnabled)
        }

        if (shouldRunBle || dirConEnabled || (application as com.spop.poverlay.GrupettoApplication).recorder.moving.value) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun isBleTxEnabled(): Boolean {
        val prefs = getSharedPreferences(ConfigurationRepository.SharedPrefsName, MODE_PRIVATE)
        return prefs.getBoolean(ConfigurationRepository.Preferences.BleTxEnabled.key, true)
    }

    private fun isDirConEnabled(): Boolean {
        val prefs = getSharedPreferences(ConfigurationRepository.SharedPrefsName, MODE_PRIVATE)
        return prefs.getBoolean(ConfigurationRepository.Preferences.DirConEnabled.key, true)
    }

    private fun hasBleRuntimePermissions(): Boolean {
        val hasBaseBluetooth = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED

        val hasBluetoothAdmin = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED

        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            return hasBaseBluetooth && hasBluetoothAdmin && hasFineLocation && hasAdvertise && hasConnect
        }

        return hasBaseBluetooth && hasBluetoothAdmin && hasFineLocation
    }

    /**
     * Debug builds only: lets zone enforcement be driven from the command line.
     *
     *   adb shell am broadcast -a com.spop.poverlay.debug.SET_HR --ei bpm 130
     *   adb shell am broadcast -a com.spop.poverlay.debug.SET_HR --ei bpm -1   (strap dropout)
     *
     * Without this, every change to the state machine costs a real 45 minute ride to verify.
     */
    private fun registerDebugHeartRateReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val bpm = intent?.getIntExtra(DebugHeartRateExtra, -1) ?: -1
                HeartRateManager.injectDebugHeartRate(bpm.takeIf { it > 0 })
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ActionDebugSetHeartRate),
            ContextCompat.RECEIVER_EXPORTED,
        )
        debugHeartRateReceiver = receiver
        Timber.i("Debug heart rate injection enabled")
    }

    private fun overlayWindowType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        LayoutParams.TYPE_SYSTEM_ALERT
    }

    /**
     * Adds the full-screen penalty curtain as a third window, independent of the stat overlay
     * and its layout pipeline. Failing to add it is logged but never propagated: the media
     * resume path must not be blocked by a missing curtain.
     */
    private fun showPenaltyCurtain() {
        if (curtainView != null) return
        val wm = windowManager ?: return
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            CurtainFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            disableAnimations()
        }
        val view = ComposeView(this).apply {
            lifecycleViaService()
            // Not DisposeOnLifecycleDestroyed: the curtain is added and removed many times per
            // ride, and that strategy would leak a composition on every penalty.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                val snapshot by ZoneRuntime.snapshot.collectAsState()
                snapshot?.let {
                    PenaltyCurtain(
                        snapshot = it,
                        onRelease = { zoneCoordinator?.releaseByUser() },
                    )
                }
            }
        }
        runCatching { wm.addView(view, params) }
            .onSuccess { curtainView = view }
            .onFailure { Timber.e(it, "Failed to show penalty curtain") }
    }

    /**
     * Takes the curtain down.
     *
     * Deliberately [WindowManager.removeView] and not `removeViewImmediate`. This runs while
     * the rider's finger is on the Release button, and an immediate removal detaches the view
     * synchronously inside touch dispatch - which makes Compose cancel a pointer stream it is
     * in the middle of resuming, throw, and unwind with the curtain still on screen and its
     * reference already dropped. The result is a penalty screen that can never be removed
     * again. Deferring the detach by one message avoids the whole thing.
     */
    private fun hidePenaltyCurtain() {
        val view = curtainView ?: return
        curtainView = null
        val wm = windowManager ?: return
        runCatching { wm.removeView(view) }
            .onFailure { first ->
                // Never leave a curtain on screen with nothing holding a reference to it.
                Timber.w(first, "Failed to remove penalty curtain; retrying off the dispatch")
                view.post {
                    runCatching { wm.removeView(view) }
                        .onFailure { Timber.e(it, "Penalty curtain could not be removed") }
                }
            }
    }

    private fun removeOverlayViews() {
        val wm = windowManager
        val hasViews = overlayView != null || curtainView != null
        if (wm != null && hasViews) {
            overlayView?.let {
                runCatching { wm.removeViewImmediate(it) }
                    .onFailure { ex -> Timber.w(ex, "Failed to remove overlay view") }
            }
            curtainView?.let {
                runCatching { wm.removeViewImmediate(it) }
                    .onFailure { ex -> Timber.w(ex, "Failed to remove penalty curtain") }
            }
        } else if (wm == null && hasViews) {
            Timber.e("WindowManager unavailable during cleanup; overlay views may remain attached and leak")
        }
        overlayView = null
        curtainView = null
        windowManager = null
    }
}
