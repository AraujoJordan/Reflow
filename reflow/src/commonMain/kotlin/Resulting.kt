package com.araujojordan.reflow

/**
 * Represent a LCE (Loading, Content or Error) values
 * it uses [Kotlin.Result] to represent the Content/Error cases (success/failure)
 */
class Resulting<T> private constructor(internal var value: kotlin.Result<T>? = null) {
    companion object {
        fun <T> content(content: T): Resulting<T> = Resulting(Result.success(content))
        fun <T> loading(): Resulting<T> = Resulting()
        fun <T> failure(error: Throwable): Resulting<T> = Resulting(Result.failure(error))
    }

    val isLoading: Boolean get() = value == null
    val isSuccess: Boolean get() = value?.isSuccess ?: false
    val isFailure: Boolean get() = value?.isFailure ?: false

    fun getOrNull(): T? = value?.getOrNull()
    fun getOrThrow(): T = value?.getOrThrow() ?: error("Is Loading or no Throwable present")
    fun exceptionOrNull(): Throwable? = value?.exceptionOrNull()

    fun <R> fold(
        onLoading: () -> R,
        onSuccess: (T) -> R,
        onFailure: (Throwable) -> R
    ): R = value?.fold(onSuccess, onFailure) ?: onLoading()

    fun <R> map(transform: (T) -> R): Resulting<R> = value?.map(transform)?.let { Resulting(it) } ?: loading()

    fun <V, R> zip(other: Resulting<V>, transform: (T, V) -> R): Resulting<R> {
        return when {
            this.isLoading || other.isLoading -> loading()
            this.isSuccess && other.isSuccess -> Resulting(
                runCatching { transform(this.value!!.getOrThrow(), other.value!!.getOrThrow()) }
            )
            else -> Resulting(Result.failure(this.exceptionOrNull() ?: other.exceptionOrNull()!!))
        }
    }
}