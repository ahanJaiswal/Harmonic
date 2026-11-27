package com.harmonic.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Overpass (highway-sign lineage) for headings, buttons and the wordmark; Atkinson
 * Hyperlegible Next (Braille Institute, built for low-vision legibility) for reading text.
 * Both are bundled so they work offline. Licenses: assets/licenses/.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private val Display = variable(R.font.overpass_variable, 400, 600, 700, 800, 900)
private val Body = variable(R.font.atkinson_next_variable, 400, 500, 600, 700, 800)

private val HarmonicTypography = Typography().let { t ->
    fun TextStyle.body() = copy(fontFamily = Body)
    t.copy(
        displayLarge = t.displayLarge.body(), displayMedium = t.displayMedium.body(), displaySmall = t.displaySmall.body(),
        headlineLarge = t.headlineLarge.body(), headlineMedium = t.headlineMedium.body(), headlineSmall = t.headlineSmall.body(),
        titleLarge = t.titleLarge.body(), titleMedium = t.titleMedium.body(), titleSmall = t.titleSmall.body(),
        bodyLarge = t.bodyLarge.body(), bodyMedium = t.bodyMedium.body(), bodySmall = t.bodySmall.body(),
        labelLarge = t.labelLarge.body(), labelMedium = t.labelMedium.body(), labelSmall = t.labelSmall.body(),
    )
}

private val Ink = Color(0xFF0E1412)
private val Panel = Color(0xFF17211E)
private val Line = Color(0xFF2C4038)
private val Phosphor = Color(0xFFD6FF4A)
private val Mist = Color(0xFFA9BDB3)
private val Paper = Color(0xFFE7F2EB)
private val DisabledFill = Color(0xFF2A3F37)
private val DisabledText = Color(0xFFD5E6DB)
private val Danger = Color(0xFFFF6A4A)
private val Cloud = Color(0xFF7DD3FC)
private val CloudFill = Color(0xFF163445)
private val OfflineFill = Color(0xFF2C2A1A)
private val OfflineText = Color(0xFFE8D48A)

/** Traffic-light verdicts. The ISO zone letter only appears under Details. */
private data class Verdict(val title: String, val color: Color)

private val Verdicts = mapOf(
    "A" to Verdict("Running well", Color(0xFF3DDC97)),
    "B" to Verdict("OK for now", Phosphor),
    "C" to Verdict("Fix soon", Color(0xFFFFB020)),
    "D" to Verdict("Stop and fix", Danger),
)

private val IsoLabel = mapOf(
    "A" to "good",
    "B" to "acceptable",
    "C" to "unsatisfactory",
    "D" to "unacceptable",
)

/** Everyday names and one-line meanings for each fault the model can return. */
private val FaultName = mapOf(
    "healthy" to "No problem found",
    "imbalance" to "Out of balance",
    "misalignment" to "Shafts not lined up",
    "looseness" to "Something is loose",
    "bearing_wear" to "Worn bearing",
    "unknown" to "Unusual shaking",
)

private val FaultMeaning = mapOf(
    "healthy" to "The shaking is normal for this kind of machine.",
    "imbalance" to "One side of the spinning part is heavier than the other. Dirt build-up, a damaged blade or a lost weight can cause it.",
    "misalignment" to "The motor shaft and the machine it drives are not in a straight line.",
    "looseness" to "Mounting bolts, the base or a worn fit are letting parts move.",
    "bearing_wear" to "A bearing is starting to wear out.",
    "unknown" to "The shaking does not match a common problem.",
)

private val ZoneAction = mapOf(
    "A" to "Keep using it. Check again in a few months.",
    "B" to "Keep using it, and check again next month.",
    "C" to "Repair it soon, before it gets worse.",
    "D" to "Stop the machine and repair it before using it again.",
)

private val FaultTip = mapOf(
    "imbalance" to "Clean the spinning part and look for a damaged blade or a lost weight.",
    "misalignment" to "Check that the motor and the machine it drives sit in a straight line, and that the coupling isn't worn.",
    "looseness" to "Tighten the mounting bolts and check the base for cracks.",
    "bearing_wear" to "Listen for grinding and feel the bearings for heat.",
    "unknown" to "Check again with the phone in a different spot.",
)

