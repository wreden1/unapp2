package se.unapp.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0B1020)
private val Panel = Color(0xFF151D32)
private val Accent = Color(0xFF8B7CFF)
private val Muted = Color(0xFFAAB4CC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnAppScreen() }
    }
}

@Composable
fun UnAppScreen() {
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Skapa") }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            primary = Accent,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
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
                                result = """
                                    APP-PROJEKT SKAPAT

                                    Appens idé:
                                    $prompt

                                    PLANERADE DELAR:
                                    1. Appens startsida
                                    2. Appens funktioner
                                    3. Design och färger
                                    4. Navigation mellan sidor
                                    5. Förberedelse för APK

                                    Nästa steg är att låta UnApps
                                    lokala AI skapa projektets kod.
                                """.trimIndent()
                            },
                            enabled = prompt.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text(
                                "Skapa app med AI",
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
                        "DIN APPSTUDIO",
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureCard(
                            "Mina appar",
                            "Dina projekt",
                            Modifier.weight(1f)
                        )
                        FeatureCard(
                            "Förhandsvisa",
                            "Testa design",
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureCard(
                            "Kod & design",
                            "Full kontroll",
                            Modifier.weight(1f)
                        )
                        FeatureCard(
                            "Bygg APK",
                            "Installera appar",
                            Modifier.weight(1f)
                        )
                    }
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
                            "Status: Projektplanering fungerar. Lokal AI och APK-byggare återstår.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }
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