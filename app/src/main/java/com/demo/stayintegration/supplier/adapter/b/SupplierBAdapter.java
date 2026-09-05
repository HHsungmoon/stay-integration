package com.demo.stayintegration.supplier.adapter.b;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Envelope;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Properties;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Search;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.adapter.support.FailureClassifier;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipeline;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties;
import com.demo.stayintegration.supplier.adapter.support.SupplierWebClients;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import reactor.core.publisher.Mono;

// B는 장애여도 HTTP 200이다. 판정은 봉투의 resultCode로 하고(normalizer.unwrap), 2xx가 아닌 응답은 스펙 밖이지만 A와 같은 규칙으로 방어한다.
@Component
public class SupplierBAdapter implements SupplierAdapter {

	public static final SupplierId ID = new SupplierId("b");

	private static final String PROPERTIES_PATH = "/b/api/properties";
	private static final String SEARCH_PATH = "/b/api/search";
	private static final int DETAIL_BODY_LIMIT = 200;

	private static final ParameterizedTypeReference<Envelope<Properties>> PROPERTIES_ENVELOPE = new ParameterizedTypeReference<>() {};
	private static final ParameterizedTypeReference<Envelope<Search>> SEARCH_ENVELOPE = new ParameterizedTypeReference<>() {};

	private final WebClient webClient;
	private final SupplierCallPipeline pipeline;
	private final SupplierBNormalizer normalizer = new SupplierBNormalizer();

	public SupplierBAdapter(SupplierWebClients supplierWebClients, SupplierProperties supplierProperties) {
		this.webClient = supplierWebClients.forSupplier(ID);
		this.pipeline = new SupplierCallPipeline(ID, supplierProperties.endpointOf(ID).responseTimeout());
	}

	@Override
	public SupplierId id() {
		return ID;
	}

	@Override
	public Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog() {
		return pipeline.execute(webClient.get().uri(PROPERTIES_PATH)
				.exchangeToMono(response -> exchange(response, PROPERTIES_ENVELOPE, normalizer::toCatalog)));
	}

	@Override
	public Mono<SupplierResult<AvailabilityResult>> fetchAvailability(AvailabilityQuery query) {
		if (query.exceedsCodeLimit()) {
			return Mono.just(new SupplierResult.Failure<>(ID, FailureKind.BAD_REQUEST,
					query.hotelCodes().size() + " property ids exceed the limit of " + AvailabilityQuery.MAX_CODES, Duration.ZERO));
		}
		return pipeline.execute(webClient.get()
				.uri(uriBuilder -> uriBuilder.path(SEARCH_PATH)
						.queryParam("propertyIds", String.join(",", query.hotelCodes()))
						.queryParam("checkIn", query.checkIn())
						.queryParam("checkOut", query.checkOut())
						.queryParam("adults", query.adults())
						.queryParam("children", query.children())
						.build())
				.exchangeToMono(response -> exchange(response, SEARCH_ENVELOPE, body -> normalizer.toAvailability(query, body))));
	}

	private <D, R> Mono<CallOutcome<R>> exchange(ClientResponse response, ParameterizedTypeReference<Envelope<D>> envelopeType,
			Function<D, CallOutcome<R>> normalize) {
		HttpStatusCode status = response.statusCode();
		if (status.is2xxSuccessful()) {
			return response.bodyToMono(envelopeType).map(envelope -> SupplierBNormalizer.unwrap(envelope, normalize));
		}
		// 스펙상 오지 않는 응답이라 정해진 본문 형태가 없다. 있는 그대로 잘라서 남긴다.
		FailureKind kind = FailureClassifier.fromStatus(status);
		return response.bodyToMono(String.class)
				.onErrorResume(unreadable -> Mono.just(""))
				.defaultIfEmpty("")
				.map(body -> CallOutcome.failed(kind, "HTTP " + status.value() + (body.isBlank() ? "" : " " + abbreviate(body))));
	}

	private static String abbreviate(String body) {
		String singleLine = body.strip().replaceAll("\\s+", " ");
		return singleLine.length() <= DETAIL_BODY_LIMIT ? singleLine : singleLine.substring(0, DETAIL_BODY_LIMIT) + "…";
	}
}
