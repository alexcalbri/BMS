package com.ideasdeveloper.bms

import androidx.compose.ui.window.Window
import com.ideasdeveloper.bms.app.core.platform.DesktopBrowser
import androidx.compose.ui.window.application

fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "BMS",
        ) {
            App()
        }
    }
    // Cierra el navegador integrado de modulos web para que no quede un proceso abierto.
    DesktopBrowser.shutdown()
}