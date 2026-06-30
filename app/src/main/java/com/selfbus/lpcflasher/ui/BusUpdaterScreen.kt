package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusUpdaterScreen(
    viewModel: BusUpdaterViewModel,
    onOpenFile: () -> Unit,
    onMenuClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val log by viewModel.log.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KNX Bus-Updater") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menü")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Gateway / connection ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gateway (KNXnet/IP)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.gatewayIp,
                            onValueChange = viewModel::setGatewayIp,
                            label = { Text("IP-Adresse") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = uiState.gatewayPort,
                            onValueChange = viewModel::setGatewayPort,
                            label = { Text("Port") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.progAddress,
                            onValueChange = viewModel::setProgAddress,
                            label = { Text("Geräteadresse") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = uiState.ownAddress,
                            onValueChange = viewModel::setOwnAddress,
                            label = { Text("Eigene Adresse") },
                            singleLine = true,
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.connectAndIdentify() },
                            enabled = !uiState.isBusy && !uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Verbinden") }
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Trennen") }
                    }
                }
            }

            // ---- Device info ----
            if (uiState.uid != null || uiState.bootloaderInfo != null || uiState.appVersion != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Geräteinformationen", style = MaterialTheme.typography.titleMedium)
                        uiState.uid?.let {
                            Text("UID: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.knxSerial?.let {
                            Text("KNX#: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        uiState.bootloaderInfo?.let {
                            Text("Bootloader: $it", fontSize = 13.sp)
                        }
                        uiState.appVersion?.let {
                            if (it.isNotBlank()) Text("App-Version: $it", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ---- Firmware ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Firmware", style = MaterialTheme.typography.titleMedium)
                    uiState.firmwareFileName?.let {
                        Text(
                            "$it (${uiState.firmwareSize} Bytes ab 0x%X)".format(uiState.firmwareStartAddress),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenFile,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Firmware-Datei wählen (.hex / .bin)") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.eraseBeforeFlash,
                            onCheckedChange = viewModel::setEraseBeforeFlash,
                            enabled = !uiState.isBusy
                        )
                        Text("Bereich vor dem Flashen löschen")
                    }
                }
            }

            // ---- Actions ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aktionen", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { viewModel.flashFirmware() },
                        enabled = !uiState.isBusy && uiState.isConnected && uiState.firmwareFileName != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Flashen") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.eraseFlash() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Löschen") }
                        OutlinedButton(
                            onClick = { viewModel.restartDevice() },
                            enabled = !uiState.isBusy && uiState.isConnected,
                            modifier = Modifier.weight(1f)
                        ) { Text("Neustart") }
                    }
                }
            }

            // ---- Progress ----
            if (uiState.progress >= 0) {
                Column {
                    LinearProgressIndicator(
                        progress = (uiState.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Text("${uiState.progress}%", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- Log ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Protokoll", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearLog() }) { Text("Leeren") }
                    }
                    if (log.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall)
                    } else {
                        log.takeLast(200).forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Text(
                "Hinweis: KNX-Flashing ist experimentell und noch nicht am Gerät getestet. " +
                    "Nur Vollflash-Modus. Bei Protokollfehlern kann ein Gerät unbrauchbar werden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
