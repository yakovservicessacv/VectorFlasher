package dev.vecflash.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vecflash.FlasherViewModel
import dev.vecflash.R
import dev.vecflash.data.FirmwareCatalog
import dev.vecflash.data.OtaStorage
import dev.vecflash.rts.Rts
import dev.vecflash.rts.VectorSession

// ---------- mapeos a recursos ----------

/** Total de pasos del flujo, para la cabecera tipo "Paso 3 de 6". */
private const val TOTAL_STEPS = 6

@StringRes
private fun stackDescription(id: String): Int = when (id) {
    "pvic dev" -> R.string.stack_desc_pvic_dev
    "pvic oskr" -> R.string.stack_desc_pvic_oskr
    "pvic ddl" -> R.string.stack_desc_pvic_ddl
    "pvic unlock" -> R.string.stack_desc_pvic_unlock
    "pvic weird" -> R.string.stack_desc_pvic_weird
    "pvic dvt2", "pvic dvt3" -> R.string.stack_desc_pvic_dvt
    "custom firmware" -> R.string.stack_desc_custom
    "oskr custom firmware" -> R.string.stack_desc_oskr
    "unstable custom firmware" -> R.string.stack_desc_unstable
    "beta" -> R.string.stack_desc_beta
    "prod" -> R.string.stack_desc_prod
    "escape-pod" -> R.string.stack_desc_escapepod
    "modified escape-pod" -> R.string.stack_desc_modep
    else -> R.string.stack_desc_utility
}

@StringRes
private fun wifiStateRes(state: Int): Int = when (state) {
    0 -> R.string.wifi_state_0
    1 -> R.string.wifi_state_1
    2 -> R.string.wifi_state_2
    3 -> R.string.wifi_state_3
    else -> R.string.wifi_state_unknown
}

@StringRes
private fun authTypeRes(auth: Int): Int = when (auth) {
    0 -> R.string.auth_0
    1 -> R.string.auth_1
    2 -> R.string.auth_2
    3 -> R.string.auth_3
    4 -> R.string.auth_4
    5 -> R.string.auth_5
    6 -> R.string.auth_6
    else -> R.string.auth_unknown
}

// ---------- raiz ----------

@Composable
fun FlasherApp(vm: FlasherViewModel, permissionsGranted: Boolean) {
    val phase by vm.session.phase.collectAsState()
    val guideSeen by vm.guideSeen.collectAsState()
    val offlineAsked by vm.offlineAsked.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(VecBackdrop)
    ) {
        when {
            !permissionsGranted -> CenteredMessage(
                stringResource(R.string.err_permissions_title),
                stringResource(R.string.err_permissions_body)
            )

            !guideSeen -> GuideScreen(onDone = { vm.markGuideSeen() })

            showPicker -> OfflinePickerScreen(
                vm = vm,
                onDone = { showPicker = false; vm.markOfflineAsked() }
            )

            !offlineAsked -> OfflinePromptScreen(
                onYes = { showPicker = true },
                onNo = { vm.markOfflineAsked() }
            )

            else -> MainScaffold(vm, phase)
        }
    }
}

