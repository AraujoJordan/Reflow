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
    implementation("io.github.araujojordan:reflow:0.3.0")
}
```

### For Kotlin Multiplatform projects:
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.araujojordan:reflow:0.3.0")
        }
    }
}
```

> **Note:** If you plan to use `CacheSource.Disk()`, you must also apply the `@Serializable` from [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html#serialize-and-deserialize-json) in the class that you want to cache.

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

## Caching Strategies

Reflow supports multiple caching strategies through the `CacheSource` parameter:

| Strategy | Description                            | Requirement                                                            |
| :--- |:---------------------------------------|:-----------------------------------------------------------------------|
| `CacheSource.None()` | No caching (**Default**)                          | None                                                                   |
| `CacheSource.Memory(key)` | In-memory LRU cache.                   |  A unique `String` key (optional)                                      |
| `CacheSource.Disk(key)` | Persistent disk cache using DataStore. | A unique `String` key (optional) and the class must be `@Serializable` |

Note that if no key is provided on `Memory` or `Disk`, it will use `T::class.qualifiedName` as key and show a warning.
Not using an unique key will result in wrong behavior when caching/retrieving the same types of objects in different places.

Example with Disk caching:

```kotlin
@Serializable
data class User(val id: Int, val name: String)

val userName = reflow(Disk("user_data")) {
    api.fetchUser()
}
```

## Advanced Usage

### Custom Retry Configuration

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

### Manual Refreshing

Trigger a manual refresh (e.g., from a `SwipeRefresh`):

```kotlin
viewModel.uiState.refresh()
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
