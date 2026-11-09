#!/bin/bash
# يرتّب الملفات المرفوعة ويُنشئ ملفات Gradle والأيقونة قبل البناء
set -e
D=app/src/main/java/com/example/gametranslator
R=app/src/main/res
mkdir -p $D $R/mipmap-anydpi-v26 $R/drawable $R/values
mv *.kt $D/
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
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
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
        versionCode = 3
        versionName = "3.0"
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
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
EOF

# ---- الأيقونة ----
cat > $R/values/colors.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1565C0</color>
</resources>
EOF

cat > $R/mipmap-anydpi-v26/ic_launcher.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF

cat > $R/drawable/ic_launcher_foreground.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="0.85"
        android:scaleY="0.85"
        android:pivotX="54"
        android:pivotY="54">
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M34,30 H74 A6,6 0 0 1 80,36 V60 A6,6 0 0 1 74,66 H50 L40,76 V66 H34 A6,6 0 0 1 28,60 V36 A6,6 0 0 1 34,30 Z" />
        <path
            android:fillColor="#1565C0"
            android:pathData="M36,40 H72 V44 H36 Z" />
        <path
            android:fillColor="#1565C0"
            android:pathData="M36,50 H60 V54 H36 Z" />
    </group>
</vector>
EOF
