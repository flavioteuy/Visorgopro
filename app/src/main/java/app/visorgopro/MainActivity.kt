package app.visorgopro

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.visorgopro.ui.AppTheme
import app.visorgopro.ui.Bg
import app.visorgopro.ui.BluetoothScreen
import app.visorgopro.ui.ConnectScreen
import app.visorgopro.ui.DashboardScreen
import app.visorgopro.ui.GalleryScreen
import app.visorgopro.ui.LiveScreen
import app.visorgopro.ui.PhotoScreen
import app.visorgopro.ui.SettingsScreen
import app.visorgopro.ui.VideoScreen

class MainActivity : ComponentActivity() {

    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().background(Bg)) {
                    BackHandler(enabled = vm.screen != Screen.Connect) { vm.back() }
                    when (val s = vm.screen) {
                        Screen.Connect -> ConnectScreen(vm)
                        Screen.Bluetooth -> BluetoothScreen(vm)
                        Screen.Dashboard -> DashboardScreen(vm)
                        Screen.Live -> LiveScreen(vm)
                        Screen.Gallery -> GalleryScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.Video -> VideoScreen(vm, s.item)
                        is Screen.Photo -> PhotoScreen(vm, s.item)
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            vm.menuTick++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
