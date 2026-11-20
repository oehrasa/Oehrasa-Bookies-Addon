plugins {
    id("dev.kikugie.loom-back-compat")
}

val modName = property("mod.name") as String
val modGroup = property("mod.group") as String
val modVersion = property("mod.version") as String
val mcCompat = property("mod.mc_compat") as String

version = "${sc.current.version}-${modVersion}"
base.archivesName = "AutoBookshelf"
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
    // On obfuscated (1.21.x) nodes loom must remap it like a mod dependency;
    // the 26.x nodes are already Mojang-mapped so this is an identity remap there.
    modCompileOnly("meteordevelopment:baritone:${property("deps.baritone")}")

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
    // Obfuscated nodes (1.21.x) need the remapped-named access widener; the
    // unobfuscated 26.x nodes keep the official-namespace one.
    accessWidenerPath =
        rootProject.layout.projectDirectory.file(if (sc.current.parsed < "26.1") "src/main/resources/oehrasa.named.accesswidener" else "src/main/resources/oehrasa.accesswidener")
}

tasks {
    processResources {
        val artifactVersion = "${sc.current.version}-$modVersion"
        // Obfuscated nodes must declare the named-namespace widener.
        val namedNode = sc.current.parsed < "26.1"
        val props = mapOf(
            "version" to artifactVersion,
            "minecraft" to mcCompat,
            "java" to requiredJava.majorVersion,
            "mod_name" to modName,
            "accessWidener" to if (namedNode) "oehrasa.named.accesswidener" else "oehrasa.accesswidener",
        )

        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }

        // Ship exactly one access widener: the one matching this node's mappings.
        exclude(if (namedNode) "oehrasa.accesswidener" else "oehrasa.named.accesswidener")
    }

    jar {
        // Local capture: config-cache can't serialize closures that reach back into the
        // script object graph, so give the renamed file a plain local val instead.
        inputs.property("archivesName", "AutoBookshelf")

        from("LICENSE") {
            rename { "${it}_AutoBookshelf" }
        }
        // The vendored AGPL, Apache and MIT texts for the portions under those terms, plus
        // the notice that attributes them. All ship inside the jar.
        from("third-party/licenses") {
            into("META-INF/licenses")
        }
        from("THIRD_PARTY_NOTICES.md") {
            into("META-INF")
            rename { "THIRD_PARTY_NOTICES_AutoBookshelf.md" }
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
