plugins {
    kotlin("jvm") version "1.5.21"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "xyz.acrylicstyle"
version = "0.0.3"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://repo2.acrylicstyle.xyz") }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("xyz.acrylicstyle:java-util-kotlin:0.15.4")
    implementation("xyz.acrylicstyle:sequelize4j:0.4.6")
    implementation("org.mariadb.jdbc:mariadb-java-client:2.7.3")
    compileOnly("net.md-5:bungeecord-api:1.17-R0.1-SNAPSHOT")
}

tasks {
    compileKotlin { kotlinOptions.jvmTarget = "1.8" }
    compileTestKotlin { kotlinOptions.jvmTarget = "1.8" }

    shadowJar {
        relocate("kotlin", "xyz.acrylicstyle.maxPlayerCounter.libs.kotlin")
        relocate("util", "xyz.acrylicstyle.maxPlayerCounter.libs.util") { exclude("util.agent.JavaAgents") }
        relocate("xyz.acrylicstyle.mcutil", "xyz.acrylicstyle.maxPlayerCounter.libs.xyz.acrylicstyle.mcutil")
        relocate("xyz.acrylicstyle.sql", "xyz.acrylicstyle.maxPlayerCounter.libs.xyz.acrylicstyle.sql")
        relocate("org.mariadb", "xyz.acrylicstyle.maxPlayerCounter.libs.org.mariadb")

        minimize()
    }
}
