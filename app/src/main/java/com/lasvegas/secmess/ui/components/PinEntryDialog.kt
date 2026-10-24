package com.lasvegas.secmess.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PinEntryDialog(
    isSettingPin: Boolean = false,
    onDismiss: () -> Unit,
    onPinEntered: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSettingPin) "Set Secret PIN" else "Secret Access") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isSettingPin) "Choose a 4-digit PIN for future access" else "Enter 4-digit PIN to unlock")
                Spacer(modifier = Modifier.height(20.dp))

                // 4 discrete dots that fill + change colour as each digit is entered,
                // so the user can see how many he has pressed. Tap to (re)focus the keypad.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.clickable { focusRequester.requestFocus() }
                ) {
                    repeat(4) { i ->
                        val filled = i < pin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .border(
                                    2.dp,
                                    if (filled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    }
                }

                // Hidden capture field: drives the dots and pops the numeric keypad.
                // Backspace works (it's a normal text field) so a typo can be corrected
                // before submitting — nothing is sent until the user presses Unlock.
                BasicTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(4) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onPinEntered(pin) },
                enabled = pin.length == 4
            ) {
                Text(if (isSettingPin) "Save" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}