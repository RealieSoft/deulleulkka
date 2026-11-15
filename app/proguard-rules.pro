# 출시 APK 줄이기(R8) 규칙. 들를까 코드 중 쓰지 않는 것(엔진의 재생 시나리오 등)만 빠지게 한다.

# Pleos SDK: 기기 안 서비스와 AIDL binder·Parcelable·kotlinx.serialization으로 주고받아 클래스·필드 이름을 그대로 쓴다. 통째로 둔다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# kotlinx.serialization(Pleos 데이터 클래스가 씀): 직렬화기와 Companion을 둔다
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# android.car는 기기에 있는 라이브러리(useLibrary)라 APK에 들어가지 않는다
-dontwarn android.car.**

# 로그에 남기는 흐름 명령·단계 이름은 읽을 수 있게 이름만 둔다(줄이기는 그대로)
-keepnames class app.deulleulkka.core.FlowCommand$*
-keepnames class app.deulleulkka.core.Phase$*
