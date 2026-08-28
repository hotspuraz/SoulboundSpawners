plugins {
    id("java-library")
}

// The Paper API version the plugin is COMPILED against. A plugin built against
// an older API runs fine on newer servers, so we target the lowest version we
// still support and stay forward-compatible. Bump this and `targetJava` together
// once every server is on the newer release (and its paper-api artifact exists).
val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"
val targetJava = 21

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Vault and the auction/shop plugins are reached purely by reflection at
    // runtime (see evo.soulboundspawners.hook) so there is nothing to declare.
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJava)
    withSourcesJar()
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = targetJava
    }

    processResources {
        val props = mapOf("version" to project.version.toString())
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        archiveFileName = "SoulboundSpawners-${project.version}.jar"
    }
}

// To spin up a local test server from Gradle, add back the run-paper plugin
// (needs internet on first run):
//   plugins { id("xyz.jpenilla.run-paper") version "3.1.0" }
//   tasks.runServer { minecraftVersion("1.21.11") }
