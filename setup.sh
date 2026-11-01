#!/bin/bash
# يرتّب الملفات المرفوعة ويُنشئ ملفات Gradle قبل البناء
set -e
D=app/src/main/java/com/example/gametranslator
mkdir -p $D
mv MainActivity.kt TranslateService.kt $D/
mv AndroidManifest.xml app/src/main/AndroidManifest.xml

cat > settings.gradle.kts << 'EOF'
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
    }
}
rootProject.name = "GameTranslator"
include(":app")
EOF

cat > build.gradle.kts << 'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF

cat > gradle.properties << 'EOF'
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
EOF

cat > app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gametranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gametranslator"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
EOF
