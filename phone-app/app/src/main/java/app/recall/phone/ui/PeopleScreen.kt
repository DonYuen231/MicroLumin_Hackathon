package app.recall.phone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.phone.AppContainer
import app.recall.phone.core.formatDate
import app.recall.phone.core.latestEncounter
import app.recall.phone.data.PersonRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PeopleScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val people by container.people.observePeople()
        .collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("People")
        val list = people
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyPeople()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { person ->
                    PersonRow(person) { onOpen(person.id) }
                }
            }
        }
    }
}

@Composable
private fun EmptyPeople() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No one saved yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Capture a first meeting on the glasses, then open Captures to bring it here.",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PersonRow(person: PersonRecord, onClick: () -> Unit) {
    val profile = person.profile
    val encounter = latestEncounter(profile)
    val lastMet = listOf(encounter?.place.orEmpty(), formatDate(encounter?.time))
        .filter { it.isNotBlank() }
        .joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(person.thumbnailPath, profile.name, 52.dp)
        Column(
            Modifier
                .padding(start = 14.dp)
                .weight(1f),
        ) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.headline.isNotBlank()) {
                Text(
                    profile.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (lastMet.isNotBlank()) {
                Text(
                    "Last met: $lastMet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Thumbnail(path: String?, name: String, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }
    val image = bitmap
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = "Photo of $name",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
    )
}
