package com.harmonic.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlin.math.abs
import kotlin.math.sin

private val Ink = Color(0xFF0E1412)
private val Panel = Color(0xFF17211E)
private val Line = Color(0xFF2C4038)
private val Phosphor = Color(0xFFD6FF4A)
private val Mist = Color(0xFF9BB0A6)
private val Paper = Color(0xFFE7F2EB)
private val DisabledFill = Color(0xFF2A3F37)
private val DisabledText = Color(0xFFD5E6DB)
private val Danger = Color(0xFFFF5A36)
private val Cloud = Color(0xFF7DD3FC)
private val CloudFill = Color(0xFF163445)
private val PhysicsFill = Color(0xFF2C2A1A)
private val PhysicsText = Color(0xFFE8D48A)

private val ZoneColor = mapOf(
    "A" to Color(0xFF3DDC97),
    "B" to Phosphor,
    "C" to Color(0xFFFFB020),
    "D" to Danger,
)

private val IsoOptions = listOf(
    "class_I" to "Class I – small machines",
    "group2_rigid" to "Group 2 – rigid mount",
    "group2_flexible" to "Group 2 – flexible mount",
)

private val MachineOptions = listOf(
    "fan" to "Fan",
    "pump" to "Pump",
    "motor" to "Motor",
)

private val FieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Phosphor,
        unfocusedBorderColor = Line,
        focusedLabelColor = Phosphor,
        unfocusedLabelColor = Mist,
        cursorColor = Phosphor,
        focusedTextColor = Paper,
        unfocusedTextColor = Paper,
    )

