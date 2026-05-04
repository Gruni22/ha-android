package io.homeassistant.btdashboard.sync

import androidx.room.withTransaction
import io.homeassistant.btdashboard.dashboard.HaPacketClient
import io.homeassistant.btdashboard.db.AppDatabase
import io.homeassistant.btdashboard.db.AreaEntity
import io.homeassistant.btdashboard.db.DashboardEntity
import io.homeassistant.btdashboard.db.EntityEntity
import io.homeassistant.btdashboard.db.ViewEntity
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

sealed class SyncResult {
    data class Success(val areasCount: Int, val entitiesCount: Int, val dashboardsCount: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class SyncManager(
    private val client: HaPacketClient,
    private val db: AppDatabase,
    private val deviceId: String,
) {
    /**
     * Fetch areas → devices → dashboards from HA and persist them partitioned by deviceId.
     */
    suspend fun performInitialSync(): SyncResult {
        Timber.i("SyncManager[$deviceId]: starting initial sync")

        // Network requests outside the transaction (transactions hold a thread blocked).
        val areas: List<io.homeassistant.btdashboard.dashboard.HaArea>
        val entities: List<io.homeassistant.btdashboard.dashboard.HaEntityState>
        val dashboards: List<io.homeassistant.btdashboard.dashboard.HaDashboardInfo>
        try {
            areas = client.requestAreas()
            entities = client.requestDevices(areaId = null)
            dashboards = client.requestDashboards()
        } catch (e: CancellationException) {
            throw e  // structured concurrency: cancellation must propagate
        } catch (e: Exception) {
            Timber.e(e, "SyncManager[$deviceId]: network sync failed")
            return SyncResult.Error(e.message ?: "Unknown sync error")
        }

        // Persist atomically: either all four tables update together, or none do.
        return try {
            db.withTransaction {
                db.areaDao().deleteAllForDevice(deviceId)
                db.areaDao().upsertAll(areas.map {
                    AreaEntity(deviceId, it.id, it.name, it.icon.ifBlank { null })
                })

                db.entityDao().deleteAllForDevice(deviceId)
                db.entityDao().upsertAll(entities.map { e ->
                    EntityEntity(
                        deviceId       = deviceId,
                        id             = e.entityId,
                        name           = e.friendlyName,
                        domain         = e.domain,
                        areaId         = e.areaId,
                        state          = e.state,
                        attributesJson = JSONObject(e.attributes).toString(),
                        lastUpdated    = System.currentTimeMillis(),
                    )
                })

                db.dashboardDao().deleteAllForDevice(deviceId)
                db.viewDao().deleteAllForDevice(deviceId)
                db.dashboardDao().upsertAll(dashboards.map { d ->
                    DashboardEntity(deviceId, d.id, d.urlPath, d.title)
                })
                db.viewDao().upsertAll(dashboards.flatMap { d ->
                    d.views.map { v ->
                        ViewEntity(
                            deviceId      = deviceId,
                            id            = v.id,
                            dashboardId   = d.id,
                            path          = v.path,
                            title         = v.title,
                            entityIdsJson = JSONArray(v.entityIds).toString(),
                        )
                    }
                })
            }
            Timber.i("SyncManager[$deviceId]: synced ${areas.size} areas, ${entities.size} entities, ${dashboards.size} dashboards")
            SyncResult.Success(areas.size, entities.size, dashboards.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "SyncManager[$deviceId]: DB write failed")
            SyncResult.Error(e.message ?: "DB write error")
        }
    }
}
