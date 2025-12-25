![Reflow](assets/ReflowLogo.png)

[![Maven Central](https://github.com/AraujoJordan/Reflow/actions/workflows/publish.yml/badge.svg)](https://github.com/AraujoJordan/Reflow/actions/workflows/publish.yml) [![Tests](https://github.com/AraujoJordan/Reflow/actions/workflows/test.yml/badge.svg)](https://github.com/AraujoJordan/Reflow/actions/workflows/test.yml)  

A Kotlin Multiplatform library that simplifies data fetching with automatic retry logic, loading state management, and flexible caching strategies. It is designed to integrate seamlessly with Jetbrains/Jetpack Compose for an offline-first experience.

## Getting Started

In your `ViewModel`, use the `reflow` extension function to wrap your data fetching logic:

```kotlin
class MyViewModel : ViewModel() {
    val uiState = reflow {
        api.fetchData() 
    }
}
```

### Displaying Content in Compose

Use `ReflowContent` to automatically handle loading, error, and success states:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    ReflowContent(viewModel.uiState) { data ->
        MyContent(data)
    }
}
```

Or for more control, use the `state` property directly:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val dataState by viewModel.uiState.state
    
    dataState.foldUi(
        onLoading = { CircularProgressIndicator() },
        onSuccess = { value -> MyContent(value) },
        onFailure = { error -> ErrorMessage(onRetry = { viewModel.uiState.refresh() }) }
    )
}
```

## Setup

Add the dependency to your **module** `build.gradle.kts`:

### For Android-only projects:
```kotlin
dependencies {
    implementation("io.github.araujojordan:reflow:0.2.1")
}
```

### For Kotlin Multiplatform projects:
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.araujojordan:reflow:0.2.1")
        }
    }
}
```

> **Note:** If you plan to use `CacheSource.Disk()`, you must also apply the `@Serializable` from [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html#serialize-and-deserialize-json) in the class that you want to cache.

## Pagination

Reflow provides a streamlined way to handle paginated data:

```kotlin
class MyViewModel : ViewModel() {
    val users = reflowPaginated { page ->
        api.fetchUsers(page = page.value, size = page.pageSize)
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

## Caching Strategies

Reflow supports multiple caching strategies through the `CacheSource` parameter:

| Strategy | Description                            | Requirement                                                  |
| :--- |:---------------------------------------|:-------------------------------------------------------------|
| `CacheSource.None()` | No caching (**Default**)                          | None                                                         |
| `CacheSource.Memory(key)` | In-memory LRU cache.                   | A unique `String` key                                        |
| `CacheSource.Disk(key)` | Persistent disk cache using DataStore. | A unique `String` key and the class must be `@Serializable`  |

Note that if no key is provided on `Memory` or `Disk`, it will use `T::class.qualifiedName` as key.

Example with Disk caching:

```kotlin
@Serializable
data class User(val id: Int, val name: String)

val uiState = reflow(cacheSource = CacheSource.Disk<User>("user_cache")) {
    api.fetchUser()
}
```

## Advanced Usage

### Custom Retry Configuration

```kotlin
val users = reflow(
    maxRetries = 5,
    retryDelay = 3000L, // 3 seconds
    shouldRetry = { exception ->
        exception is IOException || exception is HttpException
    }
) {
    api.fetchUsers()
}
```

### Manual Refreshing

Trigger a manual refresh (e.g., from a `SwipeRefresh`):

```kotlin
viewModel.uiState.refresh()
```

By default, refreshing shows a loading state. You can disable this for a smoother user experience:

```kotlin
val users = reflow(shouldLoadingOnRefresh = false) {
    api.fetchUsers()
}
```

### Initial State

Customize the initial state (e.g., start with empty data instead of loading):

```kotlin
val data = reflow(
    initial = Resulting.success(yourDefaultValue) 
) {
    api.fetchData()
}
```

### Custom Scope (reflowIn)

For use outside of `ViewModel`, use `reflowIn`:

```kotlin
class MyRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    val users = reflowIn(
        scope = scope,
        fetchFlow = flow {
            val data = api.fetchData()
            emit(data)
        }
    )
}
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Other resources

- [GitHub Repository](https://github.com/AraujoJordan/reflow)
- [Issue Tracker](https://github.com/AraujoJordan/reflow/issues)

---

Made with ❤️ by [AraujoJordan](https://github.com/AraujoJordan)
