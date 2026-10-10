package com.bashworks.cameraim

import java.net.URI
import org.json.JSONObject

object MonitoringProtocol {
    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            throw IllegalArgumentException("Adresa servera nije ispravna.")
        }
        require(uri.scheme == "http" || uri.scheme == "https") {
            "Adresa mora početi sa http:// ili https://."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Adresa servera nije ispravna."
        }
        return normalized
    }

    fun websocketUrl(baseUrl: String, machineId: String, afterSequence: Long): String {
        val scheme = if (baseUrl.startsWith("https://")) "wss://" else "ws://"
        val withoutScheme = baseUrl.substringAfter("://")
        return "$scheme$withoutScheme/api/v1/stream?machineId=$machineId&afterSequence=$afterSequence"
    }

    fun parseEventEnvelope(json: String): EventMessage? {
        val envelope = JSONObject(json)
        if (envelope.optString("type") != "event") return null
        val event = envelope.getJSONObject("data")
        return EventMessage(
            eventId = event.getString("eventId"),
            kind = event.getString("kind"),
            detail = event.stringOrNull("detail"),
            serverSequence = event.getLong("serverSequence")
        )
    }
}
