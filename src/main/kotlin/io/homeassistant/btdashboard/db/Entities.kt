package io.homeassistant.btdashboard.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "areas", primaryKeys = ["deviceId", "id"])
data class AreaEntity(
    val deviceId: String,
    val id: String,
    val name: String,
    val icon: String?,
)

@Entity(tableName = "entities", primaryKeys = ["deviceId", "id"])
data class EntityEntity(
    val deviceId: String,
    val id: String,          // = entity_id, e.g. "light.living_room"
    val name: String,
    val domain: String,
    val areaId: String?,
    val state: String,
    val attributesJson: String,     // JSON-encoded attributes map
    val lastUpdated: Long,          // epoch millis
)

@Entity(tableName = "dashboards", primaryKeys = ["deviceId", "id"])
data class DashboardEntity(
    val deviceId: String,
    val id: String,
    val urlPath: String,
    val title: String,
)

@Entity(tableName = "views", primaryKeys = ["deviceId", "id"])
data class ViewEntity(
    val deviceId: String,
    val id: String,
    val dashboardId: String,
    val path: String,
    val title: String,
    val entityIdsJson: String,      // JSON array of entity_id strings
)
