plugins {
    id("java")
    id("application")
    id("com.google.protobuf") version "0.9.6"
}

application {
    mainClass = "app.twintaillauncher.mobius.Main"
}

group = "app.twintaillauncher.mobius"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.34.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.github.luben:zstd-jni:1.5.7-3")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        proto {
            srcDir("proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.34.2"
    }
    generateProtoTasks {
        ofSourceSet("main").forEach { task ->
            task.generateDescriptorSet = true
            task.descriptorSetOptions.includeImports = true
            task.descriptorSetOptions.path =
                layout.buildDirectory.file("descriptors/mobius.desc").get().asFile.path
        }
    }
}

tasks.jar {
    archiveFileName = "mobius.jar"
    dependsOn("generateProto")
    from(layout.buildDirectory.file("descriptors/mobius.desc"))
    manifest {
        attributes("Main-Class" to "app.twintaillauncher.mobius.Main")
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("configs/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    exclude("aix/**", "freebsd/**")
    exclude("linux/arm/**", "linux/i386/**", "linux/loongarch64/**", "linux/mips64/**", "linux/ppc64/**", "linux/ppc64le/**", "linux/riscv64/**", "linux/s390x/**")
    exclude("win/x86/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("stageConfigs") {
    from(layout.projectDirectory.dir("src/main/resources/configs"))
    into(layout.buildDirectory.dir("libs/configs"))
}
// mobius.desc beside the jar overrides the bundled copy, so a deployed build
// can take a new provider's protos without mobius itself being rebuilt
tasks.register<Copy>("stageDescriptor") {
    dependsOn("generateProto")
    from(layout.buildDirectory.file("descriptors/mobius.desc"))
    into(layout.buildDirectory.dir("libs"))
}

tasks.jar { finalizedBy("stageConfigs", "stageDescriptor") }

tasks.named("startScripts") { mustRunAfter("stageConfigs", "stageDescriptor") }
tasks.named("distTar") { mustRunAfter("stageConfigs", "stageDescriptor") }
tasks.named("distZip") { mustRunAfter("stageConfigs", "stageDescriptor") }

tasks.test {
    useJUnitPlatform()
}
