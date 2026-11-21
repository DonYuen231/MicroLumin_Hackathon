package app.recall.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.phone.AppContainer
import app.recall.phone.hub.CaptureItem
import app.recall.phone.hub.ItemStatus
import kotlinx.coroutines.launch

@Composable
fun CapturesScreen(container: AppContainer) {
    val state by container.captures.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Captures")
        Button(
            enabled = !state.running,
            onClick = { container.scope.launch { container.captures.pullAndProcess() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) { Text(if (state.running) "Working" else "Get captures from glasses") }

        if (state.message.isNotBlank()) {
            Text(
                state.message,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.captureId }) { CaptureRow(it) }
        }
    }
}

@Composable
private fun CaptureRow(item: CaptureItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.captureId, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.step,
                style = MaterialTheme.typography.bodySmall,
                color = when (item.status) {
                    ItemStatus.FAILED -> MaterialTheme.colorScheme.error
                    ItemStatus.DONE -> MaterialTheme.colorScheme.primary
                    ItemStatus.WORKING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (item.status == ItemStatus.WORKING) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
