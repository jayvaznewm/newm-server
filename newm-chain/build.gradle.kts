plugins {
    application
    id("com.github.ben-manes.versions")
    id("com.github.johnrengelman.shadow")
    id("org.jlleitschuh.gradle.ktlint")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

ktlint {
    version.set("0.43.2")
}

application {
    mainClass.set("io.newm.chain.ApplicationKt")
}

repositories {
    maven {
        name = "sonatypeSnapshots"
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencies {
    implementation(project(":newm-chain-db"))
    implementation(Dependencies.Newm.KOGMIOS)

    compileOnly(Dependencies.Kotlin.REFLECTION)
    implementation(Dependencies.Kotlin.STDLIB_JDK8)

    implementation(Dependencies.Coroutines.CORE)
    implementation(Dependencies.Coroutines.JDK8)

    implementation(Dependencies.KotlinSerialization.JSON)

    implementation(Dependencies.Ktor.SERVER_CORE)
    implementation(Dependencies.Ktor.SERVER_CIO)
//    implementation(Dependencies.Ktor.SERVER_CONTENT_NEGOTIATION)
//    implementation(Dependencies.Ktor.SERVER_LOCATIONS)
//    implementation(Dependencies.Ktor.SERVER_CALL_LOGGING)
//    implementation(Dependencies.Ktor.SERVER_AUTH)
//    implementation(Dependencies.Ktor.SERVER_AUTH_JWT)
//    implementation(Dependencies.Ktor.SERVER_HTML_BUILDER)
//    implementation(Dependencies.Ktor.CLIENT_CORE)
//    implementation(Dependencies.Ktor.CLIENT_CIO)
//    implementation(Dependencies.Ktor.CLIENT_CONTENT_NEGOTIATION)
//    implementation(Dependencies.Ktor.CLIENT_SERIALIZATION)
//    implementation(Dependencies.Ktor.SERIALIZATION)
//    implementation(Dependencies.Ktor.SERIALIZATION_JSON)
//    implementation(Dependencies.Ktor.SERVER_STATUS_PAGES)
//    implementation(Dependencies.Ktor.SERVER_CORS)

    implementation(Dependencies.Koin.KTOR)

    implementation(Dependencies.LogBack.CLASSIC)

    implementation(Dependencies.Exposed.CORE)
    implementation(Dependencies.Exposed.DAO)
    implementation(Dependencies.Exposed.JDBC)
    implementation(Dependencies.Exposed.TIME)

    implementation(Dependencies.HikariCP.ALL)
    implementation(Dependencies.PostgreSQL.ALL)
    implementation(Dependencies.KtorFlyway.ALL)
    implementation(Dependencies.FlywayDB.ALL)
    implementation(Dependencies.Sentry.CORE)
    implementation(Dependencies.Sentry.LOGBACK)
    implementation(Dependencies.ApacheCommonsEmail.ALL)
    implementation(Dependencies.JBCrypt.ALL)

    testImplementation(Dependencies.JUnit.JUPITER)
    testImplementation(Dependencies.Mockk.MOCKK)
    testImplementation(Dependencies.GoogleTruth.TRUTH)
    testImplementation(Dependencies.Ktor.SERVER_TESTS)
    testImplementation(Dependencies.Coroutines.TEST)
    testImplementation(Dependencies.Koin.JUNIT)
    testImplementation(Dependencies.TestContainers.CORE)
    testImplementation(Dependencies.TestContainers.JUINT)
    testImplementation(Dependencies.TestContainers.POSTGRESQL)
}

tasks {
    shadowJar {
        // defaults to project.name
        // archiveBaseName.set("${project.name}-fat")

        // defaults to all, so removing this overrides the normal, non-fat jar
        archiveClassifier.set("")
    }
}
