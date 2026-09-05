package com.demo.stayintegration.supplier.adapter.a;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Availability;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Hotels;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.adapter.support.FailureClassifier;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipeline;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipelines;
import com.demo.stayintegration.supplier.adapter.support.SupplierWebClients;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import reactor.core.publisher.Mono;

// A는 실패를 HTTP 상태로 알린다. 상태를 보고 판정하되 에러 본문({error, message})도 읽어 detail에 담는다.
@Component
public class SupplierAAdapter implements SupplierAdapter {

	public static final SupplierId ID = new SupplierId("a");

	private static final String HOTELS_PATH = "/a/v1/hotels";
	private static final String AVAILABILITY_PATH = "/a/v1/availability";

	private final WebClient webClient;
	private final SupplierCallPipeline pipeline;
	private final SupplierANormalizer normalizer = new SupplierANormalizer();

	public SupplierAAdapter(SupplierWebClients supplierWebClients, SupplierCallPipelines supplierCallPipelines) {
		this.webClient = supplierWebClients.forSupplier(ID);
		this.pipeline = supplierCallPipelines.forSupplier(ID);
	}

	@Override
	public SupplierId id() {
		return ID;
	}

	@Override
	public Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog() {
		return pipeline.execute(webClient.get().uri(HOTELS_PATH)
				.exchangeToMono(response -> exchange(response, Hotels.class, normalizer::toCatalog)));
	}

	@Override
	public Mono<SupplierResult<AvailabilityResult>> fetchAvailability(AvailabilityQuery query) {
		// 뻔히 400으로 끝날 호출을 보내지 않는다. 분할은 search의 책임이지만 공급사 계약을 아는 것은 어댑터다.
		if (query.exceedsCodeLimit()) {
			return Mono.just(new SupplierResult.Failure<>(ID, FailureKind.BAD_REQUEST,
					query.hotelCodes().size() + " hotel codes exceed the limit of " + AvailabilityQuery.MAX_CODES, Duration.ZERO));
		}
		return pipeline.execute(webClient.get()
				.uri(uriBuilder -> uriBuilder.path(AVAILABILITY_PATH)
						.queryParam("hotelCodes", String.join(",", query.hotelCodes()))
						.queryParam("checkIn", query.checkIn())
						.queryParam("checkOut", query.checkOut())
						.queryParam("adults", query.adults())
						.queryParam("children", query.children())
						.build())
				.exchangeToMono(response -> exchange(response, Availability.class, body -> normalizer.toAvailability(query, body))));
	}

	// retrieve()가 아니라 exchangeToMono인 이유: 4xx/5xx의 에러 본문을 읽어 detail에 담아야 하고,
	// 상태와 본문을 한 곳에서 보는 편이 B와 같은 골격을 쓸 수 있다.
	private <B, R> Mono<CallOutcome<R>> exchange(ClientResponse response, Class<B> bodyType, Function<B, CallOutcome<R>> normalize) {
		HttpStatusCode status = response.statusCode();
		if (status.is2xxSuccessful()) {
			return response.bodyToMono(bodyType).map(normalize);
		}
		FailureKind kind = FailureClassifier.fromStatus(status);
		return response.bodyToMono(SupplierAResponse.Error.class)
				.map(error -> "HTTP " + status.value() + " " + error.error() + ": " + error.message())
				// 게이트웨이가 낸 HTML 같은 비JSON 본문이면 상태만 남긴다. 판정은 상태로 이미 끝났다.
				.onErrorResume(unreadable -> Mono.just("HTTP " + status.value() + " (unreadable error body)"))
				.defaultIfEmpty("HTTP " + status.value())
				.map(detail -> CallOutcome.failed(kind, detail));
	}
}
