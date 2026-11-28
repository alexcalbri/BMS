package com.ideasdeveloper.bms.app.core.platform

import androidx.compose.runtime.Composable

// Boton "atras" del sistema (Android): mientras [enabled], lo atiende [onBack] en lugar de la navegacion. En los demas
// destinos no hace nada (no tienen boton atras del sistema que interceptar).
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