/**
 * The offline physics check writes actions for technicians, so on that path (or when the
 * model leaves it blank) the app builds a plain one from the zone and the fault.
 */
private fun plainAction(dx: com.harmonic.dsp.Diagnosis, fault: String): String {
    val fromModel = dx.recommendedAction.trim()
    if (dx.inferenceSource != "physics_fallback" && fromModel.isNotBlank()) return fromModel
    val zone = ZoneAction[dx.severityZone] ?: ZoneAction.getValue("B")
    val tip = FaultTip[fault]
    return if (tip == null || dx.severityZone == "A") zone else "$tip $zone"
}

private enum class MachineIcon { Pump, Fan, Motor }

private data class MachineOption(val id: String, val label: String, val icon: MachineIcon)

private val Machines = listOf(
    MachineOption("pump", "Pump", MachineIcon.Pump),
    MachineOption("fan", "Fan", MachineIcon.Fan),
    MachineOption("motor", "Motor", MachineIcon.Motor),
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
                typography = HarmonicTypography,
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
        Spacer(Modifier.height(32.dp))
        if (model.screen == Screen.Home || model.screen == Screen.Join || model.screen == Screen.Lobby) {
            model.error?.let {
                Notice(it, Danger)
                Spacer(Modifier.height(16.dp))
            }
        }
        when (model.screen) {
            Screen.Home -> Home(model)
            Screen.Join -> Join(model)
            Screen.Lobby -> Lobby(model)
            Screen.Record -> Record(model)
            Screen.Result -> Result(model)
            Screen.Machine -> MachineHistory(model)
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mark()
        Spacer(Modifier.width(14.dp))
        Column {
            Text("HARMONIC", color = Phosphor, fontWeight = FontWeight.Black, fontFamily = Display, letterSpacing = 4.sp, fontSize = 26.sp)
            Text("Machine health check", color = Mist, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Mark() {
    Canvas(
        Modifier
            .size(52.dp)
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
        drawPath(path, Phosphor, style = Stroke(width = size.width * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── Home ─────────────────────────────────────────────────────────────────────

@Composable
private fun Home(model: HarmonicModel) {
    homeNote(model.status)?.let {
        Notice(it, OfflineText)
        Spacer(Modifier.height(16.dp))
    }
    val active = model.activeMachine
    if (active != null) {
        ActiveMachineCard(model, active)
    } else {
        if (model.machines.isNotEmpty()) {
            YourMachines(model)
            Spacer(Modifier.height(32.dp))
        }
        MachineSetup(model, if (model.machines.isEmpty()) "What are you checking?" else "Check something new")
    }
    Spacer(Modifier.height(20.dp))
    PlacementHint("Lay the phone flat on the machine, screen up, close to the motor or a bearing.")
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Start check", enabled = model.sensorOk) { model.recordThisPhone() }
    if (!model.sensorOk) {
        Spacer(Modifier.height(8.dp))
        Text("This phone has no motion sensor, so it can't run a check.", color = OfflineText, fontSize = 15.sp)
    }
    if (FirebaseGate.ready) {
        Spacer(Modifier.height(28.dp))
        Text("Checking with more than one phone?", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("More phones on one machine give a surer answer.", color = Mist, fontSize = 15.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Use more phones", Modifier.weight(1f), enabled = !model.busy) { model.host() }
            SecondaryButton("Join a phone", Modifier.weight(1f), enabled = !model.busy) { model.openJoin() }
        }
    }
    if (BuildConfig.DEBUG) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { model.openLobbyPreview() }, modifier = Modifier.fillMaxWidth()) {
            Text("Preview the session lobby (debug)", color = Mist, fontSize = 14.sp)
        }
    }
}

/** Sign-in chatter stays hidden; only a real connection problem is shown. */
private fun homeNote(status: String): String? = when {
    status.isBlank() || status == "Signed in" || status == "Signing in…" -> null
    BuildConfig.DEBUG -> status
    else -> "Can't connect right now. Checks still work on this phone."
}

/** Machine picture choice, size, and optional speed. Shared by Home and the host lobby. */
@Composable
private fun MachineSetup(model: HarmonicModel, title: String = "What are you checking?") {
    Text(title, color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Machines.forEach { option ->
            MachineTile(
                option = option,
                selected = model.machineType == option.id,
                modifier = Modifier.weight(1f),
            ) { model.machineType = option.id }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Pick Motor for augers, mixers, conveyors and other belt or shaft drives.", color = Mist, fontSize = 14.sp, lineHeight = 20.sp)
    Spacer(Modifier.height(22.dp))
    Text("How big is the motor?", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceTile("Under 20 hp", !model.isLargeMachine, Modifier.weight(1f)) { model.setLargeMachine(false) }
        ChoiceTile("20 hp or more", model.isLargeMachine, Modifier.weight(1f)) { model.setLargeMachine(true) }
    }
    Spacer(Modifier.height(14.dp))
    var showMore by rememberSaveable { mutableStateOf(model.rpmText.isNotBlank()) }
    if (!showMore) {
        TextButton(onClick = { showMore = true }) {
            Text("I know the motor speed (RPM)", color = Phosphor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedTextField(
            value = model.rpmText,
            onValueChange = { model.rpmText = it.filter { ch -> ch.isDigit() }.take(5) },
            label = { Text("Motor speed (RPM) — optional") },
            supportingText = { Text("On the motor's label. Leave blank if you don't know.", color = Mist) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = FieldColors,
            singleLine = true,
        )
    }
}

@Composable
private fun MachineTile(option: MachineOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val stroke = if (selected) Phosphor else Paper
    Column(
        modifier
            .heightIn(min = 112.dp)
            .background(if (selected) Phosphor.copy(alpha = 0.12f) else Panel, RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Phosphor else Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = option.label + if (selected) ", selected" else "" }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(52.dp)) { drawMachine(option.icon, stroke) }
        Spacer(Modifier.height(8.dp))
        Text(option.label, color = stroke, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    }
}

/** Line pictograms drawn in one stroke weight so they read at a glance outdoors. */
private fun DrawScope.drawMachine(icon: MachineIcon, color: Color) {
    val w = size.width
    val h = size.height
    val s = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        MachineIcon.Pump -> {
            // Volute casing, outlet pipe and base.
            drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.55f), style = s)
            drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.42f, h * 0.55f), style = s)
            val pipe = Path().apply {
                moveTo(w * 0.52f, h * 0.31f)
                lineTo(w * 0.52f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.14f)
            }
            drawPath(pipe, color, style = s)
            drawLine(color, Offset(w * 0.68f, h * 0.31f), Offset(w * 0.68f, h * 0.14f), strokeWidth = s.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.10f, h * 0.90f), Offset(w * 0.78f, h * 0.90f), strokeWidth = s.width, cap = StrokeCap.Round)
        }
        MachineIcon.Fan -> {
            val c = Offset(w / 2f, h / 2f)
            drawCircle(color, radius = w * 0.44f, center = c, style = s)
            for (i in 0 until 3) {
                rotate(degrees = i * 120f, pivot = c) {
                    val blade = Path().apply {
                        moveTo(c.x, c.y)
                        quadraticTo(c.x - w * 0.20f, c.y - h * 0.18f, c.x - w * 0.04f, c.y - h * 0.36f)
                        quadraticTo(c.x + w * 0.10f, c.y - h * 0.20f, c.x, c.y)
                    }
                    drawPath(blade, color, style = s)
                }
            }
            drawCircle(color, radius = w * 0.05f, center = c)
        }
        MachineIcon.Motor -> {
            // Finned body, shaft and feet.
            drawRoundRect(
                color,
                topLeft = Offset(w * 0.10f, h * 0.26f),
                size = Size(w * 0.58f, h * 0.44f),
                cornerRadius = CornerRadius(w * 0.08f),
                style = s,
            )
            for (i in 1..3) {
                val x = w * (0.10f + 0.58f * i / 4f)
                drawLine(color, Offset(x, h * 0.34f), Offset(x, h * 0.62f), strokeWidth = s.width * 0.8f, cap = StrokeCap.Round)
            }
            drawLine(color, Offset(w * 0.68f, h * 0.48f), Offset(w * 0.92f, h * 0.48f), strokeWidth = s.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.20f, h * 0.70f), Offset(w * 0.14f, h * 0.84f), strokeWidth = s.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.58f, h * 0.70f), Offset(w * 0.64f, h * 0.84f), strokeWidth = s.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.06f, h * 0.84f), Offset(w * 0.74f, h * 0.84f), strokeWidth = s.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ChoiceTile(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 56.dp)
            .background(if (selected) Phosphor.copy(alpha = 0.12f) else Panel, RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Phosphor else Line, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Phosphor else Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    }
}

@Composable
private fun PlacementHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(44.dp)) {
            // A phone lying flat on a machine housing.
            val w = size.width
            val h = size.height
            val s = Stroke(width = w * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(Mist, Offset(w * 0.06f, h * 0.56f), Size(w * 0.88f, h * 0.34f), CornerRadius(w * 0.08f), style = s)
            drawRoundRect(Phosphor, Offset(w * 0.22f, h * 0.38f), Size(w * 0.56f, h * 0.14f), CornerRadius(w * 0.05f))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Paper, fontSize = 16.sp, lineHeight = 22.sp)
    }
}

// ── Join ─────────────────────────────────────────────────────────────────────

@Composable
private fun Join(model: HarmonicModel) {
    var code by remember { mutableStateOf("") }
    Text("Join another phone", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(8.dp))
    Text("Type the code shown on the first phone.", color = Mist, fontSize = 16.sp)
    Spacer(Modifier.height(18.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
        label = { Text("Code") },
        textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, fontWeight = FontWeight.Bold, fontFamily = Display),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
        colors = FieldColors,
        singleLine = true,
    )
    Spacer(Modifier.height(18.dp))
    PrimaryButton(if (model.busy) "Joining…" else "Join", enabled = code.length == 6 && !model.busy) { model.join(code) }
    Spacer(Modifier.height(10.dp))
    SecondaryButton("Back") { model.home() }
}

// ── Lobby ────────────────────────────────────────────────────────────────────

@Composable
private fun Lobby(model: HarmonicModel) {
    val live = model.session
    if (model.isPreview) {
        Notice("Debug preview. Not a live session.", OfflineText)
        Spacer(Modifier.height(16.dp))
    }
    if (model.isOwner) HostLobby(model, live) else JoinerLobby(model, live)
    Spacer(Modifier.height(10.dp))
    SecondaryButton(if (model.isPreview) "Close preview" else "Leave") { model.home() }
}

@Composable
private fun HostLobby(model: HarmonicModel, live: LiveSession?) {
    val code = live?.code ?: model.code
    Text("Add the other phones", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(6.dp))
    Text("On each phone, tap Join a phone and type this code, or scan it.", color = Mist, fontSize = 16.sp, lineHeight = 22.sp)
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (code.isNotBlank()) {
            Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)) {
                Image(
                    bitmap = remember(code) { qrImage(code) },
                    contentDescription = "QR code for $code",
                    modifier = Modifier.size(120.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text("Code", color = Mist, fontSize = 14.sp)
            Text(code, color = Phosphor, fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = Display, letterSpacing = 4.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
    PhoneCount(live)
    Spacer(Modifier.height(24.dp))
    MachineSetup(model)
    Spacer(Modifier.height(20.dp))
    PlacementHint("Lay every phone flat on the machine, screen up. Spread them out: one near the motor, one near the far bearing.")
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Start check on all phones", enabled = !model.busy) { model.startSessionRecording() }
}

@Composable
private fun JoinerLobby(model: HarmonicModel, live: LiveSession?) {
    val uploaded = model.status.startsWith("Uploaded")
    Text(if (uploaded) "Done on this phone" else "You're connected", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(8.dp))
    Text(
        if (uploaded) "Waiting for the first phone to work out the result." else
            "Lay this phone flat on the machine, screen up. The check starts by itself when the first phone starts it.",
        color = Paper,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )
    Spacer(Modifier.height(16.dp))
    PhoneCount(live)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun PhoneCount(live: LiveSession?) {
    val n = live?.members?.size ?: 0
    Text(
        when (n) {
            0 -> "Connecting…"
            1 -> "1 phone connected"
            else -> "$n phones connected"
        },
        color = Paper,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// ── Record ───────────────────────────────────────────────────────────────────

@Composable
private fun Record(model: HarmonicModel) {
    if (model.analyzing) {
        Text("Working out the result…", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Phosphor, trackColor = Line)
        Spacer(Modifier.height(12.dp))
        Text("You can pick the phone up now.", color = Mist, fontSize = 16.sp)
        return
    }
    val total = model.seconds.coerceAtLeast(1)
    val left = ceil(total - model.elapsed).toInt().coerceAtLeast(0)
    val tapFresh = model.lastTapAt != 0L && SystemClock.elapsedRealtime() - model.lastTapAt < 1500
    val (step, help) = when {
        !model.syncTaps -> "Keep the phone still" to "Don't touch it. It's feeling the machine shake."
        model.recordStep == 0 -> "Tap the phones together" to "One firm tap, then put them back on the machine."
        model.recordStep == 2 -> "Tap them together again" to "One more firm tap."
        else -> "Keep the phones still" to "Don't touch them. They're feeling the machine shake."
    }
    if (model.syncTaps) {
        Text("Step ${model.recordStep + 1} of 3", color = Mist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
    }
    Text(step, color = Phosphor, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = Display, lineHeight = 36.sp)
    Spacer(Modifier.height(8.dp))
    Text(help, color = Paper, fontSize = 17.sp, lineHeight = 24.sp)
    Spacer(Modifier.height(20.dp))
    Text(if (left == 1) "1 second left" else "$left seconds left", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    val fraction = (model.elapsed / total).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Line, cornerRadius = r)
        if (fraction > 0f) drawRoundRect(Phosphor, size = Size(size.width * fraction, size.height), cornerRadius = r)
    }
    Spacer(Modifier.height(16.dp))
    LiveTrace(model.trace)
    if (model.syncTaps) {
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                tapFresh -> "Tap felt"
                model.tapCount > 0 -> "Taps felt: ${model.tapCount}"
                else -> "No tap felt yet"
            },
            color = if (tapFresh) Phosphor else Mist,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold, fontFamily = Display,
        )
    }
    Spacer(Modifier.height(24.dp))
    SecondaryButton("Cancel") { model.cancelRecording() }
}

// ── Result ───────────────────────────────────────────────────────────────────

@Composable
private fun Result(model: HarmonicModel) {
    val shown = model.result
    if (shown == null) {
        Text("No result yet.", color = Mist)
        return
    }
    val dx = shown.diagnosis
    val verdict = Verdicts[dx.severityZone] ?: Verdicts.getValue("B")
    val fault = dx.fault.takeIf { it in FaultName } ?: "unknown"
    Column(
        Modifier
            .fillMaxWidth()
            .background(verdict.color, RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Text(verdict.title, color = Ink, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = Display, lineHeight = 40.sp)
        Spacer(Modifier.height(4.dp))
        Text(FaultName.getValue(fault), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    }
    shown.comparison?.let { c ->
        Spacer(Modifier.height(14.dp))
        Text(
            c.text,
            color = if (c.worse) Verdicts.getValue("C").color else Verdicts.getValue("A").color,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Display,
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(FaultMeaning.getValue(fault), color = Paper, fontSize = 17.sp, lineHeight = 24.sp)
    Spacer(Modifier.height(20.dp))
    Text("What to do", color = Mist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        plainAction(dx, fault),
        color = Paper,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sureness(dx.confidence), color = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        SourceBadge(dx.inferenceSource)
    }
    Spacer(Modifier.height(24.dp))
    val saved = shown.machineName
    if (saved != null) {
        Text("Saved to the history for $saved.", color = Mist, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
    } else {
        SaveToHistory(model)
        Spacer(Modifier.height(20.dp))
    }
    PrimaryButton("Check again") { model.home() }
    Spacer(Modifier.height(8.dp))
    var details by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) {
        Text(if (details) "Hide details" else "Show details", color = Phosphor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    if (details) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailLine("ISO 10816 zone", "${dx.severityZone} (${IsoLabel[dx.severityZone] ?: "?"})")
            DetailLine("Confidence", "${(dx.confidence * 100).toInt()}%")
            DetailLine("Fault code", dx.faultDetail.replace('_', ' '))
            if (shown.platforms.isNotEmpty()) DetailLine("Phones", shown.platforms.joinToString())
            if (dx.summary.isNotBlank()) Text(dx.summary, color = Paper, fontSize = 15.sp, lineHeight = 21.sp)
            dx.evidence.forEach { Text("• $it", color = Mist, fontSize = 14.sp, lineHeight = 20.sp) }
            if (shown.gate.actions.isNotEmpty()) {
                Text("Physics check: ${shown.gate.actions.joinToString { it.action }}", color = Mist, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SaveToHistory(model: HarmonicModel) {
    var name by rememberSaveable { mutableStateOf(model.suggestedMachineName()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text("Keep a history of this machine", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        Spacer(Modifier.height(4.dp))
        Text("Next time, you'll see if it's shaking more or less than today.", color = Mist, fontSize = 15.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Machine name, like North well pump") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
            colors = FieldColors,
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Save this machine", enabled = name.isNotBlank()) { model.saveResultAsMachine(name) }
    }
}

// ── Machines and history ─────────────────────────────────────────────────────

private fun verdictFor(zone: String) = Verdicts[zone] ?: Verdicts.getValue("B")

private fun machineLabel(m: Machine): String {
    val type = Machines.firstOrNull { it.id == m.type }?.label ?: m.type.replaceFirstChar { it.uppercase() }
    val size = if (m.isoClass == "class_I") "under 20 hp" else "20 hp or more"
    return "$type · $size"
}

@Composable
private fun YourMachines(model: HarmonicModel) {
    Text("Your machines", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        model.machines.take(6).forEach { m -> MachineRow(m) { model.openMachine(m.id) } }
    }
}

@Composable
private fun MachineRow(m: Machine, onClick: () -> Unit) {
    val last = m.last
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(last?.let { verdictFor(it.zone).color } ?: Line, CircleShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
            Text(
                last?.let { "${verdictFor(it.zone).title} · ${shortDate(it.at)}" } ?: "Not checked yet",
                color = Mist,
                fontSize = 15.sp,
            )
        }
        Text("Open", color = Phosphor, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    }
}

@Composable
private fun ActiveMachineCard(model: HarmonicModel, m: Machine) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp))
            .border(2.dp, Phosphor, RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) {
        Text("Checking", color = Mist, fontSize = 15.sp)
        Text(m.name, color = Phosphor, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = Display, lineHeight = 32.sp)
        Text(machineLabel(m), color = Paper, fontSize = 15.sp)
        m.last?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last check ${shortDate(it.at)}: ${verdictFor(it.zone).title}", color = Paper, fontSize = 16.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { model.openMachine(m.id) }) {
            Text("See history", color = Phosphor, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        }
        TextButton(onClick = { model.clearActiveMachine() }) {
            Text("Check a different machine", color = Phosphor, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        }
    }
}

@Composable
private fun MachineHistory(model: HarmonicModel) {
    val m = model.viewedMachine
    if (m == null) {
        Text("That machine is no longer saved.", color = Mist, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Back") { model.home() }
        return
    }
    Text(m.name, color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = Display, lineHeight = 34.sp)
    Text(machineLabel(m), color = Mist, fontSize = 16.sp)
    Spacer(Modifier.height(20.dp))
    PrimaryButton("Check this machine", enabled = model.sensorOk) { model.checkMachine(m.id) }
    if (m.readings.size >= 2) {
        Spacer(Modifier.height(28.dp))
        Text("Shaking over time", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        Spacer(Modifier.height(10.dp))
        TrendBars(m.readings.takeLast(12))
    }
    Spacer(Modifier.height(28.dp))
    Text("Past checks", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
    Spacer(Modifier.height(6.dp))
    m.readings.asReversed().forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(verdictFor(r.zone).color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(verdictFor(r.zone).title, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
                Text(FaultName[r.fault] ?: FaultName.getValue("unknown"), color = Mist, fontSize = 15.sp)
            }
            Text(shortDate(r.at), color = Mist, fontSize = 15.sp)
        }
    }
    Spacer(Modifier.height(20.dp))
    SecondaryButton("Back") { model.home() }
    Spacer(Modifier.height(12.dp))
    var confirm by remember(m.id) { mutableStateOf(false) }
    if (!confirm) {
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove this machine", color = Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        val n = m.readings.size
        Notice("Remove ${m.name} and its $n ${if (n == 1) "check" else "checks"} from this phone? This can't be undone.", Danger)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Keep it", Modifier.weight(1f)) { confirm = false }
            SecondaryButton("Remove", Modifier.weight(1f)) { model.deleteMachine(m.id) }
        }
    }
}

/** One bar per check, oldest on the left, coloured by verdict and scaled by measured shake. */
@Composable
private fun TrendBars(readings: List<Reading>) {
    val zoneRank = mapOf("A" to 1.0, "B" to 2.0, "C" to 3.0, "D" to 4.0)
    val values = readings.map { it.shakeMmS ?: zoneRank[it.zone] ?: 1.0 }
    val peak = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "Shaking over the last ${readings.size} checks" },
    ) {
        val slot = size.width / readings.size
        val barW = (slot * 0.6f).coerceAtMost(48.dp.toPx())
        readings.forEachIndexed { i, r ->
            val h = (size.height * (values[i] / peak).toFloat()).coerceAtLeast(size.height * 0.08f)
            drawRoundRect(
                verdictFor(r.zone).color,
                topLeft = Offset(slot * i + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.2f),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Oldest", color = Mist, fontSize = 13.sp)
        Text("Latest", color = Mist, fontSize = 13.sp)
    }
}

private fun sureness(confidence: Double): String = when {
    confidence >= 0.75 -> "Sure"
    confidence >= 0.5 -> "Fairly sure"
    else -> "Not sure. Check again."
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Mist, fontSize = 14.sp)
        Text(value, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, bg, fg) = when (source) {
        "on_device" -> Triple("AI on this phone", Phosphor.copy(alpha = 0.16f), Phosphor)
        "cloud", "cloud_refinement" -> Triple("AI in the cloud", CloudFill, Cloud)
        else -> Triple("Offline check", OfflineFill, OfflineText)
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun Notice(text: String, color: Color) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Phosphor,
            contentColor = Ink,
            disabledContainerColor = DisabledFill,
            disabledContentColor = DisabledText,
        ),
    ) { Text(label, fontWeight = FontWeight.Bold, fontFamily = Display, fontSize = 18.sp) }
}

@Composable
private fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper, disabledContentColor = Mist),
    ) { Text(label, fontWeight = FontWeight.Bold, fontFamily = Display, fontSize = 16.sp) }
}

@Composable
private fun LiveTrace(samples: FloatArray) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Live shaking trace" },
    ) {
        val mid = size.height / 2f
        drawLine(Line, Offset(16f, mid), Offset(size.width - 16f, mid), strokeWidth = 1.2f)
        if (samples.size < 2) return@Canvas
        val peak = samples.maxOf { abs(it) }.coerceAtLeast(0.75f)
        val path = Path()
        val usable = size.width - 16f
        samples.forEachIndexed { i, v ->
            val x = 8f + usable * i / (samples.size - 1)
            val y = mid - (v / peak) * (size.height * 0.42f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Phosphor, style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