@Composable
private fun MainScaffold(vm: FlasherViewModel, phase: VectorSession.Phase) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { FlasherTopBar(vm, phase) }
    ) { inner ->
        Box(
            Modifier
                .padding(inner)
                .fillMaxSize()
        ) {
            when (phase) {
                is VectorSession.Phase.Failed ->
                    ErrorScreen((phase as VectorSession.Phase.Failed).message) { vm.session.clearError() }
                is VectorSession.Phase.Idle -> ScanScreen(vm)
                is VectorSession.Phase.Connecting -> BusyScreen(stringResource(R.string.connecting))
                is VectorSession.Phase.Handshaking -> BusyScreen(stringResource(R.string.handshaking))
                is VectorSession.Phase.NeedPin -> PinScreen(vm)
                is VectorSession.Phase.Authenticating -> BusyScreen(stringResource(R.string.authenticating))
                is VectorSession.Phase.Ready -> ReadyScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlasherTopBar(vm: FlasherViewModel, phase: VectorSession.Phase) {
    TopAppBar(
        title = {
            Column {
                Text(
                    stringResource(R.string.app_name),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                val sub = when (phase) {
                    is VectorSession.Phase.Ready -> vm.session.connectedName
                    is VectorSession.Phase.Idle -> stringResource(R.string.not_connected)
                    else -> ""
                }
                if (sub.isNotBlank()) {
                    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            when (phase) {
                is VectorSession.Phase.Ready ->
                    TextButton(onClick = { vm.session.disconnect() }) {
                        Text(stringResource(R.string.disconnect))
                    }
                is VectorSession.Phase.Idle ->
                    TextButton(onClick = { vm.showGuideAgain() }) {
                        Text(stringResource(R.string.guide_again), fontSize = 12.sp)
                    }
                else -> Unit
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

// ---------- guia ----------

private data class GuideStep(
    val image: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val screen: Int? = null
)

/**
 * Solo tres pasos: dejar a Vector en modo emparejamiento.
 * El cuarto (teclear los 6 numeros) no pinta nada aqui, porque el codigo no
 * aparece hasta despues de elegir el robot: vive en la pantalla del PIN.
 */
private val GUIDE_STEPS = listOf(
    GuideStep(R.drawable.art_charger, R.string.guide_step1_title, R.string.guide_step1_body),
    GuideStep(R.drawable.art_lights, R.string.guide_step2_title, R.string.guide_step2_body, R.string.guide_step2_screen),
    GuideStep(R.drawable.art_press, R.string.guide_step3_title, R.string.guide_step3_body, R.string.guide_step3_screen)
)

@Composable
private fun GuideScreen(onDone: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    val step = GUIDE_STEPS[index]
    val last = index == GUIDE_STEPS.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.weight(1f)) {
                StepHeader(
                    step = index + 1,
                    totalSteps = TOTAL_STEPS,
                    title = stringResource(step.title),
                    session = null,
                    progress = (index + 1) / TOTAL_STEPS.toFloat()
                )
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.guide_skip)) }
        }

        Spacer(Modifier.height(20.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, CardShape)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(step.image),
                    contentDescription = stringResource(step.title),
                    modifier = Modifier.size(200.dp),
                    // El arte original es de trazo blanco: sobre fondo claro hay
                    // que tenirlo o no se ve nada.
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(Modifier.height(26.dp))

            Row(verticalAlignment = Alignment.Top) {
                StepBadge(index + 1)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(step.body),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }

            step.screen?.let {
                Spacer(Modifier.height(14.dp))
                ScreenChip(stringResource(it))
            }
        }

        Spacer(Modifier.height(16.dp))
        Dots(GUIDE_STEPS.size, index)
        Spacer(Modifier.height(18.dp))

        PrimaryButton(
            text = stringResource(if (last) R.string.guide_start else R.string.guide_next),
            onClick = { if (last) onDone() else index++ },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------- descarga offline: pregunta y seleccion ----------

@Composable
private fun OfflinePromptScreen(onYes: () -> Unit, onNo: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_sdcard),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.offline_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.offline_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.offline_note),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            text = stringResource(R.string.offline_yes),
            onClick = onYes,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.offline_no))
        }
    }
}

@Composable
private fun OfflinePickerScreen(vm: FlasherViewModel, onDone: () -> Unit) {
    val stacks by vm.stacks.collectAsState()
    // clave: nombre local unico -> (id del stack, firmware)
    val selected = remember { mutableStateMapOf<String, Pair<String, FirmwareCatalog.Firmware>>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            stringResource(R.string.offline_pick_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.offline_pick_body),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f)) {
            stacks.forEach { stack ->
                item(key = "h_" + stack.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded[stack.id] = !(expanded[stack.id] ?: false) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stack.label,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            if (expanded[stack.id] == true) "−" else "+",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 18.sp
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.outline)
                }
                if (expanded[stack.id] == true) {
                    items(stack.firmwares, key = { stack.id + "/" + it.name }) { fw ->
                        val key = OtaStorage.localName(stack.id, fw.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selected.containsKey(key)) selected.remove(key)
                                    else selected[key] = stack.id to fw
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.containsKey(key),
                                onCheckedChange = { on ->
                                    if (on) selected[key] = stack.id to fw else selected.remove(key)
                                }
                            )
                            Text(fw.name, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(R.string.offline_download_n, selected.size),
            onClick = {
                vm.enqueueAll(selected.values.toList())
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selected.isNotEmpty()
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.guide_skip))
        }
    }
}

// ---------- pantallas auxiliares ----------

@Composable
private fun BusyScreen(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorScreen(message: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.err_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.err_retry), onDismiss)
    }
}

