package com.spop.poverlay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.media.GrupettoNotificationListenerService
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import com.spop.poverlay.zone.ZoneRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ConfigurationViewModel

    /**
     * Opens the notification-access screen so the user can enable Grupetto's media control.
     *
     * Some locked-down Peloton builds ship without this Settings activity, hence the fallback to
     * the adb command - this audience already sideloads, so it is a usable instruction rather
     * than a dead end. The feature still works without the grant, via media keys.
     */
    private fun openNotificationListenerSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            }
        }
        // Built from the real component so the command stays correct for the .dev build, whose
        // applicationId is suffixed while the class name is not.
        val component = ComponentName(this, GrupettoNotificationListenerService::class.java)
        Toast.makeText(
            this,
            "This tablet has no notification access screen. Run:\n" +
                "adb shell cmd notification allow_listener ${component.flattenToString()}",
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel =
            ConfigurationViewModel(
                application, ConfigurationRepository(applicationContext, this),
                ReleaseChecker()
            )
        viewModel.finishActivity.observe(this) {
            finish()
        }
        viewModel.requestOverlayPermission.observe(this) {
            requestScreenPermission()
        }
        viewModel.requestBluetoothPermissions.observe(this) { permissions ->
            requestBluetoothPermissions(permissions)
        }
        viewModel.requestRestart.observe(this) {
            restartGrupetto()
        }
        viewModel.requestQuit.observe(this) {
            quitGrupetto()
        }
        viewModel.requestIgnoreBatteryOptimizations.observe(this) {
            requestIgnoreBatteryOptimizations()
        }
        viewModel.requestNotificationListenerAccess.observe(this) {
            openNotificationListenerSettings()
        }
        viewModel.infoPopup.observe(this) {
            Toast.makeText(
                this,
                it,
                Toast.LENGTH_LONG
            ).show()
        }
        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    ConfigurationPage(
                        viewModel
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onResume()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The penalty curtain draws over every app, this one included. Covering the settings
        // someone came here to change - very possibly to turn enforcement off - would be
        // obnoxious, so the overlay stands down while this is on screen.
        ZoneRuntime.setSettingsVisible(true)
        viewModel.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        ZoneRuntime.setSettingsVisible(false)
        viewModel.onAppStopped()
    }

    private fun restartGrupetto() {
        Toast.makeText(
            this@MainActivity,
            HtmlCompat.fromHtml("<big>Restarting Grupetto</big>", HtmlCompat.FROM_HTML_MODE_LEGACY),
            Toast.LENGTH_LONG
        )
            .apply { setGravity(Gravity.CENTER, 0, 0) }
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            delay(1500L)
            val pm: PackageManager = applicationContext.packageManager
            val intent = pm.getLaunchIntentForPackage(applicationContext.packageName)
            val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
            applicationContext.startActivity(mainIntent)
            Runtime.getRuntime().exit(0)
        }
    }

    private fun quitGrupetto() {
        Toast.makeText(
            this@MainActivity,
            HtmlCompat.fromHtml("<big>Closing Grupetto</big>", HtmlCompat.FROM_HTML_MODE_LEGACY),
            Toast.LENGTH_LONG
        ).apply { setGravity(Gravity.CENTER, 0, 0) }.show()

        CoroutineScope(Dispatchers.Main).launch {
            // Explicitly stop long-running components before closing the task so Android won't revive it.
            stopService(Intent(this@MainActivity, OverlayService::class.java))
            HeartRateManager.stop()
            (application as GrupettoApplication).bleServer.stop()
            delay(750L)
            finishAffinity()
            finishAndRemoveTask()
        }
    }

    private val overlayPermissionRequest =
        registerForActivityResult(StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= 23) {
                viewModel.onOverlayPermissionRequestCompleted(
                    Settings.canDrawOverlays(this)
                )
            }
        }

    private val bluetoothPermissionRequest =
        registerForActivityResult(RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            viewModel.onBluetoothPermissionsResult(allGranted)
        }

    private val batteryOptimizationRequest =
        registerForActivityResult(StartActivityForResult()) {
            viewModel.onBatteryOptimizationRequestCompleted()
        }

    private fun requestScreenPermission() = Intent(
        "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        Uri.parse("package:${packageName}")
    ).apply {
        overlayPermissionRequest.launch(this)
    }

    private fun requestBluetoothPermissions(permissions: Array<String>) {
        bluetoothPermissionRequest.launch(permissions)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)

        try {
            batteryOptimizationRequest.launch(requestIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            batteryOptimizationRequest.launch(fallbackIntent)
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PTONOverlayTheme {
        // Preview placeholder - actual ConfigurationPage requires ViewModel
    }
}
