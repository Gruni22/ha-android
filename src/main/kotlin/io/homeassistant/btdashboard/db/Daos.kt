package io.homeassistant.btdashboard.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AreaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(areas: List<AreaEntity>)

    @Query("SELECT * FROM areas WHERE deviceId = :deviceId ORDER BY name")
    fun observeAll(deviceId: String): Flow<List<AreaEntity>>

    @Query("SELECT * FROM areas WHERE deviceId = :deviceId ORDER BY name")
    suspend fun getAll(deviceId: String): List<AreaEntity>

    @Query("DELETE FROM areas WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query("DELETE FROM areas")
    suspend fun deleteAll()
}

@Dao
interface EntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EntityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntityEntity)

    @Query("SELECT * FROM entities WHERE deviceId = :deviceId ORDER BY name")
    fun observeAll(deviceId: String): Flow<List<EntityEntity>>

    @Query("SELECT * FROM entities WHERE deviceId = :deviceId ORDER BY name")
    suspend fun getAll(deviceId: String): List<EntityEntity>

    @Query("SELECT * FROM entities WHERE deviceId = :deviceId AND id IN (:ids)")
    suspend fun getByIds(deviceId: String, ids: List<String>): List<EntityEntity>

    @Query("SELECT * FROM entities WHERE deviceId = :deviceId AND areaId = :areaId ORDER BY name")
    fun observeByArea(deviceId: String, areaId: String): Flow<List<EntityEntity>>

    @Query("SELECT * FROM entities WHERE deviceId = :deviceId AND id = :entityId")
    suspend fun get(deviceId: String, entityId: String): EntityEntity?

    @Query("DELETE FROM entities WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query("DELETE FROM entities")
    suspend fun deleteAll()
}

@Dao
interface DashboardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(dashboards: List<DashboardEntity>)

    @Query("SELECT * FROM dashboards WHERE deviceId = :deviceId ORDER BY title")
    suspend fun getAll(deviceId: String): List<DashboardEntity>

    @Query("DELETE FROM dashboards WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query("DELETE FROM dashboards")
    suspend fun deleteAll()
}

@Dao
interface ViewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(views: List<ViewEntity>)

    @Query("SELECT * FROM views WHERE deviceId = :deviceId AND dashboardId = :dashboardId")
    suspend fun getByDashboard(deviceId: String, dashboardId: String): List<ViewEntity>

    @Query("DELETE FROM views WHERE deviceId = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)

    @Query("DELETE FROM views")
    suspend fun deleteAll()
}
