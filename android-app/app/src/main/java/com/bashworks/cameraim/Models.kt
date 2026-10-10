package com.bashworks.cameraim

import org.json.JSONObject

data class ConnectionSettings(
    val baseUrl: String,
    val machineId: String,
    val readToken: String
)

data class EventMessage(
    val eventId: String,
    val kind: String,
    val detail: String?,
    val serverSequence: Long
)

data class DashboardState(
    val machineId: String,
    val freshness: String,
    val connection: String,
    val lastStatusAt: String,
    val cameraAvailable: Boolean,
    val cameraDetail: String?,
    val lastFrameAt: String?,
    val imageUrl: String?,
    val imageCapturedAt: String?,
    val jobFile: String?,
    val jobState: String?,
    val progressPercent: Double?,
    val elapsedSeconds: Long?,
    val estimatedRemainingSeconds: Long?,
    val feed: Double?,
    val feedUnit: String?,
    val rpm: Double?,
    val x: Double?,
    val y: Double?,
    val z: Double?,
    val positionUnit: String?
) {
    companion object {
        fun fromJson(json: String): DashboardState {
            val root = JSONObject(json)
            val camera = root.getJSONObject("camera")
            val job = root.optJSONObject("job")
            val motion = root.optJSONObject("motion")
            val position = motion?.optJSONObject("position")
            val image = root.optJSONObject("latestImage")
            return DashboardState(
                machineId = root.getString("machineId"),
                freshness = root.getString("freshness"),
                connection = root.getString("connection"),
                lastStatusAt = root.getString("lastStatusAt"),
                cameraAvailable = camera.getBoolean("available"),
                cameraDetail = camera.stringOrNull("detail"),
                lastFrameAt = camera.stringOrNull("lastFrameAt"),
                imageUrl = image?.stringOrNull("url"),
                imageCapturedAt = image?.stringOrNull("capturedAt"),
                jobFile = job?.stringOrNull("fileName"),
                jobState = job?.stringOrNull("state"),
                progressPercent = job?.doubleOrNull("progressPercent"),
                elapsedSeconds = job?.longOrNull("elapsedSeconds"),
                estimatedRemainingSeconds = job?.longOrNull("estimatedRemainingSeconds"),
                feed = motion?.doubleOrNull("feed"),
                feedUnit = motion?.stringOrNull("feedUnit"),
                rpm = motion?.doubleOrNull("rpm"),
                x = position?.doubleOrNull("x"),
                y = position?.doubleOrNull("y"),
                z = position?.doubleOrNull("z"),
                positionUnit = position?.stringOrNull("unit")
            )
        }
    }
}

internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else getString(name)

internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (isNull(name) || !has(name)) null else getDouble(name)

internal fun JSONObject.longOrNull(name: String): Long? =
    if (isNull(name) || !has(name)) null else getLong(name)
