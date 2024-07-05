import java.util.*

plugins {
    java
    id("org.jetbrains.intellij") version "1.17.2"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.devoxx.genie"
version = "0.2.4"

repositories {
    mavenCentral()
}

tasks.register("updateProperties") {
    doLast {
        val projectVersion = version
        val propertiesFile = file("src/main/resources/application.properties")

        if (propertiesFile.exists()) {
            val properties = Properties().apply {
                load(propertiesFile.inputStream())
            }

            properties.setProperty("version", projectVersion.toString())

            properties.store(propertiesFile.outputStream(), null)
        } else {
            println("application.properties file not found!")
        }
    }
}

tasks.named("buildPlugin") {
    dependsOn("updateProperties")
}

dependencies {
    // Add the dependencies for the core module
    implementation("com.devoxx.genie:core:0.1.16")

    implementation("dev.langchain4j:langchain4j:0.32.0")
    implementation("dev.langchain4j:langchain4j-ollama:0.32.0")
    implementation("dev.langchain4j:langchain4j-local-ai:0.32.0")
    implementation("dev.langchain4j:langchain4j-open-ai:0.32.0")
    implementation("dev.langchain4j:langchain4j-anthropic:0.32.0")
    implementation("dev.langchain4j:langchain4j-mistral-ai:0.32.0")
    implementation("dev.langchain4j:langchain4j-web-search-engine-google-custom:0.32.0")
    implementation("dev.langchain4j:langchain4j-web-search-engine-tavily:0.32.0")

    implementation("com.knuddels:jtokkit:1.0.0")
    implementation("org.commonmark:commonmark:0.22.0")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0-M2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("io.github.cdimascio:dotenv-java:3.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0-M2")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.3.4")
    type.set("IC")
    plugins.set(listOf("java", "markdown"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
    }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("242.*")
    }

    shadowJar {
        mergeServiceFiles()
        manifest {
            attributes(
                "Implementation-Title" to "DevoxxGenie",
                "Implementation-Version" to version,
            )
        }
    }

    test {
        useJUnitPlatform()
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
