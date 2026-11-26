package se.unapp.studio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val ForgePanel = Color(0xFF151D32)
private val ForgeAccent = Color(0xFF8B7CFF)
private val ForgeMuted = Color(0xFFAAB4CC)
private const val FORGE_PREFS = "appforge_projects_v1"
private const val FORGE_PROJECTS = "projects"

private data class ForgeProject(
    val name: String,
    val description: String,
    val blueprint: String,
    val previewInstructions: String = ""
)

private fun loadForgeProjects(context: Context): List<ForgeProject> {
    val raw = context.getSharedPreferences(FORGE_PREFS, Context.MODE_PRIVATE)
        .getString(FORGE_PROJECTS, "[]") ?: "[]"
    return try {
        val array = JSONArray(raw)
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ForgeProject(
                item.optString("name", "Min app"),
                item.optString("description", ""),
                item.optString("blueprint", ""),
                item.optString("previewInstructions", "")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveForgeProjects(context: Context, projects: List<ForgeProject>) {
    val array = JSONArray()
    projects.forEach { project ->
        array.put(JSONObject().apply {
            put("name", project.name)
            put("description", project.description)
            put("blueprint", project.blueprint)
            put("previewInstructions", project.previewInstructions)
        })
    }
    context.getSharedPreferences(FORGE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(FORGE_PROJECTS, array.toString()).apply()
}

@Composable
fun AppForgeScreen(
    onBack: () -> Unit,
    onGenerate: suspend (String, (String) -> Unit) -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var allowedRules by remember { mutableStateOf("") }
    var blockedRules by remember { mutableStateOf("") }
    var allowInternet by remember { mutableStateOf(false) }
    var allowCamera by remember { mutableStateOf(false) }
    var allowLocation by remember { mutableStateOf(false) }
    var allowMicrophone by remember { mutableStateOf(false) }
    var allowFiles by remember { mutableStateOf(true) }
    var isGenerating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var blueprint by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(loadForgeProjects(context)) }
    var previewProject by remember { mutableStateOf<ForgeProject?>(null) }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF0B1020),
        surface = ForgePanel,
        primary = ForgeAccent,
        onBackground = Color.White,
        onSurface = Color.White
    )) {
        Scaffold(containerColor = Color(0xFF0B1020)) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onBack) { Text("← Tillbaka") }
                Text("APPFORGE AI", color = ForgeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Bygg appar på ditt sätt", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Beskriv appen fritt. Du väljer projektets funktioner, behörigheter och egna regler.", color = ForgeMuted, fontSize = 14.sp)

                ForgeField("APPENS NAMN", appName, { appName = it }, "Exempel: Min träningsapp")
                ForgeField("VAD SKA APPEN GÖRA?", description, { description = it }, "Beskriv appens utseende, funktioner, flöden och målgrupp...", minLines = 5)

                ForgeField(
                    "VAD VILL DU SE I FÖRHANDSVISNINGEN? (VALFRITT)",
                    previewInstructions,
                    { previewInstructions = it },
                    "Exempel: startsidan med en stor display och runda knappar..."
                )
                Button(
                    onClick = {
                        previewProject = ForgeProject(
                            appName.ifBlank { "Min app" },
                            description,
                            blueprint,
                            previewInstructions
                        )
                    },
                    enabled = description.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Förhandsvisa appens utseende", fontWeight = FontWeight.Bold)
                }

                Text("BEHÖRIGHETER DU TILLÅTER", color = ForgeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Avstängda behörigheter ska inte begäras av den skapade appen.", color = ForgeMuted, fontSize = 12.sp)
                ForgeToggle("Internetåtkomst", allowInternet) { allowInternet = it }
                ForgeToggle("Kamera", allowCamera) { allowCamera = it }
                ForgeToggle("Platsinformation", allowLocation) { allowLocation = it }
                ForgeToggle("Mikrofon", allowMicrophone) { allowMicrophone = it }
                ForgeToggle("Filer och lokal lagring", allowFiles) { allowFiles = it }

                ForgeField("EGNA TILLÅTNA REGLER", allowedRules, { allowedRules = it }, "Skriv vad appen får göra...", minLines = 3)
                ForgeField("EGNA FÖRBJUDNA REGLER", blockedRules, { blockedRules = it }, "Skriv vad appen inte får göra...", minLines = 3)

                Text("Dina regler styr appens utformning. Androids behörighetsmodell och grundläggande säkerhetskrav kan inte stängas av.", color = ForgeMuted, fontSize = 11.sp)

                Button(
                    onClick = {
                        isGenerating = true
                        status = "AI:n arbetar lokalt..."
                        blueprint = ""
                        val request = buildString {
                            appendLine("Du är AppForge AI, en lokal Android-appbyggare.")
                            appendLine("Skapa en detaljerad, genomförbar Android-appspecifikation utifrån användarens beskrivning.")
                            appendLine("Svara på svenska. Hitta inte på att APK har byggts. Redovisa skärmar, användarflöden, datamodell, funktioner, behörigheter, filstruktur och byggsteg.")
                            appendLine("Gör inga nätverksanrop i den skapade appen om inte internet uttryckligen är tillåtet.")
                            appendLine("Appnamn: ${appName.ifBlank { "Namnlös app" }}")
                            appendLine("Appbeskrivning: $description")
                            appendLine("Tillåtna behörigheter: " + listOfNotNull(
                                if (allowInternet) "Internet" else null,
                                if (allowCamera) "Kamera" else null,
                                if (allowLocation) "Plats" else null,
                                if (allowMicrophone) "Mikrofon" else null,
                                if (allowFiles) "Filer/lokal lagring" else null
                            ).joinToString().ifBlank { "Inga särskilda behörigheter" })
                            appendLine("Egna tillåtna regler: $allowedRules")
                            appendLine("Egna förbjudna regler: $blockedRules")
                            appendLine("Om någon önskad funktion kräver en nekad behörighet, föreslå ett alternativ som följer användarens regel.")
                        }
                        scope.launch {
                            try {
                                blueprint = onGenerate(request) { message -> status = message }
                                status = if (blueprint.isBlank()) "AI:n gav inget resultat. Försök igen." else "Appspecifikationen är skapad."
                            } catch (error: Throwable) {
                                status = error.message ?: "Det gick inte att skapa appspecifikationen."
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = description.isNotBlank() && !isGenerating,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isGenerating) "Skapar..." else "Skapa appspecifikation med lokal AI", fontWeight = FontWeight.Bold)
                }

                if (status.isNotBlank()) Text(status, color = ForgeMuted, fontSize = 13.sp)
                if (blueprint.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().background(ForgePanel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text("APPENS PLAN", color = ForgeAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(blueprint, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            val saved = projects + ForgeProject(appName.ifBlank { "Min app" }, description, blueprint)
                            projects = saved
                            saveForgeProjects(context, saved)
                            status = "Appspecifikationen har sparats lokalt."
                        }, modifier = Modifier.fillMaxWidth()) { Text("Spara projekt lokalt") }
                        OutlinedButton(
                            onClick = { status = "APK-byggaren är nästa steg. Projektet är sparat, men en separat installationsfil kan ännu inte skapas." },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Bygg APK – kommer i nästa steg") }
                    }
                }

                Text("SPARADE APPROJEKT", color = ForgeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (projects.isEmpty()) {
                    Text("Dina skapade projekt visas här.", color = ForgeMuted, fontSize = 13.sp)
                } else {
                    projects.forEachIndexed { index, project ->
                        Column(Modifier.fillMaxWidth().background(ForgePanel, RoundedCornerShape(14.dp)).padding(13.dp)) {
                            Text(project.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(project.description, color = ForgeMuted, fontSize = 12.sp, maxLines = 3)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { previewProject = project }, modifier = Modifier.weight(1f)) {
                                    Text("Öppna appen")
                                }
                                TextButton(onClick = {
                                    projects = projects.filterIndexed { i, _ -> i != index }
                                    saveForgeProjects(context, projects)
                                }) { Text("Ta bort") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        previewProject?.let { project ->
            ForgePreviewDialog(project = project, onDismiss = { previewProject = null })
        }
    }
}

@Composable
private fun ForgePreviewDialog(project: ForgeProject, onDismiss: () -> Unit) {
    val combined = (project.name + " " + project.description + " " + project.previewInstructions).lowercase()
    val kind = when {
        listOf("miniräknare", "kalkylator", "calculator").any { it in combined } -> "calculator"
        listOf("chatt", "chat", "meddelande", "meddelanden").any { it in combined } -> "chat"
        listOf("kalender", "schema", "bokning").any { it in combined } -> "calendar"
        listOf("anteckning", "notes", "kom ihåg").any { it in combined } -> "notes"
        else -> "generic"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("FÖRHANDSVISNING", color = ForgeAccent, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 570.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Så här kan din app se ut",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF4F5FA),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("9:41", color = Color(0xFF20243A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("●  ●  ▰", color = Color(0xFF20243A), fontSize = 10.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(project.name.ifBlank { "Min app" }, color = Color(0xFF171B2D), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Surface(color = Color(0xFFE7E8F4), shape = RoundedCornerShape(50)) {
                                Text("•••", color = Color(0xFF343954), modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                            }
                        }
                        if (project.previewInstructions.isNotBlank()) {
                            Text(project.previewInstructions, color = Color(0xFF626982), fontSize = 12.sp)
                        }
                        when (kind) {
                            "calculator" -> PreviewCalculator()
                            "chat" -> PreviewChat()
                            "calendar" -> PreviewCalendar()
                            "notes" -> PreviewNotes()
                            else -> PreviewGeneric(project)
                        }
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFFE8EAF3), RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("Hem", "Sök", "Sparat", "Profil").forEach {
                                Text(it, color = Color(0xFF4A5068), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Text(
                    "Detta är ett visuellt appskal. Knappar och funktioner är bara exempel och gör ingenting ännu.",
                    color = ForgeMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Stäng") } },
        containerColor = Color(0xFF151D32),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
private fun PreviewCalculator() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(color = Color.White, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End) {
                Text("123 + 456", color = Color(0xFF8A8FA4), fontSize = 13.sp)
                Text("579", color = Color(0xFF171B2D), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        listOf(
            listOf("AC", "±", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("⌫", "0", ",", "=")
        ).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val accent = key in listOf("÷", "×", "−", "+", "=")
                    Surface(
                        modifier = Modifier.weight(1f).height(48.dp),
                        color = if (accent) Color(0xFF6658D9) else Color.White,
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(key, color = if (accent) Color.White else Color(0xFF252A40), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewChat() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Color(0xFFE5E7F1), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth(0.82f)) {
            Text("Hej! Välkommen till chatten.", color = Color(0xFF272C43), modifier = Modifier.padding(12.dp), fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFF6B5CE7), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth(0.82f)) {
                Text("Hej! Vad kan jag hjälpa dig med?", color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Skriv ett meddelande…                         ➤", color = Color(0xFF858BA0), modifier = Modifier.padding(14.dp), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PreviewCalendar() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NOVEMBER 2026", color = Color(0xFF292E45), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("M", "T", "O", "T", "F", "L", "S").forEach {
                Text(it, color = Color(0xFF858BA0), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp)
            }
        }
        (1..28 step 7).forEach { first ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (first..(first + 6)).forEach { day ->
                    Surface(color = if (day == 26) Color(0xFF6658D9) else Color.Transparent, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(day.toString(), color = if (day == 26) Color.White else Color(0xFF343950), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        PreviewInfoCard("Kommande", "09:00  •  Möte\n13:30  •  Påminnelse")
    }
}

@Composable
private fun PreviewNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewInfoCard("Mina anteckningar", "Idéer och tankar samlade på ett ställe.")
        PreviewInfoCard("Att göra", "□ Planera veckan\n□ Handla\n□ Följ upp projektet")
        PreviewInfoCard("Snabbanteckning", "Skriv en ny anteckning här…")
    }
}

@Composable
private fun PreviewGeneric(project: ForgeProject) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Color(0xFFE7E5FF), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("VÄLKOMMEN", color = Color(0xFF6558C8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(project.previewInstructions.ifBlank { "Din app, på ditt sätt" }, color = Color(0xFF20243A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(project.description.take(110), color = Color(0xFF626982), fontSize = 12.sp)
            }
        }
        PreviewInfoCard("Översikt", "Här visas appens viktigaste information.")
        PreviewInfoCard("Funktioner", "• Första funktionen\n• Andra funktionen\n• Dina genvägar")
        PreviewInfoCard("Senaste aktivitet", "Inget att visa ännu")
    }
}

@Composable
private fun PreviewInfoCard(title: String, body: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color(0xFF252A40), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF697087), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ForgeField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    minLines: Int = 1
) {
    Column(Modifier.fillMaxWidth().background(ForgePanel, RoundedCornerShape(14.dp)).padding(13.dp)) {
        Text(title, color = ForgeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, color = ForgeMuted) },
            minLines = minLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = ForgeAccent,
                unfocusedBorderColor = ForgeMuted.copy(alpha = 0.35f),
                cursorColor = ForgeAccent
            )
        )
    }
}

@Composable
private fun ForgeToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
