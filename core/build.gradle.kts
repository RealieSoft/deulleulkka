plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

// 묻고 답하는 흐름(상태 기계). Android에 기대지 않아 단위 테스트로 덮고, 앱은 SDK 호출과 화면만 맡는다.
dependencies {
    api(project(":engine"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
