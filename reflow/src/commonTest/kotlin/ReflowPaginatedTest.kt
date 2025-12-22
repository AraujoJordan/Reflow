package com.araujojordan.reflow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReflowPaginatedTest {

    @Test
    fun `start as loading by default`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 10),
        ) {
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
    }

    @Test
    fun `should emit Loading then Success with items`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 10),
        ) {
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(listOf("Item 1", "Item 2"), result.getOrNull()?.items)
    }

    @Test
    fun `should fail when unknown exception is thrown`() = runTest {
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 10),
        ) {
            delay(500L)
            error("Something went wrong")
        }

        val stateFlow = reflow.stateFlow

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
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 10),
        ) {
            throw IOException("Bad network")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(Reflow.RETRY_DELAY * Reflow.MAX_RETRIES + 100L)
        assertTrue(stateFlow.first().isFailure)
    }

    @Test
    fun `should load more pages`() = runTest {
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
        ) { pageKey ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as Page.Number).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

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
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 10),
        ) {
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()?.hasMorePages ?: true)
        assertFalse(reflow.hasMorePages)
    }

    @Test
    fun `should have more pages when pageSize items returned`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
        ) {
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.hasMorePages ?: false)
        assertTrue(reflow.hasMorePages)
    }

    @Test
    fun `should refresh and reset pagination`() = runTest {
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
            shouldLoadingOnRefresh = true,
        ) { pageKey ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as Page.Number).value
            listOf("Fetch $pagesFetched Page $page Item 1", "Fetch $pagesFetched Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

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
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
        ) { pageKey ->
            delay(100L)
            val page = (pageKey as Page.Number).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

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

    @Test
    fun `should save cache when FetchPolicy is CacheAndNetwork`() = runTest {
        var cachedItems: List<String>? = null
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
            fetchPolicy = FetchPolicy.CacheAndNetwork(
                onStore = { items -> cachedItems = items },
                onRetrieve = suspend { cachedItems ?: emptyList() },
            ),
        ) { pageKey ->
            delay(100L)
            val page = (pageKey as Page.Number).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(cachedItems == null)
        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(102L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        assertEquals(listOf("Page 1 Item 1", "Page 1 Item 2"), cachedItems)
        assertEquals(listOf("Page 1 Item 1", "Page 1 Item 2"), result.getOrNull()?.items)
    }

    @Test
    fun `should retrieve from cache and replace with network data when FetchPolicy is CacheAndNetwork`() = runTest {
        var cachedItems: List<String>? = listOf("Cached Item 1", "Cached Item 2")
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            initialPage = Page.Number(value = 1, pageSize = 2),
            fetchPolicy = FetchPolicy.CacheAndNetwork(
                onStore = { items -> cachedItems = items },
                onRetrieve = suspend { cachedItems ?: emptyList() },
            ),
        ) { pageKey ->
            delay(100L)
            val page = (pageKey as Page.Number).value
            listOf("Fetched Page $page Item 1", "Fetched Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(stateFlow.first().isLoading)
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertTrue(result.isSuccess)
        // Network data should replace cached data
        assertEquals(listOf("Fetched Page 1 Item 1", "Fetched Page 1 Item 2"), result.getOrNull()?.items)
        assertEquals(listOf("Fetched Page 1 Item 1", "Fetched Page 1 Item 2"), cachedItems)
        assertFalse(result.getOrNull()?.isLoadingMore ?: true)
    }
}
