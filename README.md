# Reflow

A Kotlin Multiplatform library that simplifies data fetching with automatic retry logic, loading state management, and flexible caching strategies. Built on top of Kotlin Flows and designed to integrate seamlessly with Jetpack Compose.

### How to use it:

```kotlin
class MyViewModel : ViewModel() {
    val uiState = reflow {
        api.fetchData() 
    }
}
```

you can also cache it in disk with:

```kotlin
class MyViewModel : ViewModel() {
    val uiState = reflow(cacheSource = CacheSource.Disk<UiState>()) {
        api.fetchData()
    }
}
```

Reflow will automatically retry on common network failures and handle exceptions for you.

Then in your Compose UI:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    ReflowBox(viewModel.uiState) { ui ->
        MyContent(ui)
    }
}
```

Or more customizable like:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val data by viewModel.uiState.state
    SwipeRefresh(onRefresh = { viewModel.uiState.refresh() }) {
        data.foldUi(
            onLoading = { CircularProgressIndicator() },
            onSuccess = { value -> MyContent(value) },
            onFailure = { error -> ErrorMessage(onRetry = { viewModel.uiState.refresh() }) }
        )
    }
}
```

### For pagination flow:

```kotlin
class MyViewModel : ViewModel() {
    val users = reflowPaginated { page ->
        api.fetchUsers(page = page.value, size = page.pageSize)
    }
}
```

### LazyColumnPaginated Composable

Use the built-in `LazyColumnPaginated` composable for automatic pagination with loading states:

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

## What is it?

Reflow wraps your data fetching operations in a reactive Flow that automatically handles:
- **Loading states** - Track when data is being fetched
- **Compose integration** - Direct integration with ViewModel and Compose state
- **Error handling** - Automatic retry with configurable policies
- **Pagination** - Built-in support for paginated lists with automatic load-more detection

Perfect for building robust Android, iOS, and JVM applications that need reliable data fetching with minimal boilerplate.

## Features

- 🌐 **Multiplatform** - Works on Android, iOS, and JVM
- 🎨 **Flexible Configuration** - Customize retry behavior, loading states, and error handling
- 🔄 **Automatic Retry Logic** - Configurable retry attempts with custom delays
- 🔌 **ViewModel Integration** - Extension functions for seamless ViewModel usage
- 🧩 **Compose Ready** - First-class support for Jetpack Compose with reactive state
- ⚡ **Coroutine-Based** - Built on Kotlin Coroutines and Flow for efficient async operations
- 📄 **Pagination Support** - Built-in pagination with automatic load-more and LazyColumn integration

## Installation

For Android-only projects, add the following dependency to your app `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.araujojordan:reflow:0.0.7")
}
```

For Kotlin Multiplatform projects:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.araujojordan:reflow:0.0.7")
        }
    }
}
```

### Refreshing Data

Trigger a manual refresh at any time:

```kotlin
@Composable
fun UserScreen(viewModel: MyViewModel) {
    val state by viewModel.users.state
    
    SwipeRefresh(
        onRefresh = { viewModel.users.refresh() }
    ) {
        // Your content
    }
}
```

This keeps the previous data visible while fetching new data in the background.

### Custom Retry Configuration

Configure retry behavior for your specific needs:

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

### Refresh Without Loading State

By default, refreshing shows a loading state. You can disable this for a smoother UX:

```kotlin
val users = reflow(
    shouldLoadingOnRefresh = false
) {
    api.fetchUsers()
}
```

### Advanced: Custom Scope with reflowIn

For use outside of ViewModels, use `reflowIn` with a custom CoroutineScope:

```kotlin
class MyRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    val users = reflowIn(
        scope = scope,
        dispatcher = Dispatchers.Main, // Dispatchers.IO is the default
        fetchFlow = flow {
            // Run some heavy operation
            heavyCalculations()
            val users = withScope(Dispatchers.IO) { api.fetchUsers() }
            emit(users)
        }
    )
}
```

### Initial State Configuration

Customize the initial state of your Reflow:

```kotlin
val users = reflow(
    initial = Resulting.success(emptyList()) // Start with empty list instead of loading
) {
    api.fetchUsers()
}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Other resources

- [GitHub Repository](https://github.com/AraujoJordan/reflow)
- [Issue Tracker](https://github.com/AraujoJordan/reflow/issues)

---

Made with ❤️ by [Jordan Lira de Araujo Junior](https://github.com/AraujoJordan)
