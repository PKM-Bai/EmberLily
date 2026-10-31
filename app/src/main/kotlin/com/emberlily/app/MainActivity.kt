package com.emberlily.app

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.emberlily.app.emulation.FrameBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener, InputManager.InputDeviceListener {
    private val player: PlayerModel by viewModels()
    private var companion: Presentation? = null
    private var dual by mutableStateOf(false)
    private var started = false
    private var showLicenses by mutableStateOf(false)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) player.importRom(uri)
    }
    private val displays get() = getSystemService(DisplayManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A direct cold launch on a secondary display takes the independent router.
        // No PlayerModel/core has been accessed here. Finishing this Activity is safe;
        // the router uses a different component and task affinity to launch the player.
        if (savedInstanceState == null && activityDisplayId() != Display.DEFAULT_DISPLAY &&
            !intent.getBooleanExtra(DisplayRouterActivity.EXTRA_DISPLAY_ROUTED, false)) {
            val routed = runCatching {
                startActivity(Intent(this, DisplayRouterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w("EmberLilyDisplays", "Could not start display router; using single-screen layout", it) }.isSuccess
            if (routed) { finish(); return }
        }
        if (activityDisplayId() != Display.DEFAULT_DISPLAY) {
            Log.w("EmberLilyDisplays", "Player remains on display ${activityDisplayId()}; using single-screen layout")
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            EmberTheme {
                val state by player.state.collectAsState()
                if (showLicenses) {
                    val licenseText = remember { assets.open("licenses/notices.txt").bufferedReader().use { it.readText() } }
                    AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源许可") },
                        text = { Text(licenseText, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } })
                }
                Surface(Modifier.fillMaxSize()) {
                    if (dual) {
                        GameScreen(state, Modifier.fillMaxSize().systemBarsPadding())
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                            if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
                                GameScreen(state, Modifier.weight(1.4f).fillMaxHeight())
                                Controls(state, Modifier.weight(1f).fillMaxHeight())
                            } else Column(Modifier.fillMaxSize()) {
                                GameScreen(state, Modifier.fillMaxWidth().weight(1f))
                                Controls(state, Modifier.fillMaxWidth().weight(1.2f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun GameScreen(state: PlayerState, modifier: Modifier) {
        Column(modifier.background(Color.Black)) {
            if (state.loaded) AndroidView(factory = { GameView(it, player.frames) }, modifier = Modifier.fillMaxWidth().weight(1f))
            else Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("EmberLily", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFF5D59B))
                Spacer(Modifier.height(12.dp))
                Text("上屏游戏 · 下屏同行", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("打开 GBA 文件，开始你的旅程", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE0D9CE))
            }
            if (state.loaded) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TouchKey("↑", 1 shl 6); TouchKey("↓", 1 shl 7); TouchKey("←", 1 shl 5); TouchKey("→", 1 shl 4)
                TouchKey("B", 2); TouchKey("A", 1)
            }
        }
    }

    @Composable private fun TouchKey(label: String, bit: Int) {
        val scope = rememberCoroutineScope()
        val description = when (label) {
            "↑" -> "方向键：上"
            "↓" -> "方向键：下"
            "←" -> "方向键：左"
            "→" -> "方向键：右"
            "SELECT" -> "选择键"
            "START" -> "开始键"
            else -> "$label 键"
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
            modifier = Modifier.defaultMinSize(48.dp, 48.dp).semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                onClick(label = "按下$description") {
                    if (player.state.value.busy) false else {
                        scope.launch {
                            val source = "accessibility:$label:${System.nanoTime()}"
                            player.input.set(source, bit)
                            try { delay(80) } finally { player.input.set(source, 0) }
                        }
                        true
                    }
                }
            }.pointerInput(label) {
                detectTapGestures(onPress = {
                    player.input.set("touch:$label", bit)
                    try { tryAwaitRelease() } finally { player.input.set("touch:$label", 0) }
                })
            }) {
            Box(Modifier.padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(label) }
        }
    }

    @Composable private fun Controls(state: PlayerState, modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.experiment) "实验分支" else "游玩会话", style = MaterialTheme.typography.headlineSmall)
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            Text(if (state.loaded) "${state.branchLabel} · ${if (state.running) "运行中" else "已暂停"}" else
                if (dual) "副屏已连接" else "单屏模式 · 连接副屏后自动分屏", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("打开 GBA 文件") }
            if (!state.loaded && state.hasRecent) OutlinedButton(onClick = player::resumeRecent,
                enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("继续上次进度") }
            if (state.loaded) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = player::togglePause, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.running) "暂停" else "继续") }
                    OutlinedButton(onClick = player::save, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("保存恢复点") }
                }
                OutlinedButton(onClick = player::restore, enabled = !state.busy && state.hasState,
                    modifier = Modifier.fillMaxWidth()) { Text("恢复此分支") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TouchKey("SELECT", 4); TouchKey("START", 8); TouchKey("L", 512); TouchKey("R", 256) }
                HorizontalDivider()
                Text("独立实验", style = MaterialTheme.typography.titleMedium)
                Text("从当前进度建立副本，保存与恢复只影响该分支。", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = player::fork, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("创建实验分支") }
                if (state.experiment) OutlinedButton(onClick = player::returnToMain, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("返回主进度") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.error ?: state.message, color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            Text("基础游玩版本 · 火纹信息面板尚未接入", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { player.input.releaseAll(); showLicenses = true }) { Text("关于与开源许可") }
        }
    }

    override fun onStart() { super.onStart(); if (isFinishing) return; started = true; displays.registerDisplayListener(this, null); getSystemService(InputManager::class.java).registerInputDeviceListener(this, null); updateCompanion(); player.foreground(true) }
    override fun onStop() {
        if (started) {
            started = false
            player.foreground(false)
            displays.unregisterDisplayListener(this)
            getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        }
        dismissCompanion()
        super.onStop()
    }
    override fun onDestroy() { companion?.dismiss(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A singleTask activity can be moved to another display without recreation.
        // Route its existing views after the window's new display has been applied.
        window.decorView.post { updateCompanion() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post { updateCompanion() }
    }
    override fun onDisplayAdded(displayId: Int) = updateCompanion()
    override fun onDisplayRemoved(displayId: Int) = updateCompanion()
    override fun onDisplayChanged(displayId: Int) = updateCompanion()
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) = player.input.releaseDevice(deviceId)
    override fun onInputDeviceRemoved(deviceId: Int) = player.input.releaseDevice(deviceId)

    private fun dismissCompanion() {
        val previous = companion
        companion = null
        dual = false
        previous?.dismiss()
    }

    private fun activityDisplayId(): Int = if (android.os.Build.VERSION.SDK_INT >= 30)
        display?.displayId ?: Display.DEFAULT_DISPLAY
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId

    private fun updateCompanion() {
        if (!started || isFinishing || isDestroyed) return
        val own = activityDisplayId()
        // Android requires FLAG_PRESENTATION and rejects Presentation on the default
        // display. The router puts the Activity there; only the companion uses this API.
        val target = if (own == Display.DEFAULT_DISPLAY)
            displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.isValid && it.displayId != own }
            else null
        if (target?.displayId == companion?.display?.displayId && companion?.isShowing == true) return
        player.input.releaseAll()
        dismissCompanion()
        if (target != null) {
            val panel = object : Presentation(this, target) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val view = ComposeView(context)
                    view.setViewTreeLifecycleOwner(this@MainActivity)
                    view.setViewTreeSavedStateRegistryOwner(this@MainActivity)
                    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContentView(view)
                    view.setContent {
                        EmberTheme {
                            val state by player.state.collectAsState()
                            Surface(Modifier.fillMaxSize()) {
                                Controls(state, Modifier.fillMaxSize().systemBarsPadding())
                            }
                        }
                    }
                }
            }
            panel.setOnDismissListener {
                if (companion === panel) {
                    companion = null
                    dual = false
                    player.input.releaseAll()
                    window.decorView.post { updateCompanion() }
                }
            }
            runCatching { panel.show() }.onSuccess {
                companion = panel
                dual = true
            }
                .onFailure {
                    Log.w("EmberLilyDisplays", "Companion display ${target.displayId} rejected Presentation; keeping single-screen layout", it)
                    panel.dismiss()
                }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z -> 0
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> 1
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> 2
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> 3
            KeyEvent.KEYCODE_DPAD_RIGHT -> 4
            KeyEvent.KEYCODE_DPAD_LEFT -> 5
            KeyEvent.KEYCODE_DPAD_UP -> 6
            KeyEvent.KEYCODE_DPAD_DOWN -> 7
            KeyEvent.KEYCODE_BUTTON_R1 -> 8
            KeyEvent.KEYCODE_BUTTON_L1 -> 9
            else -> return super.dispatchKeyEvent(event)
        }
        if (!player.state.value.loaded || showLicenses) return super.dispatchKeyEvent(event)
        if (player.state.value.busy) { player.input.releaseAll(); return true }
        player.input.set("key:${event.deviceId}:${event.keyCode}", if (event.action == KeyEvent.ACTION_DOWN) 1 shl key else 0)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && player.state.value.loaded && !showLicenses) {
            if (player.state.value.busy) { player.input.releaseAll(); return true }
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var keys = 0
            if (x > .4f) keys = keys or 16 else if (x < -.4f) keys = keys or 32
            if (y > .4f) keys = keys or 128 else if (y < -.4f) keys = keys or 64
            player.input.set("stick:${event.deviceId}", keys)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!started) return
        if (!hasFocus) player.input.releaseAll() else updateCompanion()
    }
}

private class GameView(context: Context, private val buffer: FrameBuffer) : View(context) {
    private val callback: () -> Unit = { postInvalidateOnAnimation() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); buffer.invalidate = callback }
    override fun onDetachedFromWindow() { if (buffer.invalidate === callback) buffer.invalidate = null; super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) = buffer.draw(canvas, width, height)
}

@Composable private fun EmberTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFE9C184), onPrimary = Color(0xFF3C2B10))
        else lightColorScheme(primary = Color(0xFF73551D), onPrimary = Color.White)
    MaterialTheme(colorScheme = colors, content = content)
}
