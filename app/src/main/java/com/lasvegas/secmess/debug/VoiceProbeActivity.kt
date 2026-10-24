package com.lasvegas.secmess.debug

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log

class VoiceProbeActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startProbe() else Log.w("OGVoice", "probe aborted: RECORD_AUDIO denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startProbe() {
        Log.d("OGVoice", "Probe started — admin voice command ready")
        finish()
    }
}
