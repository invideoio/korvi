plugins.withType<MavenPublishPlugin> {
    configure<PublishingExtension> {
        repositories {
            maven {
                url = uri("https://maven.pkg.jetbrains.space/invideo/p/mobile/korlibs")
                val spaceUsername = project.properties["space.username"] as String
                val spacePassword = project.properties["space.password"] as String
                credentials {
                    username = spaceUsername
                    password = spacePassword
                }
            }
        }
    }
}
