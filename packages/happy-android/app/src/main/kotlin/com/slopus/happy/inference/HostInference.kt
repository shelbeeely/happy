package com.slopus.happy.inference

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The computer's answers to the inference machine RPCs (happy-wire
 * `InferenceStatus` / `InferenceTestResult`). Every call is end-to-end
 * encrypted with the machine's key like any other machine RPC.
 */
@Immutable
data class HostCredentials(
    val present: Map<String, Boolean>,
    val oriInstalled: Boolean,
    val oriVersion: String?,
    val oriDefaultModel: String?,
) {
    companion object {
        fun from(answer: JsonElement?): HostCredentials? {
            val o = answer as? JsonObject ?: return null
            val credentials = (o["credentials"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                val ref = (e["ref"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                ref to ((e["present"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }.toMap()
            val ori = o["ori"] as? JsonObject
            return HostCredentials(
                present = credentials,
                oriInstalled = (ori?.get("installed") as? JsonPrimitive)?.booleanOrNull ?: false,
                oriVersion = (ori?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                oriDefaultModel = (ori?.get("defaultModel") as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }
}

/** Why a connection test failed; each has its own words on screen. */
enum class ConnectionProblem(val wire: String?) {
    MissingCredential("MISSING_CREDENTIAL"),
    AuthFailed("AUTH_FAILED"),
    ModelNotFound("MODEL_NOT_FOUND"),
    ModelNoTools("MODEL_NO_TOOLS"),
    ServerToolRejected("SERVER_TOOL_REJECTED"),
    ProviderUnreachable("PROVIDER_UNREACHABLE"),
    InvalidRequest("INVALID_REQUEST"),
    /** The computer didn't answer: offline, asleep, or the daemon isn't running. */
    HostUnreachable(null),
    /** The computer answered but runs a Happy CLI without these RPCs. */
    HostOutdated(null),
    ;

    companion object {
        fun fromWire(code: String?) = entries.find { it.wire != null && it.wire == code } ?: InvalidRequest
    }
}

sealed interface ConnectionTest {
    data class Passed(
        val keyLabel: String?,
        val limitUsd: Double?,
        val usageUsd: Double?,
        val contextLength: Long?,
        val serverTools: List<ServerTool>,
    ) : ConnectionTest

    /** [detail] is the computer's own sentence, shown under ours. */
    data class Failed(val problem: ConnectionProblem, val detail: String?) : ConnectionTest

    companion object {
        fun from(answer: JsonElement?): ConnectionTest {
            val o = answer as? JsonObject ?: return Failed(ConnectionProblem.HostOutdated, null)
            val message = (o["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                return Failed(ConnectionProblem.fromWire((o["code"] as? JsonPrimitive)?.content), message)
            }
            val key = o["key"] as? JsonObject
            val model = o["model"] as? JsonObject
            return Passed(
                keyLabel = (key?.get("label") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                limitUsd = (key?.get("limitUsd") as? JsonPrimitive)?.doubleOrNull,
                usageUsd = (key?.get("usageUsd") as? JsonPrimitive)?.doubleOrNull,
                contextLength = (model?.get("contextLength") as? JsonPrimitive)?.longOrNull,
                serverTools = (o["serverTools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(ServerTool::fromWire) },
            )
        }
    }
}

sealed interface StoreCredentialResult {
    data object Stored : StoreCredentialResult
    data class Failed(val problem: ConnectionProblem, val detail: String?) : StoreCredentialResult
}

/** RPC failures that mean the method isn't registered on that computer. */
internal fun isMissingMethod(message: String?): Boolean =
    message != null && message.contains("Method not found", ignoreCase = true)
