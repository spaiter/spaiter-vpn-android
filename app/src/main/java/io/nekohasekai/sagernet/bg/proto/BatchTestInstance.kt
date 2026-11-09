package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One core instance for a whole group URL test. Every profile is built into the
 * same config and tested by its outbound tag, instead of starting (and tearing
 * down) a core per node as [TestInstance] does. Fewer wake-ups, far less CPU on
 * groups with dozens of nodes.
 */
class BatchTestInstance(
    private val profiles: List<ProxyEntity>,
    private val link: String,
    private val timeout: Int,
    private val attempts: Int,
    private val pause: Int,
) : BoxInstance(profiles.first()) {

    override val syncMasqueCache = false

    /** profile id -> outbound tag inside this instance */
    val tagMap: Map<Long, String>
        get() = config.profileTagMap

    /** Builds the config and starts the core (and any plugin processes). Call once. */
    // Cancellable so the caller's timeout can give up on a core whose start hangs.
    suspend fun prepare() = suspendCancellableCoroutine { c ->
        val done = AtomicBoolean(false)
        fun finish(e: Throwable?) {
            if (!done.compareAndSet(false, true)) return
            if (e == null) c.resume(Unit) else c.resumeWithException(e)
        }
        processes = GuardedProcessPool {
            Logs.w(it)
            finish(it)
        }
        runOnDefaultDispatcher {
            try {
                init()
                launch()
                if (processes.processCount > 0) {
                    // wait for plugin start
                    delay(500)
                }
                finish(null)
            } catch (e: Exception) {
                finish(e)
            }
        }
    }

    /** Tests one node of the running instance; returns RTT in ms, throws on failure. */
    fun testTag(tag: String): Int = box.urlTestTag(tag, link, timeout, attempts, pause)

    override fun buildConfig() {
        config = buildConfig(profile, forTest = true, testProfiles = profiles)
    }

    override suspend fun loadConfig() {
        if (BuildConfig.DEBUG) Logs.d(config.config)
        // Holds the socket-protect server for the instance's lifetime: without it
        // every dial from the app process fails with "failed to connect: no such
        // file or directory" while the VPN service is not running.
        box = Libcore.newSingBoxInstanceForURLTest(config.config, LocalResolverImpl)
    }
}
