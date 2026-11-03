package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.JarvisAmberGold
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisDeepNavy
import com.example.ui.theme.JarvisElectricBlue
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisObsidian
import com.example.ui.theme.JarvisSurfaceCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AstrafolioSessionManager {
    private const val PREFS_NAME = "astrafolio_local_auth_prefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_OPERATOR_NAME = "operator_name"

    private val _currentOperator = MutableStateFlow<String?>("jarvis_operator")
    val currentOperator: StateFlow<String?> = _currentOperator.asStateFlow()

    fun initSession(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, true)
        val name = prefs.getString(KEY_OPERATOR_NAME, "jarvis_operator") ?: "jarvis_operator"
        _currentOperator.value = if (loggedIn) name else null
    }

    fun signInLocally(context: Context, operatorName: String = "jarvis_operator") {
        val cleanName = operatorName.trim().ifBlank { "jarvis_operator" }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_OPERATOR_NAME, cleanName)
            .apply()
        _currentOperator.value = cleanName
    }

    fun signOutLocally(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
        _currentOperator.value = null
    }
}

fun signOutFromAstrafolio(
    context: Context,
    onSignOutComplete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null
) {
    AstrafolioSessionManager.signOutLocally(context)
    onSignOutComplete()
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var operatorName by rememberSaveable { mutableStateOf("Astra-Operator") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(JarvisObsidian, JarvisDeepNavy, JarvisObsidian)
                )
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = JarvisSurfaceCard),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, JarvisCyan.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = JarvisCyan.copy(alpha = 0.14f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_jarvis_hologram),
                            contentDescription = "Astrafolio Core Emblem",
                            modifier = Modifier.size(58.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ASTRA-YORDAMCHI • JARVIS",
                    style = MaterialTheme.typography.headlineSmall,
                    color = JarvisCyan,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "5-AI Ovozli Boshqaruv • 24/7 Orqa Fon • Avto-Kotib • Lokal Baza",
                    style = MaterialTheme.typography.labelMedium,
                    color = JarvisAmberGold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                FeatureHighlightRow(
                    icon = Icons.Default.GraphicEq,
                    title = "Ovoz bilan Telefonni To'liq Boshqarish",
                    subtitle = "Fonarik, ovoz, budilnik, taymer, qo'ng'iroq, SMS, kamera va sozlamalarni o'zbekcha ovozda boshqaring",
                    tint = JarvisCyan
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeatureHighlightRow(
                    icon = Icons.Default.PhoneInTalk,
                    title = "Qo'ng'iroqlarga Avtomatik AI Javobgar",
                    subtitle = "Sizga telefon qilganlarga AI kotib o'zbek tilida avtomatik javob beradi va lokal Room bazasiga yozib boradi",
                    tint = JarvisEmerald
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeatureHighlightRow(
                    icon = Icons.Default.Hub,
                    title = "Multi-API Klaster va Lokal HTTP Server",
                    subtitle = "Bir nechta AI Studio (AQ. / AIza) kalitlarini ulang, tokenlarni hisoblang va 127.0.0.1:8080 lokal serverni boshqaring",
                    tint = JarvisElectricBlue
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = operatorName,
                    onValueChange = { operatorName = it },
                    label = { Text("Operator ismi yoki profil nomi") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisCyan.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("operator_name_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        AstrafolioSessionManager.signInLocally(context, operatorName)
                        onAuthSuccess()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan,
                        contentColor = JarvisObsidian
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("google_sign_in_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Astra-Yordamchi Tizimiga Kirish",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        AstrafolioSessionManager.signInLocally(context, "termux_local_operator")
                        onAuthSuccess()
                    },
                    border = BorderStroke(1.dp, JarvisAmberGold.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_enter_local_api_mode")
                ) {
                    Text(
                        text = "AQ. API / Lokal Server Rejimida Kirish (Termux)",
                        style = MaterialTheme.typography.labelMedium,
                        color = JarvisAmberGold,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = JarvisEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "100% Mustaqil Lokal Room SQLite xotirasi • Firebase'siz ishlaydi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = JarvisObsidian.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
