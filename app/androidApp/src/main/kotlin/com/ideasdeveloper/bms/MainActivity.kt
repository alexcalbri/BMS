package com.ideasdeveloper.bms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ideasdeveloper.bms.app.core.company.PersistentCompanyIdentityStore
import com.ideasdeveloper.bms.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.bms.app.core.platform.AndroidFileExport
import com.ideasdeveloper.bms.app.core.session.PersistentSessionStore
import com.ideasdeveloper.bms.app.core.theme.PersistentPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ClientConfigurationStore.initialize(this)
        PersistentCompanyIdentityStore.initialize(this)
        PersistentSessionStore.initialize(this)
        PersistentPreferences.initialize(this)
        AndroidFileExport.initialize(this)

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
