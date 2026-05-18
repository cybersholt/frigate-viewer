package net.triton.frigateviewer.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * Offline event cache. Keyed by (serverId, eventId) so multiple servers can be cached
 * independently. Rows are written after every successful events() fetch and read first
 * when the events screen opens (instant UI, network refresh in background).
 *
 * Migrations: explicit per version. fallbackToDestructiveMigration is banned.
 */
@Entity(primaryKeys = ["serverId", "eventId"])
data class CachedEvent(
    val serverId: String,
    val eventId: String,
    val camera: String,
    val label: String,
    val startTime: Double,
    val endTime: Double?,
    val hasSnapshot: Boolean,
    val hasClip: Boolean,
    val retained: Boolean,
    val topScore: Double?,
)

@Dao
interface EventDao {
    @Query("SELECT * FROM CachedEvent WHERE serverId = :serverId ORDER BY startTime DESC LIMIT :limit")
    fun observe(serverId: String, limit: Int = 200): Flow<List<CachedEvent>>

    @Query("SELECT * FROM CachedEvent WHERE serverId = :serverId ORDER BY startTime DESC LIMIT :limit")
    suspend fun list(serverId: String, limit: Int = 200): List<CachedEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CachedEvent>)

    @Query("DELETE FROM CachedEvent WHERE serverId = :serverId AND eventId = :eventId")
    suspend fun delete(serverId: String, eventId: String)

    @Query("DELETE FROM CachedEvent WHERE serverId = :serverId")
    suspend fun clearServer(serverId: String)
}

@Database(entities = [CachedEvent::class], version = 1, exportSchema = true)
abstract class FrigateDb : RoomDatabase() {
    abstract fun eventDao(): EventDao
}

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): FrigateDb =
        Room.databaseBuilder(ctx, FrigateDb::class.java, "frigate.db")
            // No fallbackToDestructiveMigration — explicit migrations only.
            .build()

    @Provides
    fun provideEventDao(db: FrigateDb): EventDao = db.eventDao()
}
