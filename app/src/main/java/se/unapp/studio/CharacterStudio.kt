package se.unapp.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val CSInk = Color(0xFF0B1020)
private val CSPanel = Color(0xFF151D32)
private val CSAccent = Color(0xFF8B7CFF)
private val CSMuted = Color(0xFFAAB4CC)
private const val CSPrefs = "unapp_character_studio"

data class UnAppCharacter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val age: String,
    val appearance: String,
    val personality: String,
    val background: String,
    val speakingStyle: String,
    val imageUri: String? = null,
    val category: String = "Kvinna"
)
private data class CharacterLine(val fromUser: Boolean, val text: String, val imagePath: String? = null)

private fun characterInstruction(character: UnAppCharacter): String =
    """
    You are roleplaying as the fictional adult character ${character.name}, age ${character.age}.
    Appearance: ${character.appearance}.
    Personality: ${character.personality}.
    Background and memories: ${character.background}.
    Speaking style: ${character.speakingStyle}.

    Stay in character and speak naturally in first person. Respond directly to the user's
    latest message and its specific details. Do not reuse a fixed greeting, canned response,
    or repeat previous answers. Make each reply meaningfully different and relevant.
    Remember details from earlier turns in this conversation. Do not narrate the user's
    thoughts or actions. Keep the conversation interactive and let the character's
    personality show through actions, opinions, feelings, and natural follow-up questions.
    This is fictional roleplay. Any adult-themed scenario involves adults aged 18 or older.
    """.trimIndent()

private fun getCharacters(context: Context): List<UnAppCharacter> {
    val raw = context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).getString("characters", "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            UnAppCharacter(o.optString("id"), o.optString("name"), o.optString("age", "25"),
                o.optString("appearance"), o.optString("personality"), o.optString("background"),
                o.optString("speakingStyle"), o.optString("imageUri").takeIf { it.isNotBlank() },
                o.optString("category", "Kvinna"))
        }
    } catch (_: Exception) { emptyList() }
}
private fun putCharacters(context: Context, list: List<UnAppCharacter>) {
    val a = JSONArray()
    list.forEach { c ->
        a.put(JSONObject().put("id", c.id).put("name", c.name).put("age", c.age)
            .put("appearance", c.appearance).put("personality", c.personality)
            .put("background", c.background) .put("speakingStyle", c.speakingStyle)
            .put("imageUri", c.imageUri ?: "").put("category", c.category))
    }
    context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).edit().putString("characters", a.toString()).apply()
}
private fun getChat(context: Context, id: String): List<CharacterLine> {
    val raw = context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).getString("chat_" + id, "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i -> val o = a.getJSONObject(i); CharacterLine(o.optBoolean("fromUser"), o.optString("text"), o.optString("imagePath").takeIf { it.isNotBlank() }) }
    } catch (_: Exception) { emptyList() }
}
private fun putChat(context: Context, id: String, lines: List<CharacterLine>) {
    val a = JSONArray()
    lines.forEach { a.put(JSONObject().put("fromUser", it.fromUser).put("text", it.text).put("imagePath", it.imagePath ?: "")) }
    context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).edit().putString("chat_" + id, a.toString()).apply()
}

