package com.spop.poverlay.workout

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.strava.StravaUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun WorkoutOverlayControls(minimized: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as GrupettoApplication
    val recorder = app.recorder
    val current by recorder.current.collectAsState()
    val ready by recorder.ready.collectAsState()
    val confirm by recorder.finishRequested.collectAsState()
    val error by recorder.error.collectAsState()
    val scope = rememberCoroutineScope()
    Column(if (minimized) Modifier else Modifier.background(Color(25, 25, 25)).padding(horizontal = 8.dp)) {
        if (confirm && current != null) {
            if (!minimized) Text("Finish this workout?", color = Color.White)
            Row {
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), onClick = { scope.launch { recorder.finish() } }) { Text("Save & finish") }
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), onClick = { recorder.finishRequested.value = false }) { Text("Keep riding") }
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), onClick = { scope.launch { recorder.finish(discard = true) } }) { Text("Discard") }
            }
        } else if (current?.status == WorkoutStatus.INTERRUPTED) {
            Row {
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), enabled = ready, onClick = { scope.launch { recorder.start(resume = true) } }) { Text("Resume workout") }
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), onClick = { recorder.finishRequested.value = true }) { Text("Finish interrupted ride") }
            }
        } else {
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), enabled = ready || current != null, onClick = {
                if (current != null) recorder.finishRequested.value = true else scope.launch { recorder.start() }
            }) {
                Text(when {
                    current == null -> "Start workout"
                    minimized -> "Finish workout"
                    else -> "Recording · Finish workout"
                })
            }
        }
        error?.let { Text(it, color = Color(0xFFFFAB91)); TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80BFFF)), onClick = { recorder.error.value = null }) { Text("Dismiss") } }
    }
}

@Composable
fun WorkoutSettingsCard() {
    val context = LocalContext.current
    val app = context.applicationContext as GrupettoApplication
    val scope = rememberCoroutineScope()
    val athlete by app.strava.athleteName.collectAsState()
    val authMessage by app.strava.message.collectAsState()
    val authUrl by app.strava.authorizationUrl.collectAsState()
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var callback by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf(app.workoutSettings.timeoutMinutes) }
    var automatic by remember { mutableStateOf(app.workoutSettings.autoUpload) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val current by app.recorder.current.collectAsState()
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { failure = "No browser available. Copy the authorization link and open it on another device, then paste the returned URL." }
    }
    Card(Modifier.fillMaxWidth().padding(16.dp), elevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("Workouts & Strava", style = MaterialTheme.typography.h6)
            Text(athlete?.let { "Connected as $it" } ?: "Record locally, then connect your personal Strava account to upload.")
            WorkoutOverlayControls()
            if (!app.recorder.ready.collectAsState().value && current == null) Text("Start the overlay to begin recording.")
            Text("Auto-end after no pedaling: ${if (timeout == 0) "Off" else "$timeout minutes"}")
            Row {
                WorkoutSettings.choices.forEach { minutes ->
                    TextButton(onClick = { timeout = minutes; app.workoutSettings.timeoutMinutes = minutes }) {
                        Text(if (minutes == 0) "Off" else "$minutes", color = if (timeout == minutes) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
                    }
                }
            }
            Text("Shared with zone enforcement. Trailing inactivity is excluded from saved rides.", style = MaterialTheme.typography.caption)
            Row {
                Checkbox(checked = automatic, onCheckedChange = { automatic = it; app.workoutSettings.autoUpload = it })
                Text("Automatically upload finished workouts", Modifier.padding(top = 12.dp))
            }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide connection settings" else "Strava connection") }
                TextButton(onClick = { history = true }) { Text("Workout history") }
            }
            if (expanded) {
                Text("Personal setup: create your own Strava API application. Set Authorization Callback Domain to 127.0.0.1. Enter its client ID and secret here once. Your Strava password stays in the browser.")
                TextButton(onClick = { open("https://www.strava.com/settings/api") }) { Text("Open Strava API settings") }
                OutlinedTextField(clientId, { clientId = it }, label = { Text("Personal client ID") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, label = { Text("Personal client secret") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row {
                    Button(enabled = !busy && current == null && athlete == null, onClick = {
                        busy = true; failure = null
                        scope.launch {
                            try {
                                WorkManager.getInstance(context).cancelAllWorkByTag(StravaUploadWorker.Tag)
                                val url = app.strava.connect(clientId, secret)
                                secret = ""
                                open(url)
                            } catch (e: Exception) { failure = e.message ?: "Could not connect." }
                            finally { busy = false }
                        }
                    }) { Text("Connect Strava") }
                    TextButton(enabled = !busy && current == null, onClick = {
                        scope.launch {
                            WorkManager.getInstance(context).cancelAllWorkByTag(StravaUploadWorker.Tag)
                            app.strava.disconnect()
                        }
                    }) { Text("Disconnect") }
                }
                if (current != null) Text("Finish the current workout before changing accounts.")
                if (athlete != null) Text("Disconnect first to reconnect or change credentials.")
                authUrl?.let { url ->
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strava authorization", url))
                    }) { Text("Copy authorization link") }
                    Text("If the browser cannot return automatically, paste its complete final URL here. On another device the callback page may fail to load; its address still contains the response.")
                    OutlinedTextField(callback, { callback = it }, label = { Text("Returned URL") })
                    Row {
                        TextButton(onClick = { scope.launch {
                            try { app.strava.acceptCallback(callback); callback = "" }
                            catch (e: Exception) { failure = e.message }
                        } }) { Text("Complete connection") }
                        TextButton(onClick = { app.strava.cancelAuthorization() }) { Text("Cancel connection") }
                    }
                }
            }
            authMessage?.let { Text(it) }
            failure?.let { Text(it, color = MaterialTheme.colors.error) }
        }
    }
    if (history) WorkoutHistory(onDismiss = { history = false })
}

