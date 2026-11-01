package com.glossirc.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val soundEvents = listOf(
    "connect" to "Server connected", "private" to "Private message received",
    "join" to "Channel joined", "dcc_send" to "DCC send started",
    "dcc_receive" to "DCC receive started", "dcc_complete" to "DCC transfer completed"
)

@Composable
fun GlossSettings(vm: IrcViewModel, onDismiss: () -> Unit, onThemeChanged: () -> Unit) {
    var selectedEvent by remember { mutableStateOf("connect") }
    var revision by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf("Sounds") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { vm.getApplication<android.app.Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.soundManager.setUri(selectedEvent, uri.toString()); revision++
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("GlossIRC Settings") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 550.dp).verticalScroll(rememberScrollState())) {
            Row { listOf("Sounds", "Colors").forEach { label -> TextButton(onClick = { tab = label }) { Text(label) } } }
            if (tab == "Sounds") {
                Text("Select a sound for each event, or disable it.")
                soundEvents.forEach { (key, title) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(title)
                            Text(if (vm.soundManager.uri(key).isBlank()) "System notification" else "Custom audio selected", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = vm.soundManager.enabled(key), onCheckedChange = { vm.soundManager.setEnabled(key, it); revision++ })
                    }
                    Row {
                        TextButton(onClick = { selectedEvent = key; picker.launch(arrayOf("audio/*")) }) { Text("Choose file") }
                        TextButton(onClick = { vm.soundManager.setUri(key, ""); revision++ }) { Text("Default") }
                        TextButton(onClick = { vm.soundManager.play(key) }) { Text("Test") }
                    }
                    HorizontalDivider()
                }
            } else {
                Text("Appearance — select a palette color. Changes apply immediately.")
                listOf("background" to "Chat background", "panel" to "Panels / drawers", "accent" to "Accent color").forEach { (key, label) ->
                    Text(label)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf(0xFF080D1B, 0xFF18223B, 0xFF26355F, 0xFF000000, 0xFF24382D, 0xFF47314F, 0xFF6BE6F5, 0xFFA9A0FF, 0xFFF4F4F4).forEach { argb ->
                            Box(Modifier.padding(4.dp).size(32.dp).background(Color(argb), RoundedCornerShape(6.dp)).border(1.dp, Color.Gray, RoundedCornerShape(6.dp)).clickable {
                                vm.uiPrefs.edit().putInt(key, argb.toInt()).apply(); onThemeChanged()
                            })
                        }
                    }
                }
                TextButton(onClick = {
                    vm.uiPrefs.edit().clear().apply(); onThemeChanged()
                }) { Text("Restore default colors") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
