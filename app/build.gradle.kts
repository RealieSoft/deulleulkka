plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.deulleulkka"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.deulleulkka"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // 차량 속도(PERF_VEHICLE_SPEED)를 읽는 Android 표준 차량 API. 기기에 있는 라이브러리를 쓰고 APK에는 넣지 않는다
    useLibrary("android.car")

    buildTypes {
        release {
            // 쓰지 않는 코드(엔진의 재생 시나리오 등)를 뺀다. Pleos SDK는 이름을 그대로 쓰므로 통째로 둔다(proguard-rules.pro)
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 줄인 판을 에뮬레이터에서 시험 입력으로 돌려 볼 때만 켠다(-PminifyDebug). 평소 debug는 줄이지 않는다
            if (project.hasProperty("minifyDebug")) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 묻고 답하는 흐름(core)과 판단 엔진(engine). 둘 다 Android에 기대지 않는 순수 Kotlin이다
    implementation(project(":core"))
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}
