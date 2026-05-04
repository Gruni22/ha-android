package io.homeassistant.btdashboard.github

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Fetches entity/service type metadata from the Home Assistant core repository on GitHub.
 *
 * Each domain's strings.json contains human-readable names for services and states.
 * Result is stored as ha_types.json in the app's internal storage.
 *
 * Structure of ha_types.json:
 * {
 *   "light": {
 *     "services": { "turn_on": "Turn on", "turn_off": "Turn off", ... },
 *     "states": { "on": "On", "off": "Off", ... }
 *   },
 *   ...
 * }
 */
class HaTypesFetcher(private val context: Context) {

    private val outputFile = File(context.filesDir, "ha_types.json")

    private val domains = listOf(
        "light", "switch", "input_boolean", "climate", "cover",
        "fan", "lock", "media_player", "automation", "script",
        "sensor", "binary_sensor", "alarm_control_panel", "humidifier",
    )

    suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val combined = JSONObject()
        var success = 0
        var failed = 0

        for (domain in domains) {
            try {
                val obj = fetchDomainStrings(domain)
                if (obj != null) {
                    combined.put(domain, parseDomainStrings(obj))
                    success++
                } else {
                    failed++
                }
            } catch (e: Exception) {
                Timber.w(e, "HaTypesFetcher: failed to fetch $domain")
                failed++
            }
        }

        outputFile.writeText(combined.toString())
        Timber.i("HaTypesFetcher: fetched $success domains, $failed failed")
        FetchResult(success, failed, outputFile.absolutePath)
    }

    private fun fetchDomainStrings(domain: String): JSONObject? {
        val urlStr = "https://raw.githubusercontent.com/home-assistant/core/master/" +
            "homeassistant/components/$domain/strings.json"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().readText()
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDomainStrings(obj: JSONObject): JSONObject {
        val result = JSONObject()

        // Extract service names: services.{name}.name
        val services = JSONObject()
        obj.optJSONObject("services")?.let { svcObj ->
            svcObj.keys().forEach { svcKey ->
                val name = svcObj.optJSONObject(svcKey)?.optString("name", svcKey) ?: svcKey
                services.put(svcKey, name)
            }
        }
        result.put("services", services)

        // Extract state strings from entity_component or state
        val states = JSONObject()
        val stateSource = obj.optJSONObject("entity_component")
            ?.optJSONObject("_")
            ?.optJSONObject("state")
            ?: obj.optJSONObject("state")
        stateSource?.keys()?.forEach { stateKey ->
            states.put(stateKey, stateSource.optString(stateKey, stateKey))
        }
        result.put("states", states)

        return result
    }

    fun loadCached(): JSONObject? = runCatching {
        if (outputFile.exists()) JSONObject(outputFile.readText()) else null
    }.getOrNull()

    fun hasCachedData(): Boolean = outputFile.exists() && outputFile.length() > 10

    data class FetchResult(val successCount: Int, val failedCount: Int, val filePath: String)
}
