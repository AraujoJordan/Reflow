package io.github.araujojordan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertTrue(stateFlow.first().isLoading)
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
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetched", result.getOrNull())
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
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertTrue(result.isFailure)
        assertEquals(
            expected = "Something went wrong",
            actual = result.exceptionOrNull()?.message,
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
        repeat(MAX_RETRIES) {
            assertTrue(stateFlow.first().isLoading)
            advanceTimeBy(RETRY_DELAY)
        }
        assertTrue(stateFlow.first().isFailure)
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
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(3L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 1", result.getOrNull())
        reflow.refresh()
        advanceTimeBy(1L)

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(3L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 2", result.getOrNull())
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
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(3L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 1", result.getOrNull())

        // When
        reflow.refresh()

        // Then
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 1", result.getOrNull())
        advanceTimeBy(1L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 1", result.getOrNull())
        advanceTimeBy(2L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetch number 2", result.getOrNull())
    }

    @Test
    fun `should handle cache-then-network pattern with flow merge`() = runTest {
        // Given
        var cacheValue: String? = null
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = flow {
                delay(500L)
                val fetched = "Fetched"
                cacheValue = fetched
                emit(fetched)
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertNull(cacheValue)
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetched", result.getOrNull())
        assertEquals("Fetched", cacheValue)
    }

    @Test
    fun `should emit cached data first in cache-then-network pattern`() = runTest {
        // Given
        val cacheFlow = MutableStateFlow("Cached")
        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            fetchFlow = kotlinx.coroutines.flow.merge(
                cacheFlow,
                flow {
                    delay(500L)
                    emit("Fetched")
                }
            )
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(1L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull())
        advanceTimeBy(500L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals("Fetched", result.getOrNull())
    }

    @Serializable
    data class TestData(val id: Int, val name: String)

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `should load data from Disk cache`() = runTest {
        // Given
        val testData = TestData(1, "Cached Name")
        val cacheName = "test_cache_load_${kotlin.random.Random.nextInt()}"
        val dataStore = createDatastore { "${cacheName}.preferences_pb" }
        val diskCache = CacheSource.Disk("test_cache", TestData.serializer(), dataStore)
        diskCache.store(testData)

        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSource = diskCache,
            fetchFlow = flow {
                delay(500L)
                emit(TestData(1, "Fetched Name"))
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        // Wait for cached value
        stateFlow.first { it.isSuccess }
        assertEquals(testData, stateFlow.value.getOrNull())

        advanceTimeBy(501L)
        // Wait for fetched value
        stateFlow.first { it.isSuccess && it.getOrNull()?.name == "Fetched Name" }
        assertEquals("Fetched Name", stateFlow.value.getOrNull()?.name)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `should save data to Disk cache on fetch`() = runTest {
        // Given
        val cacheName = "test_cache_${kotlin.random.Random.nextInt()}"
        val dataStore = createDatastore { "${cacheName}.preferences_pb" }
        val diskCache = CacheSource.Disk("test_cache", TestData.serializer(), dataStore)
        val fetchedData = TestData(2, "Fetched from Network")

        val reflow = reflowIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSource = diskCache,
            fetchFlow = flow {
                delay(500L)
                emit(fetchedData)
            }
        )

        // When
        reflow.stateFlow.first { it.isSuccess && it.getOrNull() == fetchedData }

        // Then
        assertEquals(fetchedData, diskCache.data.filterNotNull().first())
    }
}