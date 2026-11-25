package se.unapp.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    val imageUri: String? = null
)
private data class CharacterLine(val fromUser: Boolean, val text: String)

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
                o.optString("speakingStyle"), o.optString("imageUri").takeIf { it.isNotBlank() })
        }
    } catch (_: Exception) { emptyList() }
}
private fun putCharacters(context: Context, list: List<UnAppCharacter>) {
    val a = JSONArray()
    list.forEach { c ->
        a.put(JSONObject().put("id", c.id).put("name", c.name).put("age", c.age)
            .put("appearance", c.appearance).put("personality", c.personality)
            .put("background", c.background).put("speakingStyle", c.speakingStyle)
            .put("imageUri", c.imageUri ?: ""))
    }
    context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).edit().putString("characters", a.toString()).apply()
}
private fun getChat(context: Context, id: String): List<CharacterLine> {
    val raw = context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).getString("chat_" + id, "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i -> val o = a.getJSONObject(i); CharacterLine(o.optBoolean("fromUser"), o.optString("text")) }
    } catch (_: Exception) { emptyList() }
}
private fun putChat(context: Context, id: String, lines: List<CharacterLine>) {
    val a = JSONArray()
    lines.forEach { a.put(JSONObject().put("fromUser", it.fromUser).put("text", it.text)) }
    context.getSharedPreferences(CSPrefs, Context.MODE_PRIVATE).edit().putString("chat_" + id, a.toString()).apply()
}

@Composable
fun CharacterStudioScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var characters by remember { mutableStateOf(getCharacters(context)) }
    var editing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<UnAppCharacter?>(null) }
    Column(Modifier.fillMaxSize().background(CSInk).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (active != null) active = null else if (editing) editing = false else onClose() }) {
                Text("← Back", color = CSAccent)
            }
            Spacer(Modifier.weight(1f))
            Text("CHARACTER STUDIO", color = CSAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        when {
            active != null -> CharacterConversation(active!!, onBack = { active = null })
            editing -> CharacterForm(
                existing = active,
                onCancel = { editing = false; active = null },
                onSave = { c -> characters = characters.filterNot { it.id == c.id } + c; putCharacters(context, characters); editing = false; active = null }
            )
            else -> {
                Text("Bring your characters to life", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Create a personality, choose its picture and start chatting.", color = CSMuted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Create character") }
                Spacer(Modifier.height(12.dp))
                if (characters.isEmpty()) {
                    CSCard {
                        Text("No characters yet", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Your characters and conversations will be saved on this phone.", color = CSMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(characters, key = { it.id }) { c ->
                            CSCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CharacterPicture(c.imageUri, Modifier.size(58.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("Age " + c.age, color = CSMuted, fontSize = 11.sp)
                                        Text(c.personality, color = CSMuted, fontSize = 12.sp, maxLines = 2)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { active = c }, modifier = Modifier.weight(1f)) { Text("Chat") }
                                    OutlinedButton(onClick = { active = c; editing = true }) { Text("Edit") }
                                    OutlinedButton(onClick = { characters = characters.filterNot { it.id == c.id }; putCharacters(context, characters) }) { Text("Delete") }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            picture = uri.toString()
        }
    }
    Text(if (existing == null) "Create your character" else "Edit your character", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Describe the character in your own words.", color = CSMuted, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.fillMaxHeight(0.72f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterPicture(picture, Modifier.size(72.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Character picture", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Choose your own photo.", color = CSMuted, fontSize = 11.sp)
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose picture") }
                }
            }
        }
        item { CSField("Name", name, { name = it }, "Character name") }
        item { CSField("Age", age, { age = it }, "25") }
        item { CSField("Appearance", appearance, { appearance = it }, "Hair, eyes, clothing, style...") }
        item { CSField("Personality", personality, { personality = it }, "Confident, caring, funny...") }
        item { CSField("Background", history, { history = it }, "History, goals, memories...") }
        item { CSField("Speaking style", voice, { voice = it }, "Warm, sarcastic, poetic...") }
        item { Text("Adult-themed roleplay must involve adults aged 18 or older.", color = CSMuted, fontSize = 11.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = { val n = age.toIntOrNull(); if (name.isNotBlank() && n != null && n > 0) onSave(UnAppCharacter(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), age = n.toString(), appearance = appearance, personality = personality, background = history, speakingStyle = voice, imageUri = picture)) },
            enabled = name.isNotBlank() && (age.toIntOrNull() ?: 0) > 0,
            modifier = Modifier.weight(1f)
        ) { Text("Save character") }
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
                            ModelDownloader.download(context) { progress = it }
                            ai.loadModel(model, characterInstruction(character), lines.takeLast(12).map { it.fromUser to it.text })
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
                    try { ai.loadModel(model, characterInstruction(character), lines.takeLast(12).map { it.fromUser to it.text }); status = "Offline AI ready." }
                    catch (e: Exception) { status = e.message ?: "Could not load AI." }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Load offline AI model") }
        }
        Text(status, color = CSMuted, fontSize = 10.sp)
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
                        Text(line.text, color = Color.White, fontSize = 14.sp)
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
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).background(CSPanel, RoundedCornerShape(12.dp)).padding(12.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                decorationBox = { inner -> Box { if (draft.isEmpty()) Text("Write a message...", color = CSMuted); inner() } }
            )
            Spacer(Modifier.width(7.dp))
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
                                ai.loadModel(model, characterInstruction(character), updated.dropLast(1).takeLast(12).map { it.fromUser to it.text })
                                status = "AI loaded (" + ai.activeBackend + ")."
                            }
                            // LiteRT-LM keeps the complete turn history in its active conversation.
                            val answer = ai.ask(message)
                            val completedTranscript = updated + CharacterLine(false, answer)
                            lines = completedTranscript
                            putChat(context, character.id, completedTranscript)
                            status = "Reply generated on this phone."
                        } catch (e: Exception) { status = e.message ?: "AI reply failed." }
                        finally { busy = false }
                    }
                }
            }, enabled = draft.isNotBlank() && !busy) { Text("Send") }
        }
        Text("The AI model must be downloaded before chatting offline.", color = CSMuted, fontSize = 10.sp)
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
