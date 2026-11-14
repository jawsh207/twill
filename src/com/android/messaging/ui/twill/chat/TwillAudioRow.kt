package com.android.messaging.ui.twill.chat

import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import kotlinx.coroutines.delay

/** A voice message or other audio file in a chat bubble: play/pause, progress and length. */
@Composable
internal fun TwillAudioRow(path: String, modifier: Modifier = Modifier) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableIntStateOf(0) }
    val length = remember(path) {
        runCatching {
            MediaPlayer().run {
                setDataSource(path)
                prepare()
                duration.also { release() }
            }
        }.getOrDefault(0)
    }

    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            position = player?.currentPosition ?: 0
            delay(PROGRESS_TICK_MILLIS)
        }
    }

    fun toggle() {
        val current = player
        when {
            current == null -> runCatching {
                MediaPlayer().apply {
                    setDataSource(path)
                    setOnCompletionListener {
                        isPlaying = false
                        position = 0
                    }
                    prepare()
                    start()
                }
            }.onSuccess {
                player = it
                isPlaying = true
            }

            current.isPlaying -> {
                current.pause()
                isPlaying = false
            }

            else -> {
                current.start()
                isPlaying = true
            }
        }
    }

    Row(
        modifier = modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = ::toggle) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.twill_audio_pause else R.string.twill_audio_play,
                ),
            )
        }
        LinearProgressIndicator(
            progress = { if (length > 0) position.toFloat() / length else 0f },
            modifier = Modifier.width(140.dp),
        )
        Text(
            modifier = Modifier.padding(start = 10.dp),
            text = DateUtils.formatElapsedTime(
                ((if (isPlaying || position > 0) position else length) / 1000).toLong(),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private const val PROGRESS_TICK_MILLIS = 200L
