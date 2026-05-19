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

// UC07 § Feature E — `.deb` pipeline. jdeb 1.14's published jar at
// Maven Central contains ONLY the Maven plugin entry points and the
// Ant task class `org.vafer.jdeb.ant.DebAntTask` (empirically verified
// via `unzip -l` on the upstream artifact: no `org.vafer.jdeb.gradle.
// JdebTask` exists in 1.14). We therefore put jdeb on the buildscript
// classpath, register it as an Ant task, and drive it from a Gradle
// task via `ant.withGroovyBuilder { … }`. This keeps the build pure-
// Java (no dpkg-deb dependency on the build host) and matches jdeb's
// documented "Ant task" surface.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Pinned literal — version is duplicated in
        // gradle/libs.versions.toml (`jdeb = "1.14"`) for documentation.
        // Gradle's `libs.*` accessor is not reliably available inside a
        // subproject's buildscript block, so we use the literal here.
        // Drift between the two is caught by reviewers, not the build.
        classpath("org.vafer:jdeb:1.14")
    }
}

import org.apache.tools.ant.filters.ReplaceTokens
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
    // UC04 § B3 — `aisandboxctl client invite` encodes the {u,t,exp,pin}
    // payload into a QR (ASCII to stdout or PNG to --out). The dep was
    // already pinned in libs.versions.toml for the :android module
    // (ZXing chosen over ML Kit for guaranteed-zero GMS deps); reusing
    // the same artifact on the server saves vendoring ~1000 lines of
    // the Nayuki QR-Code-generator.
    implementation(libs.zxing.core)

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
    // UC05 § AC20 — systemd ExecStart points at /opt/ai-sandbox-server/lib/
    // aisandbox-server.jar. Drop the version from the filename; the version
    // still lives in the manifest's Implementation-Version (auto-populated
    // by Spring Boot's BootJar from project.version) and in the release zip
    // filename. AC32's "rm -rf /opt/.../lib && re-unzip" upgrade depends on
    // the install-path being version-free.
    archiveVersion.set("")
    // Override the default Implementation-Title ("server", from the Gradle
    // subproject name) so an operator running `unzip -p ... MANIFEST.MF`
    // can tell the two fat jars apart.
    manifest { attributes("Implementation-Title" to "aisandbox-server") }
    doLast {
        val jar = archiveFile.get().asFile
        val manifest = providers.exec {
            commandLine("unzip", "-p", jar.absolutePath, "META-INF/MANIFEST.MF")
        }.standardOutput.asText.get()
        check(manifest.contains("Implementation-Version:")) {
            "bootJar manifest is missing Implementation-Version (jar=$jar). " +
                "Spring Boot should have populated it from project.version."
        }
    }
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
    // UC05 § AC20 — same rationale as bootJar above; the install path
    // /opt/ai-sandbox-server/lib/aisandboxctl.jar must not encode the version.
    archiveVersion.set("")
    mainClass.set("com.aisandbox.server.cli.AisandboxctlCommand")
    classpath(sourceSets["main"].runtimeClasspath)
    targetJavaVersion.set(JavaVersion.toVersion(libs.versions.java.get()))
    // Same rationale as bootJar — distinguishable manifest title for
    // operators inspecting either jar.
    manifest { attributes("Implementation-Title" to "aisandboxctl") }
    doLast {
        val jar = archiveFile.get().asFile
        val manifest = providers.exec {
            commandLine("unzip", "-p", jar.absolutePath, "META-INF/MANIFEST.MF")
        }.standardOutput.asText.get()
        check(manifest.contains("Implementation-Version:")) {
            "aisandboxctlJar manifest is missing Implementation-Version (jar=$jar). " +
                "Spring Boot should have populated it from project.version."
        }
    }
}

tasks.named("assemble") {
    dependsOn(aisandboxctlJar)
}

