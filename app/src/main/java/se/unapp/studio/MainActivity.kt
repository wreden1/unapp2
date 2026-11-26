package se.unapp.studio

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray

private val Ink = Color(0xFF0B1020)
private val Panel = Color(0xFF151D32)
private val Accent = Color(0xFF8B7CFF)
private val Muted = Color(0xFFAAB4CC)

private const val PREFS_NAME = "unapp_projects"
private const val PROJECTS_KEY = "projects"

private fun loadProjects(context: Context): List<String> {
    val prefs = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    val saved = prefs.getString(PROJECTS_KEY, "[]") ?: "[]"

    return try {
        val array = JSONArray(saved)
        List(array.length()) { index ->
            array.getString(index)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveProjects(
    context: Context,
    projects: List<String>
) {
    val array = JSONArray()
    projects.forEach { array.put(it) }

    context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    ).edit()
        .putString(PROJECTS_KEY, array.toString())
        .apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnAppScreen() }
    }
}

@Composable
fun UnAppScreen() {
    val context = LocalContext.current

    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var showAppForge by remember { mutableStateOf(false) }
    var showCharacterStudio by remember { mutableStateOf(false) }
    var isChatGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var savedProjects by remember {
        mutableStateOf(loadProjects(context))
    }
    val localAiEngine = remember { LocalAiEngine(context) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            primary = Accent,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        if (showAppForge) {
            AppForgeScreen(
                onBack = { showAppForge = false },
                onGenerate = { request, reportStatus ->
                    var modelFile = ModelDownloader.modelFile(context)
                    if (!modelFile.isFile) {
                        reportStatus("Laddar ner AI-modellen första gången. Anslut till Wi-Fi och vänta...")
                        modelFile = ModelDownloader.download(context)
                    }
                    if (!localAiEngine.isLoaded()) {
                        reportStatus("Startar den lokala AI-motorn...")
                        localAiEngine.loadModel(modelFile, "Du är AppForge AI. Hjälp användaren att planera Android-appar. Svara alltid på svenska. Hitta inte på att en APK redan är byggd.", emptyList())
                    }
                    reportStatus("AI:n är ansluten. Skapar din appspecifikation...")
                    localAiEngine.ask(request)
                }
            )
        } else {
        Scaffold(containerColor = Ink) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "UNAPP",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                    Text(
                        "Your AI. Your Apps. Your Rules.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Vad vill du bygga?",
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Beskriv din idé. UnApp hjälper dig att skapa en riktig Android-app.",
                        color = Muted,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showAppForge = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Öppna AppForge AI – egna regler och appbygge")
                    }
                    OutlinedButton(
                        onClick = { showCharacterStudio = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Öppna chatt – Character Studio")
                    }
                }

                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Panel, RoundedCornerShape(22.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            "BESKRIV DIN APP",
                            color = Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        BasicTextField(
                            value = prompt,
                            onValueChange = {
                                prompt = it
                                result = ""
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 130.dp),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            "Exempel: Skapa en träningsapp med träningspass, statistik och egna mål...",
                                            color = Muted
                                        )
                                    }
                                    inner()
                                }
                            }
                        )

                        Spacer(Modifier.height(18.dp))

                        Button(
                            onClick = {
                                val idea = prompt.trim()
                                isChatGenerating = true
                                result = "Förbereder den lokala AI:n..."
                                scope.launch {
                                    try {
                                        var modelFile = ModelDownloader.modelFile(context)
                                        if (!modelFile.isFile) {
                                            result = "Laddar ner AI-modellen första gången. Anslut till Wi-Fi och vänta..."
                                            modelFile = ModelDownloader.download(context) { percent -> result = "Laddar ner AI-modellen: $percent%" }
                                        }
                                        if (!localAiEngine.isLoaded()) {
                                            result = "Startar den lokala AI-motorn..."
                                            localAiEngine.loadModel(modelFile, "Du är UnApp, en hjälpsam lokal AI som hjälper användaren att planera Android-appar. Svara på svenska.", emptyList())
                                        }
                                        result = localAiEngine.ask(
                                            "Du är UnApp, en hjälpsam lokal AI som hjälper användaren att planera Android-appar. Svara på svenska. Användarens appidé: $idea"
                                        )
                                        val updated = savedProjects + idea
                                        savedProjects = updated
                                        saveProjects(context, updated)
                                        prompt = ""
                                    } catch (error: Throwable) {
                                        result = "AI-fel: ${error.message ?: "Okänt fel"}"
                                    } finally {
                                        isChatGenerating = false
                                    }
                                }
                            },
                            enabled = prompt.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text(
                                if (isChatGenerating) "AI arbetar..." else "Skapa app med AI",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (result.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                result,
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }

                item {
                    Text(
                        "MINA APPAR",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Dina sparade appidéer",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }

                if (savedProjects.isEmpty()) {
                    item {
                        FeatureCard(
                            "Inga appar ännu",
                            "Skapa din första appidé ovan"
                        )
                    }
                } else {
                    items(savedProjects.size) { index ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Panel,
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(14.dp)
                        ) {
                            Text(
                                "APP ${index + 1}",
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                savedProjects[index],
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val updated = savedProjects
                                        .filterIndexed { i, _ -> i != index }

                                    savedProjects = updated
                                    saveProjects(context, updated)
                                }
                            ) {
                                Text("Ta bort")
                            }
                        }
                    }
                }

                item {
                    Text(
                        "DIN APPSTUDIO",
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {
                        FeatureCard(
                            "Förhandsvisa",
                            "Testa design",
                            Modifier.weight(1f)
                        )
                        FeatureCard(
                            "Kod & design",
                            "Full kontroll",
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FeatureCard(
                        "Bygg APK",
                        "Installera appar"
                    )
                }

                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Panel, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            "LOKAL AI",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Målet är att AI:n ska köras direkt på telefonen – även utan internet.",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Status: Appidéer kan sparas lokalt. Lokal AI och automatisk APK-byggare återstår.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
        }
        if (showCharacterStudio) {
            Dialog(
                onDismissRequest = { showCharacterStudio = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                CharacterStudioScreen(onClose = { showCharacterStudio = false })
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(5.dp))
        Text(
            subtitle,
            color = Muted,
            fontSize = 11.sp
        )
    }
}