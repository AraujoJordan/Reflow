import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.reflow.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Reflow Sample") {
        App()
    }
}
