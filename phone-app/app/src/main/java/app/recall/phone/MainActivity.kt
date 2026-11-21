package app.recall.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.recall.phone.hub.HubService
import app.recall.phone.ui.RecallRoot

class MainActivity : ComponentActivity() {
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The hub runs either way. This only controls whether its notification is visible.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        HubService.start(this)
        val container = (application as RecallApp).container
        setContent { RecallRoot(container) }
    }
}
