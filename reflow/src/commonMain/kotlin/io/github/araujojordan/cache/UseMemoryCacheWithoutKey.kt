package io.github.araujojordan.cache

@Suppress("ExperimentalAnnotationRetention")
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Avoid using caching without a unique key. This can lead to unexpected behavior if you use the same key in multiple places."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
internal annotation class UseMemoryCacheWithoutKey