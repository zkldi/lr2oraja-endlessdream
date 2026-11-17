import java.nio.file.FileSystems
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("java-library")
    id("application")
    id("com.gradleup.shadow") version "9.3.2"
    id("org.endlessdream.extra.multiplatform-convention")
}

val backbeatSdkVersion = "1.0.1"
val backbeatReleaseUrl = "https://github.com/zkldi/backbeat/releases/download/v$backbeatSdkVersion"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()

    flatDir {
        dirs("../lib")
    }
    maven(url = "https://jitpack.io")
    exclusiveContent {
        forRepository {
            ivy {
                url = uri(backbeatReleaseUrl)
                patternLayout {
                    artifact("[artifact]-[revision](-[classifier]).[ext]")
                }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("ac.backbeat.release") }
    }
}

version = libs.versions.beatoraja.get()

sourceSets {
    main {
        java.srcDirs("src/", "dependencies/jbms-parser/", "dependencies/jbmstable-parser")
        resources.srcDirs("src/")
    }
    test {
        java.srcDirs("test/")
        resources.srcDirs("test/resources")
    }
}

application {
    mainClass.set("bms.player.beatoraja.MainLoader")
}

tasks {
    jar {
        dependsOn("generateBuildMetaInfo")
    }
    compileTestJava {
        dependsOn("generateBuildMetaInfo")
    }

    // fat/uber-jar task provided by https://github.com/GradleUp/shadow
    shadowJar {
        dependsOn("generateBuildMetaInfo", "test")

        val archProp = System.getProperty("arch")
        val archVariant = archProp?.let { "$it-" } ?: ""
        val platformProp = System.getProperty("platform")
        val endlessDreamVersion = libs.versions.endlessdream.get()
        val classifierPlatform = platformProp?.let { "$it-$archVariant$endlessDreamVersion"} ?: endlessDreamVersion

        destinationDirectory.set(projectDir.resolveSibling("dist"))
        archiveBaseName.set("lr2oraja")
        archiveClassifier.set(classifierPlatform)
        mergeServiceFiles()
    }

    // shadow task that extends java `application` plugin JavaExec to cover fatjars
    // used to test builds, does not contain portaudio natives.
    runShadow {
        val runDirProp = System.getProperty("runDir")
        val runDir = when (runDirProp != null) {
            true -> FileSystems.getDefault().getPath(runDirProp).normalize().toAbsolutePath().toFile()
            false -> projectDir.resolve("../assets")
        }
        val useIRProp = System.getProperty("useIR")
        if (runDirProp != null && useIRProp.toBoolean()) {
            application.applicationDefaultJvmArgs += "-DcustomIRDirectory=$runDir/ir"
        }
        workingDir = runDir
    }
}

tasks.test {
    useJUnitPlatform()
}

val gitHashProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }.orElse("unknown")

// Generate current build's meta info: git commit hash, build time, etc
tasks.register("generateBuildMetaInfo") {
    inputs.property("gitHash", gitHashProvider)

    val output = layout.buildDirectory.file("resources/main/resources/build.properties")
    outputs.file(output)

    doLast {
        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()
        val gitHash = gitHashProvider.get()
        outputFile.writeText(
            """
            git_commit=${gitHash}
            """.trimIndent()
        )
    }
}

val backbeatClassifier = "${System.getProperty("platform") ?: "windows"}-${System.getProperty("arch") ?: "x86-64"}"
val backbeatNativeSha256 = mapOf(
    "windows-x86-64" to "619d6c9dbf7f2c05e76fb78500a7001e950306892bfd6ff94551a27d506b4dd7",
    "macos-x86-64" to "8a0a0ab44b1099a49f1439a36bde6870517c7340a80e918fecbaa94f0d3ec890",
    "macos-aarch64" to "d4561e6d7f752928e2b50bc0b5e3e2a6674bf2db2369c56f4171d6c8ff3f9dc8",
    "linux-x86-64" to "3b8fd6b597726ab040936b28bc4c6690a08ed04475915b92226540d215cc28f2",
    "linux-aarch64" to "50f925e3a52a0f799b74296396980088ba458dea798319d1cfc61b8c1a3bf8d3",
)[backbeatClassifier] ?: throw GradleException("Backbeat SDK $backbeatSdkVersion does not support $backbeatClassifier")

// versions and bundles defined in ../gradle/libs.versions.toml
dependencies {
    implementation(libs.bundles.libgdx)

    implementation(libs.gdx.platform) {
        artifact {
            classifier = "natives-desktop"
        }
    }
    implementation(libs.gdx.freetype.platform) {
        artifact {
            classifier = "natives-desktop"
        }
    }

    /* After version 1.86.11 imgui-java updated their lwjgl3 dependency to 3.3.4
     * this introduced an lwjgl3 bug that causes crashes on Nvidia under Wayland
     * #82. Until such a point that libgdx and imgui-java update their lwjgl3 to
     * 3.3.7 we stay on 3.3.3 to avoid the crash and related issues.
     *
     * See also:
     * https://github.com/libgdx/libgdx/issues/7495
     * https://github.com/libgdx/libgdx/pull/7555
     */
    implementation(libs.bundles.imgui) {
        exclude(group = "org.lwjgl")
    }

    implementation(libs.bundles.ffmpeg)

    implementation(libs.bundles.codecs)
    implementation(libs.bundles.jackson)

    implementation(libs.bundles.jna)

    implementation("ac.backbeat.release:backbeat-java-sdk:$backbeatSdkVersion")
    runtimeOnly("ac.backbeat.release:backbeat-java-sdk:$backbeatSdkVersion:$backbeatClassifier")

    implementation(libs.sqlite)
    implementation(libs.commons.compress)
    implementation(libs.commons.csv)
    implementation(libs.commons.dbutils)
    implementation(libs.xz)

    implementation(libs.twitter4j)

    implementation(libs.shapedrawer)
    implementation(libs.guacamole)

    implementation(libs.ebur128java)

    implementation(libs.javawebsocket)
    implementation(libs.bundles.slf4j)

    // non-gradle managed file dependencies. jportaudio not on maven. "custom" scares me.
    implementation(":jportaudio")
    implementation(":luaj-jse:3.0.2-custom")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val backbeatJavaSha256 = "96cebe81c21f6aa0c6ef6c382035185625f1b0de0ebde7f4f7c23538eb0679fc"

val verifyBackbeatSdk by tasks.registering {
    val expected = mapOf(
        "backbeat-java-sdk-$backbeatSdkVersion.jar" to
            backbeatJavaSha256,
        "backbeat-java-sdk-$backbeatSdkVersion-$backbeatClassifier.jar" to backbeatNativeSha256,
    )
    inputs.files(configurations.named("runtimeClasspath"))

    doLast {
        val artifacts = configurations.getByName("runtimeClasspath").files.associateBy { it.name }
        expected.forEach { (name, expectedSha256) ->
            val artifact = artifacts[name] ?: throw GradleException("Backbeat SDK artifact is missing: $name")
            val actualSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            )
            if (actualSha256 != expectedSha256) {
                throw GradleException(
                    "SHA-256 mismatch for $name: expected $expectedSha256, got $actualSha256"
                )
            }
        }
    }
}

tasks.compileJava {
    dependsOn(verifyBackbeatSdk)
}
