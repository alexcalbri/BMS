package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.platform.PlatformBackHandler
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.PersistentPreferences
import com.ideasdeveloper.bms.app.features.assistant.data.assistantCompanyCode
import com.ideasdeveloper.bms.app.features.assistant.data.assistantInServerWorkspace
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Lado de la pantalla donde descansa el boton flotante del asistente (siempre abajo).
internal enum class AssistantFabSide { Start, End }

// Lado elegido, guardado en el dispositivo por usuario con las mismas preferencias sencillas del tema y el idioma.
internal object AssistantFabPreferences {
    private fun key(userId: String?) = "assistant.fab.side." + userId.orEmpty()

    fun load(userId: String?): AssistantFabSide =
        if (runCatching { PersistentPreferences.get(key(userId)) }.getOrNull() == "start") AssistantFabSide.Start else AssistantFabSide.End

    fun save(userId: String?, side: AssistantFabSide) {
        runCatching { PersistentPreferences.set(key(userId), if (side == AssistantFabSide.Start) "start" else "end") }
    }
}

private val FabSize = 56.dp
private val FabMargin = 16.dp
private val PanelWidth = 420.dp

/**
 * Boton flotante del asistente y su panel, sobre todas las pantallas de la sesion iniciada. Se monta una vez en App,
 * por encima de la navegacion, asi su estado (respuesta en curso, borrador, lista) sobrevive al cambiar de pantalla;
 * con otra sesion o empresa se crea de nuevo. No dibuja nada si el servidor no tiene el asistente (404) o dice que no
 * se ve. [openRequest] abre el panel cada vez que aumenta; [onOpenServerSettings] lleva a los ajustes del asistente
 * del servidor (espacio del servidor).
 */
@Composable
fun AssistantFloatingHost(
    serverUrl: String?,
    session: LoginResponse?,
    openRequest: Int,
    onOpenServerSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = serverUrl.orEmpty()
    val token = session?.accessToken.orEmpty()
    val code = session.assistantCompanyCode.orEmpty()
    if (session == null || url.isBlank() || token.isBlank() || code.isBlank()) return
    AssistantFloatingContent(
        serverUrl = url,
        session = session,
        token = token,
        code = code,
        openRequest = openRequest,
        onOpenServerSettings = onOpenServerSettings,
        modifier = modifier,
    )
}

