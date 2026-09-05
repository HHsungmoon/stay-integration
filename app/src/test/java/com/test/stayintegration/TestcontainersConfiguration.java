package com.test.stayintegration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// spring-boot-docker-compose는 developmentOnly 스코프라 테스트 클래스패스에 없다.
	// 테스트에서 DataSource 접속 정보를 줄 주체가 따로 필요해 컨테이너를 직접 띄운다.
	// 인메모리 DB를 쓰지 않는 이유는 매핑의 upsert·유니크 제약이 불변 조건이기 때문이다.
	// 태그를 고정한 것은 latest가 major를 올려 빌드를 깨뜨리는 것을 막기 위해서다(compose.yaml과 동일).
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:18-alpine");
	}
}
