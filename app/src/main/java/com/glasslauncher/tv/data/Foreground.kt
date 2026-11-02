package com.glasslauncher.tv.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Waits until Frost-flix is in front (this flow is true), then runs [block]. If Frost-flix goes to the
 * background before [block] is done, [block] is cancelled. Returns true when [block] finished, false when it
 * was cancelled (the caller then runs it again on the next return to the front).
 */
suspend fun StateFlow<Boolean>.runInFront(block: suspend () -> Unit): Boolean = coroutineScope {
    first { it }
    val work = launch { block() }
    val watch = launch { first { !it }; work.cancel() }
    work.join()
    watch.cancel()
    !work.isCancelled
}
