package dev.vecflash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vecflash.ui.FlasherApp

/**
 * Misma estructura visual que el cliente oficial (cabecera de paso, titulos finos,
 * verde como unico acento) pero en oscuro.
 */
private val VectorDark = darkColorScheme(
    primary = Color(0xFF2EE06B),
    onPrimary = Color(0xFF00210C),
    primaryContainer = Color(0xFF14401A),
    onPrimaryContainer = Color(0xFF8CF9B0),
    secondary = Color(0xFF6FE39A),
    onSecondary = Color(0xFF00210C),
    background = Color(0xFF080B09),
    onBackground = Color(0xFFE9F2EC),
    surface = Color(0xFF121714),
    onSurface = Color(0xFFE9F2EC),
    surfaceVariant = Color(0xFF1E2620),
    onSurfaceVariant = Color(0xFF94A39A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2A0907),
    outline = Color(0xFF2A342D)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = VectorDark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: FlasherViewModel = viewModel()
                    WithBlePermissions { granted ->
                        FlasherApp(vm = vm, permissionsGranted = granted)
                    }
                }
            }
        }
    }
}

/** Pide en runtime los permisos de Bluetooth que toquen segun la version de Android. */
@Composable
private fun WithBlePermissions(content: @Composable (Boolean) -> Unit) {
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var granted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
    }

    LaunchedEffect(Unit) { launcher.launch(permissions) }

    content(granted)
}
