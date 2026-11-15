package app.dcrux.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.dcrux.LocalGraph
import coil3.compose.AsyncImage

/** On a computer the video plays in the browser, so there is nothing here to drive. */
class PlayerController {
    @Volatile var busy = false
    fun grabCaptions(lang: String, kind: String, vssId: String) {}
    fun doneCaptions(restore: Boolean) {}
    fun pause() {}
}

/**
 * The video's picture with a play button that opens it on YouTube in the browser. A computer
 * build has no embedded player yet, so it cannot read a video's captions either: notes written
 * on the phone arrive here through sync.
 */
@Composable
fun YouTubePlayer(
    videoId: String,
    controller: PlayerController,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onState: (Int) -> Unit = {},
    onError: (Int) -> Unit = {},
    onTranscript: (String) -> Unit = {},
) {
    val platform = LocalGraph.current.platform
    Box(modifier.clickable { platform.openUrl("https://www.youtube.com/watch?v=$videoId") }, contentAlignment = Alignment.Center) {
        AsyncImage("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", "Video thumbnail", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(26.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Watch on YouTube", color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
    }
}
