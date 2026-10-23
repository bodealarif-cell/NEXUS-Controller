plugins { kotlin("jvm") version "2.0.20" }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// Dependency-free test runner (same code as tests/run-kotlin-tests.sh). `gradle nexusTests` exits non-zero on any failure.
val nexusTests by tasks.registering(JavaExec::class) {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.nexus.tests.TestMainKt")
}
tasks.named("check") { dependsOn(nexusTests) }
