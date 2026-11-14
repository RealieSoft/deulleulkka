plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    // 무작위 입력으로 안전 규칙을 검증하는 데만 쓴다(앱에는 들어가지 않음). 6.x는 Kotlin 2.2 이상이 필요해 5.9.1로 고정
    testImplementation("io.kotest:kotest-property:5.9.1")
    // kotest-property가 실행 시점에 가져오는 판과 같다. 테스트에서 runBlocking으로 checkAll을 부르려고 컴파일에도 둔다
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    // 시나리오 재생기: ./gradlew :engine:run
    mainClass.set("app.deulleulkka.engine.replay.MainKt")
}
