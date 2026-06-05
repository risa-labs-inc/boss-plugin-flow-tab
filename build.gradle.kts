import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.42.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // JSON serialization for graph persistence
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // --- Tests ---
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // The boss-plugin-api is compileOnly for main (host-provided); tests need it
    // on the classpath to build a fake PluginContext / BrowserService / BrowserHandle.
    if (useLocalDependencies) {
        testImplementation(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.42.jar"))
    } else {
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-flow-tab-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Flow Tab Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.flowtab.FlowTabDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Deploy the plugin JAR to the BOSS plugins directory.
//
// The host resolves its data dir as ~/.boss_debug when dev mode is on
// (BOSS_DEV_MODE / boss.dev.mode truthy) and ~/.boss otherwise, then loads
// plugins from <dataDir>/plugins. We deploy to BOTH so the JAR is picked up
// whether you run a debug or release build.
tasks.register("deployPlugin") {
    dependsOn("buildPluginJar")

    doLast {
        val userHome = System.getProperty("user.home")
        val jar = layout.buildDirectory
            .file("libs/boss-plugin-flow-tab-${version}.jar").get().asFile
        val targets = listOf(
            file("$userHome/.boss_debug/plugins"),
            file("$userHome/.boss/plugins")
        )
        targets.forEach { dir ->
            dir.mkdirs()
            val dest = dir.resolve(jar.name)
            jar.copyTo(dest, overwrite = true)
            println("Plugin JAR deployed to: ${dest.absolutePath}")
        }
    }
}
