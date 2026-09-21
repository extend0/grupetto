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
        const val ApiBase = "https://www.strava.com/api/v3"
        const val Tag = "strava-upload"
        fun enqueue(context: Context, id: String) {
            val work = OneTimeWorkRequestBuilder<StravaUploadWorker>()
                .setInputData(workDataOf("workoutId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(Tag).build()
            WorkManager.getInstance(context).enqueueUniqueWork("strava-$id", ExistingWorkPolicy.KEEP, work)
        }
    }

}

/** Separates the delivery protocol from Android scheduling so failures can be exercised offline. */
class StravaUploader(private val repository: WorkoutRepository, private val auth: StravaAuthManager) {
    suspend fun upload(id: String, runAttemptCount: Int = 0): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val dao = repository.dao
        var workout = dao.get(id) ?: return@withContext ListenableWorker.Result.success()
        if (workout.status == WorkoutStatus.UPLOADED || workout.endedAt == null) return@withContext ListenableWorker.Result.success()
        if (workout.status !in listOf(WorkoutStatus.QUEUED, WorkoutStatus.PROCESSING, WorkoutStatus.UPLOADING)) return@withContext ListenableWorker.Result.success()
        if (workout.retryAfter > System.currentTimeMillis()) return@withContext ListenableWorker.Result.retry()
        if (workout.status == WorkoutStatus.UPLOADING && workout.uploadId == null) {
            dao.update(workout.copy(status = WorkoutStatus.REVIEW, error = "Submission was interrupted. Check Strava before explicitly retrying."))
            return@withContext ListenableWorker.Result.success()
        }
        try {
            val (athlete, token) = auth.accessToken()
            check(athlete == workout.athleteId) { "This workout belongs to a different Strava account. Reconnect its original account." }
            if (workout.uploadId == null) {
                val samples = dao.samples(id)
                val tcx = TcxExporter.export(workout, samples)
                val resistance = samples.mapNotNull { it.resistance }.takeIf { it.isNotEmpty() }?.average()
                val description = "Recorded by Grupetto. Indoor ride; distance is estimated from power." +
                    (resistance?.let { " Average Peloton resistance: ${it.toInt()}." } ?: "")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("data_type", "tcx").addFormDataPart("trainer", "true")
                    .addFormDataPart("name", workout.title).addFormDataPart("description", description)
                    .addFormDataPart("external_id", "grupetto-${workout.id}.tcx")
                    .addFormDataPart("file", "grupetto-${workout.id}.tcx", tcx.toRequestBody("application/xml".toMediaType())).build()
                workout = workout.copy(status = WorkoutStatus.UPLOADING, error = null)
                dao.update(workout) // A process death after this point needs reconciliation, not blind POST retries.
                val response = request("uploads", token, body)
                workout = workout.copy(uploadId = response.getLong("id"), status = WorkoutStatus.PROCESSING)
                dao.update(workout)
            }
            repeat(6) { attempt ->
                val response = request("uploads/${workout.uploadId}", token)
                val activityId = response.optLong("activity_id").takeIf { it > 0 }
                if (activityId != null) {
                    dao.update(workout.copy(status = WorkoutStatus.UPLOADED, activityId = activityId, error = null))
                    dao.prune()
                    return@withContext ListenableWorker.Result.success()
                }
                if (!response.isNull("error") && response.optString("error").isNotBlank()) {
                    val duplicate = response.optString("error").contains("duplicate", ignoreCase = true)
                    dao.update(workout.copy(status = if (duplicate) WorkoutStatus.REVIEW else WorkoutStatus.FAILED,
                        error = if (duplicate) "Strava reports a duplicate. Check your activities before retrying."
                        else "Strava could not process this file. Export it to inspect or retry manually."))
                    return@withContext ListenableWorker.Result.success()
                }
                delay((attempt + 1) * 2_000L)
            }
            ListenableWorker.Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: StravaHttpException) {
            if (e.status == 401 && runAttemptCount == 0) {
                try {
                    auth.accessToken(force = true)
                    dao.update(workout.copy(status = if (workout.uploadId == null) WorkoutStatus.QUEUED else WorkoutStatus.PROCESSING))
                    return@withContext ListenableWorker.Result.retry()
                } catch (_: Exception) { /* Require reconnect below. */ }
            }
            val retry = e.status == 429 || (e.status >= 500 && workout.uploadId != null)
            val ambiguous = e.status >= 500 && workout.uploadId == null && workout.status == WorkoutStatus.UPLOADING
            dao.update(workout.copy(status = when {
                ambiguous -> WorkoutStatus.REVIEW
                e.status == 401 || e.status == 403 -> WorkoutStatus.AUTH_REQUIRED
                retry -> if (workout.uploadId == null) WorkoutStatus.QUEUED else WorkoutStatus.PROCESSING
                else -> WorkoutStatus.FAILED
            }, retryAfter = e.retryAt, error = if (ambiguous) "Strava may have received this ride. Check Strava before retrying." else e.message))
            if (retry) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
        } catch (e: IOException) {
            val ambiguous = workout.status == WorkoutStatus.UPLOADING && workout.uploadId == null
            dao.update(workout.copy(status = if (ambiguous) WorkoutStatus.REVIEW else workout.status,
                error = if (ambiguous) "Connection lost during submission. Check Strava before retrying." else "Waiting for a working connection."))
            if (ambiguous) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
        } catch (e: Exception) {
            val ambiguous = workout.status == WorkoutStatus.UPLOADING && workout.uploadId == null
            dao.update(workout.copy(status = if (ambiguous) WorkoutStatus.REVIEW else WorkoutStatus.FAILED,
                error = if (ambiguous) "The submission response was incomplete. Check Strava before retrying."
                    else if (e is IllegalStateException) e.message else "Upload failed. Reconnect or export this workout."))
            ListenableWorker.Result.success()
        }
    }

    private fun request(path: String, token: String, body: RequestBody? = null): JSONObject {
        val builder = Request.Builder().url("${StravaUploadWorker.ApiBase}/$path").header("Authorization", "Bearer $token")
        if (body != null) builder.post(body)
        return auth.client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) {
                val now = System.currentTimeMillis()
                val retryAt = if (it.code == 429) {
                    val usage = it.header("X-RateLimit-Usage")?.split(',')?.mapNotNull { v -> v.trim().toLongOrNull() }
                    val limits = it.header("X-RateLimit-Limit")?.split(',')?.mapNotNull { v -> v.trim().toLongOrNull() }
                    val interval = if (usage?.size == 2 && limits?.size == 2 && usage[1] >= limits[1]) 86_400_000L else 900_000L
                    val reset = (now / interval + 1) * interval + 1_000
                    maxOf(reset, now + (it.header("Retry-After")?.toLongOrNull() ?: 0) * 1_000)
                } else 0
                throw StravaHttpException(it.code, retryAt)
            }
            JSONObject(it.body!!.string())
        }
    }
}