@Composable
private fun AssistantFloatingContent(
    serverUrl: String,
    session: LoginResponse,
    token: String,
    code: String,
    openRequest: Int,
    onOpenServerSettings: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(serverUrl, token, code) { AssistantController(serverUrl, token, code, scope) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    LaunchedEffect(controller) { controller.start() }
    // Solo las peticiones nuevas abren el panel: las anteriores a este anfitrion (o a este controlador) ya se atendieron.
    var handledRequest by remember { mutableIntStateOf(openRequest) }
    LaunchedEffect(controller, openRequest) {
        if (openRequest > handledRequest) {
            handledRequest = openRequest
            controller.open()
        }
    }

    val userId = session.userId
    var side by remember(userId) { mutableStateOf(AssistantFabPreferences.load(userId)) }
    fun moveTo(target: AssistantFabSide) {
        side = target
        AssistantFabPreferences.save(userId, target)
    }
    var settingsOpen by remember(controller) { mutableStateOf(false) }
    val serverWorkspace = session.assistantInServerWorkspace
    val onActivate: () -> Unit = {
        if (serverWorkspace) {
            // En el espacio del servidor los ajustes son la pantalla del asistente del servidor.
            controller.close()
            onOpenServerSettings()
        } else {
            settingsOpen = true
        }
    }
    // Ya se puede usar: el dialogo de ajustes cumplio su funcion.
    LaunchedEffect(controller.status?.available) { if (controller.status?.available == true) settingsOpen = false }

    if (!controller.visible && !controller.panelOpen) return

    val colors = AppTheme.colors
    if (controller.panelOpen) {
        PlatformBackHandler(enabled = true, onBack = controller::close)
        Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Ventanas amplias: panel pegado a la esquina del boton, la pantalla de abajo sigue a la vista.
                // Telefonos y ventanas bajas: hoja a pantalla completa.
                val wide = maxWidth >= 600.dp && maxHeight >= 480.dp
                val frameModifier = if (wide) {
                    Modifier
                        .align(if (side == AssistantFabSide.Start) Alignment.BottomStart else Alignment.BottomEnd)
                        .padding(FabMargin)
                        .widthIn(max = PanelWidth)
                        .fillMaxWidth()
                        .height((maxHeight * 0.7f).coerceAtLeast(480.dp).coerceAtMost(maxHeight - FabMargin * 2))
                } else {
                    Modifier.fillMaxSize()
                }
                Surface(
                    modifier = frameModifier
                        .pointerInput(Unit) {}
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                                controller.close()
                                true
                            } else {
                                false
                            }
                        },
                    shape = if (wide) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp),
                    color = colors.surface,
                    shadowElevation = if (wide) 12.dp else 0.dp,
                    border = if (wide) BorderStroke(1.dp, colors.border) else null,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("Asistente"),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.accentText,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = controller::close,
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = tr("Cerrar el asistente") },
                            ) {
                                Text("✕", color = colors.accentText, modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                        AssistantPanelBody(
                            controller = controller,
                            twoPane = false,
                            onActivate = onActivate,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    } else {
        // Con el teclado abierto el boton se oculta: no tapa lo que se escribe.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        if (!keyboardOpen) {
            Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    AssistantFab(
                        side = side,
                        containerWidth = maxWidth,
                        dimmed = controller.status?.available != true,
                        attention = controller.attention,
                        onOpen = controller::open,
                        onSideChange = ::moveTo,
                        modifier = Modifier.align(if (side == AssistantFabSide.Start) Alignment.BottomStart else Alignment.BottomEnd),
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        AssistantSettingsDialog(
            serverUrl = serverUrl,
            session = session,
            onChanged = controller::loadStatus,
            onDismiss = { settingsOpen = false },
        )
    }
}

// Boton redondo: un toque abre el chat; arrastrado en horizontal se queda en la esquina inferior mas cercana. Para quien no
// arrastra: las flechas izquierda y derecha (teclado) y las acciones de accesibilidad lo cambian de lado.
@Composable
private fun AssistantFab(
    side: AssistantFabSide,
    containerWidth: Dp,
    dimmed: Boolean,
    attention: Boolean,
    onOpen: () -> Unit,
    onSideChange: (AssistantFabSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Desplazamiento respecto al lugar de descanso del lado actual, en pixeles; solo mientras se arrastra o se asienta.
    var dragX by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    // Distancia entre los dos lugares de descanso (izquierda y derecha).
    val travel = with(density) { (containerWidth - FabSize - FabMargin * 2).coerceAtLeast(0.dp).toPx() }
    val dragState = rememberDraggableState { delta ->
        val low = if (side == AssistantFabSide.Start) 0f else -travel
        val high = if (side == AssistantFabSide.Start) travel else 0f
        dragX = (dragX + delta).coerceIn(low, high)
    }

    val label = when {
        attention -> tr("Asistente, requiere tu atencion")
        dimmed -> tr("Asistente, no disponible ahora")
        else -> tr("Abrir el asistente")
    }

    Box(
        modifier = modifier
            .padding(FabMargin)
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .size(FabSize)
            .alpha(if (dimmed) 0.55f else 1f),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(FabSize)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.accent, CircleShape)
                .then(if (focused) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                .semantics {
                    contentDescription = label
                    customActions = listOf(
                        CustomAccessibilityAction(tr("Mover a la izquierda")) {
                            onSideChange(AssistantFabSide.Start)
                            true
                        },
                        CustomAccessibilityAction(tr("Mover a la derecha")) {
                            onSideChange(AssistantFabSide.End)
                            true
                        },
                    )
                }
                .onFocusChanged { focused = it.hasFocus }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else if (event.key == Key.DirectionLeft) {
                        onSideChange(AssistantFabSide.Start)
                        true
                    } else if (event.key == Key.DirectionRight) {
                        onSideChange(AssistantFabSide.End)
                        true
                    } else {
                        false
                    }
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        // Posicion medida desde el descanso izquierdo; se asienta en el lado mas cercano sin saltos.
                        val fromStart = if (side == AssistantFabSide.Start) dragX else travel + dragX
                        val target = if (fromStart > travel / 2f) AssistantFabSide.End else AssistantFabSide.Start
                        val rest = if (target == AssistantFabSide.Start) 0f else travel
                        val remaining = fromStart - rest
                        dragX = remaining
                        if (target != side) onSideChange(target)
                        scope.launch { animate(remaining, 0f) { value, _ -> dragX = value } }
                    },
                )
                .clickable(role = Role.Button, onClick = onOpen),
        ) {
            Text("IA", color = colors.onAccent, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics {})
        }
        if (attention) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .background(colors.warning, CircleShape)
                    .border(2.dp, colors.surface, CircleShape)
                    .clearAndSetSemantics {},
            )
        }
    }
}

// Ajustes del asistente de la empresa en un dialogo (activacion con el aviso de privacidad, clave propia y uso).
@Composable
private fun AssistantSettingsDialog(serverUrl: String, session: LoginResponse, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).widthIn(max = 640.dp).padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Ajustes del asistente"),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.accentText,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = tr("Cerrar") },
                    ) {
                        Text("✕", color = colors.accentText, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    AssistantCompanySettingsSection(
                        serverUrl = serverUrl,
                        session = session,
                        primaryColor = colors.accent,
                        framed = false,
                        onChanged = onChanged,
                    )
                }
            }
        }
    }
}
