plugins {
    kotlin("jvm") version "1.6.10"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "net.azisaba"
version = "1.0.0"

repositories {
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://repo.acrylicstyle.xyz/repository/maven-public/") }
    maven { url = uri("https://nexus.velocitypowered.com/repository/maven-public/") }
}

dependencies {
    implementation(kotlin("stdlib"))
    //implementation("xyz.acrylicstyle.util:all:0.16.5")
    implementation("xyz.acrylicstyle.util:yaml:0.16.5")
    implementation("xyz.acrylicstyle.util:promise:0.16.5")
    implementation("xyz.acrylicstyle.util:kotlin:0.16.5")
    implementation("xyz.acrylicstyle:sequelize4j:0.6.2") {
        exclude("xyz.acrylicstyle.util", "all")
    }
    implementation("org.mariadb.jdbc:mariadb-java-client:2.7.3")
    compileOnly("net.azisaba.library:velocity:1.1.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.0.1")
    annotationProcessor("com.velocitypowered:velocity-api:3.0.1")
}

tasks {
    compileKotlin { kotlinOptions.jvmTarget = "1.8" }
    compileTestKotlin { kotlinOptions.jvmTarget = "1.8" }

    shadowJar {
        relocate("kotlin", "net.azisaba.maxPlayerCounter.libs.kotlin")
        relocate("util", "net.azisaba.maxPlayerCounter.libs.util") { exclude("util.agent.JavaAgents") }
        relocate("xyz.acrylicstyle.sql", "net.azisaba.maxPlayerCounter.libs.xyz.acrylicstyle.sql")
        relocate("org.mariadb", "net.azisaba.maxPlayerCounter.libs.org.mariadb")
    }
}
