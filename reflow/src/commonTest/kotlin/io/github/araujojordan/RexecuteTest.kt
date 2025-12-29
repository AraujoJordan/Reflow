package io.github.araujojordan

import androidx.lifecycle.ViewModel
import io.github.araujojordan.model.Resulting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RexecuteTest {

    @Test
    fun `rexecuteIn should emit loading then content on success`() = runTest {
        // Given
        val results = mutableListOf<Resulting<String>>()
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // When
        val flow = rexecuteIn(key = "testKey", dispatcher = testDispatcher) { "Success" }
        val job = launch { flow.toList(results) }
        advanceUntilIdle()

        // Then
        assertEquals(2, results.size)
        assertTrue(results[0].isLoading)
        assertTrue(results[1].isSuccess)
        assertEquals("Success", results[1].getOrNull())
        job.cancel()
    }

    @Test
    fun `rexecuteIn should emit loading then failure on non-retryable error`() = runTest {
        // Given
        val results = mutableListOf<Resulting<String>>()
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // When
        val flow = rexecuteIn<String>(
            key = "testKeyFail",
            shouldRetry = { false },
            dispatcher = testDispatcher
        ) {
            throw RuntimeException("Permanent error")
        }
        val job = launch { flow.toList(results) }
        advanceUntilIdle()

        // Then
        assertEquals(2, results.size)
        assertTrue(results[0].isLoading)
        assertTrue(results[1].isFailure)
        assertEquals("Permanent error", results[1].exceptionOrNull()?.message)
        job.cancel()
    }

    @Test
    fun `rexecuteIn should schedule retry on retryable error`() = runTest {
        // Given
        Rexecute.clear()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val flow = rexecuteIn<String>(
            key = "testRetry",
            shouldRetry = { it is IOException },
            dispatcher = testDispatcher
        ) {
            throw IOException("Temporary error")
        }
        val results = mutableListOf<Resulting<String>>()

        // When
        val job = launch { flow.collect { results.add(it) } }
        advanceUntilIdle()

        // Then
        assertEquals(1, results.size)
        assertTrue(results[0].isLoading)
        assertEquals(1, Rexecute.activeJobCount())
        assertEquals("testRetry", Rexecute.peekKey())
        job.cancel()
    }

    @Test
    fun `rexecuteIn should replace existing job with same key in queue`() = runTest {
        // Given
        Rexecute.clear()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Rexecute.submitJob(RexecuteJob("testReplace", 0, {true}, {}, {"old"}))
        assertEquals(1, Rexecute.activeJobCount())

        // When
        val flow = rexecuteIn<String>(
            key = "testReplace",
            shouldRetry = { true },
            dispatcher = testDispatcher
        ) {
            throw IOException("Replace me")
        }
        val job = launch { flow.collect {} }
        advanceUntilIdle()

        // Then
        assertEquals(1, Rexecute.activeJobCount())
        assertEquals("testReplace", Rexecute.peekKey())
        job.cancel()
    }

    private class TestViewModel : ViewModel()

    @Test
    fun `ViewModel rexecute should delegate to rexecuteIn`() = runTest {
        // Given
        val viewModel = TestViewModel()
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // When
        val flow1 = viewModel.rexecute(key = "manualKey", dispatcher = testDispatcher) { "Success1" }
        val results1 = mutableListOf<Resulting<String>>()
        val job1 = launch { flow1.toList(results1) }
        advanceUntilIdle()

        // Then
        assertEquals(2, results1.size)
        assertEquals("Success1", results1[1].getOrNull())
        job1.cancel()

        // When
        val flow2 = viewModel.rexecute<String>(dispatcher = testDispatcher) { "Success2" }
        val results2 = mutableListOf<Resulting<String>>()
        val job2 = launch { flow2.toList(results2) }
        advanceUntilIdle()

        // Then
        assertEquals(2, results2.size)
        assertEquals("Success2", results2[1].getOrNull())
        job2.cancel()
    }
}