import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin.Companion.shadowJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.undefinedcreations.echo") version "0.0.11"
    id("com.gradleup.shadow") version "9.3.1"
}

group = "gg.aquatic.betterworlds"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    echo(
        "1.21.4",
        generateSource = true,
        generateDocs = true
    )

    // MessagePack for efficient serialization
    implementation("org.msgpack:msgpack-core:0.9.8")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.9.8")
}

kotlin {
    jvmToolchain(21)
}

tasks {

    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        filesMatching("paper-plugin.yml") {
            expand(getProperties())
            expand(mutableMapOf("version" to version))
        }
    }

    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    artifacts {
        archives(sourcesJar)
    }
}


tasks.withType<ShadowJar> {
    archiveFileName.set("BetterWorlds-${project.version}.jar")

    exclude("kotlin/**")
    exclude("org/intellij/**")
    exclude("org/jetbrains/**")
    exclude("com/fasterxml/**")
    exclude("org/msgpack/**")

    relocate("kotlinx", "gg.aquatic.waves.libs.kotlinx")
    relocate("org.jetbrains.kotlin", "gg.aquatic.waves.libs.kotlin")
    relocate("kotlin", "gg.aquatic.waves.libs.kotlin")

    //relocate("com.zaxxer.hikari", "gg.aquatic.waves.libs.hikari")

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/**",
    )
}