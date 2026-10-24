package com.lasvegas.secmess.network

/**
 * Fonte única de verdade para o relay e o download.
 *
 * P2: o relay só pode ser o `bwtj3`. Qualquer `custom_relay_url` fora da
 * allowlist é substituído por este valor (migração em RelayService).
 */
object RelayConfig {
    const val RELAY_ONION = "bwtj3gzbsuod72ymgxujdcmkd6hfinpslwpijlmpi5iha6mddtoteaqd.onion"
    const val RELAY_URL = "ws://$RELAY_ONION"

    const val DOWNLOAD_ONION = "3l7zfw5rmihonii6yu5iqs7bmt33sleiyji4ljjwsrqv3mcctmhh3zqd.onion"
    const val DOWNLOAD_BASE = "http://$DOWNLOAD_ONION"

    /**
     * URLs aceites como relay. Apenas o relay de produção e o servidor local
     * de desenvolvimento. `offline.invalid`, `mjfe`, `3l7z`, `l7z` e `djzuf5`
     * nunca são relays.
     */
    private val ALLOWED = setOf(
        RELAY_URL,
        "ws://127.0.0.1:8080",
        "ws://10.0.2.2:8080",
    )

    fun isAllowed(url: String?): Boolean = url != null && url in ALLOWED

    /** Devolve o URL guardado se for válido; caso contrário, o relay canónico. */
    fun resolve(stored: String?): String = if (isAllowed(stored)) stored!! else RELAY_URL
}
