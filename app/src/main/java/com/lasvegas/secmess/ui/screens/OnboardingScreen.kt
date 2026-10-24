package com.lasvegas.secmess.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lasvegas.secmess.crypto.KeyStoreHelper

@Composable
fun OnboardingScreen(
    keyStoreHelper: KeyStoreHelper,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("O.G. Systems Activation", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Your identity has been established. Proceed to the main interface.")
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onComplete) {
            Text("Enter System")
        }
    }
}
