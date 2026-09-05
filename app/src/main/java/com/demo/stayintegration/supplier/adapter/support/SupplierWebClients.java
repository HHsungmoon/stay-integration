package com.demo.stayintegration.supplier.adapter.support;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Endpoint;
import com.demo.stayintegration.supplier.port.SupplierId;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

// WebClient 하나를 공급사가 공유하지 않는다 — 타임아웃이 공급사 단위 설정(D-7)이므로 클라이언트도 공급사 단위다.
// Boot가 자동설정한 Builder를 clone해서 쓰는 이유: Jackson 3 코덱과 Micrometer 계측을 그대로 물려받고 커넥터만 바꾼다.
@Component
public class SupplierWebClients {

	public static final String API_KEY_HEADER = "X-Api-Key";

	private final WebClient.Builder webClientBuilder;
	private final SupplierProperties supplierProperties;

	public SupplierWebClients(WebClient.Builder webClientBuilder, SupplierProperties supplierProperties) {
		this.webClientBuilder = webClientBuilder;
		this.supplierProperties = supplierProperties;
	}

	public WebClient forSupplier(SupplierId supplierId) {
		Endpoint endpoint = supplierProperties.endpointOf(supplierId);
		// 연결 타임아웃은 Netty 채널 옵션에만 걸 수 있다. responseTimeout은 응답 헤더 수신까지를 재므로
		// 느린 본문은 잡지 못한다 — 그쪽은 SupplierCallPipeline의 Reactor timeout이 맡는다(두 겹).
		HttpClient httpClient = HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(endpoint.connectTimeout().toMillis()))
				.responseTimeout(endpoint.responseTimeout());
		return webClientBuilder.clone()
				.baseUrl(endpoint.baseUrl())
				.defaultHeader(API_KEY_HEADER, endpoint.apiKey())
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.build();
	}
}
