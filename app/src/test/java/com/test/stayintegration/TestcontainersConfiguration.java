package com.test.stayintegration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트용 PostgreSQL 컨테이너.
 *
 * <p>{@code spring-boot-docker-compose}는 developmentOnly 스코프라 테스트 클래스패스에
 * 없다. 그래서 테스트가 돌 때는 접속 정보를 줄 주체가 따로 필요하다.
 * {@link ServiceConnection}이 컨테이너의 host/port/계정을 DataSource에 자동으로 연결한다.
 *
 * <p>이미지 태그를 고정한 것은 latest가 어느 날 major 버전을 올려 빌드를 깨뜨리는 것을
 * 막기 위해서다. compose.yaml과 같은 버전을 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:18-alpine");
	}
}
