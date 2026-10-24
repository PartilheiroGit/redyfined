package com.lasvegas.secmess.repository

/**
 * Relógio monotónico para mensagens enviadas.
 *
 * `System.currentTimeMillis()` pode devolver o mesmo valor para duas mensagens
 * seguidas; a UI ordena por timestamp e duas mensagens no mesmo ms ficavam com
 * ordem aleatória. Este relógio garante timestamp estritamente crescente por
 * device, sem saltos grandes.
 */
object MessageClock {
    private var last = 0L

    @Synchronized
    fun next(): Long {
        val now = System.currentTimeMillis()
        last = if (now > last) now else last + 1
        return last
    }
}
