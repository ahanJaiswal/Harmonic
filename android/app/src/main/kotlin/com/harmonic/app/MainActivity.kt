package com.harmonic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlin.math.sin

private val Ink = Color(0xFF0E1412)
private val Panel = Color(0xFF17211E)
private val Line = Color(0xFF2C4038)
private val Phosphor = Color(0xFFD6FF4A)
private val Mist = Color(0xFF9BB0A6)
private val Paper = Color(0xFFE7F2EB)

private val ZoneColor = mapOf(
    "A" to Color(0xFF3DDC97),
    "B" to Phosphor,
    "C" to Color(0xFFFFB020),
    "D" to Color(0xFFFF5A36),
)

class MainActivity : ComponentActivity() {
    private val model: HarmonicModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Phosphor, onPrimary = Ink)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    HarmonicScreen(model)
                }
            }
        }
    }
}

@Composable
private fun HarmonicScreen(model: HarmonicModel) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("HARMONIC", color = Phosphor, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text("Multi-phone vibration analyzer", color = Mist, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        // Keep session/auth status on Home and Lobby so Recording/Result stay uncluttered.
        if (model.status.isNotBlank() && (model.screen == Screen.Home || model.screen == Screen.Lobby)) {
            Text(model.status, color = Mist, fontSize = 12.sp)
        }
        if (model.screen == Screen.Home || model.screen == Screen.Join || model.screen == Screen.Lobby) {
            model.error?.let { Text(it, color = Color(0xFFFF5A36), modifier = Modifier.padding(top = 8.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        when (model.screen) {
            Screen.Home -> Home(model)
            Screen.Join -> Join(model)
            Screen.Lobby -> Lobby(model)
            Screen.Record -> Record(model)
            Screen.Result -> Result(model)
        }
    }
}

@Composable
private fun Home(model: HarmonicModel) {
    Wave()
    Spacer(Modifier.height(16.dp))
    Text(
        "Put two or more phones on the machine, record together, and read a plain-language fault call with an ISO severity zone.",
        color = Paper,
    )
    Spacer(Modifier.height(8.dp))
    Text("Screen up. Top edge toward the drive end. Tap the phones together when the trace starts and again near the end.", color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(18.dp))
    PrimaryButton("Host a session", enabled = FirebaseGate.ready && !model.busy) { model.host() }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Join with a code", enabled = FirebaseGate.ready && !model.busy) { model.openJoin() }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Record this phone only") { model.recordThisPhone() }
    if (!model.sensorOk) Text("No accelerometer on this device.", color = Color(0xFFFFB020), modifier = Modifier.padding(top = 8.dp))
    if (!FirebaseGate.ready) {
        Text(
            "Add google-services.json to enable sessions and hybrid Gemini. A single-phone reading still runs the DSP.",
            color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Join(model: HarmonicModel) {
    var code by remember { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase().take(6) },
        label = { Text("Session code") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    PrimaryButton("Join", enabled = code.length >= 4 && !model.busy) { model.join(code) }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Back") { model.home() }
}

@Composable
private fun Lobby(model: HarmonicModel) {
    val live = model.session
    Text(live?.code ?: model.code, color = Phosphor, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
    if (live != null) {
        Image(bitmap = remember(live.code) { qrImage(live.code) }, contentDescription = "QR code for ${live.code}", modifier = Modifier.height(180.dp))
    }
    Text("Others type this code or scan the QR. Android and iPhone can share one session.", color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Text("On this session", color = Paper, fontWeight = FontWeight.SemiBold)
    live?.members?.forEach { Text("• ${it.name}  ${it.platform}", color = Paper) }
        ?: Text("Connecting…", color = Mist)
    Spacer(Modifier.height(12.dp))
    if (model.isOwner) {
        MachineForm(model)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Start recording", enabled = !model.busy) {
            model.saveMachine()
            model.startSessionRecording()
        }
    } else {
        Text("Waiting for the host to start. Keep the phone on the bearing.", color = Paper)
    }
}

@Composable
private fun MachineForm(model: HarmonicModel) {
    Text("Machine", color = Paper, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("fan", "pump", "motor").forEach { type ->
            FilterChip(selected = model.machineType == type, onClick = { model.machineType = type }, label = { Text(type) })
        }
    }
    OutlinedTextField(
        value = model.rpmText,
        onValueChange = { model.rpmText = it.filter { ch -> ch.isDigit() }.take(5) },
        label = { Text("Nameplate RPM") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("class_I", "group2_rigid", "group2_flexible").forEach { iso ->
            FilterChip(selected = model.isoClass == iso, onClick = { model.isoClass = iso }, label = { Text(iso) })
        }
    }
    Text("Record ${model.seconds}s", color = Mist)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 12, 20, 40).forEach { s ->
            FilterChip(selected = model.seconds == s, onClick = { model.seconds = s }, label = { Text("${s}s") })
        }
    }
}

@Composable
private fun Record(model: HarmonicModel) {
    Text("Recording", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val total = model.seconds.coerceAtLeast(1)
    LinearProgressIndicator(
        progress = { (model.elapsed / total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        color = Phosphor,
        trackColor = Line,
    )
    Text("${model.elapsed.toInt()} / $total s    |a| = ${"%.2f".format(model.magnitude)} m/s²", color = Phosphor)
    Spacer(Modifier.height(8.dp))
    val cue = when {
        model.elapsed < 2.5f -> "Tap the phones together now."
        model.elapsed > total - 2.5f -> "Tap them together once more."
        else -> "Hold still on the bearing housing."
    }
    Text(cue, color = Paper)
    Wave(live = model.magnitude)
}

@Composable
private fun Result(model: HarmonicModel) {
    val shown = model.result
    if (shown == null) {
        Text("No result yet.", color = Mist)
        return
    }
    val dx = shown.diagnosis
    val zoneColor = ZoneColor[dx.severityZone] ?: Phosphor
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(dx.severityZone, color = zoneColor, fontSize = 56.sp, fontWeight = FontWeight.Black)
        Column {
            Text(dx.fault.replace('_', ' '), color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Confidence ${(dx.confidence * 100).toInt()}%", color = Mist)
            Text(sourceLabel(dx.inferenceSource), color = Phosphor, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(dx.summary, color = Paper)
    if (dx.recommendedAction.isNotBlank() && !dx.summary.contains(dx.recommendedAction)) {
        Spacer(Modifier.height(8.dp))
        Text(dx.recommendedAction, color = Paper)
    }
    if (shown.platforms.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Phones: ${shown.platforms.joinToString()}", color = Mist, fontSize = 12.sp)
    }
    if (shown.gate.actions.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Physics gate: ${shown.gate.actions.joinToString { it.action }}", color = Mist, fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("New reading") { model.home() }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Phosphor, contentColor = Ink, disabledContainerColor = Line, disabledContentColor = Mist),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Wave(live: Float = 0f) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).background(Panel, RoundedCornerShape(8.dp))) {
        val path = Path()
        val amp = (size.height * 0.28f) * (0.35f + (live / 20f).coerceIn(0f, 1f))
        val mid = size.height / 2f
        path.moveTo(0f, mid)
        val steps = 64
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = mid + amp * sin(i / 4.5f + live).toFloat()
            path.lineTo(x, y)
        }
        drawPath(path, Phosphor, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

private fun sourceLabel(source: String) = when (source) {
    "on_device" -> "Answered on device"
    "cloud" -> "Answered in the cloud"
    "cloud_refinement" -> "Cloud refinement"
    else -> "Physics fallback — model unavailable"
}
