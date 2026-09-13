package dev.vecflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema visual de la app, calcado del cliente oficial de Vector:
 * tipografia grande y ligera, verde como unico acento,
 * y una cabecera de paso con barra de progreso que se repite en todo el flujo.
 */

/** Negro con un verde muy tenue arriba, para que el fondo no sea plano. */
val VecBackdrop = Brush.verticalGradient(
    colors = listOf(Color(0xFF0C1610), Color(0xFF080B09), Color(0xFF080B09))
)

val CardShape = RoundedCornerShape(14.dp)
val ButtonShape = RoundedCornerShape(10.dp)
val PillShape = RoundedCornerShape(50)

/** "STEP 2 OF 17": pequeno, en versalitas y espaciado. */
@Composable
fun StepLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        letterSpacing = 1.3.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Titulo grande y de trazo fino, como en el original. */
@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Light,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/**
 * Cabecera completa del flujo: paso, titulo, linea de sesion y progreso.
 * Es lo que da a toda la app el aire del cliente oficial.
 */
@Composable
fun StepHeader(
    step: Int,
    totalSteps: Int,
    title: String,
    session: String?,
    progress: Float
) {
    Column(Modifier.fillMaxWidth()) {
        StepLabel("Paso $step de $totalSteps")
        Spacer(Modifier.height(6.dp))
        BigTitle(title)
        if (!session.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                session,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(PillShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Encabezado de seccion, en verde y pequeno: "Found Vectors". */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun VecCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Misma tarjeta, con algo mas de aire: la del robot y la del OTA en curso. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = VecCard(modifier = modifier, padding = PaddingValues(18.dp), content = content)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showChevron: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Circulo numerado de los pasos, con relleno verde muy claro. */
@Composable
fun StepBadge(number: Int) {
    Box(
        Modifier
            .size(30.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$number",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Lo que se lee literalmente en la cara de Vector. */
@Composable
fun ScreenChip(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun Dots(count: Int, active: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(6.dp)
                    .width(if (i == active) 22.dp else 6.dp)
                    .background(
                        if (i == active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        PillShape
                    )
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, accent: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

fun formatRate(bytesPerSecond: Long): String {
    val kb = bytesPerSecond / 1024.0
    return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0)
           else String.format("%.0f KB", kb)
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val m = seconds / 60
    return if (m >= 60) String.format("%dh %02dm", m / 60, m % 60)
           else String.format("%d min", if (m < 1) 1 else m)
}

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0)
           else String.format("%.0f MB", mb)
}
