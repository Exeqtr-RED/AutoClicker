plugins {
    alias(libs.plugins.android.application)
}

// ФИКС (безопасность): keystore и пароли ВЫНЕСЕНЫ из репозитория.
// Раньше keystore/sekira.keystore и пароль лежали в git в открытом виде —
// любой мог подписать APK этим ключом и подсунуть обновление поверх
// установленного приложения. Теперь:
//  - keystore НЕ коммитится (см. .gitignore); CI получает его из секрета
//    SEKIRA_KEYSTORE_B64 (base64) перед сборкой — см. android.yml;
//  - пароль и alias — из переменных окружения SEKIRA_STORE_PASSWORD /
//    SEKIRA_KEY_ALIAS / SEKIRA_KEY_PASSWORD или gradle.properties
//    (локально — в ~/.gradle/gradle.properties):
//        sekiraStorePassword=...
//        sekiraKeyAlias=sekira
//        sekiraKeyPassword=...
// ВНИМАНИЕ: старый ключ скомпрометирован (пароль попал в историю git) —
// сгенерируйте НОВЫЙ, иначе смысла в этом фиксе нет:
//   keytool -genkeypair -v -keystore keystore/sekira.keystore -alias sekira \
//       -keyalg RSA -keysize 2048 -validity 10000
val sekiraStorePassword = System.getenv("SEKIRA_STORE_PASSWORD")
    ?: providers.gradleProperty("sekiraStorePassword").orNull ?: ""
val sekiraKeyAlias = System.getenv("SEKIRA_KEY_ALIAS")
    ?: providers.gradleProperty("sekiraKeyAlias").orNull ?: "sekira"
val sekiraKeyPassword = System.getenv("SEKIRA_KEY_PASSWORD")
    ?: providers.gradleProperty("sekiraKeyPassword").orNull ?: sekiraStorePassword
val sekiraKeystoreExists = rootProject.file("keystore/sekira.keystore").exists()

android {
    namespace = "com.example.autoclicker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.autoclicker"
        minSdk = 24
        targetSdk = 37
        // Маркер сборки (v39): Настройки -> Приложения -> Sekira Cliker ->
        // версия 2.7 (18). Если там 2.7 — APK собран из свежих файлов
        versionCode = 18
        versionName = "2.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ФИКС (v19): единый ФИКСИРОВАННЫЙ ключ подписи для ВСЕХ сборок
    // (иначе release подписывался отладочным, и версии не ставились поверх).
    // ФИКС (безопасность): keystore и пароли вынесены из репозитория
    // — см. верх файла и .github/workflows/android.yml
    signingConfigs {
        create("sekira") {
            storeFile = rootProject.file("keystore/sekira.keystore")
            storePassword = sekiraStorePassword
            keyAlias = sekiraKeyAlias
            keyPassword = sekiraKeyPassword
        }
    }

    buildTypes {
        debug {
            // если keystore не подложен — остаётся отладочный ключ раннера
            if (sekiraKeystoreExists) {
                signingConfig = signingConfigs.getByName("sekira")
            }
        }
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
            if (sekiraKeystoreExists) {
                signingConfig = signingConfigs.getByName("sekira")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ФИЧА (Task 26/28/29): удобное имя файла — после release-сборки в папке
// <корень проекта>/dist/ появляется копия APK с именем "Sekira Cliker.apk".
// Классическое переименование через applicationVariants в AGP 9 УДАЛЕНО —
// используется отдельная задача-финализатор на стабильном API.
//
// Три жёстких ограничения, учтённые здесь:
// 1. Configuration cache (включён в gradle.properties): действия задач НЕ имеют
//    права ссылаться на объект build-скрипта (топ-уровневые val, layout,
//    rootProject) — иначе в САМОМ КОНЦЕ сборки возникает
//    "cannot serialize Gradle script object references", весь билд
//    помечается FAILED, хотя задачи уже выполнились (v10 на CI).
//    Поэтому значения фиксируются ЛОКАЛЬНЫМИ val внутри конфигурации задачи
//    (документированный паттерн), а финализатор подключается ПО ИМЕНИ.
// 2. UP-TO-DATE: doLast у UP-TO-DATE задач не выполняется — копирование
//    не в assembleRelease, а в отдельную задачу через finalizedBy.
// 3. Gradle Kotlin DSL: java.io.File(...) не резолвится — только
//    автоимпортируемые kotlin.io-расширения (resolve/copyTo).
tasks.register("copyReleaseApk") {
    group = "build"
    description = "Копирует release-APK как 'Sekira Cliker.apk' в dist/"
    // Значения фиксируются на фазе конфигурации: project.* берётся от самой
    // задачи (не от скрипта), результат — обычные java-файлы, CC-сериализуемо
    val apkDirFile = project.layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val distDirFile = project.rootDir.resolve("dist")
    doLast {
        val apk = apkDirFile.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
        if (apk == null) {
            println(">>> [Sekira] ВНИМАНИЕ: release-APK не найден в ${apkDirFile.absolutePath}")
            println(">>> [Sekira] Сначала соберите release: gradlew assembleRelease")
            return@doLast
        }
        val target = distDirFile.resolve("Sekira Cliker.apk")
        apk.copyTo(target, overwrite = true)
        println(">>> APK готов: ${target.absolutePath}")
    }
}

// Финализатор ПО ИМЕНИ (строкой): ссылка на топ-уровневый val задачи внутри
// конфигурационного действия захватывает объект скрипта и ломает configuration cache
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApk")
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
