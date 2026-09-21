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
            // ФИЧА (Task 26): release подписывается отладочным ключом.
            // Раньше release-APK собирался unsigned и НЕ УСТАНАВЛИВАЛСЯ на
            // телефон — приходилось пользоваться debug-сборкой. Отладочный
            // keystore (~/.android/debug.keystore) создаётся автоматически,
            // поэтому подпись работает и локально, и в CI. Для публикации
            // в Google Play понадобится отдельный release-keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ФИЧА (Task 26): удобное имя файла — после каждой release-сборки в папке
// <корень проекта>/dist/ появляется копия APK с именем "Sekira Cliker.apk".
// Классическое переименование через applicationVariants в AGP 9 УДАЛЕНО,
// поэтому используется простая задача-копия на стабильном API.
// Код совместим с configuration cache (включён в gradle.properties):
// все пути фиксируются на фазе конфигурации, в doLast — только автоимпортируемые
// kotlin.io-расширения (resolve/copyTo) — без java.* (см. ВАЖНО ниже)
val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
val distDir = rootProject.layout.projectDirectory.dir("dist")

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val dir = releaseApkDir.get().asFile
        val apk = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() } ?: return@doLast
        // ВАЖНО (Task 27): НЕ использовать "java.io.File(...)" в build.gradle.kts —
        // имя "java" внутри скрипта занято расширением плагина, и "java.io"
        // не резолвится ("Unresolved reference 'io'"). resolve() из kotlin.io
        // доступен в скриптах автоматически, без импортов
        val target = distDir.asFile.resolve("Sekira Cliker.apk")
        apk.copyTo(target, overwrite = true)
        println(">>> APK готов: ${target.absolutePath}")
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
