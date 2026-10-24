package com.lasvegas.secmess.model

import kotlinx.serialization.Serializable

@Serializable
data class CalendarEvent(
    val date: String, // ISO-8601 YYYY-MM-DD
    val title: String,
    val alarmTime: String // HH:mm
)
