package com.bashworks.cameraim

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        val client = CamerAimClient()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFFD08C34),
                    secondary = androidx.compose.ui.graphics.Color(0xFF61C0BF),
                    background = androidx.compose.ui.graphics.Color(0xFF101820),
                    surface = androidx.compose.ui.graphics.Color(0xFF1B2732)
                )
            ) {
                CamerAimScreen(store, client)
            }
        }
    }

    @Composable
    private fun CamerAimScreen(store: SettingsStore, client: CamerAimClient) {
        val saved = remember { store.load() }
        var baseUrl by remember { mutableStateOf(saved.baseUrl) }
        var machineId by remember { mutableStateOf(saved.machineId) }
        var token by remember { mutableStateOf(saved.readToken) }
        var dashboard by remember { mutableStateOf<DashboardState?>(null) }
        var cameraImage by remember { mutableStateOf<Bitmap?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var refreshing by remember { mutableStateOf(false) }
        var monitoring by remember { mutableStateOf(store.monitoringActive()) }
        var monitoringDetail by remember { mutableStateOf(store.monitoringDetail()) }
        val scope = rememberCoroutineScope()

        fun currentSettings(): ConnectionSettings {
            require(machineId.matches(Regex("^[A-Za-z0-9._-]{1,80}$"))) { "Machine ID nije ispravan." }
            require(token.isNotBlank()) { "Read token je obavezan." }
            return ConnectionSettings(MonitoringProtocol.normalizeBaseUrl(baseUrl), machineId, token)
        }

        fun refresh() {
            scope.launch {
                refreshing = true
                try {
                    val settings = currentSettings()
                    store.save(settings)
                    val state = client.state(settings)
                    dashboard = state
                    cameraImage = state.imageUrl?.let { client.image(settings, it) }
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "Veza sa serverom nije uspela."
                } finally {
                    refreshing = false
                }
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                try {
                    val settings = currentSettings()
                    store.save(settings)
                    MonitoringService.start(this, settings)
                    monitoring = true
                    monitoringDetail = "Povezivanje"
                    error = null
                } catch (failure: Exception) {
                    error = failure.message
                }
            } else {
                error = "Dozvola za obaveštenja je potrebna za foreground praćenje."
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                monitoring = store.monitoringActive()
                monitoringDetail = store.monitoringDetail()
                delay(1_000)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("G-BASHWARE CamerAim", style = MaterialTheme.typography.headlineSmall)
            Text("Lokalni CNC nadzor — API v1", color = MaterialTheme.colorScheme.secondary)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Veza", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.1.20:5187") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = machineId,
                        onValueChange = { machineId = it },
                        label = { Text("Machine ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Read token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refresh() }, enabled = !refreshing) {
                            Text(if (refreshing) "Osvežavam…" else "Osveži")
                        }
                        Button(onClick = {
                            if (monitoring) {
                                MonitoringService.stop(this@MainActivity)
                                monitoring = false
                                monitoringDetail = "Zaustavljanje"
                            } else {
                                try {
                                    val settings = currentSettings()
                                    store.save(settings)
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        MonitoringService.start(this@MainActivity, settings)
                                        monitoring = true
                                        monitoringDetail = "Povezivanje"
                                        error = null
                                    }
                                } catch (failure: Exception) {
                                    error = failure.message
                                }
                            }
                        }) {
                            Text(if (monitoring) "Zaustavi praćenje" else "Pokreni praćenje")
                        }
                    }
                    Text("Praćenje: " + monitoringDetail)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            dashboard?.let { state ->
                CameraCard(state, cameraImage)
                StatusCard(state)
            } ?: Card {
                Text(
                    "Unesite adresu i read token, zatim izaberite Osveži.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Prototip koristi HTTP samo na pouzdanoj lokalnoj mreži. Android može prekinuti " +
                    "praćenje; pri sledećem otvaranju proverite prikazani status.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun CameraCard(state: DashboardState, bitmap: Bitmap?) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kamera", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.cameraAvailable) "Dostupna" else "Nedostupna")
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Poslednja fotografija CNC kamere",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                } else {
                    Text(state.cameraDetail ?: "Fotografija još nije primljena.")
                }
                Text("Snimljeno: " + ageLabel(state.imageCapturedAt))
            }
        }
    }

    @Composable
    private fun StatusCard(state: DashboardState) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Status mašine", style = MaterialTheme.typography.titleMedium)
                ValueRow("Podaci", state.freshness)
                ValueRow("Veza", state.connection)
                ValueRow("Posao", state.jobFile ?: "—")
                ValueRow("Stanje", state.jobState ?: "—")
                state.progressPercent?.let {
                    LinearProgressIndicator(
                        progress = { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ValueRow("Napredak", "%.1f %%".format(it))
                }
                ValueRow("Proteklo", durationLabel(state.elapsedSeconds))
                ValueRow("Preostalo (procena)", durationLabel(state.estimatedRemainingSeconds))
                ValueRow("Feed", state.feed?.let { "%.1f %s".format(it, state.feedUnit ?: "") } ?: "—")
                ValueRow("RPM", state.rpm?.let { "%.0f".format(it) } ?: "—")
                val unit = state.positionUnit ?: ""
                ValueRow("XYZ", listOf(state.x, state.y, state.z).joinToString(" / ") { it?.let { value -> "%.3f".format(value) } ?: "—" } + " " + unit)
                ValueRow("Poslednji status", ageLabel(state.lastStatusAt))
            }
        }
    }

    @Composable
    private fun ValueRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value)
        }
    }

    private fun durationLabel(seconds: Long?): String {
        if (seconds == null) return "—"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining) else "%02d:%02d".format(minutes, remaining)
    }

    private fun ageLabel(timestamp: String?): String {
        if (timestamp == null) return "—"
        return try {
            val age = Duration.between(OffsetDateTime.parse(timestamp), OffsetDateTime.now()).seconds.coerceAtLeast(0)
            when {
                age < 5 -> "upravo sada"
                age < 60 -> "pre " + age + " s"
                age < 3600 -> "pre " + (age / 60) + " min"
                else -> "pre " + (age / 3600) + " h"
            }
        } catch (_: Exception) {
            timestamp
        }
    }
}
