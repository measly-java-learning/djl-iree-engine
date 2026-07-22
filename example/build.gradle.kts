plugins {
    application
    alias(libs.plugins.jmh)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":"))            // this IREE engine (brings its native .so via resources)
    implementation(libs.djl.api)            // Image, ImageClassificationTranslator
    runtimeOnly(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "org.measly.example.MobilenetExample"
}

tasks.test { useJUnitPlatform() }

// Model artifacts are generated on demand into this directory
val modelsDir = layout.buildDirectory.dir("models")

val exportModels = tasks.register<Exec>("exportModels") {
    group = "build"
    description = "Generate MobileNetV2 .vmfb via uv (heavy; needs uv on PATH)."
    val out = modelsDir.get().asFile
    val script = rootProject.file("tools/scripts/export_mobilenet.py")
    inputs.file(script)
    outputs.files(
        out.resolve("mobilenet_v2.vmfb"),
        out.resolve("versions.json"),
    )
    doFirst { out.mkdirs() }
    workingDir = out
    commandLine("uv", "run", script.absolutePath)
}

// Pass the models directory to the JVM so ModelArtifacts can resolve it at runtime.
tasks.named<JavaExec>("run") {
    systemProperty("example.models.dir", modelsDir.get().asFile.absolutePath)
}

jmh {
    warmupIterations = 3
    iterations = 5
    fork = 1
    jvmArgs = listOf(
        "-Dexample.models.dir=" + modelsDir.get().asFile.absolutePath,
    )
}
