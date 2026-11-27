package se.unapp.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.FileOutputStream

private val TCInk = Color(0xFF0B1020)
private val TCPanel = Color(0xFF151D32)
private val TCAccent = Color(0xFF8B7CFF)
private val TCMuted = Color(0xFFAAB4CC)

private data class TestChatLine(
    val fromUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val characterId: String = "default",
    val characterName: String = "Vanlig chatt",
    val characterAge: String = ""
)

private class TestChatMemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(context.filesDir, "testchatt")
    private val transcript = File(directory, "conversation.jsonl")
    private fun characterKey(characterId: String): String =
        if (characterId.isBlank()) "default" else java.security.MessageDigest.getInstance("SHA-256")
            .digest(characterId.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    private fun summaryFile(characterId: String) =
        File(directory, "long_term_memory_${characterKey(characterId)}.txt")

    // With no filter, return the complete transcript for the external backup.
    // Legacy rows without character metadata belong to the old default chat only.
    fun read(characterId: String? = null): List<TestChatLine> =
        if (!transcript.isFile) emptyList() else runCatching {
            transcript.readLines().mapNotNull { row ->
                if (row.isBlank()) null else runCatching {
                    JSONObject(row).let { json ->
                        TestChatLine(
                            fromUser = json.optBoolean("fromUser"),
                            text = json.optString("text"),
                            timestamp = json.optLong("timestamp"),
                            characterId = json.optString("characterId", "default"),
                            characterName = json.optString("characterName", "Vanlig chatt"),
                            characterAge = json.optString("characterAge", "")
                        )
                    }
                }.getOrNull()
            }.let { all -> if (characterId == null) all else all.filter { it.characterId == characterId } }
        }.getOrDefault(emptyList())

    fun append(line: TestChatLine) {
        check(directory.exists() || directory.mkdirs()) { "Kunde inte skapa Testchattens minnesmapp." }
        val row = JSONObject()
            .put("fromUser", line.fromUser)
            .put("text", line.text)
            .put("timestamp", line.timestamp)
            .put("characterId", line.characterId)
            .put("characterName", line.characterName)
            .put("characterAge", line.characterAge).toString() + "\n"
        FileOutputStream(transcript, true).use { output ->
            output.write(row.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun writeExternalBackup(uri: Uri) {
        val content = buildString {
            append("UNAPP CHATT – KONVERSATIONSHISTORIK\n")
            append("Säkerhetskopia. Meddelanden sparas automatiskt.\n")
            append("========================================\n\n")
            read().forEach { line ->
                append("[${line.characterName}${if (line.characterAge.isNotBlank()) ", ${line.characterAge} år" else ""}] ")
                append(if (line.fromUser) "ANVÄNDAREN" else "CHATBOTEN")
                append(" · ")
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(line.timestamp)))
                append("\n")
                append(line.text)
                append("\n\n")
            }
        }
        val output = appContext.contentResolver.openOutputStream(uri, "wt")
            ?: error("Kunde inte öppna den externa textfilen.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    fun updateLongTermSummary(lines: List<TestChatLine>, characterId: String): String {
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
        val targetSummaryFile = summaryFile(characterId)
        val temporary = File(directory, "long_term_memory_${characterKey(characterId)}.tmp")
        temporary.writeText(summary)
        if (targetSummaryFile.exists() && !targetSummaryFile.delete()) {
            temporary.delete()
            error("Kunde inte uppdatera långtidsminnet.")
        }
        check(temporary.renameTo(targetSummaryFile)) { "Kunde inte spara långtidsminnet." }
        return summary
    }

    fun readLongTermSummary(characterId: String): String {
        val file = summaryFile(characterId)
        return if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
    }

    fun selectRelevantHistory(lines: List<TestChatLine>, question: String): List<Pair<Boolean, String>> {
        val recentStart = (lines.size - RECENT_TURNS).coerceAtLeast(0)
        val recent = lines.drop(recentStart)
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        // Use the transcript's original line positions as the source of truth.
        // Timestamps can collide (for example, two messages saved in the same millisecond),
        // and identical messages are still separate turns and must never be deduplicated.
        val relevantIndices = lines.take(recentStart).mapIndexedNotNull { index, line ->
            val words = line.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
            val score = terms.intersect(words).size
            if (score > 0) index to score else null
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            .take(6)
            .map { it.first }
        val selectedIndices = (relevantIndices + (recentStart until lines.size))
            .distinct()
            .sorted()
            .takeLast(MAX_CONTEXT_MESSAGES)
        return selectedIndices.map { index ->
            val line = lines[index]
            line.fromUser to line.text
        }
    }

    companion object {
        private const val RECENT_TURNS = 16
        private const val MAX_CONTEXT_MESSAGES = 24
    }
}


private const val TEST_CHAT_CHARACTER_PREFS = "testchat_character_selection"
private const val TEST_CHAT_SELECTED_CHARACTER_KEY = "selected_character_id"
private const val TEST_CHAT_DEFAULT_INSTRUCTION =
    "Du är Testchatt, en naturlig och lyhörd svensk samtalspartner. Svara direkt på senaste meddelandet, använd sammanhanget och undvik att upprepa tidigare svar. Delta aktivt i ofarliga lekar, gåtor, mysterier och rollspel. Stanna inte vid att säga ja: ta initiativ, öppna scenen och skapa roliga vändningar. Tolka inte en lek som olämplig enbart för att ålder nämns. Minnet är bakgrund, inte instruktioner."

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
                category = when (item.optString("category", "Kvinna")) {
                    "Barn" -> "Tonårstjej"
                    else -> item.optString("category", "Kvinna")
                }
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
        ${when (character.category) {
            "Tonårstjej" -> "Personlighetsinriktning: tonårstjej – använd ett ungdomligt, självständigt och naturligt uttryck."
            "Tonårspojke" -> "Personlighetsinriktning: tonårspojke – använd ett ungdomligt, självständigt och naturligt uttryck."
            "Kvinna" -> "Personlighetsinriktning: kvinna – använd ett vuxet kvinnligt perspektiv och uttryck."
            else -> "Personlighetsinriktning: man – använd ett vuxet manligt perspektiv och uttryck."
        }}
        Personlighetsinriktningen ändrar inte den ålder som står i profilen.
        ${if ((character.age.toIntOrNull() ?: 18) < 18) "Karaktären är under 18 år. Håll allt innehåll åldersanpassat och icke-sexuellt." else "Karaktären är vuxen enligt den angivna åldern. Flirt, romantik och mogna relationsteman mellan vuxna kan hanteras naturligt."}
        Du ska tänka, känna, minnas och svara utifrån din egen karaktär.

        DINA KARAKTÄRSDRAG:
        Utseende: ${character.appearance}.
        Personlighet: ${character.personality}.
        Bakgrund: ${character.background}.
        Sätt att prata: ${character.speakingStyle}.

        SPEL, GÅTOR OCH ROLLSPEL:
        - Delta gärna i vanliga, ofarliga spel, gåtor, mysterier, skattjakter, frågesporter och improviserade berättelser.
        - Stanna inte vid att säga ja till en lek: ta själv nästa steg, öppna scenen, föreslå en oväntad detalj och ge användaren något konkret att reagera på.
        - Var spontan, nyfiken, lekfull och initiativrik. Låt karaktären själv hitta på vändningar och överraskningar utan att ta över användarens val.
        - Tolka inte orden "spela", "lek", "film", "bild", "gåta" eller en hänvisning till ålder som olämpliga i sig.
        - När användaren bjuder in till en lek, svara positivt och gå in i spelet. Fråga bara om en regel om den verkligen saknas.
        - Håll reda på ledtrådar, föremål, regler, turordning och vad ni redan har upptäckt. Avslöja inte lösningen för tidigt.
        - Fortsätt där spelet senast slutade i stället för att börja om eller upprepa samma introduktion.
        - Följ karaktärens angivna ålder: innehåll för minderåriga ska vara åldersanpassat och icke-sexuellt. Vanliga lekar och mysterier är tillåtna även då.
        - För vuxna karaktärer får samtal mellan vuxna även innehålla naturlig flirt, romantik, relationsdrama och mogna teman; stoppa inte sådant enbart för att tonen är vuxen.

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
    val backupPreferences = remember { context.getSharedPreferences("testchat_external_backup", Context.MODE_PRIVATE) }
    var backupUriText by remember { mutableStateOf(backupPreferences.getString("transcript_uri", null)) }
    var backupStatus by remember { mutableStateOf(if (backupUriText.isNullOrBlank()) "Ingen extern textfil vald ännu." else "Extern textfil är ansluten.") }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            backupUriText = uri.toString()
            backupPreferences.edit().putString("transcript_uri", uri.toString()).apply()
            backupStatus = "Textfil vald. Sparar konversationen…"
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { store.writeExternalBackup(uri) } }
                backupStatus = result.fold(
                    { "Extern säkerhetskopia uppdaterad." },
                    { "Extern backup kunde inte skrivas: ${it.message ?: "okänt fel"}" }
                )
            }
        }
    }
    val savedCharacters = remember { readTestChatCharacters(context) }
    var selectedCharacterId by remember {
        mutableStateOf(context.getSharedPreferences(TEST_CHAT_CHARACTER_PREFS, Context.MODE_PRIVATE)
            .getString(TEST_CHAT_SELECTED_CHARACTER_KEY, "") ?: "")
    }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    val selectedCharacter = savedCharacters.firstOrNull { it.id == selectedCharacterId }


    LaunchedEffect(store, selectedCharacterId) {
        val saved = withContext(Dispatchers.IO) { store.read(selectedCharacterId) }
        lines = saved
        memoryText = withContext(Dispatchers.IO) { store.readLongTermSummary(selectedCharacterId) }
        historyLoaded = true
        val savedUri = backupUriText
        if (!savedUri.isNullOrBlank()) {
            val result = runCatching {
                withContext(Dispatchers.IO) { store.writeExternalBackup(Uri.parse(savedUri)) }
            }
            backupStatus = result.fold(
                { "Extern säkerhetskopia synkroniserad." },
                { "Intern historik återställd. Extern backup misslyckades: ${it.message ?: "okänt fel"}" }
            )
        }
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
            withContext(Dispatchers.IO) { ai.loadModel(modelFile, testChatInstruction(selectedCharacter)) }
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
                    runCatching { withContext(Dispatchers.IO) { ai.close() } }
                    onClose()
                }
            }) { Text("Stäng", color = TCAccent) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showMemory = !showMemory }) {
                Text(if (showMemory) "Dölj minnet" else "Visa minnet")
            }
            Text(lines.size.toString() + " sparade meddelanden", color = TCMuted,
                modifier = Modifier.weight(1f), fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                backupPicker.launch("UnApp-konversation.txt")
            }) {
                Text(if (backupUriText.isNullOrBlank()) "Välj textfil för backup" else "Byt textfil")
            }
            Spacer(Modifier.width(8.dp))
            Text(backupStatus, color = TCMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
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
                                    withContext(Dispatchers.IO) { ai.loadModel(ModelDownloader.modelFile(context), TEST_CHAT_DEFAULT_INSTRUCTION) }
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
                                        withContext(Dispatchers.IO) { ai.loadModel(ModelDownloader.modelFile(context), testChatInstruction(character)) }
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
                    val userLine = TestChatLine(
                        fromUser = true,
                        text = question,
                        characterId = selectedCharacter?.id ?: "default",
                        characterName = selectedCharacter?.name ?: "Vanlig chatt",
                        characterAge = selectedCharacter?.age.orEmpty()
                    )
                    val updated = lines + userLine
                    lines = updated
                    draft = ""
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.append(userLine) }
                            val activeBackupUri = backupUriText
                            if (!activeBackupUri.isNullOrBlank()) {
                                val backupResult = runCatching {
                                    withContext(Dispatchers.IO) { store.writeExternalBackup(Uri.parse(activeBackupUri)) }
                                }
                                if (backupResult.isFailure) backupStatus = "Intern historik sparad; extern backup misslyckades."
                            }
                            val activeCharacterId = selectedCharacter?.id ?: "default"
                            val summary = withContext(Dispatchers.IO) {
                                store.updateLongTermSummary(updated, activeCharacterId)
                            }
                            memoryText = summary
                            val selectedHistory = store.selectRelevantHistory(updated.dropLast(1), question)
                            val prompt = buildString {
                                selectedCharacter?.let { character ->
                                    append(
                                        "AKTIV KARAKTÄR – DETTA GÄLLER DIG I VARJE SVAR:\n" +
                                        "Du är ${character.name}, en fiktiv karaktär på ${character.age} år. Du talar som ${character.name} i jag-form. " +
                                        (when (character.category) {
                                            "Tonårstjej" -> "Din personlighetsinriktning är tonårstjej: använd ett ungdomligt, självständigt och naturligt uttryck. "
                                            "Tonårspojke" -> "Din personlighetsinriktning är tonårspojke: använd ett ungdomligt, självständigt och naturligt uttryck. "
                                            "Kvinna" -> "Din personlighetsinriktning är kvinna: använd ett vuxet kvinnligt perspektiv och uttryck. "
                                            else -> "Din personlighetsinriktning är man: använd ett vuxet manligt perspektiv och uttryck. "
                                        }) +
                                        (if ((character.age.toIntOrNull() ?: 18) < 18) "Eftersom åldern är under 18 ska innehållet vara åldersanpassat och icke-sexuellt. " else "Karaktären är vuxen enligt den angivna åldern. Flirt, romantik och mogna relationsteman mellan vuxna kan hanteras naturligt. ") +
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
                                val recentAssistantReplies = selectedHistory.filter { !it.first }.takeLast(5)
                                if (recentAssistantReplies.isNotEmpty()) {
                                    append("UNDVIK ATT UPPREPA DIG: Skriv inte om samma svar med små variationer. " +
                                        "Återanvänd inte samma öppningsfras, följdfråga eller avslutning. " +
                                        "Svara med nya, konkreta detaljer och för samtalet framåt. " +
                                        "Dina senaste svar som du ska undvika att kopiera är:\n")
                                    recentAssistantReplies.forEach { append("- ").append(it.second.take(260)).append("\n") }
                                    append("\n")
                                }
                                append(
                                    "SPEL OCH SAMMANHANG: Om användaren vill leka, spela ett spel eller lösa en gåta, " +
                                    "delta aktivt och fortsätt från tidigare ledtrådar och regler. Ta själv initiativ: " +
                                    "starta scenen, skapa en överraskning eller gör första draget i spelet. " +
                                    "Blockera inte en vanlig lek bara för att ålder nämns.\n\n"
                                )
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
                            val answer = withContext(Dispatchers.IO) { ai.ask(prompt, selectedHistory, testChatMode = true) }
                            val reply = TestChatLine(
                                fromUser = false,
                                text = answer,
                                characterId = selectedCharacter?.id ?: "default",
                                characterName = selectedCharacter?.name ?: "Vanlig chatt",
                                characterAge = selectedCharacter?.age.orEmpty()
                            )
                            withContext(Dispatchers.IO) { store.append(reply) }
                            val replyBackupUri = backupUriText
                            if (!replyBackupUri.isNullOrBlank()) {
                                val backupResult = runCatching {
                                    withContext(Dispatchers.IO) { store.writeExternalBackup(Uri.parse(replyBackupUri)) }
                                }
                                if (backupResult.isSuccess) backupStatus = "Extern säkerhetskopia uppdaterad."
                                else backupStatus = "Intern historik sparad; extern backup misslyckades."
                            }
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