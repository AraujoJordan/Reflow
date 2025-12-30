![Reflow](assets/ReflowLogo.png)

[![Kotlin Multiplatform Library](https://img.shields.io/badge/Kotlim%20Multiplatform-Library-blue?logo=kotlin)](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
[![Tests](https://github.com/AraujoJordan/Reflow/actions/workflows/test.yml/badge.svg)](https://github.com/AraujoJordan/Reflow/actions/workflows/test.yml)
[![Maven Central](https://github.com/AraujoJordan/Reflow/actions/workflows/publish.yml/badge.svg)](https://github.com/AraujoJordan/Reflow/actions/workflows/publish.yml)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![GitHub license](https://img.shields.io/github/license/AraujoJordan/Reflow)](LICENSE.txt)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.araujojordan/reflow.svg)](https://mvnrepository.com/artifact/io.github.araujojordan/reflow)


A Kotlin Multiplatform library that simplifies data fetching with automatic retry logic, loading state management, and flexible caching strategies. It is designed to integrate seamlessly with Jetbrains/Jetpack Compose for an offline-first experience.

## Getting Started

In your `ViewModel`, use the `reflow` extension function to wrap your data fetching logic:

```kotlin
class MyViewModel : ViewModel() {
    val dataFlow = reflow {
        api.fetchData() 
    }
}
```

### Displaying Content in Compose

Use [ReflowContent](https://github.com/AraujoJordan/Reflow/blob/master/reflow/src/commonMain/kotlin/io/github/araujojordan/Reflow.kt#L44) to automatically handle loading, error, and success states.
It **does** also work on [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform)


```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    ReflowContent(viewModel.dataFlow) { data ->
        MyContent(data)
    }
}
```

or

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val dataFlow = viewModel.dataFlow
    PullToRefreshBox(
        isRefreshing = dataFlow.state.isLoading,
        onRefresh = { dataFlow.refresh() },
    ) {
        ReflowContent(dataFlow) { data ->
            MyContent(data)
        }
    }
}
```


## Advanced Usage

```kotlin
val data = reflow(
    cacheSource = CacheSource.None(), // default
    dispatcher = Dispatchers.IO, // default
    initial = Resulting.loading(), // default
    shouldLoadingOnRefresh = true, // default
    maxRetries = 3, // default value, 0 to disable
    retryDelay = 2_000L, // default value
    shouldRetry = { it is HttpException },
) {
    api.fetchData()
}
```


You can also use the `state` property directly and extracting the fetch state:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val dataState by viewModel.ui.state
    
    dataState.foldUi(
        onLoading = { CircularProgressIndicator() },
        onSuccess = { value -> MyContent(value) },
        onFailure = { error -> ErrorMessage(onRetry = { viewModel.uiState.refresh() }) }
    )
}
```

## Setup

Add the dependency to your **module** `build.gradle.kts`

Latest version: [![Maven Central](https://img.shields.io/maven-central/v/io.github.araujojordan/reflow.svg)](https://mvnrepository.com/artifact/io.github.araujojordan/reflow)

### For KMP or Android-only projects:
```kotlin
dependencies {
    implementation("io.github.araujojordan:reflow:0.3.0") // Add this
}
```

For KMP, just add it on `commonMain.dependencies {}`

## Caching Strategies

Reflow supports multiple caching strategies through the `CacheSource` parameter:

| Strategy | Description                            | Requirement                                                            |
| :--- |:---------------------------------------|:-----------------------------------------------------------------------|
| `CacheSource.None()` | No caching (**Default**)                          | None                                                                   |
| `CacheSource.Memory(key)` | In-memory LRU cache.                   |  A unique `String` key (optional)                                      |
| `CacheSource.Disk(key)` | Persistent disk cache using DataStore. | A unique `String` key (optional) and the class must be `@Serializable` |

> **Note:** If you plan to use `CacheSource.Disk()`, you must also apply the `@Serializable` from [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html#serialize-and-deserialize-json) in the class that you want to cache.
If no key is provided on `Memory` or `Disk`, it will use `T::class.qualifiedName` as key and show a warning.
Not using an unique key will result in wrong behavior when caching/retrieving the same types of objects in different places.

Example with Disk caching:

```kotlin
@Serializable
data class User(val id: Int, val name: String)

val userName = reflow(Disk("user_data")) {
    api.fetchUser()
}
```

## Pagination

Reflow provides a streamlined way to handle paginated data:

```kotlin
class MyViewModel : ViewModel() {
    val list = reflowPaginated { page ->
        api.fetchList(page = page.number, size = page.pageSize)
    }
}
```

### LazyColumnPaginated Composable

Use the built-in `LazyColumnPaginated` for automatic pagination with loading states:

```kotlin
@Composable
fun UserListScreen(viewModel: MyViewModel) {
    LazyColumnPaginated(
        paginatedFlow = viewModel.users,
        modifier = Modifier.fillMaxSize()
    ) { user ->
        UserItem(user = user)
    }
}
```

## Rexecute

For tasks like a RestFull Post or a GraphQL Mutation you can use `rexecute`:

```kotlin
class MyViewModel : ViewModel() {
    
    val toggleState = reflow(Disk("user_profile_notification")) {
        api.getUserNotificationState() // Returns a Boolean
    }
    
    fun onToggleChange(toggle: Boolean) = rexecute(key = "user_profile_notification") { 
        api.toggleUserNotification(toggle) // Returns a Boolean 
    }
}
```

- Rexecute will automatically retry failed tasks (due to network issues)

> **Note:** Passing the same key will automatically reuse the `api.toggleUserNotification()` task result on `toggleState`.
This will work on different screens/VMs without the need to pass the result manually.

### Key Features of Rexecute:

- **Global Retry Queue**: Managed independently of UI components.
- **Automatic Caching**: Results are automatically cached in an in-memory LRU cache for `reflow` reusal.
- **Deduplication**: Multiple calls queue with the same key will only execute the last.
- **Compose Ready**: Returns a `Flow<Resulting<T>>` that can be easily observed in your UI for loading and error handling.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Other resources

- [GitHub Repository](https://github.com/AraujoJordan/reflow)
- [Issue Tracker](https://github.com/AraujoJordan/reflow/issues)

---

Made with ❤️ by [AraujoJordan](https://github.com/AraujoJordan)
