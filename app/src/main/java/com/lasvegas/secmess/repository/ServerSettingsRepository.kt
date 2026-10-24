package com.lasvegas.secmess.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Definições do servidor editáveis pelo admin (ex.: limite de downloads simultâneos). */
object ServerSettingsRepository {
    private val _downloadMax = MutableStateFlow<Int?>(null)
    val downloadMax = _downloadMax.asStateFlow()

    fun setDownloadMax(v: Int?) {
        if (v != null) _downloadMax.value = v.coerceIn(1, 10)
    }
}
