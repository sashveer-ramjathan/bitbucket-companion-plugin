plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension
    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    //
    // Deliberately NOT declaring testFramework(TestFrameworkType.Platform) here - it pulls in a
    // full IDE test-sandbox environment (a heavy download), and there are no tests yet that
    // actually need a running IDE fixture (PSI, UI components, etc.). Plain JUnit is enough for
    // pure-logic tests (GitOps parsing, API client URL building). Add TestFrameworkType.Platform
    // back only when a real platform-integration test needs it, as its own consciously-slower
    // path - don't pay this cost on every push by default.
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    // buildPlugin otherwise launches a full headless IDE just to index Settings-panel fields
    // for the IDE's search box. We have no such indexed content today, so this is pure dead
    // weight on every build - re-enable if/when that indexing is actually wanted.
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        vendor {
            name = providers.gradleProperty("pluginVendorName")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginVendorUrl")
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    test {
        useJUnit()
    }
}
