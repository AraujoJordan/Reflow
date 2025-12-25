import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

group = "io.github.araujojordan"
version = "0.2.2"

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.github.araujojordan"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compileTaskProvider.configure{
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }
    iosArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.io.core)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)

            implementation(libs.datastore)
            implementation(libs.datastore.preferences.core)
            implementation(libs.protobuf.kotlin)

            implementation(libs.kermit)
            implementation(libs.androidxCollection)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "reflow", version.toString())
    pom {
        name = "Reflow"
        description = "A Flow that handle errors, loading states and retries."
        inceptionYear = "2025"
        url = "https://github.com/AraujoJordan/reflow/"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "AraujoJordan"
                name = "Jordan Lira de Araujo Junior"
                url = "https://github.com/AraujoJordan"
            }
        }
        scm {
            url = "https://github.com/AraujoJordan/reflow"
            connection = "scm:git:git://github.com/AraujoJordan/reflow.git"
            developerConnection = "scm:git:ssh://git@github.com/AraujoJordan/reflow.git"
        }
    }
}
