package app.oralis

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import app.oralis.data.db.OralisDatabase
import app.oralis.data.files.ExportManager
import app.oralis.data.settings.SettingsRepository
import app.oralis.generation.GenerationRepository
import app.oralis.model.ModelRepository
import app.oralis.notifications.Notifications
import app.oralis.playback.PlayerConnection
import app.oralis.playback.SleepTimer
import app.oralis.tts.EngineHolder
import app.oralis.tts.VoicePreviewPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container: the graph is small, so no DI framework is needed. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = OralisDatabase.build(app)
    val settings = SettingsRepository(app)
    val modelRepository = ModelRepository(app, scope)
    val engineHolder = EngineHolder(app, modelRepository, settings, scope)
    val generationRepository = GenerationRepository(app, database.audioDao())
    val previewPlayer = VoicePreviewPlayer(engineHolder, File(app.cacheDir, "previews")) { generationRepository.live.value != null }
    val sleepTimer = SleepTimer()
    val playerConnection = PlayerConnection(app, database.audioDao(), settings, scope)
    val exportManager = ExportManager(app, database.audioDao())
}

class OralisApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            runCatching { container.generationRepository.reconcile() }
                .onFailure { Log.w("Oralis", "reconcile failed", it) }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()
}
