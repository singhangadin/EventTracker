package `in`.singhangad.eventtracker.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, DeadLetterEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun dlqDao(): DLQDao

    companion object {
        private const val DB_NAME = "eventtracker.db"

        @Volatile
        private var INSTANCE: EventDatabase? = null

        fun get(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): EventDatabase =
            Room.databaseBuilder(context.applicationContext, EventDatabase::class.java, DB_NAME)
                // WAL mode for concurrent reads from multiple processes
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** Used in tests to inject an in-memory database. */
        internal fun setInstanceForTesting(db: EventDatabase) {
            INSTANCE = db
        }

        /** Clears the singleton so the next [get] creates a fresh instance. Tests only. */
        internal fun clearTestInstance() {
            INSTANCE = null
        }
    }
}
