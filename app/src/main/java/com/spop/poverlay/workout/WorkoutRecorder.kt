package com.spop.poverlay.workout

import android.content.Context
import android.os.SystemClock
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.strava.StravaAuthManager
import com.spop.poverlay.strava.StravaUploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** All recording commands are serialized; samples are committed before the next state is published. */
class WorkoutRecorder(
    private val context: Context, private val repository: WorkoutRepository,
    private val settings: WorkoutSettings, private val auth: StravaAuthManager,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    val current = MutableStateFlow<Workout?>(null)
    val error = MutableStateFlow<String?>(null)
    val finishRequested = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val moving = MutableStateFlow(false)
    private val mutex = Mutex()
    private var jobs = listOf<Job>()
    private var onStart: suspend () -> Unit = {}
    private var onEnd: suspend () -> Unit = {}
    private var latestPower: Reading? = null
    private var latestCadence: Reading? = null
    private var latestSpeed: Reading? = null
    private var latestResistance: Reading? = null
    private var lastTick = 0L
    private var lastPedaledElapsed: Long? = null
    private var startedElapsed: Long? = null
    private var clockAnchor = 0L
    private var elapsedAnchor = 0L
    data class Reading(val value: Float, val at: Long) {
        fun fresh(now: Long): Float? = value.takeIf { it.isFinite() && it >= 0 && now - at in 0..10_000 }
    }

    init { scope.launch { guarded { auth.initialized.await(); reconcile() } } }
    private suspend fun reconcile() = mutex.withLock {
        if (current.value != null) return@withLock
        val old = repository.dao.unfinished()
        if (old != null) {
            current.value = old.copy(status = WorkoutStatus.INTERRUPTED)
            repository.dao.update(current.value!!)
            if (expired(old, wallClock(), null)) finishLocked("Inactivity", true)
        }
        repository.dao.pending().forEach { StravaUploadWorker.enqueue(context, it.id) }
    }
    private fun expired(w: Workout, wall: Long, elapsed: Long?): Boolean {
        val limit = settings.timeoutMs
        if (limit == 0L) return false
        val gap = if (elapsed != null && (lastPedaledElapsed ?: startedElapsed) != null)
            elapsed - (lastPedaledElapsed ?: startedElapsed)!! else wall - (w.lastPedaledAt ?: w.startedAt)
        return gap >= limit || (elapsed == null && gap < 0)
    }

    fun attach(sensors: SensorInterface, start: suspend () -> Unit, end: suspend () -> Unit) {
        jobs.forEach { it.cancel() }
        onStart = start; onEnd = end
        ready.value = true
        fun reading(value: Float) = Reading(value, elapsedClock())
        jobs = listOf(
            scope.launch { sensors.power.collect { latestPower = reading(it) } },
            scope.launch { sensors.speed.collect { latestSpeed = reading(it) } },
            scope.launch { sensors.resistance.collect { latestResistance = reading(it) } },
            scope.launch { sensors.cadence.collect {
                val elapsed = elapsedClock()
                // End an expired session BEFORE accepting a returning rider's sample.
                guarded { mutex.withLock {
                    current.value?.let { w -> if (expired(w, wallClock(), elapsedClock())) finishLocked("Inactivity", true) }
                    latestCadence = reading(it)
                    if (it.isFinite() && it >= 1f && elapsed >= elapsedAnchor && current.value?.status == WorkoutStatus.RECORDING) {
                        lastPedaledElapsed = elapsed
                        current.value = current.value!!.copy(lastPedaledAt = clockAnchor + elapsed - elapsedAnchor)
                    }
                } }
            } },
            scope.launch { while (isActive) { guarded { tick() }; delay(1_000) } },
        )
    }
    fun detach() {
        jobs.forEach { it.cancel() }; jobs = emptyList()
        ready.value = false; moving.value = false
        onStart = {}; onEnd = {}
        latestPower = null; latestCadence = null; latestSpeed = null; latestResistance = null
        lastPedaledElapsed = null; startedElapsed = null
        scope.launch { guarded { mutex.withLock {
            current.value?.takeIf { it.status == WorkoutStatus.RECORDING }?.let {
                val interrupted = it.copy(status = WorkoutStatus.INTERRUPTED)
                repository.dao.update(interrupted); current.value = interrupted
            }
        } } }
    }
    suspend fun start(resume: Boolean = false) = guarded { auth.initialized.await(); mutex.withLock {
        check(ready.value) { "Start the overlay before recording." }
        if (current.value != null && !resume) return@withLock
        if (resume && current.value?.status != WorkoutStatus.INTERRUPTED) return@withLock
        if (resume && expired(current.value!!, wallClock(), null)) {
            finishLocked("Inactivity", true); return@withLock
        }
        val now = wallClock()
        val workout = if (resume) current.value!!.copy(status = WorkoutStatus.RECORDING) else
            Workout(UUID.randomUUID().toString(), now, athleteId = auth.athleteId.value, deliveryOrigin = auth.origin.value)
        if (resume) repository.dao.update(workout) else repository.dao.insert(workout)
        clockAnchor = maxOf(now, repository.dao.samples(workout.id).lastOrNull()?.timeMs?.plus(1) ?: now)
        elapsedAnchor = elapsedClock(); lastTick = elapsedAnchor
        startedElapsed = elapsedAnchor - (now - (workout.lastPedaledAt ?: workout.startedAt)).coerceAtLeast(0)
        lastPedaledElapsed = null
        current.value = workout; error.value = null
        if (!resume) onStart()
    } }
    suspend fun finish(discard: Boolean = false) = guarded { mutex.withLock {
        if (discard) {
            current.value?.let { repository.dao.delete(it.id) }
            current.value = null; moving.value = false; finishRequested.value = false; onEnd()
        } else finishLocked("Finished manually", false)
    } }
    private suspend fun finishLocked(reason: String, trim: Boolean) {
        val w = current.value ?: return
        // No pedaling is kept locally as a zero-effort record and never uploaded.
        val end = if (trim) w.lastPedaledAt ?: w.startedAt else maxOf(w.startedAt, repository.dao.samples(w.id).lastOrNull()?.timeMs ?: w.startedAt)
        val autoUpload = w.lastPedaledAt != null && settings.autoUpload && w.athleteId != null && w.athleteId == auth.athleteId.value
        repository.finalize(w, end, reason, queueUpload = autoUpload)
        current.value = null; moving.value = false; finishRequested.value = false
        onEnd()
        if (autoUpload) StravaUploadWorker.enqueue(context, w.id)
    }
    suspend fun queue(workout: Workout) {
        val connection = auth.connection()
        val athlete = connection.athlete
        check(workout.endedAt != null && workout.lastPedaledAt != null) { "Only finished workouts with pedaling can be uploaded." }
        check(workout.athleteId == null || workout.athleteId == athlete) { "Reconnect this workout's original Strava account." }
        check(workout.status !in listOf(WorkoutStatus.UPLOADED, WorkoutStatus.UPLOADING, WorkoutStatus.PROCESSING, WorkoutStatus.QUEUED)) { "This workout is already uploaded or scheduled." }
        val retryNew = workout.status in listOf(WorkoutStatus.FAILED, WorkoutStatus.REVIEW)
        check(workout.deliveryOrigin == null || workout.deliveryOrigin == connection.origin) { "Reconnect this workout's original upload server." }
        repository.dao.update(workout.copy(deliveryOrigin = connection.origin,
            deliveryId = if (retryNew) UUID.randomUUID().toString() else workout.deliveryId ?: workout.id,
            athleteId = athlete, status = if (workout.uploadId != null && workout.status != WorkoutStatus.FAILED && workout.status != WorkoutStatus.REVIEW) WorkoutStatus.PROCESSING else WorkoutStatus.QUEUED,
            uploadId = if (workout.status in listOf(WorkoutStatus.FAILED, WorkoutStatus.REVIEW)) null else workout.uploadId, error = null))
        StravaUploadWorker.enqueue(context, workout.id)
    }
    internal suspend fun tick() = mutex.withLock {
        val elapsed = elapsedClock()
        val w = current.value ?: return@withLock
        if (expired(w, wallClock(), elapsed)) { finishLocked("Inactivity", true); return@withLock }
        if (w.status != WorkoutStatus.RECORDING) return@withLock
        val cadence = latestCadence?.fresh(elapsed)
        val riding = cadence != null && cadence >= 1f
        moving.value = riding
        val delta = (elapsed - lastTick).coerceIn(0, 2_000) / 1000.0
        lastTick = elapsed
        val speed = latestSpeed?.fresh(elapsed)?.toDouble()?.times(0.44704)?.takeIf { riding }
        val distance = w.distanceMeters + (speed ?: 0.0) * delta
        val time = clockAnchor + elapsed - elapsedAnchor
        val updated = w.copy(distanceMeters = distance)
        repository.append(updated, WorkoutSample(w.id, time, latestPower?.fresh(elapsed), cadence,
            latestResistance?.fresh(elapsed), speed, HeartRateManager.heartRate.value?.takeIf {
                it in 1..254 && HeartRateManager.heartRateAgeMs in 0..10_000
            }, distance))
        current.value = updated
    }
    private suspend fun guarded(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            error.value = e.message ?: "Recording stopped. Your saved samples are retained."
            current.value = current.value?.copy(status = WorkoutStatus.INTERRUPTED)
            moving.value = false
        }
    }
}
