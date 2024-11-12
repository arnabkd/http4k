import groovy.namespace.QName
import groovy.util.Node
import org.gradle.api.JavaVersion.VERSION_1_8
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration
import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.net.URI
import java.time.Duration

plugins {
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    id("org.jetbrains.dokka")

    id("http4k-conventions")
    id("license-check")
    id("publishing")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    dependencies {
        classpath(Kotlin.gradlePlugin)
        classpath("org.openapitools:openapi-generator-gradle-plugin:_")
        classpath("org.jetbrains.kotlin:kotlin-serialization:_")
        classpath("gradle.plugin.com.github.johnrengelman:shadow:_")
        classpath("org.jetbrains.dokka:dokka-base:_")
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "kotlin")
    apply(plugin = "org.gradle.jacoco")
    apply(plugin = "java-test-fixtures")

    repositories {
        mavenCentral()
    }

    version = project.properties["releaseVersion"] ?: "LOCAL"
    group = "org.http4k"

    jacoco {
        toolVersion = "0.8.12"
    }

    tasks {
        withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JVM_1_8)
            }
        }

        java {
            sourceCompatibility = VERSION_1_8
            targetCompatibility = VERSION_1_8
        }

        withType<Test> {
            useJUnitPlatform()
            jvmArgs = listOf("--enable-preview")
        }

        named<JacocoReport>("jacocoTestReport") {
            reports {
                html.required.set(true)
                xml.required.set(true)
                csv.required.set(false)
            }
        }

        withType<GenerateModuleMetadata> {
            enabled = false
        }
    }

    dependencies {
        testImplementation(Testing.junit.jupiter.api)
        testImplementation(Testing.junit.jupiter.engine)
        testImplementation("com.natpryce:hamkrest:_")

        testFixturesImplementation(Testing.junit.jupiter.api)
        testFixturesImplementation(Testing.junit.jupiter.engine)
        testFixturesImplementation("com.natpryce:hamkrest:_")
    }
}

subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "idea")
    apply(plugin = "http4k-conventions")

    if (hasAnArtifact(project)) {

        if (!project.name.contains("serverless")) {
            apply(plugin = "org.jetbrains.dokka")
        }

        apply(plugin = "license-check")
        apply(plugin = "publishing")
        apply(plugin = "maven-publish") // required to upload to sonatype

        publishing {
            repositories {
                maven {
                    name = "http4k"
                    url = URI("s3://http4k-maven")

                    val ltsPublishingUser: String? by project
                    val ltsPublishingPassword: String? by project

                    credentials(AwsCredentials::class.java) {
                        accessKey = ltsPublishingUser
                        secretKey = ltsPublishingPassword
                    }
                }
            }

            publications {
                val javaComponent = components["java"] as AdhocComponentWithVariants

                javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
                javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

                val archivesBaseName = tasks.jar.get().archiveBaseName.get()
                create<MavenPublication>("mavenJava") {
                    artifactId = archivesBaseName
                    pom.withXml {
                        asNode().appendNode("name", archivesBaseName)
                        asNode().appendNode("description", description)
                        asNode().appendNode("url", "https://http4k.org")
                        asNode().appendNode("developers")
                            .appendNode("developer").appendNode("name", "Ivan Sanchez").parent()
                            .appendNode("email", "ivan@http4k.org")
                            .parent().parent()
                            .appendNode("developer").appendNode("name", "David Denton").parent()
                            .appendNode("email", "david@http4k.org")
                        asNode().appendNode("scm")
                            .appendNode("url", "https://github.com/http4k/http4k").parent()
                            .appendNode("connection", "scm:git:git@github.com:http4k/http4k.git").parent()
                            .appendNode("developerConnection", "scm:git:git@github.com:http4k/http4k.git")
                        asNode().appendNode("licenses").appendNode("license")
                            .appendNode("name", "Apache License, Version 2.0").parent()
                            .appendNode("url", "http://www.apache.org/licenses/LICENSE-2.0.html")
                    }
                    from(components["java"])

                    // replace all runtime dependencies with provided
                    pom.withXml {
                        asNode()
                            .childrenCalled("dependencies")
                            .flatMap { it.childrenCalled("dependency") }
                            .flatMap { it.childrenCalled("scope") }
                            .forEach { if (it.text() == "runtime") it.setValue("provided") }
                    }
                    artifact(tasks.named("sourcesJar"))
                    artifact(tasks.named("javadocJar"))
                }
            }
        }
    }

    sourceSets {
        test {
            kotlin.srcDir("$projectDir/src/examples/kotlin")
        }
    }

}

tasks.register<JacocoReport>("jacocoRootReport") {
    dependsOn(subprojects.map { it.tasks.named<Test>("test").get() })

    sourceDirectories.from(subprojects.flatMap { it.the<SourceSetContainer>()["main"].allSource.srcDirs })
    classDirectories.from(subprojects.map { it.the<SourceSetContainer>()["main"].output })
    executionData.from(subprojects
        .filter { it.name != "http4k-bom" && hasAnArtifact(it) }
        .map {
            it.tasks.named<JacocoReport>("jacocoTestReport").get().executionData
        }
    )

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(file("${layout.buildDirectory}/reports/jacoco/test/jacocoRootReport.xml"))
    }
}

dependencies {
    subprojects
        .filter { hasAnArtifact(it) }
        .forEach {
            api(project(it.name))
            testImplementation(testFixtures(project(it.name)))
        }
}

fun hasAnArtifact(it: Project) = !it.name.contains("test-function") && !it.name.contains("integration-test")

sourceSets {
    test {
        kotlin.srcDir("$projectDir/src/docs")
        resources.srcDir("$projectDir/src/docs")
    }
}

tasks.register("listProjects") {
    doLast {
        subprojects
            .filter { hasAnArtifact(it) }
            .forEach { System.err.println(it.name) }
    }
}

tasks.named("checkLicense") {
    onlyIf {
        project != rootProject
    }
}
fun Node.childrenCalled(wanted: String) = children()
    .filterIsInstance<Node>()
    .filter {
        val name = it.name()
        (name is QName) && name.localPart == wanted
    }

tasks {
    named<KotlinJvmCompile>("compileTestKotlin").configure {
        if (name == "compileTestKotlin") {
            compilerOptions {
                jvmTarget.set(JVM_1_8)
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }
    }
}

val nexusUsername: String? by project
val nexusPassword: String? by project

nexusPublishing {
    repositories {
        sonatype {
            username.set(nexusUsername)
            password.set(nexusPassword)
        }
    }
    transitionCheckOptions {
        maxRetries.set(150)
        delayBetween.set(Duration.ofSeconds(5))
    }
}

tasks.withType<DokkaMultiModuleTask>().configureEach {
    pluginConfiguration<DokkaBase, DokkaBaseConfiguration> {
        moduleVersion.set(version.toString())
        customAssets = listOf(file("src/docs/img/favicon-mono.png"))
        footerMessage = "(c) 2024 http4k"
        homepageLink = "https://http4k.org"
        customStyleSheets = listOf(file("src/docs/css/dokka.css"))
    }
}
