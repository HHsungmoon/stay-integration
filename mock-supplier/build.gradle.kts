plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

group = "com.test"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// 가짜 공급사 서버다. 고정 응답과 모드 전환만 하면 되므로 웹 계층 하나로 충분하다.
// DB·WebClient는 필요 없다.
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
