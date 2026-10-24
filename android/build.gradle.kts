// If Android Studio offers to upgrade these versions, accepting is fine.
// If the build says LiteRT-LM was compiled with a newer Kotlin, raise the Kotlin version below.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}
