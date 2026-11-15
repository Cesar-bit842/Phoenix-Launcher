plugins {
    id("com.android.library")
}

android {
    namespace = "com.winlator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cFlags += listOf("-std=c11", "-D_POSIX_C_SOURCE=200809L")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.collection:collection:1.5.0")
}