@Composable
private fun WorkoutHistory(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GrupettoApplication
    val scope = rememberCoroutineScope()
    val workouts by app.workouts.history.collectAsState(initial = emptyList())
    var failure by remember { mutableStateOf<String?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Workout?>(null) }
    var retrying by remember { mutableStateOf<Workout?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val id = exportId
        if (uri != null && id != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                val workout = app.workouts.dao.get(id) ?: error("Workout was deleted.")
                val text = TcxExporter.export(workout, app.workouts.dao.samples(id))
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Could not open export destination.")
            } } catch (e: Exception) { failure = e.message }
        }
    }
    fun upload(w: Workout) { scope.launch {
        try { app.recorder.queue(w) } catch (e: Exception) { failure = e.message }
    } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Workout history") },
        buttons = { TextButton(onClick = onDismiss) { Text("Close") } }, text = {
            Column {
                failure?.let { Text(it, color = MaterialTheme.colors.error) }
                Text("Keeps 30 uploaded rides and all unsynced rides. Deleting here does not delete from Strava.")
                LazyColumn(Modifier.heightIn(max = 500.dp)) {
                    items(workouts, key = { it.id }) { w ->
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(w.startedAt)), style = MaterialTheme.typography.subtitle1)
                            Text("${w.status} · ${((w.endedAt ?: w.startedAt) - w.startedAt) / 60_000} min · ${"%.2f".format(w.distanceMeters / 1000)} km estimated")
                            w.reason?.let { Text(it) }; w.error?.let { Text(it, color = MaterialTheme.colors.error) }
                            Row {
                                if (w.endedAt != null) {
                                    TextButton(onClick = { exportId = w.id; exporter.launch("grupetto-${w.id}.tcx") }) { Text("Export TCX") }
                                    if (w.status in listOf(WorkoutStatus.LOCAL, WorkoutStatus.FAILED, WorkoutStatus.REVIEW, WorkoutStatus.AUTH_REQUIRED)) {
                                        TextButton(onClick = { if (w.status == WorkoutStatus.REVIEW) retrying = w else upload(w) }) { Text("Upload / Retry") }
                                    }
                                    if (w.status !in listOf(WorkoutStatus.QUEUED, WorkoutStatus.UPLOADING, WorkoutStatus.PROCESSING)) {
                                        TextButton(onClick = { deleting = w }) { Text("Delete locally") }
                                    }
                                }
                            }
                            w.activityId?.let { id -> TextButton(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$id"))) }
                                catch (_: Exception) { failure = "No browser available." }
                            }) { Text("View on Strava") } }
                            Divider()
                        }
                    }
                }
            }
        })
    deleting?.let { w -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete local workout?") },
        text = { Text("This removes the local samples permanently. Strava is unchanged.") },
        confirmButton = { TextButton(onClick = { scope.launch { app.workouts.dao.delete(w.id) }; deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    retrying?.let { w -> AlertDialog(onDismissRequest = { retrying = null }, title = { Text("Check Strava before retrying") },
        text = { Text("The previous submission may have succeeded. Retry only after checking your Strava activities to avoid a duplicate.") },
        confirmButton = { TextButton(onClick = { upload(w); retrying = null }) { Text("Checked — retry") } },
        dismissButton = { TextButton(onClick = { retrying = null }) { Text("Cancel") } }) }
}
