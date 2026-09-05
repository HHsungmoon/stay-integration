package com.demo.stayintegration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.demo.stayintegration.search.controller.StaySearchController;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.PriceResponse;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.StayItem;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.search.service.StaySearchService;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 계약 경로·400 code·Cache-Control만 본다. 서비스는 MockitoBean — 병합 로직은 assembler 테스트가 맡는다.
@WebMvcTest(StaySearchController.class)
class StaySearchControllerTest {

	private static final String VALID = "/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0";
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);

	@Autowired MockMvc mvc;
	@MockitoBean StaySearchService staySearchService;
	private final JsonMapper json = new JsonMapper();

	private static SearchResponse response(SearchResponse.Status status, List<StayItem> items) {
		return new SearchResponse(status, CHECK_IN, CHECK_IN.plusDays(3), 3, 2, 0,
				List.of(new SupplierOutcome("a", SupplierOutcome.Status.SUCCESS, 1, 0, 0, 84, items.size(), 0, 0, null)), items);
	}

	private static StayItem item() {
		return new StayItem(1, "Riverside Hotel Seoul", 1, "Deluxe Twin", 2, 1, true, "a",
				new PriceResponse("KRW", 429_000, true, false, 3), null);
	}

	@Test
	void okResponseWithItemsIsReturnedAsIsAndIsCacheable() throws Exception {
		when(staySearchService.search(any())).thenReturn(Mono.just(response(SearchResponse.Status.OK, List.of(item()))));

		MvcResult result = mvc.perform(get(VALID)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).isNull();
		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("status").asString()).isEqualTo("OK");
		assertThat(body.path("checkIn").asString()).isEqualTo("2026-09-01");
		assertThat(body.path("nights").asInt()).isEqualTo(3);
		assertThat(body.path("suppliers").get(0).path("supplier").asString()).isEqualTo("a");
		JsonNode first = body.path("items").get(0);
		assertThat(first.path("propertyId").asLong()).isEqualTo(1);
		assertThat(first.path("roomTypeName").asString()).isEqualTo("Deluxe Twin");
		assertThat(first.path("available").asBoolean()).isTrue();
		assertThat(first.path("price").path("totalAmount").asLong()).isEqualTo(429_000);
		assertThat(first.path("priceDetail").isNull()).isTrue();   // nullable이 계약이다 — 필드가 사라지면 안 된다

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(staySearchService).search(captor.capture());
		assertThat(captor.getValue()).isEqualTo(new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 0));
	}

	@ParameterizedTest
	@CsvSource({ "PARTIAL, true", "ALL_FAILED, true", "OK, false" })
	void nonOkOrEmptyResponsesAreNoStore(SearchResponse.Status status, boolean withItems) throws Exception {
		when(staySearchService.search(any())).thenReturn(Mono.just(response(status, withItems ? List.of(item()) : List.of())));

		MvcResult result = mvc.perform(get(VALID)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);   // 전 공급사 실패도 200
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
	}

	@ParameterizedTest
	@CsvSource({
			"checkOut=2026-09-04&adults=2&children=0,                          MISSING_PARAMETER, checkIn",
			"checkIn=2026-09-01&checkOut=2026-09-04&adults=2,                  MISSING_PARAMETER, children",
			"checkIn=2026-13-40&checkOut=2026-09-04&adults=2&children=0,       INVALID_PARAMETER, checkIn",
			"checkIn=2026-09-01&checkOut=2026-09-04&adults=two&children=0,     INVALID_PARAMETER, adults",
			"checkIn=2026-09-01&checkOut=2026-09-04&adults=0&children=0,       INVALID_PARAMETER, adults",
			"checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=-1,      INVALID_PARAMETER, children",
			"checkIn=2026-09-01&checkOut=2026-09-01&adults=2&children=0,       INVALID_DATE_RANGE, checkOut",
			"checkIn=2026-09-01&checkOut=2026-10-02&adults=2&children=0,       TOO_MANY_NIGHTS, 30" })
	void invalidRequestsAre400WithTheRuleCodeAndNeverReachTheService(String query, String expectedCode, String messageFragment) throws Exception {
		MvcResult result = mvc.perform(get("/api/v1/stays/search?" + query)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("code").asString()).isEqualTo(expectedCode);
		assertThat(body.path("message").asString()).contains(messageFragment);
		verifyNoInteractions(staySearchService);
	}

	@Test
	void pastCheckInIsNotRejected() throws Exception {
		when(staySearchService.search(any())).thenReturn(Mono.just(response(SearchResponse.Status.OK, List.of(item()))));

		MvcResult result = mvc.perform(get("/api/v1/stays/search?checkIn=2000-01-01&checkOut=2000-01-03&adults=1&children=0")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}
}
