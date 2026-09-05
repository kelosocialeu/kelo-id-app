package eu.keloid.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

internal val KeloPurple = Color(0xFF6757FF)
internal val KeloPink = Color(0xFFD343DD)
internal val KeloInk = Color(0xFF171824)
internal val KeloMuted = Color(0xFF707386)
internal val KeloBackground = Color(0xFFF8F8FC)
internal val KeloBorder = Color(0xFFE4E6EF)
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
    outline = KeloBorder,
    error = Color(0xFFB3261E)
)

@Composable
internal fun KeloIdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun KeloPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF7F6FF),
                        KeloBackground,
                        Color(0xFFFFF7FF)
                    )
                )
            )
    ) { content() }
}

@Composable
internal fun KeloBrandHeader(subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = KELO_ID_LOGO_URL,
            contentDescription = "Logo Kelo ID",
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Kelo ID", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            subtitle?.let { Text(it, color = KeloMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun KeloCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, KeloBorder)
    ) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }
}

@Composable
internal fun KeloGradientCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5D4DF0), Color(0xFFC13ED4))))
            .padding(22.dp)
    ) { content() }
}
