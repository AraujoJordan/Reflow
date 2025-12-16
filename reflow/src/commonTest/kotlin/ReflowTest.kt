package com.araujojordan.reflow

import fr.haan.resultat.Resultat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ReflowTest {

    @Test
    fun `start as loading by default`() = runTest {
        // Given
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow {
                delay(500L)
                emit("Fetched")
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertIs<Resultat.Loading>(stateFlow.first())
    }

    @Test
    fun `should emit Loading then Success`() = runTest {
        // Given
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow {
                delay(500L)
                emit("Fetched")
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(501L)
        assertEquals(Resultat.success("Fetched"), stateFlow.first())
    }

    @Test
    fun `should fail when unknown exception is thrown`() = runTest {
        // Given
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow<String> {
                delay(500L)
                error("Something went wrong")
            },
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(501L)
        assertEquals(
            expected = "Something went wrong",
            actual = (stateFlow.first() as Resultat.Failure).exception.message,
        )
    }

    @Test
    fun `should retry MAX_RETRIES times on bad network`() = runTest {
        // Given
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow<String> {
                throw IOException("Bad network")
            },
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        repeat(Reflow.MAX_RETRIES) {
            assertIs<Resultat.Loading>(stateFlow.first())
            advanceTimeBy(Reflow.RETRY_DELAY)
        }
        assertIs<Resultat.Failure>(stateFlow.first())
    }

    @Test
    fun `should refresh with loading on retry`() = runTest {
        // Given
        var fetches = 0
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow {
                delay(2L)
                emit("Fetch number ${++fetches}")
            },
        )
        val stateFlow = reflow.stateFlow

        // When
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(3L)
        assertEquals(Resultat.success("Fetch number 1"), stateFlow.first())
        reflow.refresh()
        advanceTimeBy(1L)

        // Then
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(3L)
        assertEquals(Resultat.success("Fetch number 2"), stateFlow.first())
    }

    @Test
    fun `should refresh without loading when shouldLoadingOnRefresh = false`() = runTest {
        // Given
        var fetches = 0
        val reflow = reflowIn(
            scope = backgroundScope,
            shouldLoadingOnRefresh = false,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow {
                delay(2L)
                emit("Fetch number ${++fetches}")
            },
        )
        val stateFlow = reflow.stateFlow
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(3L)
        assertEquals(Resultat.success("Fetch number 1"), stateFlow.first())

        // When
        reflow.refresh()

        // Then
        assertEquals(Resultat.success("Fetch number 1"), stateFlow.first())
        advanceTimeBy(1L)
        assertEquals(Resultat.success("Fetch number 1"), stateFlow.first())
        advanceTimeBy(2L)
        assertEquals(Resultat.success("Fetch number 2"), stateFlow.first())
    }

    @Test
    fun `should save cache when FetchPolicy is CacheAndNetwork`() = runTest {
        // Given
        var cacheValue: String? = null
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchPolicy = FetchPolicy.CacheAndNetwork(
                onStore = { cacheValue = it },
                onRetrieve = { cacheValue },
            ),
            fetchFlow = flow {
                delay(500L)
                emit("Fetched")
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertNull(cacheValue)
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(501L)
        assertEquals(Resultat.success("Fetched"), stateFlow.first())
        assertEquals("Fetched", cacheValue)
    }

    @Test
    fun `should retrieve from cache when FetchPolicy is CacheAndNetwork`() = runTest {
        // Given
        val cacheValue = MutableStateFlow("Cached")
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchPolicy = FetchPolicy.CacheAndNetwork(
                onStore = { cacheValue.value = it },
                onRetrieve = cacheValue,
            ),
            fetchFlow = flow {
                delay(500L)
                emit("Fetched")
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(1L)
        assertEquals(Resultat.success("Cached"), stateFlow.first())
        advanceTimeBy(500L)
        assertEquals(Resultat.success("Fetched"), stateFlow.first())
        assertEquals("Fetched", cacheValue.value)
    }
}