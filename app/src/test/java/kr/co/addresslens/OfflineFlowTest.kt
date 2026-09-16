package kr.co.addresslens

import java.net.UnknownHostException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OfflineFlowTest {
    @Test fun detailChangesShareOneBaseAddressRequest() {
        val executor = QueuedExecutor()
        val converter = AddressConverter("", executor = executor)
        val results = mutableListOf<ConversionOutcome>()
        try {
            converter.convert("본오동 123-4 101동 1203호", results::add)
            converter.convert("본오동\n123-4 102동 1501호", results::add)
            assertEquals(1, executor.work.size)
            executor.drain()
            assertEquals(2, results.size)
        } finally { converter.close() }
    }
    private class QueuedExecutor : AbstractExecutorService() {
        val work = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); work.addLast(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = work.toMutableList().also {
            work.clear(); stopped = true
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    @Test fun offlineSelectionReturnsImmediatelyWithoutQueuedWorkOrNetwork() {
        val executor = QueuedExecutor()
        val converter = AddressConverter("test-key", executor = executor, hasInternet = { false },
            connectionFactory = { error("Offline selection must never access the network") })
        try {
            var result: ConversionOutcome? = null
            converter.convert("본오동 123-4") { result = it }
            assertEquals(ConversionOutcome.Offline, result)
            assertTrue(executor.work.isEmpty())
        } finally { converter.close() }
    }

    @Test fun offlineWithoutKeysDoesNotAskForApiSettings() {
        val converter = AddressConverter("", hasInternet = { false })
        try {
            var result: ConversionOutcome? = null
            converter.convert("본오동 123-4") { result = it }
            assertEquals(ConversionOutcome.Offline, result)
        } finally { converter.close() }
    }

    @Test fun lossBeforeQueuedRequestSkipsAllProvidersAndPreservesDeduplication() {
        val executor = QueuedExecutor()
        var online = true
        val converter = AddressConverter("vworld", "naver", "secret", "kakao", executor,
            hasInternet = { online }, connectionFactory = { error("No network call expected") })
        val results = mutableListOf<ConversionOutcome>()
        try {
            repeat(2) { converter.convert("본오동 123-4", results::add) }
            assertEquals(1, executor.work.size)
            online = false
            // A tap while an earlier request is queued must not wait for that queue.
            converter.convert("본오동 123-4", results::add)
            assertEquals(listOf(ConversionOutcome.Offline), results)
            executor.drain()
            assertEquals(List(3) { ConversionOutcome.Offline }, results)
        } finally { converter.close() }
    }

    @Test fun offlineFailureIsNotCachedAndReconnectAllowsRetry() {
        val executor = QueuedExecutor()
        var online = false
        var networkCalls = 0
        val converter = AddressConverter("test-key", executor = executor, hasInternet = { online },
            connectionFactory = { networkCalls++; throw UnknownHostException("simulated") })
        var result: ConversionOutcome? = null
        try {
            converter.convert("본오동 123-4") { result = it }
            assertEquals(ConversionOutcome.Offline, result)
            online = true
            converter.convert("본오동 123-4") { result = it }
            executor.drain()
            assertEquals(1, networkCalls)
            assertTrue(result is ConversionOutcome.NetworkError)
            converter.convert("본오동 123-4") { result = it }
            executor.drain()
            assertEquals(2, networkCalls) // Transient errors must not be cached either.
        } finally { converter.close() }
    }

    @Test fun midRequestDisconnectionDoesNotTryRemainingProviders() {
        val executor = QueuedExecutor()
        var online = true
        var networkCalls = 0
        val converter = AddressConverter("vworld", "naver", "secret", "kakao", executor,
            hasInternet = { online }, connectionFactory = {
                networkCalls++; online = false; throw UnknownHostException("simulated loss")
            })
        try {
            var result: ConversionOutcome? = null
            converter.convert("본오동 123-4") { result = it }
            executor.drain()
            assertEquals(ConversionOutcome.Offline, result)
            assertEquals(1, networkCalls)
        } finally { converter.close() }
    }

    @Test fun disconnectionAndNewSelectionRejectOldAndDuplicateResponses() {
        val requests = ConversionRequestGate()
        val original = requests.begin()
        requests.invalidate() // Network lost; local selection remains in the UI.
        assertFalse(requests.pending)
        assertFalse(requests.finish(original))
        val retry = requests.begin()
        assertFalse(requests.finish(original))
        assertTrue(requests.pending)
        assertTrue(requests.finish(retry))
        assertFalse(requests.finish(retry))
        val next = requests.begin()
        requests.invalidate() // Rescan or a partial candidate selection.
        assertFalse(requests.finish(next))
    }

    @Test fun offlineOutcomeKeepsFrozenChoiceAndAlternatives() {
        val choice = AddressCandidate("경기도 안산시 상록구 본오동 123-4", AddressKind.PARCEL)
        val alternative = AddressCandidate("경기도 안산시 단원구 초지동 716-7", AddressKind.PARCEL)
        val tracker = CandidateTracker()
        tracker.freeze(choice, listOf(alternative))
        val converter = AddressConverter("test-key", hasInternet = { false })
        try {
            converter.convert(choice.text) { assertEquals(ConversionOutcome.Offline, it) }
            assertEquals(listOf(choice, alternative), tracker.update(emptyList(), 10_000L))
            assertEquals(listOf(choice, alternative), tracker.update(listOf(alternative), 11_000L))
            assertFalse(choice.verified)
            tracker.unfreeze()
            assertEquals(listOf(alternative), tracker.update(listOf(alternative), 12_000L))
        } finally { converter.close() }
    }
}
