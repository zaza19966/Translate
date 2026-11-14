#!/bin/bash
# يبني تطبيق التلفزيون المستقل (خفيف) داخل مجلد tvapp من نفس ملفات المستودع
set -e
P=tvapp
D=$P/app/src/main/java/com/example/gametranslator
R=$P/app/src/main/res
mkdir -p $D $R/mipmap-anydpi-v26 $R/drawable $R/values
cp TvActivity.kt TvService.kt Pairing.kt Prefs.kt $D/
cp TvManifest.xml $P/app/src/main/AndroidManifest.xml

cat > $P/settings.gradle.kts << 'EOF'
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
rootProject.name = "GameTranslatorTV"
include(":app")
EOF

cat > $P/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF

cat > $P/gradle.properties << 'EOF'
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
EOF

cat > $P/app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gametranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gametranslator.tv"
        minSdk = 29
        targetSdk = 30
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
    implementation("com.google.zxing:core:3.5.3")
}
EOF

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

# لافتة التطبيق في شاشة التلفزيون الرئيسية (320x180)
cat > $R/drawable/tv_banner.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp"
    android:height="180dp"
    android:viewportWidth="320"
    android:viewportHeight="180">
    <path
        android:fillColor="#1565C0"
        android:pathData="M0,0 H320 V180 H0 Z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M120,50 H200 A10,10 0 0 1 210,60 V100 A10,10 0 0 1 200,110 H150 L128,134 V110 H120 A10,10 0 0 1 110,100 V60 A10,10 0 0 1 120,50 Z" />
    <path
        android:fillColor="#1565C0"
        android:pathData="M124,66 H196 V74 H124 Z" />
    <path
        android:fillColor="#1565C0"
        android:pathData="M124,84 H172 V92 H124 Z" />
</vector>
EOF
