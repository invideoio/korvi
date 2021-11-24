buildscript {
    val easyPluginVersion: String by project

    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }

    dependencies {
        classpath("com.soywiz.korlibs:easy-kotlin-mpp-gradle-plugin:$easyPluginVersion")
    }
}

val buildExtraGradleFile = File(rootDir, "build.extra.gradle.kts")
if (buildExtraGradleFile.exists()) {
    apply(from = buildExtraGradleFile)
}
allprojects {
    repositories {
        maven {
            url = uri("https://maven.pkg.jetbrains.space/invideo/p/mobile/korgelibs")

            val spaceUsername = project.properties["space.username"] as String?
            val spacePassword = project.properties["space.password"] as String?
            credentials {
                username = spaceUsername
                password = spacePassword
            }
        }
    }
}