// ── Release bundle ───────────────────────────────────────────────────────────
//
// UC05 § A — single point of bundling. The release zip is self-contained:
// jars + OAS + reference config + systemd unit + the entire UC02 host-
// script set (POSIX + PowerShell) + the container build context. Operators
// unzip to /opt/ai-sandbox-server/ and never need to clone the repo.
//
// Determinism (AC9): preserveFileTimestamps=false zeroes out mtimes inside
// the zip; reproducibleFileOrder=true sorts entries lexicographically.
// Combined with the LF/CRLF normalization in /.gitattributes, re-running
// the same tag produces a byte-identical zip.
val releaseBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Assembles the self-contained operator zip (jars + host scripts + Compose context + systemd + docs)."
    dependsOn("bootJar", aisandboxctlJar)
    archiveBaseName.set("ai-sandbox-server")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // UC05 § AC2,AC20 — exact filenames, no wildcards. With
    // `archiveVersion.set("")` the jars now build at fixed names; a glob
    // like `aisandbox-server*.jar` would additionally pick up any stale
    // versioned artifacts left over from earlier builds (or sources jars
    // created by `withSourcesJar()`) and ship a duplicate-jar zip.
    from(layout.buildDirectory.dir("libs")) {
        include("aisandbox-server.jar", "aisandboxctl.jar")
        into("lib")
    }
    from("$projectDir/openapi.yaml") { into(".") }
    from("$projectDir/STREAM_PROTOCOL.md") { into(".") }
    from("$projectDir/README.md") { into(".") }
    from("$projectDir/sample-config.yaml") { into(".") }
    from("$projectDir/systemd") { into("systemd") }
    // UC05 § AC3,AC5 — POSIX shell scripts shipped under host/ with mode 0755.
    from(rootProject.file("spawn.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("clean.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("attach.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("lib.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("setup.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("entrypoint.sh")) {
        into("host")
        filePermissions { unix("rwxr-xr-x") }
    }
    // UC05 § AC4 — PowerShell counterparts; not exec'd by v0.0.3 systemd
    // installer, kept byte-identical for a future Windows installer.
    from(rootProject.file("spawn.ps1")) { into("host") }
    from(rootProject.file("clean.ps1")) { into("host") }
    from(rootProject.file("attach.ps1")) { into("host") }
    from(rootProject.file("lib.ps1")) { into("host") }
    from(rootProject.file("setup.ps1")) { into("host") }
    // UC05 § AC5,AC6 — container build context. SandboxDockerfile sources
    // entrypoint.sh from the same dir; docker-compose.yml references
    // SandboxDockerfile by relative path; co-locating in host/ preserves
    // both relationships.
    from(rootProject.file("docker-compose.yml")) { into("host") }
    from(rootProject.file("SandboxDockerfile")) { into("host") }
    // UC05 § AC5,AC6 — SandboxDockerfile:42 does `COPY git-hooks/ /etc/git-hooks/`,
    // so git-hooks/ is part of the container build context and must ship in host/.
    // Exec bit preserves the convention (matches entrypoint.sh above); the
    // Dockerfile's `chmod +x` on line 43 would survive without it, but keeping
    // the bit set is the host-script pattern and avoids surprises if the chmod
    // is ever removed.
    from(rootProject.file("git-hooks")) {
        into("host/git-hooks")
        filePermissions { unix("rwxr-xr-x") }
    }
}

// ── Debian package (.deb) — UC07 Feature E ───────────────────────────────────
//
// Two-stage pipeline, decoupled so that file-mode preservation is
// trivial and the actual jdeb invocation stays declarative:
//
//   prepDebStaging  →  build/deb-staging/   (Gradle Copy task, per-pattern
//                                            unix() permissions)
//   debPackage      →  build/distributions/ai-sandbox-server_<v>_amd64.deb
//                      (Ant `deb` task; data block of type=directory
//                       walks the staging tree preserving FS modes)
//
// The staging tree is laid out exactly as the .deb will install it
// (rooted as if at /, with `opt/`, `lib/`, etc. children), so the jdeb
// data block needs only a single prefix-less mapper. Maintainer
// scripts live under server/debian/ on the source tree; jdeb's
// `control` attribute picks them up and rewrites them into the deb's
// control archive.

val debStagingDir = layout.buildDirectory.dir("deb-staging")
val debControlStagingDir = layout.buildDirectory.dir("deb-control")

// Templated copy of server/debian/ — `control` carries the @aiSandboxServerVersion@
// token which gets substituted with project.version. Maintainer scripts
// (postinst/prerm/postrm) are byte-copied with their +x mode preserved.
val prepDebControl by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Stages server/debian/ to build/deb-control/ with @aiSandboxServerVersion@ interpolated."
    val captured = project.version.toString()
    inputs.property("aiSandboxServerVersion", captured)

    from("$projectDir/debian") {
        include("control")
        // `filter(ReplaceTokens, …)` substitutes @aiSandboxServerVersion@ →
        // project.version. Using `expand()` instead would clash with the
        // `${shlibs:Depends}` syntax Debian's control format reserves.
        filter(ReplaceTokens::class, mapOf("tokens" to mapOf("aiSandboxServerVersion" to captured)))
    }
    from("$projectDir/debian") {
        include("postinst", "prerm", "postrm")
        filePermissions { unix("rwxr-xr-x") }
    }
    into(debControlStagingDir)
}

val prepDebStaging by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Stages the .deb install tree under build/deb-staging/ with the right per-file unix modes."
    dependsOn("bootJar", aisandboxctlJar, generateOpenApiDocs)
    into(debStagingDir)

    // /opt/ai-sandbox-server/lib/*.jar
    from(layout.buildDirectory.dir("libs")) {
        include("aisandbox-server.jar", "aisandboxctl.jar")
        into("opt/ai-sandbox-server/lib")
        filePermissions { unix("rw-r--r--") }
    }

    // /opt/ai-sandbox-server/*.md / openapi.yaml / sample-config.yaml
    from("$projectDir/openapi.yaml") {
        into("opt/ai-sandbox-server")
        filePermissions { unix("rw-r--r--") }
    }
    from("$projectDir/STREAM_PROTOCOL.md") {
        into("opt/ai-sandbox-server")
        filePermissions { unix("rw-r--r--") }
    }
    from("$projectDir/README.md") {
        into("opt/ai-sandbox-server")
        filePermissions { unix("rw-r--r--") }
    }
    from("$projectDir/sample-config.yaml") {
        into("opt/ai-sandbox-server")
        filePermissions { unix("rw-r--r--") }
    }

    // /opt/ai-sandbox-server/host/* — POSIX shell scripts at 0755,
    // PowerShell counterparts + the compose context at 0644.
    val hostExecutables = listOf("spawn.sh", "clean.sh", "attach.sh", "lib.sh", "setup.sh", "entrypoint.sh")
    hostExecutables.forEach { name ->
        from(rootProject.file(name)) {
            into("opt/ai-sandbox-server/host")
            filePermissions { unix("rwxr-xr-x") }
        }
    }
    val hostData = listOf(
        "spawn.ps1", "clean.ps1", "attach.ps1", "lib.ps1", "setup.ps1",
        "docker-compose.yml", "SandboxDockerfile",
    )
    hostData.forEach { name ->
        from(rootProject.file(name)) {
            into("opt/ai-sandbox-server/host")
            filePermissions { unix("rw-r--r--") }
        }
    }
    // git-hooks/ — preserve exec bit (matches releaseBundle).
    from(rootProject.file("git-hooks")) {
        into("opt/ai-sandbox-server/host/git-hooks")
        filePermissions { unix("rwxr-xr-x") }
    }

    // /lib/systemd/system/ai-sandbox-server.service — Debian-canonical
    // location for distro-provided units (vs /etc/systemd/system/ which
    // is reserved for local-admin overrides per systemd.unit(5)).
    from("$projectDir/systemd/ai-sandbox-server.service") {
        into("lib/systemd/system")
        filePermissions { unix("rw-r--r--") }
    }
}

