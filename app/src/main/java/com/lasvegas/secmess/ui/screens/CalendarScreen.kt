package com.lasvegas.secmess.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lasvegas.secmess.BuildConfig
import com.lasvegas.secmess.R
import com.lasvegas.secmess.crypto.KeyStoreHelper
import com.lasvegas.secmess.model.AgendaEvent
import com.lasvegas.secmess.repository.AgendaRepository
import com.lasvegas.secmess.ui.components.OnGoingTitle
import com.lasvegas.secmess.ui.components.OpiarioExcerpt
import com.lasvegas.secmess.ui.components.AnimatedScrollIndicator
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    hasPin: Boolean,
    keyStoreHelper: KeyStoreHelper,
    onPinSet: (String) -> Unit,
    onPinVerify: (String) -> Boolean,
    onUnlockSuccess: () -> Unit,
    isInsideUnsafeZone: Boolean = false,
    isRevoked: Boolean = false,
    initialDate: String? = null,
) {
    val context = LocalContext.current
    val currentLang = keyStoreHelper.getLanguage()
    val locale = if (currentLang == "pt") java.util.Locale("pt", "PT") else java.util.Locale.ENGLISH

    var selectedDate by remember {
        mutableStateOf(
            initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        )
    }
    var currentMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    LaunchedEffect(initialDate) {
        initialDate?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { d ->
                selectedDate = d
                currentMonth = YearMonth.from(d)
            }
        }
    }
    val daysInMonth = remember(currentMonth) { currentMonth.lengthOfMonth() }
    val firstDayOfWeek = remember(currentMonth) { currentMonth.atDay(1).dayOfWeek.value % 7 }
    
    val isBanned = remember { keyStoreHelper.isBanned() }
    
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "v1.0"
        } catch (e: Exception) {
            "v1.0"
        }
    }
    
    val events by AgendaRepository.events.collectAsState()
    val eventsForSelectedDate = remember(selectedDate, events) {
        events.filter { it.date == selectedDate.toString() }
    }

    // PIN Unlock States
    var isRecordingSequence by remember { mutableStateOf(false) }
    var isSetupMode by remember { mutableStateOf(false) }
    var inputSequence by remember { mutableStateOf(emptyList<Int>()) }

    LaunchedEffect(isRevoked) {
        if (isRevoked) {
            isRecordingSequence = false
            isSetupMode = false
            inputSequence = emptyList()
        }
    }

    // Dialog State
    var showAddEventDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            ) {
                var clickCount by remember { mutableIntStateOf(0) }
                TopAppBar(
                    title = { 
                        Column {
                            OnGoingTitle(
                                text = "O-n G-oing CallEnder", 
                                isConnected = false,
                                onBannerClick = {
                                    clickCount++
                                    if (clickCount >= 5 && !isInsideUnsafeZone && !isRevoked) {  // 5 clicks; unlock disabled in unsafe zone
                                        clickCount = 0
                                        if (!hasPin) {
                                            isRecordingSequence = true
                                            isSetupMode = true
                                            inputSequence = emptyList()
                                        } else {
                                            isRecordingSequence = true
                                            isSetupMode = false
                                            inputSequence = emptyList()
                                        }
                                    }
                                }
                            )
                            com.lasvegas.secmess.ui.components.MarqueeSlogan(text = stringResource(R.string.dealing_with_mess))
                        }
                    },
                    actions = {
                        IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                            Icon(Icons.Default.ChevronLeft, null)
                        }
                        IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isBanned) {
                FloatingActionButton(
                    onClick = { showAddEventDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Event")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    )
                )
        ) {
            // Days of week header (Oscar Niemeyer - Minimalist/Straightforward)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                val daysOfWeek = remember(locale) {
                    val dayNames = mutableListOf<String>()
                    listOf(java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, 
                           java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, 
                           java.time.DayOfWeek.SATURDAY).forEach { dow ->
                        dayNames.add(dow.getDisplayName(TextStyle.SHORT, locale))
                    }
                    dayNames
                }
                daysOfWeek.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }

            // Calendar Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                items(firstDayOfWeek) { Spacer(modifier = Modifier.aspectRatio(1f)) }
                items(daysInMonth) { day ->
                    val date = currentMonth.atDay(day + 1)
                    val isToday = date == LocalDate.now()
                    val isSelected = inputSequence.contains(day + 1)
                    val hasEvents = events.any { it.date == date.toString() }
                    
                    DayCell(
                        day = day + 1,
                        isToday = isToday,
                        isSelected = isSelected || date == selectedDate,
                        hasEvents = hasEvents,
                        onClick = { 
                            selectedDate = date
                            if (isRecordingSequence && !isInsideUnsafeZone && !isRevoked) {
                                inputSequence = inputSequence + (day + 1)
                                if (inputSequence.size == 4) {
                                    val pin = inputSequence.joinToString("-")
                                    if (isSetupMode) {
                                        onPinSet(pin)
                                        isRecordingSequence = false
                                        isSetupMode = false
                                        inputSequence = emptyList()
                                        onUnlockSuccess()   // first usage: straight into the messenger
                                    } else {
                                        if (onPinVerify(pin)) {
                                            isRecordingSequence = false
                                            inputSequence = emptyList()
                                            onUnlockSuccess()
                                        } else {
                                            inputSequence = emptyList()
                                        }
                                    }
                                }
                            }
                        },
                        onLongPress = {
                            // Handled by top logo tap 2 times now
                        }
                    )
                }
            }

            // Professional Agenda Section (Dalí-inspired curves and flow)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 48.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp)
            ) {
                Text(
                    text = selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (eventsForSelectedDate.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.EventNote, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(stringResource(R.string.no_events), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                
                                Spacer(modifier = Modifier.height(32.dp))
                                OpiarioExcerpt()
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(eventsForSelectedDate) { event ->
                                AgendaEventItem(event) {
                                    AgendaRepository.deleteEvent(event.id)
                                }
                            }
                            item { 
                                OpiarioExcerpt()
                                Spacer(modifier = Modifier.height(80.dp)) // FAB space
                            }
                        }
                    }
                }

                // Global Version Display (Always at bottom of Agenda section)
                Text(
                    text = "Build: $appVersion (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
            
            // Bottom Info
            if (isRecordingSequence) {
                // Only 4 small dots — no text, no colour bar, no info.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(4) { i ->
                        val filled = i < inputSequence.size
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 5.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
            }
        }
    }

    if (showAddEventDialog) {
        var title by remember { mutableStateOf("") }
        var time by remember { mutableStateOf("12:00") }
        
        AlertDialog(
            onDismissRequest = { showAddEventDialog = false },
            title = { Text(stringResource(R.string.add_event)) },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.event_name)) })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text(stringResource(R.string.alarm_time_label)) })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        AgendaRepository.addEvent(AgendaEvent(UUID.randomUUID().toString(), selectedDate.toString(), title, time = time))
                        showAddEventDialog = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddEventDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun DayCell(day: Int, isToday: Boolean, isSelected: Boolean, hasEvents: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }
            )
            .clickable { onClick() }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPress() }, onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
            if (hasEvents) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
fun AgendaEventItem(event: AgendaEvent, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 0.dp), // Niemeyer Curves
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(event.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private val CircleShape = RoundedCornerShape(50)
