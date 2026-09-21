package com.spop.poverlay.workout

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import com.spop.poverlay.strava.CredentialVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun retentionProtectsUnsyncedRidesAndDeletionCascades() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, WorkoutDatabase::class.java).build()
        try {
            val dao = db.workouts()
            repeat(35) { dao.insert(Workout("uploaded-$it", it.toLong(), status = WorkoutStatus.UPLOADED)) }
            val pending = Workout("pending", 0, status = WorkoutStatus.FAILED)
            dao.insert(pending)
            dao.insert(WorkoutSample(pending.id, 0, 100f, 80f, null, null, null, 0.0))
            dao.prune()
            assertNotNull(dao.get("pending"))
            assertNull(dao.get("uploaded-4"))
            assertNotNull(dao.get("uploaded-5"))
            dao.delete("pending")
            assertTrue(dao.samples("pending").isEmpty())
        } finally { db.close() }
    }

    @Test fun credentialsAreEncryptedAndSurviveReopening() {
        val vault = CredentialVault(context, "test-strava-credentials")
        try {
            vault.write(JSONObject().put("refresh_token", "secret-test-refresh"))
            val stored = java.io.File(context.noBackupFilesDir, "test-strava-credentials").readText()
            assertFalse(stored.contains("secret-test-refresh"))
            assertEquals("secret-test-refresh", CredentialVault(context, "test-strava-credentials").read().getString("refresh_token"))
            vault.write(JSONObject().put("refresh_token", "rotated-token"))
            assertEquals("rotated-token", CredentialVault(context, "test-strava-credentials").read().getString("refresh_token"))
        } finally { vault.clear() }
    }

    @Test fun finalizationTrimsTrailingSamplesAndSurvivesDatabaseReopen() = runBlocking {
        // A unique test database name keeps this separate from the rider's workouts.
        context.deleteDatabase("workouts-test.db")
        val repo = WorkoutRepository(context, "workouts-test.db")
        val w = Workout("trim", 1000, lastPedaledAt = 2000)
        repo.dao.insert(w)
        repo.append(w, WorkoutSample(w.id, 2000, 100f, 80f, null, null, null, 5.0))
        repo.append(w, WorkoutSample(w.id, 3000, 0f, 0f, null, null, null, 5.0))
        repo.finalize(w, 2000, "Inactivity")
        repo.database.close()
        val reopened = WorkoutRepository(context, "workouts-test.db")
        try {
            assertEquals(2000L, reopened.dao.get(w.id)!!.endedAt)
            assertEquals(1, reopened.dao.samples(w.id).size)
            assertEquals(5.0, reopened.dao.get(w.id)!!.distanceMeters, 0.0)
        } finally { reopened.database.close(); context.deleteDatabase("workouts-test.db") }
    }
}