@Composable
fun CharacterStudioScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var characters by remember { mutableStateOf(getCharacters(context)) }
    var editing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<UnAppCharacter?>(null) }
    var exportTarget by remember { mutableStateOf<UnAppCharacter?>(null) }
    var fileStatus by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val character = exportTarget
        if (uri != null && character != null) {
            fileStatus = runCatching {
                val json = JSONObject()
                    .put("format", "unapp-character-profile")
                    .put("version", 1)
                    .put("id", character.id)
                    .put("name", character.name)
                    .put("age", character.age)
                    .put("category", character.category)
                    .put("appearance", character.appearance)
                    .put("personality", character.personality)
                    .put("background", character.background)
                    .put("speakingStyle", character.speakingStyle)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
                    ?: error("Kunde inte skriva profilfilen.")
                "Profilen \${character.name} exporterades."
            }.getOrElse { "Export misslyckades: \${it.message}" }
        }
        exportTarget = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileStatus = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Kunde inte läsa profilfilen.")
                val json = JSONObject(raw)
                require(json.optString("format") == "unapp-character-profile") { "Filen är inte en UnApp-karaktärsprofil." }
                val imported = UnAppCharacter(
                    id = UUID.randomUUID().toString(),
                    name = json.optString("name"),
                    age = json.optString("age", "25"),
                    appearance = json.optString("appearance"),
                    personality = json.optString("personality"),
                    background = json.optString("background"),
                    speakingStyle = json.optString("speakingStyle"),
                    category = json.optString("category", "Kvinna")
                )
                require(imported.name.isNotBlank()) { "Profilen saknar namn." }
                characters = characters + imported
                putCharacters(context, characters)
                "Profilen \${imported.name} importerades."
            }.getOrElse { "Import misslyckades: \${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().background(CSInk).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (editing) { editing = false; active = null } else onClose() }) {
                Text("← Tillbaka", color = CSAccent)
            }
            Spacer(Modifier.weight(1f))
            Text("KARAKTÄRSPROFILER", color = CSAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        when {
            editing -> CharacterForm(
                existing = active,
                onCancel = { editing = false; active = null },
                onSave = { character ->
                    characters = characters.filterNot { it.id == character.id } + character
                    putCharacters(context, characters)
                    editing = false
                    active = null
                    fileStatus = "Profilen sparades."
                }
            )
            else -> {
                Text("Dina karaktärer", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Profilerna kan exporteras som filer och importeras igen i UnApp. Själva chatthistoriken sparas separat.", color = CSMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text("+ Skapa profil") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Importera") }
                }
                if (fileStatus.isNotBlank()) Text(fileStatus, color = CSMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                if (characters.isEmpty()) {
                    CSCard {
                        Text("Inga profiler ännu", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Skapa en profil eller importera en sparad JSON-fil.", color = CSMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(characters, key = { it.id }) { character ->
                            CSCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CharacterPicture(character.imageUri, Modifier.size(58.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(character.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("\${character.category} · \${character.age} år", color = CSMuted, fontSize = 11.sp)
                                        Text(character.personality, color = CSMuted, fontSize = 12.sp, maxLines = 2)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { active = character; editing = true }, modifier = Modifier.weight(1f)) { Text("Redigera") }
                                    OutlinedButton(onClick = { exportTarget = character; exportLauncher.launch("\${character.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.unapp-profile.json") }, modifier = Modifier.weight(1f)) { Text("Exportera") }
                                    OutlinedButton(onClick = { characters = characters.filterNot { it.id == character.id }; putCharacters(context, characters) }) { Text("Ta bort") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterForm(existing: UnAppCharacter?, onCancel: () -> Unit, onSave: (UnAppCharacter) -> Unit) {
    val context = LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var age by remember(existing?.id) { mutableStateOf(existing?.age ?: "25") }
    var appearance by remember(existing?.id) { mutableStateOf(existing?.appearance ?: "") }
    var personality by remember(existing?.id) { mutableStateOf(existing?.personality ?: "") }
    var history by remember(existing?.id) { mutableStateOf(existing?.background ?: "") }
    var voice by remember(existing?.id) { mutableStateOf(existing?.speakingStyle ?: "") }
    var picture by remember(existing?.id) { mutableStateOf(existing?.imageUri) }
    var category by remember(existing?.id) { mutableStateOf(existing?.category ?: "Kvinna") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            picture = uri.toString()
        }
    }
    Text(if (existing == null) "Skapa karaktär" else "Redigera karaktär", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Beskriv karaktären med egna ord.", color = CSMuted, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.fillMaxHeight(0.72f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterPicture(picture, Modifier.size(72.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Karaktärsbild", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Välj en egen bild.", color = CSMuted, fontSize = 11.sp)
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Välj bild") }
                }
            }
        }
        item { CSField("Namn", name, { name = it }, "Karaktärens namn") }
        item { CSField("Ålder", age, { age = it }, "25") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Karaktärstyp", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = category == "Kvinna", onCheckedChange = { if (it) category = "Kvinna" })
                    Text("Kvinna", color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = category == "Man", onCheckedChange = { if (it) category = "Man" })
                    Text("Man", color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = category == "Barn", onCheckedChange = { if (it) category = "Barn" })
                    Text("Barn", color = Color.White, fontSize = 12.sp)
                }
                Text("Välj en typ. Endast ett alternativ kan vara markerat.", color = CSMuted, fontSize = 10.sp)
            }
        }
        item { CSField("Utseende", appearance, { appearance = it }, "Hår, ögon, kläder, stil...") }
        item { CSField("Personlighet", personality, { personality = it }, "Självsäker, omtänksam, rolig...") }
        item { CSField("Bakgrund", history, { history = it }, "Historia, mål, minnen...") }
        item { CSField("Sätt att prata", voice, { voice = it }, "Varm, sarkastisk, poetisk...") }
        item { Text("Vuxna karaktärer måste vara minst 18 år. Barnprofiler hålls barnanpassade.", color = CSMuted, fontSize = 11.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Avbryt") }
        Button(
            onClick = { val n = age.toIntOrNull(); if (name.isNotBlank() && n != null && n > 0 && ((category == "Barn" && n < 18) || (category != "Barn" && n >= 18))) onSave(UnAppCharacter(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), age = n.toString(), appearance = appearance, personality = personality, background = history, speakingStyle = voice, imageUri = picture, category = category)) },
            enabled = name.isNotBlank() && (age.toIntOrNull()?.let { it > 0 && ((category == "Barn" && it < 18) || (category != "Barn" && it >= 18)) } == true),
            modifier = Modifier.weight(1f)
        ) { Text("Spara profil") }
    }
}

@Composable
private fun CharacterConversation(character: UnAppCharacter, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai = remember { LocalAiEngine(context) }
    var lines by remember(character.id) { mutableStateOf(getChat(context, character.id)) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Chat history is stored locally.") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var showImageTools by remember { mutableStateOf(false) }
    val imageModel = remember { OfflineImageModelDownloader.modelFile(context) }
    val imageEngine = remember { OfflineImageEngine() }
    var imageReady by remember { mutableStateOf(false) }
    var imageBusy by remember { mutableStateOf(false) }
    var imageGenerating by remember { mutableStateOf(false) }
    var imageProgress by remember { mutableIntStateOf(0) }
    var imageStatus by remember { mutableStateOf("Bildgeneratorn är separat från chattens AI.") }
    DisposableEffect(imageEngine) {
        onDispose { imageEngine.close() }
    }
    val typingTransition = rememberInfiniteTransition(label = "typing dots")
    val typingStep by typingTransition.animateFloat(
        initialValue = 1f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "typing step"
    )
    val typingDots = ".".repeat(typingStep.toInt().coerceIn(1, 3))
    val model = remember { ModelDownloader.modelFile(context) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharacterPicture(character.imageUri, Modifier.size(44.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(character.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Private character conversation", color = CSMuted, fontSize = 10.sp)
            }
            TextButton(onClick = onBack) { Text("Back", color = CSAccent) }
        }
        if (!model.exists()) {
            CSCard {
                Text("Offline AI model not installed", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Download once while online. After download, the language model runs on this phone.", color = CSMuted, fontSize = 11.sp)
                if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            ModelDownloader.download(context, onProgress = { progress = it })
                            ai.loadModel(model, characterInstruction(character), lines.takeLast(CHAT_HISTORY_MESSAGE_LIMIT).map { it.fromUser to it.text })
                            status = "Offline AI ready (" + ai.activeBackend + ")."
                        } catch (e: Exception) { status = e.message ?: "Download failed." }
                        finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Downloading " + progress + "%" else "Download offline AI") }
            }
        } else if (!ai.isLoaded()) {
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try { ai.loadModel(model, characterInstruction(character), lines.takeLast(CHAT_HISTORY_MESSAGE_LIMIT).map { it.fromUser to it.text }); status = "Offline AI ready." }
                    catch (e: Exception) { status = e.message ?: e.javaClass.simpleName }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Load offline AI model") }
        }
        Text(status, color = CSMuted, fontSize = 10.sp)
        TextButton(onClick = { showImageTools = !showImageTools }) {
            Text(if (showImageTools) "Dölj bildgenerator" else "Visa bildgenerator", color = CSAccent)
        }
        if (showImageTools) CSCard {
            Text("Offline bildgenerator", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                if (imageModel.isFile) "Bildmodellen finns på telefonen." else "Bildmodellen är ${OfflineImageModelDownloader.MODEL_SIZE_LABEL}. Ladda ner en gång med internet. Därefter fungerar bildskapandet offline.",
                color = CSMuted, fontSize = 11.sp
            )
            if (imageBusy) LinearProgressIndicator(progress = { imageProgress / 100f }, modifier = Modifier.fillMaxWidth())
            if (!imageModel.isFile) {
                Button(
                    onClick = {
                        imageBusy = true
                        imageStatus = "Laddar ner bildmodell..."
                        scope.launch {
                            try {
                                OfflineImageModelDownloader.download(context) { imageProgress = it }
                                imageStatus = "Bildmodellen är nedladdad. Laddar bildmotorn..."
                                withContext(Dispatchers.IO) { imageEngine.load(imageModel, 4) }
                                imageReady = true
                                imageStatus = "Bildgeneratorn är redo och kan användas offline."
                            } catch (e: Exception) {
                                imageStatus = e.message ?: "Kunde inte installera bildmodellen."
                            } finally { imageBusy = false }
                        }
                    },
                    enabled = !imageBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (imageBusy) "Laddar ner ${imageProgress}%" else "Ladda ner bildmodell (${OfflineImageModelDownloader.MODEL_SIZE_LABEL})") }
            } else if (!imageReady) {
                Button(
                    onClick = {
                        imageBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { imageEngine.load(imageModel, 4) }
                                imageReady = true
                                imageStatus = "Bildgeneratorn är redo och fungerar offline."
                            } catch (e: Exception) {
                                imageStatus = e.message ?: "Kunde inte starta bildmotorn."
                            } finally { imageBusy = false }
                        }
                    },
                    enabled = !imageBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Starta bildgeneratorn") }
            } else {
                Text("Bildgeneratorn är redo.", color = Color(0xFF9BE7B0), fontSize = 12.sp)
            }
            Text(imageStatus, color = CSMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.isEmpty()) item {
                CSCard {
                    Text("Start a conversation with " + character.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("The AI will use the personality and background you created.", color = CSMuted, fontSize = 12.sp)
                }
            }
            items(lines) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.widthIn(max = 290.dp).background(if (line.fromUser) Color(0xFF39305F) else CSPanel, RoundedCornerShape(14.dp)).padding(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (line.text.isNotBlank()) Text(line.text, color = Color.White, fontSize = 14.sp)
                            if (line.imagePath != null) GeneratedChatImage(line.imagePath)
                        }
                    }
                }
            }
            if (busy && model.exists()) {
                item(key = "typing_indicator") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Box(
                            Modifier.background(CSPanel, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(character.name + " skriver" + typingDots, color = CSMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (imageGenerating) {
                item(key = "image_generation_indicator") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 290.dp)
                                .background(CSPanel, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🧑‍🎨", fontSize = 22.sp)
                                Spacer(Modifier.width(9.dp))
                                Text("Skapar din bild" + typingDots, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Bilden visas här automatiskt när den är klar. Det kan ta en stund på telefonen.", color = CSMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).background(CSPanel, RoundedCornerShape(12.dp)).padding(12.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Write a message...", color = CSMuted); inner() } }
            )
            Spacer(Modifier.width(7.dp))
            OutlinedButton(
                onClick = {
                    val description = draft.trim()
                    if (description.isNotBlank() && imageReady && !imageBusy) {
                        imageBusy = true
                        imageGenerating = true
                        imageStatus = "Skapar bild lokalt på telefonen. Det kan ta en stund..."
                        draft = ""
                        scope.launch {
                            try {
                                val prompt = "Photorealistic DSLR photograph of an adult fictional woman named ${character.name}, age ${character.age}. Appearance: ${character.appearance}. Scene and pose: $description. Realistic natural skin texture, anatomically correct, detailed face, natural lighting, candid photography, 35mm lens."
                                val pixels = withContext(Dispatchers.IO) {
                                    imageEngine.generate(prompt, 512, 512, 20, -1L)
                                }
                                val bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
                                val directory = java.io.File(context.filesDir, "generated-images")
                                if (!directory.exists()) check(directory.mkdirs()) { "Kunde inte skapa bildmappen." }
                                val imageFile = java.io.File(directory, "image-${UUID.randomUUID()}.png")
                                imageFile.outputStream().use { output ->
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Kunde inte spara bilden." }
                                }
                                bitmap.recycle()
                                val completed = lines + CharacterLine(false, "Här är bilden.", imageFile.absolutePath)
                                lines = completed
                                putChat(context, character.id, completed)
                                imageStatus = "Bilden är skapad och sparad lokalt."
                            } catch (e: Exception) {
                                imageStatus = e.message ?: "Bildgenereringen misslyckades."
                            } finally { imageGenerating = false; imageBusy = false }
                        }
                    }
                },
                enabled = draft.isNotBlank() && imageReady && !imageBusy && !busy
            ) { Text("Bild") }
            Button(onClick = {
                val message = draft.trim()
                if (message.isNotEmpty() && !busy) {
                    val updated = lines + CharacterLine(true, message)
                    lines = updated
                    putChat(context, character.id, updated)
                    draft = ""
                    busy = true
                    scope.launch {
                        try {
                            if (!ai.isLoaded()) {
                                check(model.exists()) { "Download the offline AI model first." }
                                ai.loadModel(model, characterInstruction(character), updated.dropLast(1).takeLast(CHAT_HISTORY_MESSAGE_LIMIT).map { it.fromUser to it.text })
                                status = "AI loaded (" + ai.activeBackend + ")."
                            }
                            // LiteRT-LM keeps the complete turn history in its active conversation.
                            val answer = ai.ask(message)
                            val completedTranscript = updated + CharacterLine(false, answer)
                            lines = completedTranscript
                            putChat(context, character.id, completedTranscript)
                            status = "Reply generated on this phone."
                        } catch (e: Exception) { status = e.message ?: e.javaClass.simpleName }
                        finally { busy = false }
                    }
                }
            }, enabled = draft.isNotBlank() && !busy) { Text("Send") }
        }
        Text("The AI model must be downloaded before chatting offline.", color = CSMuted, fontSize = 10.sp)
    }
}

@Composable
private fun GeneratedChatImage(path: String) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "AI-generated character image",
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)
        )
    }
}

@Composable
private fun CharacterPicture(uriText: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uriText) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uriText) {
        bitmap = withContext(Dispatchers.IO) {
            try { uriText?.let { context.contentResolver.openInputStream(Uri.parse(it))?.use { stream -> BitmapFactory.decodeStream(stream) } } }
            catch (_: Exception) { null }
        }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Character picture", modifier = modifier)
    else Box(modifier.background(CSPanel, CircleShape), contentAlignment = Alignment.Center) { Text("✦", color = CSAccent, fontSize = 25.sp) }
}

@Composable
private fun CSField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().background(CSPanel, RoundedCornerShape(11.dp)).padding(12.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = CSMuted, fontSize = 12.sp); inner() } })
    }
}

@Composable
private fun CSCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CSPanel, RoundedCornerShape(15.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