class MainActivity : ComponentActivity() {
    private val model: HarmonicModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink,
                    surface = Panel,
                    primary = Phosphor,
                    onPrimary = Ink,
                    onSurface = Paper,
                ),
            ) {
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BrandHeader()
        Spacer(Modifier.height(12.dp))
        val statusVisible = model.status.isNotBlank() &&
            (model.screen == Screen.Home || model.screen == Screen.Lobby) &&
            (model.error.isNullOrBlank() || model.error != model.status)
        if (statusVisible) {
            Text(model.status, color = Mist, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (model.screen == Screen.Home || model.screen == Screen.Join || model.screen == Screen.Lobby) {
            model.error?.let {
                Text(it, color = Danger, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
            }
        }
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
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark()
        Spacer(Modifier.width(10.dp))
        Column {
            Text("HARMONIC", color = Phosphor, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 20.sp)
            Text("Multi-phone vibration analyzer", color = Mist, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Mark() {
    Canvas(
        Modifier
            .size(36.dp)
            .background(Panel, CircleShape)
            .border(1.dp, Line, CircleShape),
    ) {
        val path = Path()
        val mid = size.height / 2f
        val steps = 24
        path.moveTo(size.width * 0.15f, mid)
        for (i in 1..steps) {
            val x = size.width * (0.15f + 0.7f * i / steps)
            val y = mid + size.height * 0.22f * sin(i * 0.7f).toFloat()
            path.lineTo(x, y)
        }
        drawPath(path, Phosphor, style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Home(model: HarmonicModel) {
    SubtleWave()
    Spacer(Modifier.height(20.dp))
    Text(
        "Put two or more phones on the machine, record together, and read a plain-language fault call with an ISO severity zone.",
        color = Paper,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Screen up. Top edge toward the drive end. Tap the phones together when the trace starts and again near the end.",
        color = Mist,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Host a session", enabled = FirebaseGate.ready && !model.busy) { model.host() }
    Spacer(Modifier.height(10.dp))
    PrimaryButton("Join with a code", enabled = FirebaseGate.ready && !model.busy) { model.openJoin() }
    Spacer(Modifier.height(10.dp))
    PrimaryButton("Record this phone only") { model.recordThisPhone() }
    if (BuildConfig.DEBUG) {
        TextButton(
            onClick = { model.openLobbyPreview() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Preview the session lobby", color = Phosphor, fontWeight = FontWeight.SemiBold)
        }
    }
    if (!model.sensorOk) {
        Text("No accelerometer on this device.", color = Color(0xFFFFB020), modifier = Modifier.padding(top = 8.dp))
    }
    if (!FirebaseGate.ready) {
        Text(
            "Add google-services.json to enable sessions and hybrid Gemini. A single-phone reading still runs the DSP.",
            color = Mist,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Join(model: HarmonicModel) {
    var code by remember { mutableStateOf("") }
    Text("Join a session", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Enter the 6-character code from the host phone.", color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase().take(6) },
        label = { Text("Session code") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
        colors = FieldColors,
        singleLine = true,
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Join", enabled = code.length >= 4 && !model.busy) { model.join(code) }
    Spacer(Modifier.height(10.dp))
    SecondaryButton("Back") { model.home() }
}

@Composable
private fun Lobby(model: HarmonicModel) {
    val live = model.session
    if (model.isPreview) {
        PreviewBanner("Debug preview — not a live Firebase session.")
        Spacer(Modifier.height(16.dp))
    }
    Text("Session code", color = Mist, fontSize = 12.sp, letterSpacing = 1.sp)
    Text(live?.code ?: model.code, color = Phosphor, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
    val code = live?.code ?: model.code
    if (code.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Image(
                bitmap = remember(code) { qrImage(code) },
                contentDescription = "QR code for $code",
                modifier = Modifier.size(180.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Others type this code or scan the QR. Android and iPhone can share one session.", color = Mist, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(16.dp))
    Text("On this session", color = Paper, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    live?.members?.forEach { Text("•  ${it.name}  ·  ${it.platform}", color = Paper, fontSize = 15.sp) }
        ?: Text("Connecting…", color = Mist)
    Spacer(Modifier.height(20.dp))
    if (model.isOwner) {
        MachineForm(model)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Start recording", enabled = !model.busy) {
            model.saveMachine()
            model.startSessionRecording()
        }
    } else {
        Text("Waiting for the host to start. Keep the phone on the bearing.", color = Paper)
    }
    Spacer(Modifier.height(10.dp))
    SecondaryButton(if (model.isPreview) "Close preview" else "Leave session") { model.home() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineForm(model: HarmonicModel) {
    Text("Machine", color = Paper, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MachineOptions.forEach { (id, label) ->
            ChoiceChip(selected = model.machineType == id, label = label) { model.machineType = id }
        }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = model.rpmText,
        onValueChange = { model.rpmText = it.filter { ch -> ch.isDigit() }.take(5) },
        label = { Text("Nameplate RPM") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        colors = FieldColors,
        singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    Text("ISO class", color = Paper, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IsoOptions.forEach { (id, label) ->
            ChoiceChip(selected = model.isoClass == id, label = label) { model.isoClass = id }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Record ${model.seconds}s", color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 12, 20, 40).forEach { s ->
            ChoiceChip(selected = model.seconds == s, label = "${s}s") { model.seconds = s }
        }
    }
}

@Composable
private fun Record(model: HarmonicModel) {
    val total = model.seconds.coerceAtLeast(1)
    val tapFresh = model.lastTapAt != 0L && SystemClock.elapsedRealtime() - model.lastTapAt < 2000
    Text(if (model.analyzing) "Analyzing" else "Recording", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { (model.elapsed / total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = Phosphor,
        trackColor = Line,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${model.elapsed.toInt()} / $total s", color = Phosphor, fontWeight = FontWeight.SemiBold)
        Text("|a| = ${"%.2f".format(model.magnitude)} m/s²", color = Mist)
    }
    Spacer(Modifier.height(12.dp))
    LiveTrace(model.trace)
    Spacer(Modifier.height(12.dp))
    val cue = when {
        model.analyzing -> "Running the on-device DSP and hybrid model."
        model.elapsed < 2.5f -> "Tap the phones together now."
        model.elapsed > total - 2.5f -> "Tap them together once more."
        else -> "Hold still on the bearing housing."
    }
    Text(cue, color = Paper, fontSize = 16.sp, lineHeight = 22.sp)
    if (tapFresh || model.tapCount > 0) {
        Spacer(Modifier.height(10.dp))
        val label = if (tapFresh) "Tap detected" else "Taps detected: ${model.tapCount}"
        Box(
            Modifier
                .background(if (tapFresh) Phosphor else Panel, RoundedCornerShape(50))
                .border(1.dp, if (tapFresh) Phosphor else Line, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, color = if (tapFresh) Ink else Phosphor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    if (!model.analyzing) {
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Cancel") { model.cancelRecording() }
    }
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
    Box(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(dx.severityZone, color = zoneColor, fontSize = 64.sp, fontWeight = FontWeight.Black)
                Column {
                    Text(dx.fault.replace('_', ' '), color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Confidence ${(dx.confidence * 100).toInt()}%", color = Mist, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    SourceBadge(dx.inferenceSource)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(dx.summary, color = Paper, fontSize = 16.sp, lineHeight = 22.sp)
            if (dx.recommendedAction.isNotBlank() && !dx.summary.contains(dx.recommendedAction)) {
                Spacer(Modifier.height(10.dp))
                Text(dx.recommendedAction, color = Paper, fontSize = 15.sp, lineHeight = 21.sp)
            }
            if (shown.platforms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Phones: ${shown.platforms.joinToString()}", color = Mist, fontSize = 12.sp)
            }
            if (shown.gate.actions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Physics gate: ${shown.gate.actions.joinToString { it.action }}", color = Mist, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton("New reading") { model.home() }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, bg, fg) = when (source) {
        "on_device" -> Triple("Answered on device", Phosphor.copy(alpha = 0.16f), Phosphor)
        "cloud", "cloud_refinement" -> Triple("Answered in the cloud", CloudFill, Cloud)
        else -> Triple("Physics estimate", PhysicsFill, PhysicsText)
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Panel,
            labelColor = Paper,
            selectedContainerColor = Phosphor.copy(alpha = 0.18f),
            selectedLabelColor = Phosphor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Line,
            selectedBorderColor = Phosphor,
        ),
    )
}

@Composable
private fun PreviewBanner(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(PhysicsFill, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, color = PhysicsText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Phosphor,
            contentColor = Ink,
            disabledContainerColor = DisabledFill,
            disabledContentColor = DisabledText,
        ),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper, disabledContentColor = Mist),
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
private fun SubtleWave() {
    Canvas(Modifier.fillMaxWidth().height(64.dp).background(Panel, RoundedCornerShape(12.dp))) {
        val path = Path()
        val mid = size.height / 2f
        val amp = size.height * 0.18f
        path.moveTo(0f, mid)
        val steps = 64
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = mid + amp * sin(i / 5.5f).toFloat()
            path.lineTo(x, y)
        }
        drawPath(path, Phosphor.copy(alpha = 0.45f), style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun LiveTrace(samples: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(Panel, RoundedCornerShape(12.dp))) {
        val mid = size.height / 2f
        drawLine(Line, Offset(16f, mid), Offset(size.width - 16f, mid), strokeWidth = 1.2f)
        if (samples.size < 2) {
            return@Canvas
        }
        val peak = samples.maxOf { abs(it) }.coerceAtLeast(0.75f)
        val path = Path()
        val usable = size.width - 16f
        samples.forEachIndexed { i, v ->
            val x = 8f + usable * i / (samples.size - 1)
            val y = mid - (v / peak) * (size.height * 0.42f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Phosphor,
            style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
