package com.selfbus.lpcflasher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.selfbus.lpcflasher.data.ControlLine
import com.selfbus.lpcflasher.data.HardwareConfig
import com.selfbus.lpcflasher.data.HardwareConfigs
import com.selfbus.lpcflasher.data.I18n
import com.selfbus.lpcflasher.data.Settings
import java.util.UUID

/** Highest port count of a supported adapter (FT4232H). */
private const val MAX_CONFIGURABLE_PORTS = 4

private val WarningColor = Color(0xFFE65100)

/** Highest port number (1-based) a profile needs. */
private fun HardwareConfig.requiredPorts(): Int =
    maxOf(dataPortIndex, resetPortIndex, ispPortIndex) + 1

/**
 * Lists all hardware wiring profiles (built-in + custom), lets the user pick
 * the active one, and launches create/edit/delete for custom ones.
 *
 * [detectedPortCount] is the port count of the currently selected USB device
 * (null if none is attached); profiles needing more ports are flagged.
 */
@Composable
fun HardwareConfigDialog(detectedPortCount: Int?, onDismiss: () -> Unit) {
    val t = I18n::t
    var selectedId by remember { mutableStateOf(Settings.selectedHardwareConfigId) }
    var customConfigs by remember { mutableStateOf(HardwareConfigs.customConfigs()) }
    var editTarget by remember { mutableStateOf<HardwareConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HardwareConfig?>(null) }

    fun refresh() {
        customConfigs = HardwareConfigs.customConfigs()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("hardwareConfigDialogTitle")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (detectedPortCount != null) {
                    Text(
                        "${t("hardwareConfigDetectedPorts")} $detectedPortCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                Text(t("hardwareConfigBuiltIn"), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                HardwareConfigs.BUILT_IN.forEach { config ->
                    HardwareConfigRow(
                        config = config,
                        selected = config.id == selectedId,
                        detectedPortCount = detectedPortCount,
                        onSelect = {
                            selectedId = config.id
                            HardwareConfigs.select(config.id)
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(t("hardwareConfigCustom"), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                customConfigs.forEach { config ->
                    HardwareConfigRow(
                        config = config,
                        selected = config.id == selectedId,
                        detectedPortCount = detectedPortCount,
                        onSelect = {
                            selectedId = config.id
                            HardwareConfigs.select(config.id)
                        },
                        onEdit = {
                            editTarget = config
                            showEditor = true
                        },
                        onDelete = { deleteTarget = config }
                    )
                }

                TextButton(onClick = {
                    editTarget = null
                    showEditor = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("hardwareConfigNew"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("close")) }
        }
    )

    if (showEditor) {
        HardwareConfigEditDialog(
            existing = editTarget,
            detectedPortCount = detectedPortCount,
            onDismiss = { showEditor = false },
            onSave = { config ->
                HardwareConfigs.save(config)
                refresh()
                showEditor = false
            }
        )
    }

    deleteTarget?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("hardwareConfigDeleteConfirmTitle")) },
            text = { Text(t("hardwareConfigDeleteConfirmMessage")) },
            confirmButton = {
                TextButton(onClick = {
                    HardwareConfigs.delete(config.id)
                    selectedId = Settings.selectedHardwareConfigId
                    refresh()
                    deleteTarget = null
                }) {
                    Text(t("hardwareConfigDelete"), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("cancel")) }
            }
        )
    }
}

@Composable
private fun HardwareConfigRow(
    config: HardwareConfig,
    selected: Boolean,
    detectedPortCount: Int?,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val t = I18n::t
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(config.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(config.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (detectedPortCount != null && config.requiredPorts() > detectedPortCount) {
                Text(
                    t("hardwareConfigPortsMissing").format(config.requiredPorts()),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningColor
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = t("hardwareConfigEdit"), modifier = Modifier.size(18.dp))
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = t("hardwareConfigDelete"), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Create/edit form for a single custom hardware configuration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareConfigEditDialog(
    existing: HardwareConfig?,
    detectedPortCount: Int?,
    onDismiss: () -> Unit,
    onSave: (HardwareConfig) -> Unit
) {
    val t = I18n::t
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var dataPort by remember { mutableIntStateOf(existing?.dataPortIndex ?: 0) }
    var resetPort by remember { mutableIntStateOf(existing?.resetPortIndex ?: 0) }
    var resetLine by remember { mutableStateOf(existing?.resetLine ?: ControlLine.DTR) }
    var resetInverted by remember { mutableStateOf(existing?.resetInverted ?: false) }
    var ispPort by remember { mutableIntStateOf(existing?.ispPortIndex ?: 0) }
    var ispLine by remember { mutableStateOf(existing?.ispLine ?: ControlLine.RTS) }
    var ispInverted by remember { mutableStateOf(existing?.ispInverted ?: false) }
    var showNameError by remember { mutableStateOf(false) }

    val lineConflict = resetPort == ispPort && resetLine == ispLine
    val requiredPorts = maxOf(dataPort, resetPort, ispPort) + 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) t("hardwareConfigEditTitleEdit") else t("hardwareConfigEditTitleNew")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showNameError = false },
                    label = { Text(t("hardwareConfigName")) },
                    isError = showNameError,
                    supportingText = { if (showNameError) Text(t("hardwareConfigNameRequired")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(t("hardwareConfigDataPort"), style = MaterialTheme.typography.labelLarge)
                PortDropdown(selected = dataPort, detectedPortCount = detectedPortCount, onSelect = { dataPort = it })

                HorizontalDivider()
                Text(t("hardwareConfigResetLine"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        PortDropdown(selected = resetPort, detectedPortCount = detectedPortCount, onSelect = { resetPort = it })
                    }
                    Box(Modifier.weight(1f)) { LineDropdown(selected = resetLine, onSelect = { resetLine = it }) }
                }
                InverterCheckbox(checked = resetInverted, onCheckedChange = { resetInverted = it })

                HorizontalDivider()
                Text(t("hardwareConfigIspLine"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        PortDropdown(selected = ispPort, detectedPortCount = detectedPortCount, onSelect = { ispPort = it })
                    }
                    Box(Modifier.weight(1f)) { LineDropdown(selected = ispLine, onSelect = { ispLine = it }) }
                }
                InverterCheckbox(checked = ispInverted, onCheckedChange = { ispInverted = it })

                if (lineConflict) {
                    Text(
                        t("hardwareConfigLineConflict"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (detectedPortCount != null && requiredPorts > detectedPortCount) {
                    Text(
                        t("hardwareConfigPortsMissing").format(requiredPorts),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningColor
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !lineConflict,
                onClick = {
                    if (name.isBlank()) {
                        showNameError = true
                        return@Button
                    }
                    onSave(
                        HardwareConfig(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            dataPortIndex = dataPort,
                            resetPortIndex = resetPort,
                            resetLine = resetLine,
                            resetInverted = resetInverted,
                            ispPortIndex = ispPort,
                            ispLine = ispLine,
                            ispInverted = ispInverted,
                            builtIn = false
                        )
                    )
                }
            ) {
                Text(t("save"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}

@Composable
private fun InverterCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(I18n.t("hardwareConfigInverted"), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortDropdown(selected: Int, detectedPortCount: Int?, onSelect: (Int) -> Unit) {
    val t = I18n::t
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "USB${selected + 1}",
            onValueChange = {},
            readOnly = true,
            label = { Text(t("hardwareConfigPort")) },
            isError = detectedPortCount != null && selected >= detectedPortCount,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (idx in 0 until MAX_CONFIGURABLE_PORTS) {
                val available = detectedPortCount == null || idx < detectedPortCount
                DropdownMenuItem(
                    text = {
                        Text(
                            if (available) "USB${idx + 1}" else "USB${idx + 1} ${t("hardwareConfigPortNotPresent")}",
                            color = if (available) Color.Unspecified else Color.Gray
                        )
                    },
                    onClick = { onSelect(idx); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineDropdown(selected: ControlLine, onSelect: (ControlLine) -> Unit) {
    val t = I18n::t
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text(t("hardwareConfigSignal")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ControlLine.entries.forEach { line ->
                DropdownMenuItem(
                    text = { Text(line.name) },
                    onClick = { onSelect(line); expanded = false }
                )
            }
        }
    }
}
