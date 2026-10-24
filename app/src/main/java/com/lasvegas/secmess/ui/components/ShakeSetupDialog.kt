package com.lasvegas.secmess.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lasvegas.secmess.util.ShakeDetector
import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context

@Composable
fun ShakeSetupDialog(
    onComplete: (List<Long>) -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) } // 1: Recording, 2: Confirming
    var recordedShakes = remember { mutableStateListOf<Long>() }
    var confirmationShakes = remember { mutableStateListOf<Long>() }
    
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    val shakeDetector = remember {
        ShakeDetector(
            context = context,
            onShake = { time ->
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                if (step == 1) {
                    if (recordedShakes.size < 5) recordedShakes.add(time)
                } else {
                    if (confirmationShakes.size < 5) confirmationShakes.add(time)
                }
            }
        )
    }

    DisposableEffect(Unit) {
        shakeDetector.start()
        onDispose { shakeDetector.stop() }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (step == 1) "Step 1: Record Shake Pattern" else "Step 2: Confirm Pattern") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (step == 1) "Shake your phone 5 times at your own pace." 
                    else "Repeat the exact same 5-shake pattern."
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { (if (step == 1) recordedShakes.size else confirmationShakes.size) / 5f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${if (step == 1) recordedShakes.size else confirmationShakes.size} / 5 Shakes",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            if (step == 1 && recordedShakes.size == 5) {
                Button(onClick = { step = 2 }) { Text("Next: Confirm") }
            } else if (step == 2 && confirmationShakes.size == 5) {
                Button(onClick = {
                    val recordedIntervals = getIntervals(recordedShakes)
                    val confirmIntervals = getIntervals(confirmationShakes)
                    
                    if (matchPatterns(recordedIntervals, confirmIntervals)) {
                        onComplete(recordedIntervals)
                    } else {
                        android.widget.Toast.makeText(context, "Patterns didn't match. Try again.", android.widget.Toast.LENGTH_SHORT).show()
                        recordedShakes.clear()
                        confirmationShakes.clear()
                        step = 1
                    }
                }) { Text("Save Code") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

private fun getIntervals(times: List<Long>): List<Long> {
    return times.zipWithNext { a, b -> b - a }
}

private fun matchPatterns(p1: List<Long>, p2: List<Long>): Boolean {
    if (p1.size != p2.size) return false
    val tolerance = 300L // 300ms tolerance for each interval
    return p1.zip(p2).all { (i1, i2) -> kotlin.math.abs(i1 - i2) < tolerance }
}
