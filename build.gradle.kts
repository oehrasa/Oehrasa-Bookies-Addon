plugins {
    id("dev.kikugie.loom-back-compat")
}

val modId = property("mod.id") as String
val modName = property("mod.name") as String
val modGroup = property("mod.group") as String
val modVersion = property("mod.version") as String
val mcCompat = property("mod.mc_compat") as String

version = "$modVersion+${sc.current.version}"
base.archivesName = modId
group = modGroup

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

repositories {
    maven("https://maven.meteordev.org/releases") { name = "meteor-maven" }
    maven("https://maven.meteordev.org/snapshots") { name = "meteor-maven-snapshots" }
    mavenCentral()
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang mappings on the obfuscated (1.21.x) versions; no-op on 26.1+.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:${sc.current.version}-SNAPSHOT")

    // Baritone is a compile-only dependency: the addon integrates the shared
    // `baritone.api` surface but does not bundle it; any API provider works.
    compileOnly("meteordevelopment:baritone:${property("deps.baritone")}")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

loom {
    // Stonecutter nests each non-active node as a subproject, so paths must
    // be resolved against the root project that owns src/main/resources.
    accessWidenerPath = rootProject.layout.projectDirectory.file("src/main/resources/oehrasa.accesswidener")
}

tasks {
    processResources {
        val props = mapOf(
            "version" to modVersion,
            "minecraft" to mcCompat,
            "java" to requiredJava.majorVersion,
            "mod_name" to modName,
        )

        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }

    jar {
        // Local capture: config-cache can't serialize closures that reach back into the
        // script object graph (the `modId` val above), so give the renamed file a plain
        // local val instead.
        val baseNameForJar = modId
        inputs.property("archivesName", baseNameForJar)

        from("LICENSE") {
            rename { "${it}_${baseNameForJar}" }
        }
        // The vendored AGPL, Apache and MIT texts for the portions under those terms, plus
        // the notice that attributes them. All ship inside the jar.
        from("third-party/licenses") {
            into("META-INF/licenses")
        }
        from("THIRD_PARTY_NOTICES.md") {
            into("META-INF")
            rename { "THIRD_PARTY_NOTICES_${baseNameForJar}.md" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this version's jars and copies them to build/libs/{mod version}/ at the root"

        inputs.property("version", modVersion)
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
        dependsOn("build")
    }
}
