# Reflow

A Kotlin Multiplatform library that simplifies data fetching with automatic retry logic, loading state management, and flexible caching strategies. Built on top of Kotlin Flows and designed to integrate seamlessly with Jetpack Compose.

How to use it:

```kotlin
class MyViewModel : ViewModel() {
    val uiState = reflow { 
        api.fetchData() 
    }
}
```

Reflow will automatically retry on common network failures and handle exceptions for you.

Then in your Compose UI:

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    
    val data by viewModel.uiState.state
    
    SwipeRefresh(onRefresh = { viewModel.uiState.refresh() }) {
        when (data) {
            is Resultat.Loading -> CircularProgressIndicator()
            is Resultat.Success -> MyContent(state.value)
            is Resultat.Failure -> ErrorMessage(onRetry = { viewModel.uiState.refresh() })
        }
    }
}
```

## What is it?

Reflow wraps your data fetching operations in a reactive Flow that automatically handles:
- **Loading states** - Track when data is being fetched
- **Flexible fetch policies** - Network-only, cache-only, or cache-then-network strategies
- **Compose integration** - Direct integration with ViewModel and Compose state
- **Error handling** - Automatic retry with configurable policies

Perfect for building robust Android, iOS, and JVM applications that need reliable data fetching with minimal boilerplate.

## Features

- 🌐 **Multiplatform** - Works on Android, iOS, and JVM
- 🎨 **Flexible Configuration** - Customize retry behavior, loading states, and error handling
- 🔄 **Automatic Retry Logic** - Configurable retry attempts with custom delays
- 🔌 **ViewModel Integration** - Extension functions for seamless ViewModel usage
- 🧩 **Compose Ready** - First-class support for Jetpack Compose with reactive state
- ⚡ **Coroutine-Based** - Built on Kotlin Coroutines and Flow for efficient async operations
- 📡 **Multiple Fetch Policies** - Choose between network-only, cache-only, or cache-and-network strategies

## Installation

For Android-only projects, add the following dependency to your app `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.araujojordan.reflow:reflow:0.0.1")
}
```

For Kotlin Multiplatform projects:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.araujojordan.reflow:reflow:0.0.1")
        }
    }
}
```

### Fetch Policies

Reflow supports three fetch policies to handle different caching scenarios:

#### NetworkOnly (Default)

Fetches data from the network every time:

```kotlin
class MyViewModel : ViewModel() {
    val users = reflow {
        api.fetchUsers()
    }
}
```

#### CacheAndNetwork

Implements the cache-then-network pattern - returns cached data immediately, then updates with fresh network data:

```kotlin
class MyViewModel : ViewModel() {
    val users = reflow(
        fetchPolicy = FetchPolicy.CacheAndNetwork(
            onStore = { users -> database.saveUsers(users) }, // Room, SQLDelight, etc
            onRetrieve = database.getUsersFlow() // Same
        )
    ) {
        api.fetchUsers()
    }
}
```

This pattern provides the best user experience by showing cached data instantly while fetching fresh data in the background.

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
    fetchPolicy = FetchPolicy.NetworkOnly(
        maxRetries = 5,
        retryDelay = 3000L, // 3 seconds
        shouldRetry = { exception ->
            exception is MyCustomException
        }
    )
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
    initial = Resultat.success(emptyList()) // Start with empty list instead of loading
) {
    api.fetchUsers()
}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Other resources

- [GitHub Repository](https://github.com/AraujoJordan/reflow)
- [Issue Tracker](https://github.com/AraujoJordan/reflow/issues)
- [Resultat Library](https://github.com/Haan-Studios/resultat)

---

Made with ❤️ by [Jordan Lira de Araujo Junior](https://github.com/AraujoJordan)
