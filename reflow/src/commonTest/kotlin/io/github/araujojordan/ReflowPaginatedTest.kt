package io.github.araujojordan

import io.github.araujojordan.cache.CacheSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReflowPaginatedTest {

    @Test
    fun `start as loading by default`() = runTest {
        // Given
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 10),
        ) {
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
    }

    @Test
    fun `should emit Loading then Success with items`() = runTest {
        // Given
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 10),
        ) {
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(listOf("Item 1", "Item 2"), result.getOrNull()?.items)
    }

    @Test
    fun `should fail when unknown exception is thrown`() = runTest {
        // Given
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 10),
        ) {
            delay(500L)
            error("Something went wrong")
        }

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
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 10),
        ) {
            throw IOException("Bad network")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(RETRY_DELAY * MAX_RETRIES + 100L)
        assertTrue(stateFlow.first().isFailure)
    }

    @Test
    fun `should load more pages`() = runTest {
        // Given
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 2),
        ) { pageKey ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as Page.Number).number
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.items?.size)
        assertEquals(1, pagesFetched)

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrNull()?.items?.size)
        assertEquals(2, pagesFetched)
        assertEquals("Page 1 Item 1", result.getOrNull()?.items?.get(0))
        assertEquals("Page 2 Item 1", result.getOrNull()?.items?.get(2))
    }

    @Test
    fun `should detect end of pagination when less than pageSize items returned`() = runTest {
        // Given
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 10),
        ) {
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()?.hasMorePages ?: true)
        assertFalse(reflow.hasMorePages)
    }

    @Test
    fun `should have more pages when pageSize items returned`() = runTest {
        // Given
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 2),
        ) {
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.hasMorePages ?: false)
        assertTrue(reflow.hasMorePages)
    }

    @Test
    fun `should refresh and reset pagination`() = runTest {
        // Given
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 2),
            shouldLoadingOnRefresh = true,
        ) { pageKey ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as Page.Number).number
            listOf("Fetch $pagesFetched Page $page Item 1", "Fetch $pagesFetched Page $page Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.items?.size)
        assertEquals("Fetch 1 Page 1 Item 1", result.getOrNull()?.items?.get(0))

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrNull()?.items?.size)

        reflow.refresh()
        advanceTimeBy(1L)
        assertTrue(stateFlow.first().isLoading)

        advanceTimeBy(100L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.items?.size)
        assertEquals("Fetch 3 Page 1 Item 1", result.getOrNull()?.items?.get(0))
    }

    @Test
    fun `should show isLoadingMore is false after loading additional pages`() = runTest {
        // Given
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(number = 1, pageSize = 2),
        ) { pageKey ->
            delay(100L)
            val page = (pageKey as Page.Number).number
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        // When
        val stateFlow = reflow.stateFlow

        // Then
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()?.isLoadingMore ?: true)

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()?.isLoadingMore ?: true)
        assertEquals(4, result.getOrNull()?.items?.size)
    }

    @Serializable
    data class TestData(val id: Int, val name: String)

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `should load data from Disk cache for paginated reflow`() = runTest {
        // Given
        val cachedItems = listOf(TestData(1, "Cached 1"), TestData(2, "Cached 2"))
        val diskCache = CacheSource.Disk("test_cache", serializer<List<TestData>>())
        diskCache.store(cachedItems)

        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSource = diskCache,
            initialPage = Page.Number(number = 1, pageSize = 2),
            fetch = {
                delay(500L)
                listOf(TestData(3, "Fetched 3"), TestData(4, "Fetched 4"))
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        // Wait for cached value
        var result =
            stateFlow.first { it.isSuccess && it.getOrNull()?.items?.any { item -> item.name.contains("Cached") } == true }
        assertEquals(cachedItems, result.getOrNull()?.items)

        advanceTimeBy(501L)

        result =
            stateFlow.first { it.isSuccess && it.getOrNull()?.items?.any { item -> item.name.contains("Fetched") } == true }
        assertEquals(2, result.getOrNull()?.items?.size)
        assertEquals("Fetched 3", result.getOrNull()?.items?.get(0)?.name)
    }

    @Test
    fun `should load data from Memory cache for paginated reflow`() = runTest {
        // Given
        val cachedItems = listOf("Cached 1", "Cached 2")
        val memoryCache = CacheSource.Memory<List<String>>("test_paginated_memory")
        memoryCache.clear()
        memoryCache.store(cachedItems)

        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSource = memoryCache,
            initialPage = Page.Number(number = 1, pageSize = 2),
            fetch = {
                delay(500L)
                listOf("Fetched 3", "Fetched 4")
            }
        )

        // When
        val stateFlow = reflow.stateFlow

        // Then
        // Wait for cached value
        var result = stateFlow.first { it.isSuccess && it.getOrNull()?.items?.any { it.contains("Cached") } == true }
        assertEquals(cachedItems, result.getOrNull()?.items)

        advanceTimeBy(501L)

        result = stateFlow.first { it.isSuccess && it.getOrNull()?.items?.any { it.contains("Fetched") } == true }
        assertEquals(4, result.getOrNull()?.items?.size)
        assertEquals(result.getOrNull()?.items?.any { it.contains("Cached") }, true)
        assertEquals(result.getOrNull()?.items?.any { it.contains("Fetched") }, true)
    }

    @Test
    fun `should store fetched data in Memory cache for paginated reflow`() = runTest {
        // Given
        val memoryCache = CacheSource.Memory<List<String>>("test_paginated_memory_store")
        memoryCache.clear()

        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            cacheSource = memoryCache,
            initialPage = Page.Number(number = 1, pageSize = 2),
            fetch = {
                delay(500L)
                listOf("Fetched 1", "Fetched 2")
            }
        )

        // When
        val stateFlow = reflow.stateFlow
        advanceTimeBy(501L)

        // Then
        val result = stateFlow.first { it.isSuccess && it.getOrNull()?.items?.any { it.contains("Fetched") } == true }
        assertEquals(listOf("Fetched 1", "Fetched 2"), result.getOrNull()?.items)

        advanceUntilIdle()

        // Verify it was stored
        val cachedValue = memoryCache.data.first()
        assertEquals(listOf("Fetched 1", "Fetched 2"), cachedValue)
    }

}
