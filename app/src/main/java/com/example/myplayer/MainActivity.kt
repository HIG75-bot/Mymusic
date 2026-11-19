package com.example.myplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.io.File

data class Song(val id: Long, val title: String, val artist: String, val album: String, val folder: String)

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA)
    ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
        "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE").use { c ->
        while (c != null && c.moveToNext())
            out += Song(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "Неизвестный", c.getString(3) ?: "Неизвестный",
                File(c.getString(4) ?: "").parentFile?.name ?: "/")
    }
    return out
}

// Плейлисты: имя -> список id песен, хранятся локально в SharedPreferences
class Playlists(ctx: Context) {
    private val sp = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    fun all(): Map<String, List<Long>> = sp.all.mapValues { (it.value as String).split(",").mapNotNull { s -> s.toLongOrNull() } }
    fun save(name: String, ids: List<Long>) = sp.edit().putString(name, ids.joinToString(",")).apply()
    fun delete(name: String) = sp.edit().remove(name).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        startService(Intent(this, PlayerService::class.java))
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[perm] == true) songs = loadSongs(ctx)
    }
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(perm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(perm)
        launcher.launch(perms)
    }
    val list = songs ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Нужен доступ к музыке на устройстве") }
    Library(ctx, list)
}

@Composable
fun Library(ctx: Context, songs: List<Song>) {
    val player = remember { PlayerHolder.get(ctx) }
    val pls = remember { Playlists(ctx) }
    var plState by remember { mutableStateOf(pls.all()) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    var newName by remember { mutableStateOf("") }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onMediaMetadataChanged(m: MediaMetadata) { title = m.title?.toString() ?: "" }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    fun play(list: List<Song>, i: Int) {
        player.setMediaItems(list.map {
            MediaItem.Builder().setUri(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id.toString()))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()).build()
        }, i, 0L)
        player.prepare(); player.play()
    }
    val byId = songs.associateBy { it.id }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (open == null) {
            TabRow(tab) { listOf("Песни", "Альбомы", "Папки", "Плейлисты").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) } }
        } else TextButton({ open = null }) { Icon(Icons.Default.ArrowBack, null); Text(" ${open!!.first}") }
        LazyColumn(Modifier.weight(1f)) {
            val o = open
            if (o != null) {
                items(o.second.size) { i -> SongRow(o.second[i], { play(o.second, i) }, { addTo = o.second[i] }) }
            } else when (tab) {
                0 -> items(songs.size) { i -> SongRow(songs[i], { play(songs, i) }, { addTo = songs[i] }) }
                1 -> items(songs.groupBy { it.album }.toList()) { (n, l) -> GroupRow(n, l.size) { open = n to l } }
                2 -> items(songs.groupBy { it.folder }.toList()) { (n, l) -> GroupRow(n, l.size) { open = n to l } }
                else -> {
                    item { Row(Modifier.padding(8.dp)) {
                        OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Новый плейлист") }, singleLine = true)
                        IconButton({ if (newName.isNotBlank()) { pls.save(newName.trim(), emptyList()); newName = ""; plState = pls.all() } }) { Icon(Icons.Default.Add, null) }
                    } }
                    items(plState.toList()) { (n, ids) -> GroupRow(n, ids.size) { open = n to ids.mapNotNull { byId[it] } } }
                }
            }
        }
        if (title.isNotEmpty()) Surface(tonalElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null) }
                IconButton({ if (playing) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                IconButton({ player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null) }
            }
        }
    }
    addTo?.let { s ->
        AlertDialog(onDismissRequest = { addTo = null }, confirmButton = {}, title = { Text("Добавить в плейлист") }, text = {
            Column { if (plState.isEmpty()) Text("Сначала создайте плейлист во вкладке «Плейлисты»")
                plState.forEach { (n, ids) -> TextButton({ pls.save(n, ids + s.id); plState = pls.all(); addTo = null }) { Text(n) } } }
        })
    }
}

@Composable
fun SongRow(s: Song, onPlay: () -> Unit, onMore: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
    IconButton(onMore) { Icon(Icons.Default.PlaylistAdd, null) }
}

@Composable
fun GroupRow(name: String, count: Int, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$count")
}
