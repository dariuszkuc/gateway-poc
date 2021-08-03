plugins {
    id("org.jetbrains.kotlin.jvm") version "1.5.10"
    id("org.jetbrains.kotlin.plugin.spring") version "1.5.10"
}

version = "1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "kotlin-spring")

    tasks {
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = JavaVersion.VERSION_11.toString()
                freeCompilerArgs = listOf("-Xjsr305=strict", "-Xopt-in=org.mylibrary.OptInAnnotation")
            }
        }
    }

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib")
    }
}

project(":hello") {
    dependencies {
        implementation("com.expediagroup:graphql-kotlin-spring-server:5.0.0-alpha.0")
    }
}

project(":goodbye") {
    dependencies {
        implementation("com.expediagroup:graphql-kotlin-spring-server:5.0.0-alpha.0")
    }
}

project(":gateway") {
    dependencies {
        implementation("com.expediagroup:graphql-kotlin-server:5.0.0-alpha.0")
        implementation("com.graphql-java:graphql-java:16.2")
        implementation("org.springframework.boot:spring-boot-starter-webflux:2.5.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.5.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.5.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.5.0")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.3")
        implementation("com.graphql-java-kickstart:graphql-java-tools:11.0.1")
    }
}