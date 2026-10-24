package com.lasvegas.secmess.util

/**
 * Process-wide app state.
 * appOpen = true while the messenger Activity is alive (the user has the app open),
 * even if the screen is off. false when only the background service is running
 * (booted but app not opened) -> the device is DORMANT but still receives messages.
 */
object AppState {
    @Volatile
    var appOpen: Boolean = false
}