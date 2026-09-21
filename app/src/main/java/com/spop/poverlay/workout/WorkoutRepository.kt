package com.spop.poverlay.workout

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Persisted strings deliberately keep the DB readable and migrations independent of enum order. */
object WorkoutStatus {
    const val RECORDING = "recording"
    const val INTERRUPTED = "interrupted"
    const val LOCAL = "saved locally"
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val PROCESSING = "processing"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
    const val AUTH_REQUIRED = "reconnect Strava"
    const val REVIEW = "check Strava before retrying"
}

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val lastPedaledAt: Long? = null,
    val status: String = WorkoutStatus.RECORDING,
    val reason: String? = null,
    val athleteId: Long? = null,
    val uploadId: Long? = null,
    val activityId: Long? = null,
    val error: String? = null,
    val distanceMeters: Double = 0.0,
    val title: String = "Grupetto Indoor Ride",
    val retryAfter: Long = 0,
    val deliveryOrigin: String? = null,
    val deliveryId: String? = null,
)

@Entity(tableName = "samples", primaryKeys = ["workoutId", "timeMs"],
    foreignKeys = [ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)])
data class WorkoutSample(
    val workoutId: String,
    val timeMs: Long,
    val watts: Float?,
    val cadence: Float?,
    val resistance: Float?,
    val speedMps: Double?,
    val heartRate: Int?,
    val distanceMeters: Double,
)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY startedAt DESC") fun observe(): Flow<List<Workout>>
    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun get(id: String): Workout?
    @Query("SELECT * FROM workouts WHERE status IN ('recording', 'interrupted') ORDER BY startedAt DESC LIMIT 1") suspend fun unfinished(): Workout?
    @Query("SELECT * FROM workouts WHERE status IN ('queued', 'processing', 'uploading')") suspend fun pending(): List<Workout>
    @Query("SELECT * FROM workouts WHERE athleteId = :athlete AND status IN ('queued', 'processing', 'uploading', 'reconnect Strava')") suspend fun reconnectable(athlete: Long): List<Workout>
    @Insert suspend fun insert(workout: Workout)
    @Update suspend fun update(workout: Workout)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(sample: WorkoutSample)
    @Query("SELECT * FROM samples WHERE workoutId = :id ORDER BY timeMs") suspend fun samples(id: String): List<WorkoutSample>
    @Query("DELETE FROM samples WHERE workoutId = :id AND timeMs > :end") suspend fun trim(id: String, end: Long)
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM workouts WHERE status = 'uploaded' AND id NOT IN (SELECT id FROM workouts WHERE status = 'uploaded' ORDER BY startedAt DESC LIMIT 30)") suspend fun prune()
}

@Database(entities = [Workout::class, WorkoutSample::class], version = 2, exportSchema = false)
abstract class WorkoutDatabase : RoomDatabase() { abstract fun workouts(): WorkoutDao }

class WorkoutRepository(context: Context, databaseName: String = "workouts.db") {
    val database = Room.databaseBuilder(context.applicationContext, WorkoutDatabase::class.java, databaseName).addMigrations(object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN deliveryOrigin TEXT")
            db.execSQL("ALTER TABLE workouts ADD COLUMN deliveryId TEXT")
            db.execSQL("UPDATE workouts SET status = 'check Strava before retrying', error = 'Previous direct upload needs review before upload server delivery.' WHERE status IN ('uploading', 'processing') OR (uploadId IS NOT NULL AND status != 'uploaded')")
        }
    }).build()
    val dao = database.workouts()
    val history = dao.observe()

    suspend fun append(workout: Workout, sample: WorkoutSample) = database.withTransaction {
        dao.insert(sample)
        dao.update(workout)
    }

    suspend fun finalize(workout: Workout, end: Long, reason: String, queueUpload: Boolean = false): Workout = database.withTransaction {
        dao.trim(workout.id, end)
        val distance = dao.samples(workout.id).lastOrNull()?.distanceMeters ?: 0.0
        workout.copy(endedAt = end, status = if (queueUpload) WorkoutStatus.QUEUED else WorkoutStatus.LOCAL, reason = reason,
            distanceMeters = distance, error = null).also { dao.update(it) }
    }
}

class WorkoutSettings(context: Context) {
    private val prefs = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
    var timeoutMinutes: Int
        get() = prefs.getInt("workoutInactivityMinutes", 25).takeIf { it in choices } ?: 25
        set(value) { require(value in choices); prefs.edit().putInt("workoutInactivityMinutes", value).apply() }
    val timeoutMs get() = timeoutMinutes * 60_000L
    var autoUpload: Boolean
        get() = prefs.getBoolean("stravaAutoUpload", true)
        set(value) { prefs.edit().putBoolean("stravaAutoUpload", value).apply() }
    companion object { val choices = listOf(0, 15, 25, 30, 60, 120) }
}
