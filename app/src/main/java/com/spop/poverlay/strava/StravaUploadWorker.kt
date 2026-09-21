package com.spop.poverlay.strava

import android.content.Context
import androidx.work.*
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.workout.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class StravaUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GrupettoApplication
        val id = inputData.getString("workoutId") ?: return Result.failure()
        return StravaUploader(app.workouts, app.strava).upload(id, runAttemptCount)
    }
    companion object {
        const val Tag = "strava-upload"
        fun enqueue(context: Context, id: String) {
            val work = OneTimeWorkRequestBuilder<StravaUploadWorker>()
                .setInputData(workDataOf("workoutId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(Tag).build()
            WorkManager.getInstance(context).enqueueUniqueWork("strava-$id", ExistingWorkPolicy.KEEP, work)
        }
    }

}

/** Idempotent delivery to the upload server; only the backend submits activities to Strava. */
class StravaUploader(private val repository: WorkoutRepository, private val auth: StravaAuthManager) {
    suspend fun upload(id: String, runAttemptCount: Int = 0): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val dao = repository.dao
        var workout = dao.get(id) ?: return@withContext ListenableWorker.Result.success()
        if (workout.endedAt == null || workout.status !in listOf(WorkoutStatus.QUEUED, WorkoutStatus.PROCESSING, WorkoutStatus.UPLOADING))
            return@withContext ListenableWorker.Result.success()
        if (workout.retryAfter > System.currentTimeMillis()) return@withContext ListenableWorker.Result.retry()
        try {
            val connection = auth.connection()
            check(connection.athlete == workout.athleteId) { "This workout belongs to a different Strava account. Reconnect its original rider." }
            check(workout.deliveryOrigin == null || workout.deliveryOrigin == connection.origin) { "Reconnect this workout's original upload server." }
            // Legacy in-flight submissions must never be resent automatically.
            if (workout.deliveryId == null && workout.uploadId != null) {
                dao.update(workout.copy(status = WorkoutStatus.REVIEW, error = "Check the previous direct Strava upload before retrying through your upload server."))
                return@withContext ListenableWorker.Result.success()
            }
            workout = workout.copy(deliveryOrigin = connection.origin, deliveryId = workout.deliveryId ?: workout.id)
            dao.update(workout)
            val endpoint = connection.origin + "/device/v1/workouts/" + workout.deliveryId
            var response = try { auth.request(endpoint, credential = connection.credential) }
                catch (e: StravaHttpException) { if (e.status == 404) null else throw e }
            if (response == null || response.optString("status") == "reconnect" || response.optString("status") == "receiving") {
                val tcx = TcxExporter.export(workout, dao.samples(id))
                workout = workout.copy(status = WorkoutStatus.UPLOADING, error = null)
                dao.update(workout)
                // The upload server requires application/xml exactly. The String overload adds a charset parameter.
                response = auth.request(endpoint, "PUT", tcx.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType()), connection.credential)
            }
            repeat(4) { attempt ->
                val status = response!!.getString("status")
                val mapped = when (status) {
                    "uploaded" -> WorkoutStatus.UPLOADED
                    "review", "expired" -> WorkoutStatus.REVIEW
                    "failed" -> WorkoutStatus.FAILED
                    "reconnect" -> WorkoutStatus.AUTH_REQUIRED
                    "receiving", "queued", "submitting", "processing" -> WorkoutStatus.PROCESSING
                    else -> error("Unexpected delivery status. Check your upload server.")
                }
                workout = workout.copy(status = mapped,
                    uploadId = response!!.optString("uploadId").toLongOrNull(),
                    activityId = response!!.optString("activityId").toLongOrNull(),
                    error = if (response!!.isNull("error")) null else response!!.optString("error"),
                    retryAfter = response!!.optLong("retryAt", 0))
                dao.update(workout)
                if (mapped != WorkoutStatus.PROCESSING) {
                    if (mapped == WorkoutStatus.UPLOADED) dao.prune()
                    return@withContext ListenableWorker.Result.success()
                }
                if (attempt < 3) { delay(3_000); response = auth.request(endpoint, credential = connection.credential) }
            }
            ListenableWorker.Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: StravaHttpException) {
            val retry = e.status == 429 || e.status >= 500
            dao.update(workout.copy(status = when {
                e.status == 401 || e.status == 403 -> WorkoutStatus.AUTH_REQUIRED
                e.status == 409 -> WorkoutStatus.REVIEW
                retry -> WorkoutStatus.QUEUED
                else -> WorkoutStatus.FAILED
            }, retryAfter = if (retry) e.retryAt else 0, error = e.message))
            if (retry) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
        } catch (e: IOException) {
            dao.update(workout.copy(error = "Waiting for the upload server. Your workout is saved locally."))
            ListenableWorker.Result.retry()
        } catch (e: Exception) {
            dao.update(workout.copy(status = if (e is IllegalStateException && auth.athleteId.value == null) WorkoutStatus.AUTH_REQUIRED else WorkoutStatus.FAILED,
                error = if (e is IllegalStateException) e.message else "Delivery failed. Reconnect your upload server or export this workout."))
            ListenableWorker.Result.success()
        }
    }
}
