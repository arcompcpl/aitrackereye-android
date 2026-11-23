package pl.arcom.aitrackereye

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import pl.arcom.aitrackereye.ui.AppTheme
import pl.arcom.aitrackereye.ui.MainScreen
import pl.arcom.aitrackereye.util.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.info("Start AiTrackerEye Konfigurator ${BuildConfig.VERSION_NAME} (Android)")
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { MainScreen(vm) }
        }
    }

    override fun onStop() {
        // aplikacja w tle: zwalniamy strumień RTSP (kamera ma limit połączeń)
        vm.stopPlayer()
        super.onStop()
    }
}
