package com.lasvegas.secmess.model

import kotlinx.serialization.Serializable

@Serializable
data class AgendaEvent(
    val id: String,
    val date: String, // Format: YYYY-MM-DD
    val title: String,
    val description: String = "",
    val time: String = "" // Format: HH:mm
)
