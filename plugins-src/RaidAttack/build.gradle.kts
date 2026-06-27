plugins {
    `java`
}

group = "com.raeyd"
// SINGLE SOURCE OF TRUTH for the RaidAttack / Minecraft version. Everything else (plugin.yml,
// docs) derives from this — never hard-code the version anywhere else. The plugin version
// mirrors the Minecraft version it targets.
version = "26.1.2"

java {
    // Paper 26.1+ ships its API compiled for Java 25, so we must compile against it with
    // a Java 25 toolchain (class-file version 69). Anything older fails with "class file
    // has wrong version 69.0, should be N.0".
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Paper bundles the API + transitive deps (Adventure, etc.) under server/libraries/ after
// the first boot. The paperclip jar and the extracted server jar don't expose the API
// classes directly, so we compile against the libraries tree.
val serverLibsDir = rootProject.projectDir.resolve("../../server/libraries").normalize()
// Citizens API for turret-NPC management. Compile-only — Citizens is loaded by the server at
// runtime and declared as a softdepend in plugin.yml so HomeSystem can still load without it
// (turret entities just won't spawn).
val citizensJar = rootProject.projectDir.resolve("../../server/plugins/Citizens-2.0.42-b4180.jar").normalize()
// Floodgate API for Bedrock detection + gamertag resolution (cross-play accounts). Compile-only —
// the Floodgate plugin provides it at runtime; declared as a softdepend in plugin.yml.
val floodgateJar = rootProject.projectDir.resolve("../../server/plugins/floodgate-spigot.jar").normalize()
// SkinsRestorer API — used to re-apply a linked Bedrock player's own skin (a linked player is seen
// as Java, so Floodgate would otherwise look up the offline RID's nonexistent Mojang skin).
// Compile-only; SkinsRestorer provides it at runtime, declared as a softdepend in plugin.yml.
val skinsRestorerJar = rootProject.projectDir.resolve("../../server/plugins/SkinsRestorer.jar").normalize()

dependencies {
    compileOnly(fileTree(serverLibsDir) { include("**/*.jar") })
    compileOnly(files(citizensJar))
    compileOnly(files(floodgateJar))
    compileOnly(files(skinsRestorerJar))

    // Login-gate runtime deps. compileOnly here because Paper downloads them at runtime via the
    // `libraries:` block in plugin.yml (no shadow/fat-jar). Keep versions in lockstep with plugin.yml.
    compileOnly("org.postgresql:postgresql:42.7.4")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.mindrot:jbcrypt:0.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Inject the version (single source of truth above) into plugin.yml at build time, replacing the
// @pluginVersion@ token — so the version is never hard-coded in the resource.
tasks.processResources {
    val tokens = mapOf("pluginVersion" to version.toString())
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to tokens)
    }
}

tasks.jar {
    archiveBaseName.set("RaidAttack")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Convenience: `gradle deploy` copies the built jar into the local server's plugins folder.
tasks.register<Copy>("deploy") {
    dependsOn("jar")
    from(tasks.jar.get().archiveFile)
    into(file("${rootDir}/../../server/plugins"))
}
