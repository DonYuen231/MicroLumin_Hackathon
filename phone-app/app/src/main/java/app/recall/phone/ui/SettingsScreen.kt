package app.recall.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.phone.AppContainer
import app.recall.phone.data.DEFAULT_THRESHOLD
import app.recall.phone.hub.HubStatus
import app.recall.phone.net.BackendException
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val hubRunning by HubStatus.running.collectAsStateWithLifecycle()
    val blocked by HubStatus.blockedAddress.collectAsStateWithLifecycle()

    var backendUrl by remember { mutableStateOf("") }
    var appToken by remember { mutableStateOf("") }
    var glasses by remember { mutableStateOf("") }
    var wearer by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var threshold by remember { mutableFloatStateOf(DEFAULT_THRESHOLD) }
    var demoSpeed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val current = container.settings.current()
        backendUrl = current.backendUrl
        appToken = current.appToken
        glasses = current.glassesAddress
        wearer = current.wearerName
        place = current.place
        threshold = current.matchThreshold
        demoSpeed = current.demoSpeed
    }

    suspend fun save() {
        container.settings.save(
            container.settings.current().copy(
                backendUrl = backendUrl,
                appToken = appToken,
                glassesAddress = glasses,
                wearerName = wearer,
                place = place,
                matchThreshold = threshold,
                demoSpeed = demoSpeed,
            ),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenTitle("Settings")
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Your name", wearer) { wearer = it }
            Field("Current place", place) { place = it }
            Field("Backend URL", backendUrl) { backendUrl = it }
            OutlinedTextField(
                value = appToken,
                onValueChange = { appToken = it },
                label = { Text("App token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Field("Glasses address", glasses) { glasses = it }

            Text(
                "Match threshold: ${String.format(Locale.US, "%.2f", threshold)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0.2f..0.8f)

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Demo speed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1 hour = 10 seconds, up to 1 minute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = demoSpeed,
                    onCheckedChange = { checked ->
                        demoSpeed = checked
                        scope.launch { save() }
                    },
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        save()
                        status = "Saved."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        save()
                        status = "Checking the backend"
                        status = try {
                            if (container.backend.health()) {
                                "Backend connected."
                            } else {
                                "The backend did not respond correctly."
                            }
                        } catch (e: BackendException) {
                            e.message.orEmpty()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test backend") }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.secondary)
            }

            Text(
                if (hubRunning) "Glasses hub is running on port 8080." else "Glasses hub is off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val device = blocked
            if (device != null && device != glasses.substringBefore(':')) {
                Text("A device at $device tried to connect.")
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.settings.trustGlasses(device)
                            glasses = device
                            HubStatus.blockedAddress.value = null
                            status = "Glasses address saved."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Trust this device as the glasses") }
            }
        }
    }
}
