import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "com.mira"
version = "0.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

val miraCoreVersion = "0.2.0"
val miraCoreSha256 = "66433a266a76088d2a2de90ac1beb1a5a183c26891ee8f394827b47830195b03"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

val miraLeaderboardsVersion = "0.1.1"
val miraLeaderboardsSha256 = "5aa21464ae66757fb235b86bf32d080dfb1f9239a8236468005b41be2fa7ec9a"
val miraLeaderboardsJar = layout.projectDirectory.file("libs/MiraLeaderboards-$miraLeaderboardsVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

val downloadMiraCore by tasks.registering {
    doLast {
        if (miraCoreJar.exists() && sha256(miraCoreJar) == miraCoreSha256) return@doLast
        miraCoreJar.parentFile.mkdirs()
        URI("https://github.com/FiveSOCE/MIra-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar").toURL().openStream().use { input ->
            miraCoreJar.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(miraCoreJar) == miraCoreSha256) { "Downloaded MiraCore JAR failed SHA-256 verification" }

        if (!miraLeaderboardsJar.exists() || sha256(miraLeaderboardsJar) != miraLeaderboardsSha256) {
            miraLeaderboardsJar.parentFile.mkdirs()
            URI("https://github.com/FiveSOCE/Mira-Leaderboards/releases/download/v$miraLeaderboardsVersion/MiraLeaderboards-$miraLeaderboardsVersion.jar").toURL().openStream().use { input ->
                miraLeaderboardsJar.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(miraLeaderboardsJar) == miraLeaderboardsSha256) { "Downloaded MiraLeaderboards JAR failed SHA-256 verification" }
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraLeaderboardsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraDaily-${project.version}.jar") }

tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
