package com.demo.stayintegration;

import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import com.demo.mocksupplier.MockSupplierApplication;
import com.demo.mocksupplier.control.MockMode;
import com.demo.mocksupplier.control.MockModeRegistry;

// :mock-supplier를 같은 JVM의 두 번째 Spring 컨텍스트로 띄운다. 별도 프로세스가 아니어도 "자기 자신 호출" 문제와 무관하다 —
// 포트가 다르고 톰캣 인스턴스가 다르다. 모드 전환은 HTTP 제어 API 대신 레지스트리 빈을 직접 만진다(같은 코드 경로, 왕복 없음).
public final class MockSupplierServer {

	private static MockSupplierServer instance;

	private final ConfigurableApplicationContext context;

	private MockSupplierServer(ConfigurableApplicationContext context) {
		this.context = context;
	}

	// 테스트 클래스마다 새로 띄우지 않는다. 한 JVM에서 하나면 충분하고, 종료는 Boot의 shutdown hook이 한다.
	public static synchronized MockSupplierServer start() {
		if (instance == null) {
			// 커맨드라인 인자로 주는 이유: builder.properties()는 "기본값"이라 클래스패스의 application.yaml(server.port 8080)에 진다.
			// 인자는 최고 우선순위라 어떤 yaml이 잡히든 이긴다.
			ConfigurableApplicationContext context = new SpringApplicationBuilder(MockSupplierApplication.class)
					.bannerMode(Banner.Mode.OFF)
					.run("--server.port=0",
							// Mock 컨텍스트가 본체의 테스트 클래스패스(JPA·PostgreSQL 드라이버)를 그대로 보므로 DataSource 자동설정이
							// 접속 정보를 찾다 실패한다. 이것 하나만 빼면 Hibernate·JPA 리포지토리 자동설정은 DataSource 빈 조건으로 스스로 빠진다.
							"--spring.autoconfigure.exclude=" + DataSourceAutoConfiguration.class.getName(),
							// 두 모듈의 application.yaml이 클래스패스에 함께 있어 Mock 것이 아니라 본체 것이 잡힌다. Mock 설정은 명시한다.
							"--mock.api-key.a=mock-key-a",
							"--mock.api-key.b=mock-key-b",
							"--mock.delay-default-ms=3000");
			instance = new MockSupplierServer(context);
		}
		return instance;
	}

	public int port() {
		return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
	}

	public String baseUrl() {
		return "http://localhost:" + port();
	}

	public void setMode(String supplier, String api, MockMode mode) {
		registry().set(supplier, api, mode, 0);
	}

	public void delay(String supplier, String api, long delayMs) {
		registry().set(supplier, api, MockMode.DELAY, delayMs);
	}

	public void reset() {
		registry().reset();
	}

	private MockModeRegistry registry() {
		return context.getBean(MockModeRegistry.class);
	}
}
