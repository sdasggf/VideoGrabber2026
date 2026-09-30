pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ffmpeg-kit 不在 Maven Central，需 arthenica 自有仓库（6.0 线唯一来源）
        maven { url = uri("https://maven.arthenica.com/artifactory/ffmpeg-kit") }
    }
}
rootProject.name = "VideoGrabber"
include(":app")
