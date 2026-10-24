package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Snapshot da hierarquia enviado pelo servidor (`type = "subadmins"`, só admin).
 * Em memória; é pedido ao abrir o painel e depois de cada alteração.
 */
data class SubAdminInfo(
    val id: String,
    val name: String,
    val reach: Boolean,
    val cohort: List<String>,
)

data class SubAdminUser(
    val id: String,
    val name: String,
    val role: String,
)

data class SubAdminSnapshot(
    val subs: List<SubAdminInfo>,
    val users: List<SubAdminUser>,
    val matrix: Map<String, List<String>>,
    val max: Int,
)

object SubAdminRepository {
    private val _state = MutableStateFlow<SubAdminSnapshot?>(null)
    val state = _state.asStateFlow()

    fun clear() {
        _state.value = null
    }

    fun apply(raw: String) {
        try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val subs = root["subs"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SubAdminInfo(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    reach = o["reach"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    cohort = o["cohort"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
                )
            } ?: emptyList()

            val users = root["users"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SubAdminUser(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                    role = o["role"]?.jsonPrimitive?.content ?: "user",
                )
            } ?: emptyList()

            val matrix = root["matrix"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.mapNotNull { it.jsonPrimitive.content }
            } ?: emptyMap()

            val max = root["max"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
            _state.value = SubAdminSnapshot(subs, users, matrix, max)
        } catch (_: Exception) {
            // payload inválido: mantém o último snapshot
        }
    }
}
