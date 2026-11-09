package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun disconnectArmsStopWatchdog() {
        assertEquals(
            ServiceLifecyclePolicy.StopWatchdogAction.Arm,
            ServiceLifecyclePolicy.stopWatchdogAction(
                ServiceLifecyclePolicy.ConnectionIntent.Disconnect
            )
        )
    }

    @Test
    fun connectionRequestsCancelStopWatchdog() {
        listOf(
            ServiceLifecyclePolicy.ConnectionIntent.Start,
            ServiceLifecyclePolicy.ConnectionIntent.Reload,
            ServiceLifecyclePolicy.ConnectionIntent.Connected,
        ).forEach { connectionIntent ->
            assertEquals(
                ServiceLifecyclePolicy.StopWatchdogAction.Cancel,
                ServiceLifecyclePolicy.stopWatchdogAction(connectionIntent)
            )
        }
    }

    @Test
    fun reloadConnectedProfileUsesRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = false,
            )
        )
    }

    @Test
    fun reloadStoppingProfileMarksPendingRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.MarkPendingRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = false,
                stateConnected = false,
                stateStopping = true,
                canReloadSelector = false,
            )
        )
    }

    @Test
    fun reloadEmptyProfileStopsWithoutRestart() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopEmpty,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 0L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = true,
            )
        )
    }

    @Test
    fun selectorReloadRequiresConnectedState() {
        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.SelectorReload,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = true,
                stateStopping = false,
                canReloadSelector = true,
            )
        )

        assertEquals(
            ServiceLifecyclePolicy.ReloadAction.StopRestart,
            ServiceLifecyclePolicy.reloadAction(
                selectedProxy = 1L,
                stateStopped = false,
                stateCanStop = true,
                stateConnected = false,
                stateStopping = false,
                canReloadSelector = true,
            )
        )
    }

    @Test
    fun duplicateRestartStopWhileStoppingIsPreserved() {
        assertTrue(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = true, restart = true))
        assertFalse(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = true, restart = false))
        assertFalse(ServiceLifecyclePolicy.shouldPreserveRestartOnDuplicateStop(stateStopping = false, restart = true))
    }

    @Test
    fun explicitStopRecoveryStaysDisconnected() {
        assertFalse(
            ServiceLifecyclePolicy.shouldRestartAfterRecovery(
                restartRequested = false,
                pendingRestart = false,
            )
        )
    }

    @Test
    fun pendingProfileChangeRestartsAfterRecovery() {
        assertTrue(
            ServiceLifecyclePolicy.shouldRestartAfterRecovery(
                restartRequested = false,
                pendingRestart = true,
            )
        )
    }

    @Test
    fun nativeCloseTimeoutUsesProcessRecovery() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.RecoverProcess,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = false,
                nativeCloseTimedOut = true,
            )
        )
    }

    @Test
    fun successfulRestartUsesSameProcessRestart() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.Restart,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = true,
                nativeCloseTimedOut = false,
            )
        )
    }

    @Test
    fun nonTimeoutCleanupErrorKeepsRequestedRestart() {
        assertEquals(
            ServiceLifecyclePolicy.StopCleanupAction.Restart,
            ServiceLifecyclePolicy.stopCleanupAction(
                shouldRestart = true,
                cleanupSucceeded = false,
                nativeCloseTimedOut = false,
            )
        )
    }
}
