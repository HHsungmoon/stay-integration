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

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// WebFlux는 전면 도입이 아니라 WebClient(공급사 병렬 호출·타임아웃 제어)를 쓰기 위한 것이다.
	// 웹 계층은 아래 webmvc(블로킹) 그대로 둔다.
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// 테스트는 실제 PostgreSQL 컨테이너를 띄워 검증한다.
	// 매핑의 upsert 멱등성·유니크 제약이 이 프로젝트의 불변 조건인데,
	// 인메모리 DB로는 방언 차이 때문에 "테스트는 통과하는데 운영에서 깨지는" 상황이 생긴다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// deprecation 경고를 파일명만 아니라 어느 API인지까지 보여준다.
tasks.withType<JavaCompile> {
	options.compilerArgs.add("-Xlint:deprecation")
}
