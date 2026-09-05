package eu.keloid.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

internal val KeloPurple = Color(0xFF6757FF)
internal val KeloPink = Color(0xFFD343DD)
internal val KeloInk = Color(0xFF171824)
internal val KeloMuted = Color(0xFF707386)
internal val KeloBackground = Color(0xFFF7F7FB)
internal val KeloBorder = Color.White.copy(alpha = 0.62f)
internal const val KELO_ID_LOGO_URL = "https://kelosocial.sirv.com/logoid.png"

private val scheme = lightColorScheme(
    primary = KeloPurple,
    secondary = KeloPink,
    background = KeloBackground,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = KeloInk,
    onSurface = KeloInk,
    outline = Color(0xFFE2E3EC),
    error = Color(0xFFB3261E)
)

@Composable
internal fun KeloIdTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
internal fun KeloPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFFF4F1FF), KeloBackground, Color(0xFFFFF2FC)))
        )
    ) {
        Box(Modifier.size(230.dp).clip(RoundedCornerShape(120.dp)).graphicsLayer(alpha = 0.22f).background(Brush.radialGradient(listOf(KeloPurple, Color.Transparent))).align(Alignment.TopEnd))
        Box(Modifier.size(200.dp).clip(RoundedCornerShape(120.dp)).graphicsLayer(alpha = 0.16f).background(Brush.radialGradient(listOf(KeloPink, Color.Transparent))).align(Alignment.BottomStart))
        content()
    }
}

@Composable
internal fun KeloBrandHeader(subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = KELO_ID_LOGO_URL, contentDescription = "Logo Kelo ID", modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Kelo ID", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            subtitle?.let { Text(it, color = KeloMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun KeloCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.78f)), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(1.dp, KeloBorder)) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
internal fun KeloGradientCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(KeloPurple, KeloPink))).padding(22.dp)) { content() }
}

@Composable
internal fun KeloGlassNavigation(selected: String, onSelect: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(20.dp, RoundedCornerShape(30.dp), clip = false)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .padding(7.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            GlassNavItem("home", "⌂", "Accueil", selected, onSelect)
            GlassNavItem("verify", "✓", "Vérifier", selected, onSelect)
            GlassNavItem("qr", "▣", "QR", selected, onSelect)
            GlassNavItem("account", "●", "Compte", selected, onSelect)
        }
    }
}

@Composable
private fun GlassNavItem(key: String, icon: String, label: String, selected: String, onSelect: (String) -> Unit) {
    val active = selected == key
    Box(
        Modifier.clip(RoundedCornerShape(22.dp))
            .background(if (active) Brush.linearGradient(listOf(KeloPurple, KeloPink)) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable { onSelect(key) }
            .padding(horizontal = 15.dp, vertical = 9.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(icon, color = if (active) Color.White else KeloMuted, fontWeight = FontWeight.Bold)
            Text(label, color = if (active) Color.White else KeloMuted, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
