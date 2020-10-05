import org.gradle.kotlin.dsl.`kotlin-dsl`
plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "9.4.0"
}
buildscript {
    repositories {
        mavenCentral()
        google()
        jcenter()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:4.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.0")
    }
}

allprojects {
    repositories {
        google()
        jcenter()
    }
}
