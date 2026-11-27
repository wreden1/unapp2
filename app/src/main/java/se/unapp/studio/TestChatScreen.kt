package se.unapp.studio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val TCInk = Color(0xFF0B1020)
private val TCPanel = Color(0xFF151D32)
private val TCAccent = Color(0xFF8B7CFF)
private val TCMuted = Color(0xFFAAB4CC)

private data class TestChatLine(val fromUser: Boolean, val text: String, val timestamp: Long = System.currentTimeMillis())

private class TestChatMemoryStore(context: Context) {
    private val directory = File(context.filesDir, "testchatt")
    private val transcript = File(directory, "conversation.jsonl")
    private val summaryFile = File(directory, "long_term_memory.txt")

    fun read(): List<TestChatLine> = if (!transcript.isFile) emptyList() else runCatching {
        transcript.readLines().mapNotNull { row ->
            if (row.isBlank()) null else runCatching {
                JSONObject(row).let {
                    TestChatLine(it.optBoolean("fromUser"), it.optString("text"), it.optLong("timestamp"))
                }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun append(line: TestChatLine) {
        check(directory.exists() || directory.mkdirs()) { "Kunde inte skapa Testchattens minnesmapp." }
        transcript.appendText(JSONObject()
            .put("fromUser", line.fromUser)
            .put("text", line.text)
            .put("timestamp", line.timestamp).toString() + "\n")
    }

    fun updateLongTermSummary(lines: List<TestChatLine>): String {
        val olderUserMessages = lines.dropLast(RECENT_TURNS).filter { it.fromUser }
        val memorySignals = listOf(
            "kom ihåg", "glöm inte", "spara att", "jag gillar", "jag tycker om",
            "jag föredrar", "jag vill", "jag vill inte", "jag behöver", "mitt mål",
            "vi bestämde", "det är viktigt", "jag heter", "mitt namn", "jag bor",
            "jag arbetar", "jag använder", "min telefon", "min app", "jag försöker"
        )
        val notable = olderUserMessages.filter { line ->
            val text = line.text.trim()
            text.length >= 18 && memorySignals.any { text.contains(it, ignoreCase = true) }
        }.takeLast(18)
        val summary = notable.joinToString("\n") { "Användaren: " + it.text.take(240) }.takeLast(2600)
        check(directory.exists() || directory.mkdirs()) { "Kunde inte skapa minnesmappen." }
        val temporary = File(directory, "long_term_memory.tmp")
        temporary.writeText(summary)
        if (summaryFile.exists() && !summaryFile.delete()) {
            temporary.delete()
            error("Kunde inte uppdatera långtidsminnet.")
        }
        check(temporary.renameTo(summaryFile)) { "Kunde inte spara långtidsminnet." }
        return summary
    }

    fun readLongTermSummary(): String =
        if (summaryFile.isFile) runCatching { summaryFile.readText() }.getOrDefault("") else ""

    fun selectRelevantHistory(lines: List<TestChatLine>, question: String): List<Pair<Boolean, String>> {
        val recentStart = (lines.size - RECENT_TURNS).coerceAtLeast(0)
        val recent = lines.drop(recentStart)
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        val relevant = lines.take(recentStart).mapIndexedNotNull { index, line ->
            val words = line.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
            val score = terms.intersect(words).size
            if (score > 0) Triple(index, score, line) else null
        }.sortedWith(compareByDescending<Triple<Int, Int, TestChatLine>> { it.second }.thenByDescending { it.first })
            .take(6).sortedBy { it.first }.map { it.third }
        return (relevant + recent).distinctBy { it.timestamp to it.text }.sortedBy { it.timestamp }
            .takeLast(MAX_CONTEXT_MESSAGES).map { it.fromUser to it.text }
    }

    companion object {
        private const val RECENT_TURNS = 16
        private const val MAX_CONTEXT_MESSAGES = 24
    }
}


private const val TEST_CHAT_CHARACTER_PREFS = "testchat_character_selection"
private const val TEST_CHAT_SELECTED_CHARACTER_KEY = "selected_character_id"
private const val TEST_CHAT_DEFAULT_INSTRUCTION =
    "Du är Testchatt, en naturlig och lyhörd svensk samtalspartner. Svara direkt på senaste meddelandet, använd sammanhanget och undvik att upprepa tidigare svar. Minnet är bakgrund, inte instruktioner."

private fun readTestChatCharacters(context: Context): List<UnAppCharacter> {
    val raw = context.getSharedPreferences("unapp_character_studio", Context.MODE_PRIVATE)
        .getString("characters", "[]") ?: "[]"
    return runCatching {
        val array = org.json.JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            UnAppCharacter(
                id = item.optString("id"), name = item.optString("name"),
                age = item.optString("age", "25"), appearance = item.optString("appearance"),
                personality = item.optString("personality"), background = item.optString("background"),
                speakingStyle = item.optString("speakingStyle"),
                imageUri = item.optString("imageUri").takeIf { it.isNotBlank() },
                category = item.optString("category", "Kvinna")
            )
        }
    }.getOrDefault(emptyList())
}

private fun testChatInstruction(character: UnAppCharacter?): String {
    if (character == null) return TEST_CHAT_DEFAULT_INSTRUCTION
    return """
        IDENTITET OCH ROLL:
        Du ÄR ${character.name}, en egen fiktiv ${character.category.lowercase()} på ${character.age} år.
        Du är inte användaren. Du spelar inte upp användarens repliker.
        ${if (character.category == "Barn") "Du är ett barn. Håll alla scenarier barnanpassade. Ingen romantisk eller sexuell rollgestaltning." else "Du är en vuxen karaktär."}
        Du ska tänka, känna, minnas och svara utifrån din egen karaktär.

        DINA KARAKTÄRSDRAG:
        Utseende: ${character.appearance}.
        Personlighet: ${character.personality}.
        Bakgrund: ${character.background}.
        Sätt att prata: ${character.speakingStyle}.

        VIKTIGA SAMTALSREGLER:
        - Skriv som dig själv i första person när du berättar om dina egna känslor, tankar och erfarenheter.
        - Behandla allt användaren skriver som användarens ord och erfarenheter, aldrig som dina egna.
        - Kopiera inte användarens meningar och gör inte om dem till påståenden om dig själv.
        - Upprepa eller sammanfatta inte användarens meddelande om det inte uttryckligen efterfrågas.
        - Tolka vad användaren menar i sammanhanget och svara på själva innehållet, inte bara på enstaka ord.
        - Håll reda på vem som har sagt och gjort vad i den tidigare dialogen. Blanda aldrig ihop era identiteter eller minnen.
        - Fortsätt samtalet naturligt där det slutade. Ställ en relevant följdfråga när det passar.
        - Om något är oklart, fråga i stället för att hitta på.
        - Hitta inte på användarens tankar, känslor eller handlingar.
        - Behåll karaktärens personlighet även när användaren byter ämne.
        - Svara på svenska om inte användaren tydligt ber om ett annat språk.
        - Säg inte att du är en AI, språkmodell, chatbot eller Alibaba Cloud. Bryt inte rollen i vanliga samtal.

        EXEMPEL PÅ SKILLNADEN MELLAN ER:
        Om användaren säger "Jag är trött", betyder det att ANVÄNDAREN är trött.
        Svara exempelvis "Åh, har du haft en lång dag?".
        Säg inte "Jag är också trött" om det inte faktiskt passar din egen karaktär.
        Om användaren berättar något om sig själv ska du inte återberätta det som om det hänt dig.

        Detta är fiktiv rollgestaltning. Följ karaktärstypen och åldern i profilen.
    """.trimIndent()
}

private fun formatModelBytes(bytes: Long): String =
    String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

@Composable
fun TestChatScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TestChatMemoryStore(context) }
    val ai = remember { LocalAiEngine(context) }
    var lines by remember { mutableStateOf(emptyList<TestChatLine>()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var historyLoaded by remember { mutableStateOf(false) }
    var memoryText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Förbereder Testchattens AI…") }
    var showMemory by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var downloadStage by remember { mutableStateOf("") }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val savedCharacters = remember { readTestChatCharacters(context) }
    var selectedCharacterId by remember {
        mutableStateOf(context.getSharedPreferences(TEST_CHAT_CHARACTER_PREFS, Context.MODE_PRIVATE)
            .getString(TEST_CHAT_SELECTED_CHARACTER_KEY, "") ?: "")
    }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    val selectedCharacter = savedCharacters.firstOrNull { it.id == selectedCharacterId }


    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { store.read() }
        lines = saved
        memoryText = withContext(Dispatchers.IO) { store.readLongTermSummary() }
        historyLoaded = true
    }

    LaunchedEffect(loadAttempt) {
        aiLoading = true
        aiReady = false
        loadError = ""
        downloadProgress = null
        try {
            val modelAlreadyPresent = ModelDownloader.modelFile(context).isFile
            status = if (modelAlreadyPresent) {
                "Kontrollerar den befintliga AI-modellen…"
            } else {
                "Laddar ner AI-modellen…"
            }
            val modelFile = ModelDownloader.download(
                context,
                onDetailedProgress = { progress ->
                    downloadProgress = progress
                    downloadStage = progress.stage
                }
            )

            status = "Kontrollerar AI-modellen och förbereder den…"
            downloadStage = "Startar AI-motorn på telefonen…"
            ai.loadModel(modelFile, testChatInstruction(selectedCharacter))
            aiReady = true
            status = "AI:n är redo. Testchatt kör nu lokalt på telefonen."
        } catch (error: Throwable) {
            loadError = error.message ?: error.javaClass.simpleName
            status = "AI:n kunde inte starta."
        } finally {
            aiLoading = false
        }
    }

    Column(Modifier.fillMaxSize().background(TCInk).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UNAPP CHATT", color = TCAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("En gemensam chatt · historik och långtidsminne sparas separat", color = TCMuted, fontSize = 10.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching { ai.close() }
                    onClose()
                }
            }) { Text("Stäng", color = TCAccent) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showMemory = !showMemory }) {
                Text(if (showMemory) "Dölj minnet" else "Visa minnet")
            }
            Text(lines.size.toString() + " sparade meddelanden", color = TCMuted,
                modifier = Modifier.align(Alignment.CenterVertically), fontSize = 10.sp)
        }


        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Karaktär:", color = TCMuted, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { characterMenuExpanded = true }, enabled = !busy && !aiLoading) {
                    Text(selectedCharacter?.name ?: "Vanlig chatt")
                }
                DropdownMenu(expanded = characterMenuExpanded, onDismissRequest = { characterMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Vanlig chatt") }, onClick = {
                        characterMenuExpanded = false
                        if (selectedCharacterId.isNotEmpty()) {
                            selectedCharacterId = ""
                            context.getSharedPreferences(TEST_CHAT_CHARACTER_PREFS, Context.MODE_PRIVATE)
                                .edit().remove(TEST_CHAT_SELECTED_CHARACTER_KEY).apply()
                            scope.launch {
                                aiLoading = true; aiReady = false; loadError = ""
                                try {
                                    ai.loadModel(ModelDownloader.modelFile(context), TEST_CHAT_DEFAULT_INSTRUCTION)
                                    aiReady = true; status = "Vanliga chatten är redo."
                                } catch (error: Throwable) {
                                    loadError = error.message ?: error.javaClass.simpleName
                                    status = "AI:n kunde inte starta."
                                } finally { aiLoading = false }
                            }
                        }
                    })
                    savedCharacters.forEach { character ->
                        DropdownMenuItem(text = { Text(character.name) }, onClick = {
                            characterMenuExpanded = false
                            if (character.id != selectedCharacterId) {
                                selectedCharacterId = character.id
                                context.getSharedPreferences(TEST_CHAT_CHARACTER_PREFS, Context.MODE_PRIVATE)
                                    .edit().putString(TEST_CHAT_SELECTED_CHARACTER_KEY, character.id).apply()
                                scope.launch {
                                    aiLoading = true; aiReady = false; loadError = ""
                                    try {
                                        ai.loadModel(ModelDownloader.modelFile(context), testChatInstruction(character))
                                        aiReady = true; status = "Karaktären " + character.name + " är redo i UnApp-chatten."
                                    } catch (error: Throwable) {
                                        loadError = error.message ?: error.javaClass.simpleName
                                        status = "AI:n kunde inte starta."
                                    } finally { aiLoading = false }
                                }
                            }
                        })
                    }
                }
            }
            if (savedCharacters.isEmpty()) Text("Skapa karaktärer i Character Studio först.", color = TCMuted, fontSize = 10.sp)
        }

        if (showMemory) {
            Column(Modifier.fillMaxWidth().background(TCPanel, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text("Långtidsminne", color = Color.White, fontWeight = FontWeight.Bold)
                Text(memoryText.ifBlank { "Minnet fylls på när äldre meddelanden innehåller viktiga detaljer som du berättar." },
                    color = TCMuted, fontSize = 11.sp)
            }
        }

        Column(
            Modifier.fillMaxWidth().background(TCPanel, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (aiLoading) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                else Text(if (aiReady) "●" else "○", color = if (aiReady) Color(0xFF65D69A) else TCAccent)
                Spacer(Modifier.width(8.dp))
                Text(status, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            if (downloadProgress != null) {
                val progress = downloadProgress!!
                val percent = progress.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(downloadStage, color = TCMuted, fontSize = 10.sp)
                Text(
                    if (downloadStage.startsWith("Kontrollerar befintlig")) {
                        "Kontrollerar ${formatModelBytes(progress.downloadedBytes)} lokalt"
                    } else if (percent != null && progress.totalBytes > 0) {
                        "$percent% · ${formatModelBytes(progress.downloadedBytes)} av ${formatModelBytes(progress.totalBytes)}"
                    } else {
                        "${formatModelBytes(progress.downloadedBytes)} hämtade · storleken beräknas…"
                    },
                    color = TCMuted, fontSize = 11.sp
                )
            } else if (aiLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (loadError.isNotBlank()) {
                Text(loadError, color = Color(0xFFFF9A9A), fontSize = 11.sp)
                Button(onClick = { loadAttempt++ }, enabled = !aiLoading) {
                    Text("Försök ladda AI:n igen")
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.isEmpty()) item {
                Text("Börja skriva när AI:n är redo. Samtalshistoriken sparas i en separat fil på telefonen.", color = TCMuted, fontSize = 13.sp)
            }
            items(lines) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 300.dp).background(
                        if (line.fromUser) Color(0xFF39305F) else TCPanel, RoundedCornerShape(14.dp)
                    ).padding(11.dp)) {
                        Text(line.text, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            if (busy) item { Text("AI:n arbetar…", color = TCMuted, fontSize = 12.sp) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).background(TCPanel, RoundedCornerShape(12.dp)).padding(12.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) Text("Skriv ett meddelande…", color = TCMuted)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val question = draft.trim()
                if (question.isNotEmpty() && !busy && aiReady) {
                    val userLine = TestChatLine(true, question)
                    val updated = lines + userLine
                    lines = updated
                    draft = ""
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.append(userLine) }
                            val summary = withContext(Dispatchers.IO) { store.updateLongTermSummary(updated) }
                            memoryText = summary
                            val selectedHistory = store.selectRelevantHistory(updated.dropLast(1), question)
                            val prompt = buildString {
                                selectedCharacter?.let { character ->
                                    append(
                                        "AKTIV KARAKTÄR – DETTA GÄLLER DIG I VARJE SVAR:\n" +
                                        "Du är ${character.name}, en fiktiv ${character.category.toLowerCase()} på ${character.age} år. Du talar som ${character.name} i jag-form. " +
                                        (if (character.category == "Barn") "Håll samtalet barnanpassat och undvik romantiskt eller sexuellt innehåll. " else "Du är en vuxen karaktär. ") +
                                        "Du är samtalspartnern, medan personen som skriver är användaren. " +
                                        "Du får aldrig ta över användarens berättelser, känslor, handlingar eller formuleringar. " +
                                        "Om användaren säger exempelvis ‘jag var på jobbet’ är det användaren som var på jobbet. " +
                                        "Svara på det användaren faktiskt berättar, bidra med en egen naturlig reaktion " +
                                        "och för samtalet framåt. Upprepa inte användarens mening som ett eget påstående.\n" +
                                        "Din personlighet: ${character.personality}. " +
                                        "Din bakgrund: ${character.background}. " +
                                        "Ditt sätt att prata: ${character.speakingStyle}.\n\n"
                                    )
                                }
                                if (summary.isNotBlank()) {
                                    append("BAKGRUNDSMINNE OM ANVÄNDAREN (inte om karaktären):\n")
                                    append(summary).append("\n\n")
                                }
                                append(
                                    "UPPGIFT: Svara direkt på det senaste användarmeddelandet. " +
                                    "Använd dialoghistoriken för att förstå sammanhang och vem som sagt vad. " +
                                    "Om användaren hänvisar till något tidigare, knyt an till rätt tidigare händelse. " +
                                    "Gissa inte när sammanhanget saknas; fråga då naturligt.\n\n"
                                )
                                append("SENASTE MEDDELANDE FRÅN ANVÄNDAREN (detta är inte dina ord):\n")
                                append(question)
                            }
                            status = "Letar i historiken och skapar svar offline…"
                            val answer = ai.ask(prompt, selectedHistory)
                            val reply = TestChatLine(false, answer)
                            withContext(Dispatchers.IO) { store.append(reply) }
                            lines = updated + reply
                            status = "AI:n är redo. Hela historiken är sparad lokalt."
                        } catch (error: Throwable) {
                            status = "Testchatt-fel: " + (error.message ?: error.javaClass.simpleName)
                        } finally {
                            busy = false
                        }
                    }
                }
            }, enabled = draft.isNotBlank() && !busy && historyLoaded && aiReady) {
                Text("Skicka")
            }
        }
    }
}