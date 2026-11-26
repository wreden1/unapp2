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
    val blueprint: String
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
                item.optString("blueprint", "")
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
                            TextButton(onClick = {
                                projects = projects.filterIndexed { i, _ -> i != index }
                                saveForgeProjects(context, projects)
                            }) { Text("Ta bort projekt") }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
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
