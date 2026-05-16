// ai-sandbox/server — single Gradle subproject hosting the Java
// management server (UC03). Two executable jars are produced from one
// source tree:
//
//   * `aisandbox-server.jar` — Spring Boot bootstrapper.
//   * `aisandboxctl.jar`     — plain CLI (picocli + BouncyCastle); built
//                              via a second Boot-loader fat jar so we
//                              avoid a separate fat-jar plugin.
//
// Two jars, one source tree, two main classes. The full source set is
// repackaged into each fat jar — wasteful but acceptable for an MVP.

import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.spotless)
}

group = "com.aisandbox"
version = (project.findProperty("ai_sandbox_server_version") ?: "0.0.0-SNAPSHOT").toString()
description = "ai-sandbox mTLS management server (UC03)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    withSourcesJar()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http2)
    implementation(libs.springdoc.webflux.ui)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.pty4j)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.picocli)

    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.picocli.codegen)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.awaitility)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(libs.versions.java.get().toInt())
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-serial",
            "-parameters",
        ),
    )
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        endWithNewline()
    }
}
// Detach spotlessCheck from `check` so a clean `:server:build` doesn't
// require the formatter download on first run. CI runs `:spotlessCheck`
// explicitly.
tasks.named("check") {
    setDependsOn(dependsOn.filter {
        when (it) {
            is TaskProvider<*> -> it.name != "spotlessCheck"
            is String -> it != "spotlessCheck"
            is Task -> it.name != "spotlessCheck"
            else -> true
        }
    })
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    // QA owns integration tests; the *.java naming convention (*IT.java)
    // discriminates so the ordinary `:test` run doesn't execute them.
    exclude("**/*IT.class")
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs Testcontainers-based integration tests (StreamBridgeIT et al)."
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*IT.class")
    systemProperty("file.encoding", "UTF-8")
    if (System.getenv("AI_SANDBOX_DIND") != "1") {
        enabled = false
    }
}

// ── OAS generation (docs-only profile) ──────────────────────────────────────
val generateOpenApiDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Boots the server with --docs-only and dumps /v1/openapi.yaml to server/openapi.yaml."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aisandbox.server.docs.OpenApiDumpMain")
    args = listOf(
        "--spring.profiles.active=docs-only",
        "--output=${projectDir}/openapi.yaml",
    )
    jvmArgs = listOf("-Xms128m", "-Xmx512m", "-Dfile.encoding=UTF-8")
}

// ── Server fat jar ──────────────────────────────────────────────────────────
springBoot {
    mainClass.set("com.aisandbox.server.ServerApplication")
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("aisandbox-server")
    archiveClassifier.set("")
}

// Disable the plain `jar` to avoid producing an unrunnable artifact.
tasks.named<Jar>("jar") {
    enabled = false
}

// ── CLI fat jar — uses Spring Boot's BootJar with a different main ──────────
val aisandboxctlJar by tasks.registering(BootJar::class) {
    group = "build"
    description = "Builds aisandboxctl.jar (picocli CLI; same classpath, different main)."
    archiveBaseName.set("aisandboxctl")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mainClass.set("com.aisandbox.server.cli.AisandboxctlCommand")
    classpath(sourceSets["main"].runtimeClasspath)
    targetJavaVersion.set(JavaVersion.toVersion(libs.versions.java.get()))
}

tasks.named("assemble") {
    dependsOn(aisandboxctlJar)
}

// ── Release bundle ───────────────────────────────────────────────────────────
val releaseBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Assembles the operator-shipped release zip (jars + OAS + systemd + README)."
    dependsOn("bootJar", aisandboxctlJar)
    archiveBaseName.set("ai-sandbox-server")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    from(layout.buildDirectory.dir("libs")) {
        include("aisandbox-server*.jar", "aisandboxctl*.jar")
        into("lib")
    }
    from("$projectDir/openapi.yaml") { into(".") }
    from("$projectDir/STREAM_PROTOCOL.md") { into(".") }
    from("$projectDir/README.md") { into(".") }
    from("$projectDir/sample-config.yaml") { into(".") }
    from("$projectDir/systemd") { into("systemd") }
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}
