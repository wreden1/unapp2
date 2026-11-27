package se.notenchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CharacterProfile(
    val id: String,
    val name: String,
    val description: String,
    val instruction: String
)

private val characters = listOf(
    CharacterProfile("linda", "Linda", "Varm, social och nyfiken",
        "Du spelar Linda. Svara som en egen person, naturligt och självständigt. Upprepa inte användarens formuleringar."),
    CharacterProfile("alex", "Alex", "Lugn, humoristisk och eftertänksam",
        "Du spelar Alex. Var lugn, humoristisk och eftertänksam. Svara direkt på det användaren faktiskt säger."),
    CharacterProfile("neutral", "Neutral", "Ingen särskild personlighet",
        "Svara neutralt, tydligt och hjälpsamt utan rollspel eller särskild personlighet.")
)

private data class ChatLine(val fromUser: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { InteEnChattApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InteEnChattApp() {
    var selected by remember { mutableStateOf(characters.first()) }
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<ChatLine>()) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF315C74),
            secondary = Color(0xFF547C8D),
            background = Color(0xFFF4F6F7),
            surface = Color.White
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Inte en chatt", fontWeight = FontWeight.Bold)
                            Text("Fristående karaktärstest", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selected.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Välj karaktär") },
                        supportingText = { Text(selected.description) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        characters.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                                        Text(profile.description, fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    selected = profile
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF0F3))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Aktiv karaktärsinstruktion", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(selected.instruction, fontSize = 12.sp, color = Color(0xFF33434C))
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                "Välj en karaktär och skriv ett meddelande. Den här första versionen isolerar väljaren och chattgränssnittet; AI-motorn kopplas in separat i nästa steg.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                    items(lines) { line ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (line.fromUser) Color(0xFFDCEAF2) else Color.White,
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 1.dp
                            ) {
                                Text(line.text, Modifier.padding(12.dp).widthIn(max = 280.dp))
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Skriv ett meddelande…") },
                        maxLines = 4
                    )
                    Button(
                        onClick = {
                            val message = draft.trim()
                            if (message.isNotEmpty()) {
                                lines = lines + ChatLine(true, message)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank()
                    ) { Text("Skicka") }
                }
            }
        }
    }
}
