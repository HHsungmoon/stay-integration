package com.demo.stayintegration.supplier.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.demo.stayintegration.supplier.adapter.support.SupplierProperties;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Endpoint;
import com.demo.stayintegration.supplier.adapter.support.SupplierWebClients;

import reactor.core.publisher.Mono;

// MockWebServer 대신 Spring 자체의 ExchangeFunction으로 응답을 만든다 — 네트워크 없이 상태 코드·본문 조합을 전부 만들 수 있고
// 의존성이 늘지 않는다. 실제 타임아웃·연결 거부는 Mock 모듈 통합 테스트(실제 와이어)가 맡는다.
public final class StubExchange implements ExchangeFunction {

	public static final String BASE_URL = "http://supplier.test";
	public static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(300);

	public final List<ClientRequest> requests = new ArrayList<>();
	private Supplier<Mono<ClientResponse>> next = () -> Mono.error(new IllegalStateException("no response configured"));

	@Override
	public Mono<ClientResponse> exchange(ClientRequest request) {
		requests.add(request);
		return next.get();
	}

	public void respond(HttpStatus status, String json) {
		next = () -> Mono.just(ClientResponse.create(status)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(json)
				.build());
	}

	public void respondWithoutContentType(HttpStatus status, String body) {
		next = () -> Mono.just(ClientResponse.create(status).body(body).build());
	}

	public void respondEmpty(HttpStatus status) {
		next = () -> Mono.just(ClientResponse.create(status).build());
	}

	public void neverRespond() {
		next = Mono::never;
	}

	public void fail(Throwable error) {
		next = () -> Mono.error(error);
	}

	public ClientRequest onlyRequest() {
		if (requests.size() != 1) {
			throw new AssertionError("expected exactly one request, got " + requests.size());
		}
		return requests.get(0);
	}

	// 실제 SupplierWebClients를 그대로 쓴다 — baseUrl·X-Api-Key가 붙는 것까지 같은 코드로 검증된다.
	// exchangeFunction이 설정된 Builder는 clientConnector를 무시하므로 Netty 커넥터는 만들어지되 쓰이지 않는다.
	public SupplierWebClients webClients(String supplierId, String apiKey) {
		SupplierProperties properties = properties(supplierId, apiKey);
		return new SupplierWebClients(WebClient.builder().exchangeFunction(this), properties);
	}

	public static SupplierProperties properties(String supplierId, String apiKey) {
		return new SupplierProperties(Map.of(supplierId, new Endpoint(BASE_URL, apiKey, Duration.ofMillis(100), RESPONSE_TIMEOUT)));
	}
}
