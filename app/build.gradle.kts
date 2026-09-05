plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

group = "com.demo"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// WebFlux는 전면 도입이 아니라 WebClient(공급사 병렬 호출·타임아웃 제어)를 쓰기 위한 것이다.
	// 웹 계층은 아래 webmvc(블로킹) 그대로 둔다.
	implementation("org.springframework.boot:spring-boot-starter-webclient")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// 테스트는 실제 PostgreSQL 컨테이너를 띄워 검증한다.
	// 매핑의 upsert 멱등성·유니크 제약이 이 프로젝트의 불변 조건인데,
	// 인메모리 DB로는 방언 차이 때문에 "테스트는 통과하는데 운영에서 깨지는" 상황이 생긴다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	// 어댑터의 타임아웃·연결 거부·본문 코드 판정은 스텁이 아니라 실제 와이어로 검증해야 한다.
	// Mock 모듈을 같은 JVM의 두 번째 컨텍스트로 띄운다 — base package가 달라 스캔이 겹치지 않고, 포트·톰캣이 따로다.
	testImplementation(project(":mock-supplier"))
	// 아키텍처 경계 5개(CLAUDE.md)를 빌드에서 강제한다. 1.5.0 미만은 Java 25 바이트코드를 파싱하지 못한다.
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Java 25는 Netty의 네이티브 라이브러리 로딩(restricted method)에 경고를 낸다. 허용을 명시해 빌드 출력에서 지운다.
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// deprecation 경고를 파일명만 아니라 어느 API인지까지 보여준다.
tasks.withType<JavaCompile> {
	options.compilerArgs.add("-Xlint:deprecation")
}