@Composable
private fun CenteredMessage(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

// ---------- escaneo ----------

@Composable
private fun ScanScreen(vm: FlasherViewModel) {
    val devices by vm.session.devices.collectAsState()
    val scanning by vm.session.scanning.collectAsState()

    LaunchedEffect(Unit) {
        if (!scanning && devices.isEmpty()) vm.session.startScan()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        StepHeader(
            step = 4,
            totalSteps = TOTAL_STEPS,
            title = stringResource(R.string.scan_title),
            session = null,
            progress = 4f / TOTAL_STEPS
        )
        Spacer(Modifier.height(24.dp))

        if (devices.isEmpty()) {
            VecCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        if (scanning) stringResource(R.string.scan_searching)
                        else stringResource(R.string.scan_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                if (!scanning) {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(stringResource(R.string.scan_retry), { vm.session.startScan() })
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(devices) { d ->
                VecCard(
                    modifier = Modifier
                        .padding(vertical = 5.dp)
                        .clickable { vm.session.connect(d) },
                    padding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                d.device.address,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PrimaryButton(
                            text = stringResource(R.string.wifi_connect),
                            onClick = { vm.session.connect(d) },
                            showChevron = true
                        )
                    }
                }
            }
        }

        if (devices.isNotEmpty() && scanning) {
            TextButton(onClick = { vm.session.stopScan() }) {
                Text(stringResource(R.string.scan_stop))
            }
        }

        Text(
            stringResource(R.string.attribution),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ---------- PIN ----------

@Composable
private fun PinScreen(vm: FlasherViewModel) {
    var pin by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        StepHeader(
            step = 5,
            totalSteps = TOTAL_STEPS,
            title = stringResource(R.string.pin_title),
            session = vm.session.connectedName.ifBlank { null },
            progress = 5f / TOTAL_STEPS
        )
        Spacer(Modifier.height(20.dp))
        // Esta ilustracion es propia y ya trae cuerpo, cara y digitos en colores
        // distintos. Tenirla los aplasta todos a un verde plano.
        Image(
            painter = painterResource(R.drawable.guide_step4),
            contentDescription = null,
            modifier = Modifier.size(170.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.guide_step4_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.guide_step4_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(12) },
            label = { Text(stringResource(R.string.pin_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            stringResource(R.string.pin_button),
            { vm.session.submitPin(pin) },
            enabled = pin.length >= 4
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.session.disconnect() }) { Text(stringResource(R.string.cancel)) }
    }
}

// ---------- principal ----------

@Composable
private fun ReadyScreen(vm: FlasherViewModel) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf(
        stringResource(R.string.tab_status),
        stringResource(R.string.tab_wifi),
        stringResource(R.string.tab_firmware)
    )
    val icons = listOf(R.drawable.ic_sliders, R.drawable.ic_wifi, R.drawable.ic_layers)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> StatusTab(vm)
                1 -> WifiTab(vm)
                else -> FirmwareTab(vm)
            }
        }

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = {
                        Icon(
                            painterResource(icons[i]),
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    },
                    label = {
                        Text(
                            t,
                            fontSize = 11.sp,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusTab(vm: FlasherViewModel) {
    val status by vm.session.status.collectAsState()
    val log by vm.session.log.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.art_charger),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        vm.session.connectedName.ifBlank { stringResource(R.string.app_name) },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(R.string.status_hero_connected),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    status?.version?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            it,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        VecCard {
            val s = status
            if (s == null) {
                Text(
                    stringResource(R.string.status_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val yes = stringResource(R.string.yes)
                val no = stringResource(R.string.no)
                InfoRow(stringResource(R.string.status_esn), s.esn.ifBlank { "—" }, accent = true)
                InfoRow(stringResource(R.string.status_firmware), s.version.ifBlank { "—" })
                InfoRow(stringResource(R.string.status_wifi), stringResource(wifiStateRes(s.wifiState)))
                InfoRow(stringResource(R.string.status_network), s.ssid.ifBlank { "—" })
                InfoRow(stringResource(R.string.status_battery), s.batteryState.toString())
                InfoRow(stringResource(R.string.status_owner), if (s.hasOwner) yes else no)
                InfoRow(stringResource(R.string.status_cloud), if (s.isCloudAuthed) yes else no)
                InfoRow(stringResource(R.string.status_ota), if (s.otaInProgress) yes else no)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { vm.session.requestStatus() },
            modifier = Modifier.fillMaxWidth(),
            shape = PillShape
        ) { Text(stringResource(R.string.status_refresh)) }

        Spacer(Modifier.height(24.dp))
        SectionTitle(stringResource(R.string.log_title))
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            log.takeLast(40).forEach {
                Text(
                    it,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- Wi-Fi ----------

@Composable
private fun WifiTab(vm: FlasherViewModel) {
    val networks by vm.session.networks.collectAsState()
    val result by vm.session.wifiResult.collectAsState()
    val scanning by vm.session.wifiScanning.collectAsState()
    val connecting by vm.session.wifiConnecting.collectAsState()
    var selected by remember { mutableStateOf<Rts.WifiNetwork?>(null) }
    var password by remember { mutableStateOf("") }

    // Solo cerramos la tarjeta si de verdad conecto. Si fallo, la contrasena se
    // queda escrita: volver a teclearla entera para reintentar es una tortura.
    LaunchedEffect(result) {
        val r = result
        if (r != null && (r.wifiState == 1 || r.wifiState == 2)) {
            selected = null
            password = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            stringResource(R.string.wifi_note),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            if (scanning) stringResource(R.string.wifi_scanning) else stringResource(R.string.wifi_scan),
            { vm.session.scanWifi() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanning
        )
        Spacer(Modifier.height(14.dp))

        result?.let {
            val ok = it.wifiState == 1 || it.wifiState == 2
            VecCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (ok) R.drawable.ic_wifi else R.drawable.ic_warning),
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.wifi_result, stringResource(wifiStateRes(it.wifiState))),
                            color = if (ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!ok) {
                            Text(
                                "connectResult=" + it.connectResult,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val sel = selected
        if (sel != null) {
            VecCard {
                Text(
                    sel.ssid.ifBlank { stringResource(R.string.wifi_hidden) },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(authTypeRes(sel.authType)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (sel.authType != 0) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.wifi_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = if (connecting) stringResource(R.string.wifi_connecting)
                               else stringResource(R.string.wifi_connect),
                        onClick = {
                            vm.session.connectWifi(sel.ssid, password, sel.authType, sel.hidden)
                        },
                        enabled = !connecting
                    )
                    Spacer(Modifier.width(8.dp))
                    if (connecting) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        TextButton(onClick = { selected = null; password = "" }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
                if (connecting) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.wifi_connecting_note),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (networks.isEmpty()) {
            Text(
                stringResource(R.string.wifi_none),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn {
            items(networks) { n ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = n; password = "" }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            n.ssid.ifBlank { stringResource(R.string.wifi_hidden) },
                            fontSize = 15.sp
                        )
                        Text(
                            stringResource(authTypeRes(n.authType)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n.hidden) {
                            Icon(
                                painterResource(R.drawable.ic_hidden),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            "${n.signalStrength}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ---------- firmware ----------

@Composable
private fun FirmwareTab(vm: FlasherViewModel) {
    // padding lateral generoso pero poco aire arriba: en la captura sobraba mucho
    val stacks by vm.stacks.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val ota by vm.session.ota.collectAsState()
    val status by vm.session.status.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val stored by vm.stored.collectAsState()
    val storedBytes by vm.storedBytes.collectAsState()
    val serverError by vm.localServerError.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val sourcesOk by vm.sourcesOk.collectAsState()

    // El indice guarda stack y archivo por separado; para pintar basta el nombre local.
    val storedNames: Set<String> = remember(stored) { stored.map { e -> e.localName }.toSet() }

    var openStack by remember { mutableStateOf<FirmwareCatalog.Stack?>(null) }

    // Un error viejo no debe quedarse contradiciendo al contador de guardados.
    LaunchedEffect(openStack) { vm.clearLocalServerError() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
    ) {
        if (ota.active || ota.finished || ota.error != null) {
            OtaProgressCard(ota) { vm.session.cancelOta() }
            return@Column
        }

        updateStatus?.let { s ->
            val n = s.removePrefix("updated:").toIntOrNull() ?: 0
            if (n > 0) {
                VecCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_cloud_download),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.offline_updated, n),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.dismissUpdateStatus() }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (downloads.isNotEmpty()) {
            VecCard {
                SectionTitle(stringResource(R.string.offline_downloading))
                Spacer(Modifier.height(10.dp))
                downloads.values.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(d.fileName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (d.failed) {
                            Text(
                                stringResource(R.string.offline_failed),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (d.total > 0) {
                            Text(
                                formatBytes(d.downloaded) + " / " + formatBytes(d.total),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = d.fraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(PillShape)
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { vm.cancelDownload(d.stackId, d.fileName) }) {
                        Text(stringResource(R.string.cancel), fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (stored.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_sdcard),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.offline_saved_count, stored.size, formatBytes(storedBytes)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = { vm.deleteAllStored() }) {
                    Text(stringResource(R.string.offline_delete_all), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        serverError?.let {
            VecCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        it,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.clearLocalServerError() }) { Text("✕") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val wifiOk = status?.let { it.wifiState == 1 || it.wifiState == 2 } ?: false
        if (!wifiOk) {
            Text(
                stringResource(R.string.fw_needs_wifi),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(12.dp))
        }

        val current = openStack
        if (current == null) {
            StackList(stacks, refreshing) { openStack = it }
            if (sourcesOk.isNotEmpty()) {
                Text(
                    stringResource(R.string.offline_sources, sourcesOk.joinToString(", ")),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            FirmwareList(
                stack = current,
                stored = storedNames,
                storedSizes = remember(stored) {
                    stored.associate { e -> e.localName to e.size }
                },
                onBack = { openStack = null },
                onFlash = { url -> vm.session.startOta(url) },
                onFlashLocal = { name -> vm.installFromPhone(current.id, name) },
                onDownload = { fw -> vm.enqueue(current.id, fw) },
                onDelete = { name -> vm.deleteStored(current.id, name) }
            )
        }
    }
}

@Composable
private fun ColumnScope.StackList(
    stacks: List<FirmwareCatalog.Stack>,
    refreshing: Boolean,
    onOpen: (FirmwareCatalog.Stack) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_layers),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(8.dp))
        SectionTitle(stringResource(R.string.fw_stack))
        if (refreshing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(
                Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    LazyColumn {
        items(stacks) { s ->
            VecCard(
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .clickable { onOpen(s) },
                padding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                Text(
                    s.label,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(stackDescription(s.id)),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.fw_count, s.firmwares.size),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FirmwareList(
    stack: FirmwareCatalog.Stack,
    stored: Set<String>,
    storedSizes: Map<String, Long>,
    onBack: () -> Unit,
    onFlash: (String) -> Unit,
    onFlashLocal: (String) -> Unit,
    onDownload: (FirmwareCatalog.Firmware) -> Unit,
    onDelete: (String) -> Unit
) {
    var selected by remember(stack.id) { mutableStateOf<FirmwareCatalog.Firmware?>(null) }
    var customUrl by remember(stack.id) { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var confirmLocal by remember { mutableStateOf(false) }

    val effectiveUrl = if (customUrl.isNotBlank()) customUrl.trim() else selected?.url ?: ""

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            shape = CardShape,
            title = { Text(stringResource(R.string.fw_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.fw_confirm_body,
                        if (confirmLocal) selected?.name ?: "" else effectiveUrl
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val fw = selected
                    // fw.name pelado: fileFor() ya le antepone el stack. Mandarle el
                    // nombre local dejaba la ruta con el prefijo dos veces.
                    if (confirmLocal && fw != null) onFlashLocal(fw.name)
                    else onFlash(effectiveUrl)
                }) { Text(stringResource(R.string.fw_flash), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onBack() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "‹",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                stack.label,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                stringResource(R.string.fw_count, stack.firmwares.size),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Divider(color = MaterialTheme.colorScheme.outline)

    LazyColumn(
        Modifier.weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp, bottom = 12.dp
        )
    ) {
        items(stack.firmwares) { fw ->
            val localName = OtaStorage.localName(stack.id, fw.name)
            val onPhone = stored.contains(localName)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = fw; customUrl = "" }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected?.url == fw.url && customUrl.isBlank(),
                    onClick = { selected = fw; customUrl = "" }
                )
                Column(Modifier.weight(1f)) {
                    Text(fw.name, fontSize = 14.sp)
                    if (onPhone) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_sdcard),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            // Este si es el peso de verdad del .ota.
                            val size = storedSizes[localName] ?: 0L
                            Text(
                                stringResource(R.string.offline_stored) +
                                    if (size > 0) " · " + formatBytes(size) else "",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    if (onPhone) onDelete(fw.name) else onDownload(fw)
                }) {
                    Icon(
                        painterResource(
                            if (onPhone) R.drawable.ic_trash else R.drawable.ic_cloud_download
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    Divider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = customUrl,
        onValueChange = { customUrl = it },
        label = { Text(stringResource(R.string.fw_custom_url)) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    val chosen = selected
    val chosenOnPhone = chosen != null &&
        stored.contains(OtaStorage.localName(stack.id, chosen.name)) && customUrl.isBlank()

    PrimaryButton(
        text = stringResource(R.string.fw_flash),
        onClick = { confirmLocal = false; confirming = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = effectiveUrl.isNotBlank()
    )
    if (chosenOnPhone) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmLocal = true; confirming = true },
            modifier = Modifier.fillMaxWidth(),
            shape = PillShape
        ) { Text(stringResource(R.string.offline_install_local)) }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.offline_requirements),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OtaProgressCard(ota: VectorSession.OtaState, onCancel: () -> Unit) {
    HeroCard {
        when {
            ota.finished -> {
                Text(
                    stringResource(R.string.fw_done),
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.fw_done_body),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ota.error != null -> {
                Text(
                    stringResource(R.string.fw_failed),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    ota.error,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                Text(
                    stringResource(R.string.fw_installing),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    String.format("%.0f%%", ota.fraction * 100f),
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = ota.fraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(PillShape)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Vector informa de bytes ESCRITOS en la particion, o sea la
                    // imagen ya descomprimida: sale cerca de 4x el .ota y es casi
                    // igual para todos. Presentarlo como el tamano del firmware
                    // confundia, asi que se dice lo que es.
                    Text(
                        stringResource(
                            R.string.fw_written,
                            formatBytes(ota.current),
                            formatBytes(ota.expected)
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ota.bytesPerSecond > 0) {
                        Text(
                            stringResource(R.string.fw_speed, formatRate(ota.bytesPerSecond)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ota.etaSeconds > 0)
                        stringResource(R.string.fw_eta, formatDuration(ota.etaSeconds))
                    else stringResource(R.string.fw_eta_unknown),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (ota.stalled) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.fw_stalled_tip),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (ota.isSlow && !ota.stalled) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.fw_slow_tip),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onCancel, shape = PillShape) {
                    Text(stringResource(R.string.fw_cancel_ota))
                }
            }
        }
    }
}
