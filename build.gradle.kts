import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.39"

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

// Newest boss-plugin-api jar from the sibling repo (local dev), resolved lazily in a provider so
// it runs at dependency-RESOLUTION time, not configuration time: clean/help/tasks still work on
// a fresh checkout without the sibling jar built, and compilation fails with this actionable
// message instead of unresolved-reference noise. Shared by `compileOnly` (main) and
// `testImplementation` so the two can never drift.
//
// This replaces a filename-pinned jar that no longer existed in the sibling checkout.
// `compileOnly(files(...))` on a missing path contributes *nothing* silently, so every api
// symbol came back "unresolved reference" with no hint that a stale filename was the cause.
// Never name a version here.
val newestApiJar = provider {
    val apiJarPattern = Regex("""boss-plugin-api-(\d+)\.(\d+)\.(\d+)\.jar""")
    file("$bossPluginApiPath/build/libs").listFiles()
        ?.mapNotNull { jar -> apiJarPattern.matchEntire(jar.name)?.let { m -> jar to m } }
        // Lexicographic (major, minor, patch) — no packing arithmetic that would mis-order
        // components >= 1000.
        ?.maxWithOrNull(
            compareBy(
                { it.second.groupValues[1].toInt() },
                { it.second.groupValues[2].toInt() },
                { it.second.groupValues[3].toInt() }
            )
        )?.first
        ?: error(
            "No boss-plugin-api jar found in $bossPluginApiPath/build/libs — " +
                "run ./gradlew buildPluginJar in the sibling boss-plugin-api checkout first."
        )
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// Runtime deps fat-packed into the plugin JAR (P7 external-MCP). Kept separate from the
// compile classpath so we can pick exactly which artifacts ship inside the JAR.
val bundled: Configuration by configurations.creating

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files(newestApiJar))
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

    // External MCP client (P7, feature-flagged OFF by default). The MCP Kotlin SDK
    // *client* artifact + its ktor transport. Versions pinned to what the SDK 0.7.2
    // resolves (ktor 3.3.x + SSE) to match what BossConsole bundles. stdio spawns a
    // process; HTTP/SSE talks to a remote server. Only referenced by the concrete
    // transports (McpClientTransports.kt) — the testable core is transport-agnostic.
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.7.2")
    implementation("io.ktor:ktor-client-core:3.3.0") // includes the client SSE plugin
    implementation("io.ktor:ktor-client-cio:3.3.0")  // engine for the SSE transport
    // The same P7 artifacts are added to a `bundled` configuration (declared below) so
    // buildPluginJar fat-packs them: the host classpath has no MCP SDK / ktor, and the
    // feature would ClassNotFound at runtime otherwise. Host-provided groups (kotlin
    // stdlib, coroutines, serialization, compose) are excluded from the bundle.
    "bundled"("io.modelcontextprotocol:kotlin-sdk-client:0.7.2")
    "bundled"("io.ktor:ktor-client-cio:3.3.0")

    // --- Tests ---
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // The boss-plugin-api is compileOnly for main (host-provided); tests need it
    // on the classpath to build a fake PluginContext / BrowserService / BrowserHandle.
    if (useLocalDependencies) {
        testImplementation(files(newestApiJar))
    } else {
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    dependsOn(tasks.processResources)
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

    // Fat-pack the P7 external-MCP runtime deps (MCP Kotlin SDK client + ktor + their
    // transitive closure) that the host classpath does NOT provide. Host-provided groups
    // (kotlin stdlib/reflect, coroutines, serialization, compose, decompose, skiko) are
    // excluded to avoid shadowing the host's own copies.
    val hostProvided = setOf(
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",   // coroutines + serialization (host-provided)
        "org.jetbrains.compose",
        "org.jetbrains.skiko",
        "com.arkivanov.decompose",
        "com.arkivanov.essenty",
    )
    // kotlinx-io (group org.jetbrains.kotlinx) IS required by the SDK and NOT host-provided,
    // so re-include it explicitly despite the group exclusion above.
    val forceInclude = setOf("kotlinx-io-core-jvm", "kotlinx-io-bytestring-jvm")
    from({
        bundled.resolvedConfiguration.resolvedArtifacts
            .filter { art ->
                art.moduleVersion.id.group !in hostProvided || art.name in forceInclude
            }
            .map { zipTree(it.file) }
    }) {
        // Drop signatures + module metadata from the vendored jars.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "**/module-info.class")
    }
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    // Project.version is not a file input, so declare it explicitly; otherwise Gradle can
    // reuse a manifest generated for an older release after only this value changes.
    inputs.property("pluginVersion", project.version.toString())
    filesMatching("**/plugin.json") {
        filter { line ->
            // Only the two-space-indented top-level plugin version. Dependency entries
            // have deeper indentation and must retain their own version constraints.
            line.replace(
                Regex("^  \"version\"\\s*:\\s*\"[^\"]*\""),
                "  \"version\": \"${project.version}\"",
            )
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
