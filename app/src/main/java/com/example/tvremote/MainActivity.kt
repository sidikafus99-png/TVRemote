package com.example.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Mode WiFi: ADB over WiFi (port 5555). */
object Adb {
    private var dadb: Dadb? = null
    fun connect(dir: File, host: String) {
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        dadb?.close()
        dadb = Dadb.create(host, 5555, AdbKeyPair.read(priv, pub))
        dadb!!.shell("echo ok")
    }
    fun key(code: Int) { dadb?.shell("input keyevent $code") }
}

/** Mode IR: protokol NEC 38 kHz lewat IR blaster HP. */
object Ir {
    fun available(c: Context) =
        (c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true
    fun send(c: Context, addr: Int, cmd: Int) {
        val m = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return
        if (!m.hasIrEmitter()) return
        val bits = (addr and 255) or ((addr.inv() and 255) shl 8) or
                ((cmd and 255) shl 16) or ((cmd.inv() and 255) shl 24)
        val p = ArrayList<Int>(); p += 9000; p += 4500
        for (i in 0 until 32) { p += 560; p += if ((bits shr i) and 1 == 1) 1690 else 560 }
        p += 560
        m.transmit(38000, p.toIntArray())
    }
}

object K {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    const val OK = 23; const val BACK = 4; const val HOME = 3; const val MENU = 82
    const val POWER = 26; const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val PLAY = 85; const val DEL = 67; const val ENTER = 66; const val SPACE = 62
    fun letter(c: Char) = 29 + (c - 'A')
    fun digit(c: Char) = 7 + (c - '0')
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { RemoteScreen(filesDir) } } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(dir: File) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ir", Context.MODE_PRIVATE) }
    var irMode by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "192.168.1.") ?: "") }
    var status by remember { mutableStateOf("WiFi: belum terhubung") }
    var editKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    fun irCode(code: Int): Pair<Int, Int>? {
        val s = prefs.getString("k$code", null) ?: return null
        val (a, c) = s.split(":"); return a.toInt(16) to c.toInt(16)
    }
    fun send(code: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (irMode) {
            val c = irCode(code)
            status = if (!Ir.available(ctx)) "HP ini tidak punya IR blaster"
            else if (c == null) "Kode IR tombol ini belum diatur (tekan lama tombol)"
            else { Ir.send(ctx, c.first, c.second); "IR terkirim" }
        } else scope.launch(Dispatchers.IO) { runCatching { Adb.key(code) } }
    }

    @Composable fun Btn(label: String, code: Int, w: Int = 56) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.width(w.dp).height(48.dp).combinedClickable(
                onClick = { send(code) },
                onLongClick = { if (irMode) editKey = label to code })) {
            Box(contentAlignment = Alignment.Center) { Text(label) }
        }
    }
    @Composable fun KeyRow(chars: String) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chars.forEach { c -> Btn(c.toString(), if (c.isDigit()) K.digit(c) else K.letter(c), 34) }
    }

    editKey?.let { (label, code) ->
        val cur = irCode(code)
        var addr by remember { mutableStateOf(cur?.first?.toString(16) ?: "") }
        var cmd by remember { mutableStateOf(cur?.second?.toString(16) ?: "") }
        AlertDialog(onDismissRequest = { editKey = null },
            title = { Text("Kode IR NEC: $label") },
            text = { Column {
                OutlinedTextField(addr, { addr = it }, label = { Text("Address (hex, mis. 00)") }, singleLine = true)
                OutlinedTextField(cmd, { cmd = it }, label = { Text("Command (hex, mis. 1A)") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = {
                if (addr.toIntOrNull(16) != null && cmd.toIntOrNull(16) != null)
                    prefs.edit().putString("k$code", "$addr:$cmd").apply()
                editKey = null
            }) { Text("Simpan") } },
            dismissButton = { TextButton(onClick = { editKey = null }) { Text("Batal") } })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!irMode, { irMode = false }, label = { Text("WiFi") })
            FilterChip(irMode, { irMode = true; status = "Mode IR (tekan lama tombol untuk atur kode)" }, label = { Text("Infra Merah") })
        }
        if (!irMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ip, { ip = it }, label = { Text("IP TV Box") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                status = "Menghubungkan..."; prefs.edit().putString("ip", ip).apply()
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { Adb.connect(dir, ip.trim()); "WiFi: terhubung ✓" }
                            .getOrElse { "Gagal: ${it.message}" }
                    }
                }
            }) { Text("Hubung") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("⏻", K.POWER); Btn("Home", K.HOME); Btn("Menu", K.MENU); Btn("Back", K.BACK)
        }
        Btn("▲", K.UP, 72)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("◀", K.LEFT, 72); Btn("OK", K.OK, 72); Btn("▶", K.RIGHT, 72)
        }
        Btn("▼", K.DOWN, 72)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Vol-", K.VOL_DOWN); Btn("Mute", K.MUTE); Btn("Vol+", K.VOL_UP); Btn("⏯", K.PLAY)
        }
        HorizontalDivider()
        Text("Keyboard", style = MaterialTheme.typography.labelLarge)
        KeyRow("1234567890"); KeyRow("QWERTYUIOP"); KeyRow("ASDFGHJKL"); KeyRow("ZXCVBNM")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Spasi", K.SPACE, 120); Btn("⌫", K.DEL, 70); Btn("Enter", K.ENTER, 80)
        }
    }
}
