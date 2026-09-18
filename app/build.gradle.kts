plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.autoclicker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.autoclicker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // ФИЧА: R8-минификация кода + шринк ресурсов в release-сборке.
            // В шаблоне AGP 9 оптимизация была выключена (enable = false),
            // из-за чего APK был заметно больше необходимого. enable = true
            // в AGP 9 включает сразу и обфускацию/минификацию кода, и удаление
            // неиспользуемых ресурсов. Кастомные keep-правила не нужны:
            // все компоненты (Activity/Service) объявлены в манифесте и
            // сохраняются автоматически, JSON — через org.json без рефлексии.
            // mapping.txt (деобфускация стек-трейсов) публикуется в CI.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
