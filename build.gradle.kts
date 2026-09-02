// 루트는 플러그인 버전만 한곳에 고정한다 (apply false — 루트 자체에는 적용하지 않음).
// 실제 적용과 의존성 선언은 각 모듈의 build.gradle.kts에서 한다.
plugins {
	id("org.springframework.boot") version "4.1.1" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}
