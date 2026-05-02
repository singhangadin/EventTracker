package `in`.singhangad.eventtracker.internal.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the Room schema can be created at each version and that any migrations between
 * consecutive versions are correct.
 *
 * How to add a new migration test when bumping to version N:
 *  1. Increment `version` in @Database and write a `Migration(N-1, N)` object.
 *  2. Add a `fun migrate_vX_to_vY()` test here that calls:
 *       val db = helper.createDatabase(DB_NAME, N-1)
 *       db.execSQL("INSERT INTO ...") // seed data to verify survives migration
 *       db.close()
 *       helper.runMigrationsAndValidate(DB_NAME, N, true, Migration_X_Y)
 *  3. The helper validates the migrated schema against the exported JSON for version N.
 */
@RunWith(AndroidJUnit4::class)
class EventDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EventDatabase::class.java,
    )

    @Test
    fun create_version_1_schema() {
        // Creates the v1 schema from the exported JSON and immediately closes it.
        // Failure here means the schema export is missing or the DDL is invalid.
        helper.createDatabase(DB_NAME, 1).close()
    }

    companion object {
        private const val DB_NAME = "migration_test"
    }
}
