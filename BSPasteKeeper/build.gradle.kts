plugins {
    java
}

group = "keeper"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // BetterStructures and WorldEdit are accessed reflectively, so only the Paper API is needed.
    compileOnly("io.papermc.paper:paper-api:26.3.build.194-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.jar {
    archiveFileName.set("BSPasteKeeper-${project.version}.jar")
}
