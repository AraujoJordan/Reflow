package com.araujojordan.reflow

import fr.haan.resultat.Resultat
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReflowPaginatedTest {

    @Test
    fun `start as loading by default`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 10,
        ) { _, _ ->
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
    }

    @Test
    fun `should emit Loading then Success with items`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 10,
        ) { _, _ ->
            delay(500L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(501L)
        val result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(listOf("Item 1", "Item 2"), result.value.items)
    }

    @Test
    fun `should fail when unknown exception is thrown`() = runTest {
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 10,
        ) { _, _ ->
            delay(500L)
            error("Something went wrong")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(501L)
        assertEquals(
            expected = "Something went wrong",
            actual = (stateFlow.first() as Resultat.Failure).exception.message,
        )
    }

    @Test
    fun `should retry MAX_RETRIES times on bad network`() = runTest {
        val reflow = reflowPaginatedIn<String>(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 10,
        ) { _, _ ->
            throw IOException("Bad network")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(Reflow.RETRY_DELAY * Reflow.MAX_RETRIES + 100L)
        assertIs<Resultat.Failure>(stateFlow.first())
    }

    @Test
    fun `should load more pages`() = runTest {
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
        ) { pageKey, _ ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as PaginationKey.Page).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(2, result.value.items.size)
        assertEquals(1, pagesFetched)

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(4, result.value.items.size)
        assertEquals(2, pagesFetched)
        assertEquals("Page 1 Item 1", result.value.items[0])
        assertEquals("Page 2 Item 1", result.value.items[2])
    }

    @Test
    fun `should detect end of pagination when less than pageSize items returned`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 10,
        ) { _, _ ->
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertFalse(result.value.hasMorePages)
        assertFalse(reflow.hasMorePages)
    }

    @Test
    fun `should have more pages when pageSize items returned`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
        ) { _, _ ->
            delay(100L)
            listOf("Item 1", "Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertTrue(result.value.hasMorePages)
        assertTrue(reflow.hasMorePages)
    }

    @Test
    fun `should refresh and reset pagination`() = runTest {
        var pagesFetched = 0
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
            shouldLoadingOnRefresh = true,
        ) { pageKey, _ ->
            delay(100L)
            pagesFetched++
            val page = (pageKey as PaginationKey.Page).value
            listOf("Fetch $pagesFetched Page $page Item 1", "Fetch $pagesFetched Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(2, result.value.items.size)
        assertEquals("Fetch 1 Page 1 Item 1", result.value.items[0])

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(4, result.value.items.size)

        reflow.refresh()
        advanceTimeBy(1L)
        assertIs<Resultat.Loading>(stateFlow.first())

        advanceTimeBy(100L)
        result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(2, result.value.items.size)
        assertEquals("Fetch 3 Page 1 Item 1", result.value.items[0])
    }

    @Test
    fun `should show isLoadingMore is false after loading additional pages`() = runTest {
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
        ) { pageKey, _ ->
            delay(100L)
            val page = (pageKey as PaginationKey.Page).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        var result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertFalse(result.value.isLoadingMore)

        reflow.loadMore()
        advanceTimeBy(101L)
        result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertFalse(result.value.isLoadingMore)
        assertEquals(4, result.value.items.size)
    }

    @Test
    fun `should save cache per page when FetchPolicy is CacheAndNetwork`() = runTest {
        val cache = mutableMapOf<PaginationKey, List<String>>()
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
            fetchPolicy = PaginatedFetchPolicy.CacheAndNetwork(
                onStore = { key, items -> cache[key] = items },
                onRetrieve = { key -> cache[key] },
            ),
        ) { pageKey, _ ->
            delay(100L)
            val page = (pageKey as PaginationKey.Page).value
            listOf("Page $page Item 1", "Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertTrue(cache.isEmpty())
        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(102L)
        val result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(1, cache.size)
        assertEquals(listOf("Page 1 Item 1", "Page 1 Item 2"), cache[PaginationKey.Page(1)])
    }

    @Test
    fun `should retrieve from cache per page when FetchPolicy is CacheAndNetwork`() = runTest {
        val cache = mutableMapOf<PaginationKey, List<String>>(
            PaginationKey.Page(1) to listOf("Cached Item 1", "Cached Item 2")
        )
        val reflow = reflowPaginatedIn(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            pageSize = 2,
            fetchPolicy = PaginatedFetchPolicy.CacheAndNetwork(
                onStore = { key, items -> cache[key] = items },
                onRetrieve = { key -> cache[key] },
            ),
        ) { pageKey, _ ->
            delay(100L)
            val page = (pageKey as PaginationKey.Page).value
            listOf("Fetched Page $page Item 1", "Fetched Page $page Item 2")
        }

        val stateFlow = reflow.stateFlow

        assertIs<Resultat.Loading>(stateFlow.first())
        advanceTimeBy(101L)
        val result = stateFlow.first()
        assertIs<Resultat.Success<PaginatedState<String>>>(result)
        assertEquals(listOf("Fetched Page 1 Item 1", "Fetched Page 1 Item 2"), result.value.items)
        assertFalse(result.value.isLoadingMore)
    }
}
