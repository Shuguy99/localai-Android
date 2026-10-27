package dev.localai.agent

import android.app.Application
import android.content.Context
import dev.localai.agent.agent.AgentRuntime
import dev.localai.agent.container.ContainerManager
import dev.localai.agent.data.chat.ChatStore
import dev.localai.agent.data.hf.HuggingFaceApi
import dev.localai.agent.data.model.MemoryGuard
import dev.localai.agent.data.model.ModelDownloader
import dev.localai.agent.data.model.ModelStore
import dev.localai.agent.data.settings.SecretStore
import dev.localai.agent.data.settings.SettingsRepository
import dev.localai.agent.inference.LlamaEngine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings = SettingsRepository(appContext)
    val secrets = SecretStore(appContext)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val hf = HuggingFaceApi(http) { secrets.token() }
    val modelStore = ModelStore(appContext)
    val downloader = ModelDownloader(http, hf, modelStore)
    val memoryGuard = MemoryGuard(appContext)
    val chatStore = ChatStore(java.io.File(appContext.filesDir, "chats"))

    val engine = LlamaEngine()
    val container = ContainerManager(appContext).also { it.refresh() }
    val snapshots = dev.localai.agent.container.SnapshotStore(container)
    val runtime = AgentRuntime(engine, container, settings)
}

class LocalAiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
