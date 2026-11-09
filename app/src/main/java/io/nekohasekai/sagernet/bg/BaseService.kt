package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.AppLogLevel
import io.nekohasekai.sagernet.AppLogLevelController
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_000L
private const val SING_BOX_CLOSE_TIMEOUT = "sing-box did not close in time"
private const val SERVICE_CLOSE_TIMEOUT_MS = 5_000L
private const val CONNECTING_CANCEL_TIMEOUT_MS = 5_000L

internal suspend fun Job.cancelAndJoinWithin(timeoutMillis: Long): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        cancelAndJoin()
        true
    } ?: false

private data class ServiceAdblockFilterUpdate(
    val url: String = "",
    val lastUpdated: String = "",
    val lastModified: String = "",
)

class BaseService {
    object LibcoreCrashType {
        const val GO_PANIC = "go_panic"
        const val GO_NIL_POINTER = "go_nil_pointer"
        const val GO_INDEX_OUT_OF_RANGE = "go_index_out_of_range"
        const val GO_CONCURRENT_MAP_WRITE = "go_concurrent_map_write"
        const val GO_STACK_OVERFLOW = "go_stack_overflow"
        const val GO_UNSAFE_MEMORY_WRITE = "go_unsafe_memory_write"
        const val NATIVE_ABORT = "native_abort"
        const val NATIVE_SIGSEGV = "native_sigsegv"
        const val NATIVE_TRAP = "native_trap"
        const val NATIVE_DOUBLE_FREE = "native_double_free"
        const val NATIVE_HEAP_CORRUPTION = "native_heap_corruption"
    }

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var networkRecoveryJob: Job? = null
        internal var overloadWatchdog: CoreOverloadWatchdog? = null
        var coreRecoveryConnection: ServiceConnection? = null
        var pendingRestart = false
        internal val urlTestTracker = UrlTestTracker()

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            service.handleConnectionRecovery(
                                reconnect = DataStore.wakeReconnect,
                                reset = DataStore.wakeResetConnections
                            )
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    service.resetCoreNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, R.string.reset_connections_notification, Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        val lifecycleMutex = Mutex()

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            val data = data ?: error("core not started")
            val box = data.proxy?.box ?: error("core not started")
            try {
                return data.urlTestTracker.track {
                    Libcore.urlTest(
                        box,
                        DataStore.connectionTestURL,
                        DataStore.connectionTestTimeout,
                        DataStore.profileTestType,
                        DataStore.connectionTestAttempts,
                        DataStore.connectionTestPause
                    )
                }
            } catch (e: Exception) {
                error(e.readableMessage)
            }
        }

        override fun currentClashMode(): String {
            val box = data?.proxy?.box ?: return ""
            return Libcore.currentClashMode(box)
        }

        override fun clashModeList(): String {
            val box = data?.proxy?.box ?: return "[]"
            return Libcore.clashModeList(box)
        }

        override fun setClashMode(mode: String) {
            val box = data?.proxy?.box ?: return
            Libcore.setClashMode(box, mode)
        }

        override fun setLogLevel(level: String, enabled: Boolean) {
            val appLevel = AppLogLevel.entries.firstOrNull { it.singBoxName == level && it.outputEnabled == enabled }
                ?: error("Unknown log level: $level")
            AppLogLevelController.set(appLevel)
            val box = data?.proxy?.box
            if (box == null) {
                Libcore.setLogLevel(level, enabled)
            } else {
                box.setLogLevel(level, enabled)
            }
        }

        override fun adblockStats(): String {
            val box = data?.proxy?.box ?: return Libcore.adblockStatsFromCache(Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            return Libcore.adblockStats(box)
        }

        override fun adblockFilterMetadata(url: String): String {
            if (url.isBlank()) return """{"title":"","description":""}"""
            val json = adblockFilterMetadataMap(url)
            return runCatching {
                val metadata = JavaUtil.gson.fromJson(json, Map::class.java)[url.trim()]
                if (metadata == null) """{"title":"","description":""}""" else JavaUtil.gson.toJson(metadata)
            }.getOrNull() ?: """{"title":"","description":""}"""
        }

        override fun adblockFilterMetadataMap(joinedUrls: String): String {
            val box = data?.proxy?.box
            return if (box != null) {
                Libcore.adblockFilterMetadataMapForInstance(box, joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockFilterMetadataMap(joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
        }

        override fun adblockStoredFilterVersion(url: String): String {
            if (url.isBlank()) return ""
            val box = data?.proxy?.box
            val json = if (box != null) {
                Libcore.adblockStoredFilterVersionsForInstance(box, url, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockStoredFilterVersions(url, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
            return runCatching {
                val versions = JavaUtil.gson.fromJson(json, Map::class.java)
                versions[url.trim()] as? String
            }.getOrNull().orEmpty()
        }

        override fun adblockStoredFilterVersions(joinedUrls: String): String {
            val box = data?.proxy?.box
            return if (box != null) {
                Libcore.adblockStoredFilterVersionsForInstance(box, joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockStoredFilterVersions(joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
        }

        override fun adblockPreCacheFilter(url: String): String {
            if (url.isBlank()) return ""
            val box = data?.proxy?.box
            val json = if (box != null) {
                Libcore.adblockPreCacheFiltersForInstance(box, url, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockPreCacheFilters(url, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
            return runCatching {
                val updates = JavaUtil.gson.fromJson(json, Array<ServiceAdblockFilterUpdate>::class.java)
                val update = updates.firstOrNull { it.url == url.trim() }
                update?.lastModified?.takeIf { it.isNotBlank() }
                    ?: update?.lastUpdated
            }.getOrNull().orEmpty()
        }

        override fun adblockPreCacheFilters(joinedUrls: String): String {
            val box = data?.proxy?.box
            return if (box != null) {
                Libcore.adblockPreCacheFiltersForInstance(box, joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockPreCacheFilters(joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
        }

        override fun adblockDeleteCachedFilter(url: String) {
            adblockDeleteCachedFilters(url)
        }

        override fun adblockDeleteCachedFilters(joinedUrls: String) {
            val box = data?.proxy?.box
            if (box != null) {
                Libcore.adblockDeleteCachedFiltersForInstance(box, joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            } else {
                Libcore.adblockDeleteCachedFilters(joinedUrls, Param.LIBCORE_ADBLOCK_DB_FILE_PATH)
            }
        }

        override fun adblockReloadEngine() {
            // Only the running box has a live engine to reload; when no box is
            // bound there is nothing to do (the next start reads the database).
            val box = data?.proxy?.box ?: return
            runCatching { Libcore.adblockReloadEngine(box) }
        }

        override fun isCoreProfilingRunning(): Boolean = Libcore.coreProfilingRunning()

        override fun hasCoreProfilerSnapshot(): Boolean = Libcore.hasCoreProfilerSnapshot()

        override fun performLibcoreGcSweep() {
            if (data?.proxy?.isInitialized() != true) {
                error("Service is not running")
            }
            Libcore.performLibcoreGCSweep()
        }

        override fun triggerLibcoreCrash(crashType: String) {
            if (data?.proxy?.isInitialized() != true) {
                error("Service is not running")
            }
            when (crashType) {
                LibcoreCrashType.GO_PANIC -> Libcore.triggerCrashGoPanic()
                LibcoreCrashType.GO_NIL_POINTER -> Libcore.triggerCrashGoNilPointer()
                LibcoreCrashType.GO_INDEX_OUT_OF_RANGE -> Libcore.triggerCrashGoIndexOutOfRange()
                LibcoreCrashType.GO_CONCURRENT_MAP_WRITE -> Libcore.triggerCrashGoConcurrentMapWrite()
                LibcoreCrashType.GO_STACK_OVERFLOW -> Libcore.triggerCrashGoStackOverflow()
                LibcoreCrashType.GO_UNSAFE_MEMORY_WRITE -> Libcore.triggerCrashGoUnsafeMemoryWrite()
                LibcoreCrashType.NATIVE_ABORT -> Libcore.triggerCrashNativeAbort()
                LibcoreCrashType.NATIVE_SIGSEGV -> Libcore.triggerCrashNativeSigsegv()
                LibcoreCrashType.NATIVE_TRAP -> Libcore.triggerCrashNativeTrap()
                LibcoreCrashType.NATIVE_DOUBLE_FREE -> Libcore.triggerCrashNativeDoubleFree()
                LibcoreCrashType.NATIVE_HEAP_CORRUPTION -> Libcore.triggerCrashNativeHeapCorruption()
                else -> error("Unknown libcore crash type: $crashType")
            }
        }

        override fun startCoreProfiling() {
            if (data?.proxy?.isInitialized() != true) {
                error("Core is not started yet")
            }
            Libcore.startCoreProfiling()
        }

        override fun stopCoreProfiling() {
            Libcore.stopCoreProfiling()
        }

        override fun writeCoreProfilerSnapshot(outputDir: String) {
            Libcore.writeCoreProfilerSnapshot(outputDir)
        }

        override fun deleteCoreProfilerSnapshot() {
            Libcore.deleteCoreProfilerSnapshot()
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun masterDnsVPNResolverProgress(found: Int, total: Int, ready: Boolean) = launch {
            broadcast { it.cbMasterDnsVPNResolverProgress(found, total, ready) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            val s = data.state
            val action = ServiceLifecyclePolicy.reloadAction(
                selectedProxy = DataStore.selectedProxy,
                stateStopped = s == State.Stopped,
                stateCanStop = s.canStop,
                stateConnected = s == State.Connected,
                stateStopping = s == State.Stopping,
                canReloadSelector = s == State.Connected && canReloadSelector(),
            )
            Logs.d("Service reload action: state=$s action=$action selectedProxy=${DataStore.selectedProxy}")
            when (action) {
                ServiceLifecyclePolicy.ReloadAction.StopEmpty -> {
                    stopRunner(false, (this as Context).getString(R.string.profile_empty))
                }
                ServiceLifecyclePolicy.ReloadAction.SelectorReload -> {
                    val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                    val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                    if (tag.isNotBlank() && ent != null) {
                        // select from GUI
                        val proxy = data.proxy!!
                        runBlocking {
                            proxy.looper?.pauseUpdates {
                                proxy.box.selectOutbound(tag)
                                resetCoreNetwork()
                            } ?: run {
                                proxy.box.selectOutbound(tag)
                                resetCoreNetwork()
                            }
                        }
                        // or select from webui
                        // => selector_OnProxySelected
                    }
                }
                ServiceLifecyclePolicy.ReloadAction.Start -> startRunner()
                ServiceLifecyclePolicy.ReloadAction.StopRestart -> stopRunner(true)
                ServiceLifecyclePolicy.ReloadAction.MarkPendingRestart -> data.pendingRestart = true
                ServiceLifecyclePolicy.ReloadAction.Ignore -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun hasActiveWifiRules(): Boolean {
            return SagerDatabase.rulesDao.enabledRules().any { RuleEntity.hasActiveWifiIdentity(it) }
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        /**
         * Some devices throttle service/native cleanup aggressively while running on battery.
         * Hold a short, local PARTIAL_WAKE_LOCK only for the disconnect/reconnect critical section
         * so VpnService/tun/core teardown is not delayed by idle CPU scheduling.
         */
        private inline fun <T> withStopWakeLock(block: () -> T): T {
            this as Context
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val stopWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VpnServiceStop"
            ).apply {
                setReferenceCounted(false)
                acquire(15_000L)
            }
            return try {
                block()
            } finally {
                if (stopWakeLock.isHeld) {
                    runCatching { stopWakeLock.release() }.onFailure { Logs.w(it) }
                }
            }
        }

        fun removeForegroundNotification() {
            this as Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }

        private fun Throwable.isNativeCloseTimeout(): Boolean {
            return readableMessage.contains(SING_BOX_CLOSE_TIMEOUT, ignoreCase = true)
        }

        private fun requestBgProcessRecovery(reason: String, restartService: Boolean) {
            if (!SagerNet.application.isBgProcess) {
                Logs.w("Skip background process recovery outside bg process: $reason")
                return
            }
            val serviceMode = when (this) {
                is VpnService -> Key.MODE_VPN
                is ProxyService -> Key.MODE_PROXY
                else -> {
                    Logs.w("Skip background process recovery for unsupported service: $reason")
                    return
                }
            }
            Logs.w(
                "Requesting background process recovery: reason=$reason " +
                    "restartService=$restartService"
            )
            CoreRecoveryReceiver.request(
                context = this as Context,
                serviceMode = serviceMode,
                reason = reason,
                restartService = restartService,
            )
        }

        suspend fun beforeRestartAfterStop() {
            delay(300)
        }

        private suspend fun stopCoreRecoveryForNativeTimeout() {
            data.overloadWatchdog?.close()
            data.overloadWatchdog = null
            data.coreRecoveryConnection?.let { connection ->
                runCatching {
                    (this as Context).unbindService(connection)
                }.onFailure { Logs.w(it) }
                data.coreRecoveryConnection = null
            }
        }

        private fun closeProxyInstance(proxy: ProxyInstance) {
            runBlocking {
                proxy.looper?.stop()
                proxy.looper = null
            }
            val profilingShutdown = Libcore.coreProfilingRunning()
            if (profilingShutdown) {
                runCatching { Libcore.prepareCoreProfilerShutdown() }.onFailure { Logs.w(it) }
            }
            runBlocking {
                proxy.syncMasqueConfigFromCache(disableRecreate = true, respectRecreate = false)
            }
            Logs.d("Closing native proxy instance")
            var closeCompleted = false
            try {
                proxy.close(SERVICE_CLOSE_TIMEOUT_MS)
                closeCompleted = true
                Logs.d("Native proxy instance closed")
            } finally {
                if (profilingShutdown) {
                    runCatching { Libcore.finishCoreProfilerShutdown(closeCompleted) }.onFailure { Logs.w(it) }
                }
            }
        }

        private fun releaseServiceResources() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runBlocking {
                DefaultNetworkListener.stop(this@Interface)
            }
        }

        fun killProcesses() {
            Logs.d("Service cleanup started")
            stopCoreRecovery()
            data.networkRecoveryJob?.cancel()
            data.networkRecoveryJob = null
            SagerNet.application.nativeInterface.unregisterWifiStateListener()
            SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(false)
            var closeError: Throwable? = null
            try {
                data.proxy?.let { proxy ->
                    closeProxyInstance(proxy)
                }
            } catch (e: Throwable) {
                closeError = e
            } finally {
                releaseServiceResources()
            }
            Logs.d("Service cleanup finished")
            closeError?.let { throw it }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            if (ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(data.state == State.Stopping, restart)) {
                data.pendingRestart = true
                return
            }
            if (data.state == State.Stopping) return
            data.notification?.destroy()
            data.notification = null
            val service = this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                val currentJob = currentCoroutineContext()[Job]
                val connectingJob = data.connectingJob
                if (connectingJob != null && connectingJob != currentJob) {
                    val connectingStopped =
                        connectingJob.cancelAndJoinWithin(CONNECTING_CANCEL_TIMEOUT_MS)
                    if (!connectingStopped) {
                        val shouldRestart = ServiceLifecyclePolicy.shouldRestartAfterRecovery(
                            restartRequested = restart,
                            pendingRestart = data.pendingRestart,
                        )
                        requestBgProcessRecovery(
                            reason = "connecting job cancellation timeout",
                            restartService = shouldRestart,
                        )
                        return@runOnMainDispatcher
                    }
                }

                var cleanupSucceeded = true
                var shouldRestart = restart
                var nativeCloseTimedOut = false
                var cleanupError: Throwable? = null
                data.lifecycleMutex.withLock {
                    // Remove the foreground notification promptly, but keep the service alive until
                    // native/VPN cleanup finishes. This makes the visible disconnect state immediate
                    // while avoiding a new start racing the old tun/core instance.
                    runCatching { removeForegroundNotification() }.onFailure { Logs.w(it) }

                    cleanupError = runCatching {
                        withContext(Dispatchers.IO) {
                            withStopWakeLock {
                                killProcesses()
                            }
                        }
                    }.exceptionOrNull()
                    cleanupError?.let { Logs.w(it) }
                    cleanupSucceeded = cleanupError == null
                    nativeCloseTimedOut = cleanupError?.isNativeCloseTimeout() == true

                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null

                    DataStore.baseService = null
                    DataStore.vpnService = null
                    shouldRestart = shouldRestart || data.pendingRestart
                    data.pendingRestart = false

                    if (nativeCloseTimedOut) {
                        stopCoreRecoveryForNativeTimeout()
                    }

                    // change the state
                    data.changeState(State.Stopped, msg ?: cleanupError?.readableMessage)
                }

                val cleanupAction = ServiceLifecyclePolicy.stopCleanupAction(
                    shouldRestart = shouldRestart,
                    cleanupSucceeded = cleanupSucceeded,
                    nativeCloseTimedOut = nativeCloseTimedOut,
                )
                Logs.d(
                    "Service stop cleanup action: action=$cleanupAction restart=$shouldRestart " +
                        "cleanupSucceeded=$cleanupSucceeded nativeCloseTimedOut=$nativeCloseTimedOut"
                )
                when (cleanupAction) {
                    ServiceLifecyclePolicy.StopCleanupAction.Restart -> {
                        beforeRestartAfterStop()
                        startRunner()
                    }
                    ServiceLifecyclePolicy.StopCleanupAction.RecoverProcess -> {
                        requestBgProcessRecovery(
                            reason = cleanupError!!.readableMessage,
                            restartService = shouldRestart,
                        )
                        stopSelf()
                    }
                    ServiceLifecyclePolicy.StopCleanupAction.Stop -> {
                        stopSelf()
                    }
                }
            }
        }

        fun resetCoreNetwork() {
            val proxy = data.proxy
            if (proxy != null && proxy.isInitialized()) {
                runCatching {
                    proxy.box.resetNetwork()
                }.onFailure {
                    Logs.w(it)
                    Libcore.resetAllConnections(true)
                }
                return
            }
            Libcore.resetAllConnections(true)
        }

        fun startCoreRecovery() {
            val overloadWatchdogEnabled = DataStore.overloadWatchdog
            val connectionGuardEnabled = DataStore.connectionGuard
            if (!overloadWatchdogEnabled && !connectionGuardEnabled) {
                stopCoreRecovery()
                return
            }
            val context = this as Context
            val serviceMode = when (this) {
                is VpnService -> Key.MODE_VPN
                is ProxyService -> Key.MODE_PROXY
                else -> return
            }

            stopCoreRecovery()
            DataStore.coreRecoveryExpectedStop = false
            runCatching {
                context.startService(
                    CoreRecoveryService.armIntent(
                        context = context,
                        serviceMode = serviceMode,
                        connectionGuard = connectionGuardEnabled,
                    )
                )
            }.onFailure { Logs.w(it) }

            if (connectionGuardEnabled) {
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                val bound = runCatching {
                    context.bindService(
                        CoreRecoveryService.bindIntent(
                            context = context,
                            serviceMode = serviceMode,
                            connectionGuard = true,
                        ),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.onFailure { Logs.w(it) }.getOrDefault(false)
                if (bound) {
                    data.coreRecoveryConnection = connection
                }
            }

            data.overloadWatchdog?.close()
            data.overloadWatchdog = null
            if (overloadWatchdogEnabled) {
                data.overloadWatchdog = CoreOverloadWatchdog {
                    CoreRecoveryReceiver.request(context, serviceMode)
                }.also { it.start() }
            }
        }

        fun stopCoreRecovery() {
            data.overloadWatchdog?.close()
            data.overloadWatchdog = null
            val context = this as? Context ?: return
            DataStore.coreRecoveryExpectedStop = true
            runCatching {
                context.startService(CoreRecoveryService.disarmIntent(context))
            }.onFailure { Logs.w(it) }
            data.coreRecoveryConnection?.let { connection ->
                runCatching {
                    context.unbindService(connection)
                }.onFailure { Logs.w(it) }
                data.coreRecoveryConnection = null
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        fun handleConnectionRecovery(reconnect: Boolean, reset: Boolean) {
            val effectiveReset = shouldResetConnections(reset, data.urlTestTracker.isRunning)
            if (reset && !effectiveReset) {
                Logs.d("Skip automatic connection reset while URL Test is running")
            }
            if (reconnect && data.state.canStop) {
                DataStore.pendingResetConnectionsAfterReconnect = effectiveReset
                stopRunner(true)
                return
            }
            if (effectiveReset) {
                resetCoreNetwork()
            }
        }

        fun isVpnNetwork(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }
            return SagerNet.connectivity.getLinkProperties(network)?.interfaceName?.startsWith("tun") == true
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            val networkChangeRecoveryPolicy = NetworkChangeRecoveryPolicy()

            fun handleNetworkUpdate(network: Network?) {
                SagerNet.underlyingNetwork = network
                SagerNet.application.nativeInterface.syncNetworkState(network)
                DataStore.vpnService?.updateUnderlyingNetwork()
                val link = network?.let { current -> SagerNet.connectivity.getLinkProperties(current) }
                val currentName = link?.interfaceName
                upstreamInterfaceName = currentName
                val decision = networkChangeRecoveryPolicy.onNetworkChanged(
                    interfaceName = currentName,
                    isVpnNetwork = isVpnNetwork(network),
                    reconnectEnabled = DataStore.networkChangeReconnect,
                    resetEnabled = DataStore.networkChangeResetConnections,
                )
                if (decision.reconnect || decision.reset) {
                    Logs.d("Network changed: ${decision.oldInterfaceName} -> ${decision.newInterfaceName}")
                    data.networkRecoveryJob?.cancel()
                    data.networkRecoveryJob = runOnDefaultDispatcher {
                        delay(NETWORK_RECOVERY_DEBOUNCE_MS)
                        if (!data.state.started) return@runOnDefaultDispatcher
                        handleConnectionRecovery(
                            reconnect = decision.reconnect,
                            reset = decision.reset
                        )
                    }
                }
                if (decision.ignoredReconnectForVpn) {
                    Logs.d(
                        "Ignore VPN network change for reconnect: " +
                            "${decision.oldInterfaceName} -> ${decision.newInterfaceName}"
                    )
                }
            }

            DefaultNetworkListener.start(this) {
                handleNetworkUpdate(it)
            }
            runCatching {
                DefaultNetworkListener.get()
            }.onSuccess { network ->
                handleNetworkUpdate(network)
            }.onFailure { error ->
                Logs.w("Unable to fetch initial default network: ${error.message}")
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (intent?.action == Action.RELOAD && data.state != State.Stopped) {
                reload()
                return Service.START_NOT_STICKY
            }
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            runOnDefaultDispatcher {
                SubscriptionUpdater.syncBootReceiverEnabled()
            }
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = runOnDefaultDispatcher {
                data.lifecycleMutex.withLock {
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            data.notification = createNotification(ServiceNotification.genTitle(profile))
                        }

                        Executable.killAll()    // clean up old processes
                        val hasActiveWifiRules = hasActiveWifiRules()
                        SagerNet.application.nativeInterface.setWifiRuleMonitoringEnabled(hasActiveWifiRules)
                        SagerNet.application.nativeInterface.unregisterWifiStateListener()
                        preInit()
                        proxy.init()
                        proxy.configNormalizationViolations
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("\n")
                            ?.let { data.changeState(State.Connecting, it) }
                        if (hasActiveWifiRules) {
                            SagerNet.application.nativeInterface.registerWifiStateListener()
                            SagerNet.application.nativeInterface.notifyWifiStateChanged("post-init")
                        }
                        DataStore.currentProfile = profile.id

                        proxy.processes = GuardedProcessPool {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }

                        startProcesses()
                        if (DataStore.enableCoreProfiling) {
                            Libcore.startCoreProfiling()
                        }
                        data.changeState(State.Connected)
                        CoreRecoveryService.updateStopWatchdog(
                            context = this@Interface as Context,
                            serviceMode = DataStore.serviceMode,
                            connectionIntent = ServiceLifecyclePolicy.ConnectionIntent.Connected,
                        )
                        startCoreRecovery()
                        SagerNet.application.nativeInterface.maybeShowRedactedWifiToastOnConnect()

                        lateInit()
                        if (DataStore.pendingResetConnectionsAfterReconnect) {
                            DataStore.pendingResetConnectionsAfterReconnect = false
                            resetCoreNetwork()
                        }
                    } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                    } catch (_: UnknownHostException) {
                        stopRunner(false, getString(R.string.invalid_server))
                    } catch (e: PluginManager.PluginNotFoundException) {
                        withContext(Dispatchers.Main.immediate) {
                            Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                        }
                        Logs.w(e)
                        data.binder.missingPlugin(e.plugin)
                        stopRunner(false, null)
                    } catch (exc: Throwable) {
                        if (exc.readableMessage.contains("no working DNS resolvers found", ignoreCase = true)) {
                            withContext(Dispatchers.Main.immediate) {
                                Toast.makeText(
                                    this@Interface,
                                    R.string.masterdnsvpn_no_working_dns,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            stopRunner(false, null)
                            return@withLock
                        }
                        if (exc.javaClass.name.endsWith("proxyerror")) {
                            // error from golang
                            Logs.w(exc.readableMessage)
                        } else {
                            Logs.w(exc)
                        }
                        stopRunner(
                            false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                        )
                    } finally {
                        data.connectingJob = null
                    }
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