val debPackage by tasks.registering {
    group = "distribution"
    description = "Builds ai-sandbox-server_<version>_amd64.deb via jdeb's Ant task."
    dependsOn(prepDebStaging, prepDebControl)

    val outFile = layout.buildDirectory
        .file("distributions/ai-sandbox-server_${project.version}_amd64.deb")
    val controlDir = debControlStagingDir
    val stagingRoot = debStagingDir
    val pkgVersion = project.version.toString()

    inputs.dir(stagingRoot)
    inputs.dir(controlDir)
    inputs.property("version", pkgVersion)
    outputs.file(outFile)

    doLast {
        // Ensure parent dir exists — jdeb does NOT mkdir for destfile.
        outFile.get().asFile.parentFile.mkdirs()

        // Register and invoke the jdeb Ant task. The DebAntTask's
        // public setters (`setDestfile`, `setControl`, `setVerbose`,
        // `addData(Data)`) are empirically confirmed via javap on
        // jdeb-1.14.jar; the Data type carries `src`/`type`/`addMapper(Mapper)`;
        // the Mapper type carries `type`/`prefix`/`fileMode`/`dirMode`/`user`/`group`.
        //
        // We stream one Data block of `type=directory` pointing at
        // build/deb-staging/. The mapper is `type=perm` with NO
        // fileMode/dirMode set, so jdeb preserves the FS modes set by
        // prepDebStaging — that's how we get 0755 on .sh files and
        // 0644 everywhere else without per-pattern Data blocks.
        // Ant has its own classloader and does NOT see the buildscript
        // classpath by default — taskdef must be told where jdeb lives.
        // The buildscript's `classpath` configuration carries every
        // dependency declared in `buildscript { dependencies { ... } }`
        // (including transitives), so we hand that whole FileCollection
        // to Ant as the colon-separated path attribute.
        val jdebClasspath = buildscript.configurations
            .getByName("classpath")
            .asPath
        ant.withGroovyBuilder {
            "taskdef"(
                "name" to "deb",
                "classname" to "org.vafer.jdeb.ant.DebAntTask",
                "classpath" to jdebClasspath,
            )
            "deb"(
                "destfile" to outFile.get().asFile,
                "control" to controlDir.get().asFile,
                "verbose" to false,
            ) {
                "data"(
                    "src" to stagingRoot.get().asFile,
                    "type" to "directory",
                ) {
                    "mapper"(
                        "type" to "perm",
                        "user" to "root",
                        "group" to "root",
                    )
                }
            }
        }

        logger.lifecycle("Built .deb: ${outFile.get().asFile} (version $pkgVersion)")
    }
}

// Wire .deb into `assemble` so a plain `:server:assemble` produces it
// alongside the two jars. `:server:build` includes `assemble`, so CI's
// existing `./gradlew :server:build` invocation automatically picks
// the .deb up. The release zip (`releaseBundle`) is unchanged.
tasks.named("assemble") {
    dependsOn(debPackage)
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}
