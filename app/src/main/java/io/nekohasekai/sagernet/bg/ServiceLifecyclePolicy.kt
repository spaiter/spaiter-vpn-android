package io.nekohasekai.sagernet.bg

internal object ServiceLifecyclePolicy {
    enum class ConnectionIntent {
        Start,
        Reload,
        Disconnect,
        Connected,
    }

    enum class StopWatchdogAction {
        Arm,
        Cancel,
    }

    enum class ReloadAction {
        StopEmpty,
        SelectorReload,
        Start,
        StopRestart,
        MarkPendingRestart,
        Ignore,
    }

    enum class StopCleanupAction {
        Stop,
        Restart,
        RecoverProcess,
    }

    fun stopWatchdogAction(connectionIntent: ConnectionIntent): StopWatchdogAction {
        return when (connectionIntent) {
            ConnectionIntent.Disconnect -> StopWatchdogAction.Arm
            ConnectionIntent.Start,
            ConnectionIntent.Reload,
            ConnectionIntent.Connected -> StopWatchdogAction.Cancel
        }
    }

    fun reloadAction(
        selectedProxy: Long,
        stateStopped: Boolean,
        stateCanStop: Boolean,
        stateConnected: Boolean,
        stateStopping: Boolean,
        canReloadSelector: Boolean,
    ): ReloadAction {
        if (selectedProxy == 0L) return ReloadAction.StopEmpty
        if (stateConnected && canReloadSelector) return ReloadAction.SelectorReload
        return when {
            stateStopped -> ReloadAction.Start
            stateCanStop -> ReloadAction.StopRestart
            stateStopping -> ReloadAction.MarkPendingRestart
            else -> ReloadAction.Ignore
        }
    }

    fun shouldPreserveRestartOnDuplicateStop(stateStopping: Boolean, restart: Boolean): Boolean {
        return stateStopping && restart
    }

    fun shouldRestartAfterRecovery(restartRequested: Boolean, pendingRestart: Boolean): Boolean {
        return restartRequested || pendingRestart
    }

    fun stopCleanupAction(
        shouldRestart: Boolean,
        cleanupSucceeded: Boolean,
        nativeCloseTimedOut: Boolean,
    ): StopCleanupAction {
        if (nativeCloseTimedOut) return StopCleanupAction.RecoverProcess
        return if (shouldRestart) {
            StopCleanupAction.Restart
        } else {
            StopCleanupAction.Stop
        }
    }
}
