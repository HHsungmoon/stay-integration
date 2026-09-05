package com.test.mocksupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Mock의 계약을 고정한다. 본체 통합 테스트가 실패했을 때 Mock 탓인지 본체 탓인지 가르는 근거다.
// 실제 포트로 띄우는 이유: DeferredResult(무응답·지연)는 MockMvc로는 재현되지 않는다.
// 톰캣 스레드를 4개로 줄인 이유: 기본 200개면 Thread.sleep 구현이어도 요청 20개로는 안 막혀서
// "무응답이 스레드를 점유하지 않는다"를 증명할 수 없다.
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.tomcat.threads.max=4", "server.tomcat.threads.min-spare=1" })
class MockSupplierContractTest {

	private static final String KEY_A = "mock-key-a";
	private static final String KEY_B = "mock-key-b";
	// 오늘에 따라 결과가 달라지지 않게 상대 날짜를 쓴다(D-12와 같은 이유)
	private static final LocalDate IN = LocalDate.now().plusDays(30);

	@Value("${local.server.port}")
	int port;

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(2))
			.build();
	private final JsonMapper json = new JsonMapper();

	@BeforeEach
	@AfterEach
	void resetModes() throws Exception {
		post("/control/reset");
	}

	// ── 제어 ─────────────────────────────────────────────────────────────

	@Nested
	class Control {

		@Test
		void startsWithEveryApiInNormalMode() throws Exception {
			JsonNode state = body(get("/control", null));
			assertThat(state.size()).isEqualTo(4);
			for (String key : List.of("a.catalog", "a.availability", "b.catalog", "b.availability")) {
				assertThat(state.path(key).asString()).isEqualTo("normal");
			}
		}

		@Test
		void modeWithoutApiAppliesToBothApisOfThatSupplier() throws Exception {
			JsonNode state = body(post("/control/a/mode?value=error"));
			assertThat(state.path("a.catalog").asString()).isEqualTo("error");
			assertThat(state.path("a.availability").asString()).isEqualTo("error");
			assertThat(state.path("b.catalog").asString()).isEqualTo("normal");
		}

		@Test
		void modeWithApiAppliesToThatApiOnly() throws Exception {
			JsonNode state = body(post("/control/a/mode?value=error&api=catalog"));
			assertThat(state.path("a.catalog").asString()).isEqualTo("error");
			assertThat(state.path("a.availability").asString()).isEqualTo("normal");
		}

		@Test
		void delayModeShowsItsDelay() throws Exception {
			JsonNode state = body(post("/control/b/mode?value=delay&delayMs=1500"));
			assertThat(state.path("b.availability").asString()).isEqualTo("delay(1500ms)");
		}

		@Test
		void rejectsUnknownSupplierApiOrValue() throws Exception {
			assertThat(post("/control/c/mode?value=error").statusCode()).isEqualTo(400);
			assertThat(post("/control/a/mode?value=error&api=search").statusCode()).isEqualTo(400);
			assertThat(post("/control/a/mode?value=broken").statusCode()).isEqualTo(400);
		}

		@Test
		void resetReturnsEverythingToNormal() throws Exception {
			post("/control/a/mode?value=error");
			post("/control/b/mode?value=no-response");
			JsonNode state = body(post("/control/reset"));
			for (JsonNode v : state) {
				assertThat(v.asString()).isEqualTo("normal");
			}
		}
	}

	// ── Supplier A 정상 ───────────────────────────────────────────────────

	@Nested
	class SupplierANormal {

		@Test
		void hotelsListsTwoHotelsAndSharesRoomTypeCodeAcrossHotels() throws Exception {
			HttpResponse<String> res = get("/a/v1/hotels", KEY_A);
			assertThat(res.statusCode()).isEqualTo(200);
			JsonNode items = body(res).path("items");
			assertThat(items.size()).isEqualTo(2);

			JsonNode riverside = items.get(0);
			assertThat(riverside.path("hotelCode").asString()).isEqualTo("A-10023");
			assertThat(strings(riverside.path("roomTypes"), "roomTypeCode")).containsExactly("DLX-TWN", "STD-DBL", "FAM-STE");

			// STD-DBL이 두 숙소에 있다 — 객실 타입 코드는 숙소 안에서만 유일하다는 계약을 데이터가 강제한다
			JsonNode namsan = items.get(1);
			assertThat(namsan.path("hotelCode").asString()).isEqualTo("A-10044");
			assertThat(strings(namsan.path("roomTypes"), "roomTypeCode")).containsExactly("STD-DBL");
		}

		@Test
		void availabilityFollowsRequestedDatesAndDeterministicPatterns() throws Exception {
			HttpResponse<String> res = get(aAvailability("A-10023,A-10044", IN, IN.plusDays(3), 2, 0), KEY_A);
			assertThat(res.statusCode()).isEqualTo(200);
			JsonNode items = body(res).path("items");
			assertThat(items.size()).isEqualTo(4);

			JsonNode dlx = items.get(0);
			assertThat(dlx.path("roomTypeCode").asString()).isEqualTo("DLX-TWN");
			assertThat(dlx.path("currency").asString()).isEqualTo("KRW");
			assertThat(dlx.path("breakfastIncluded").asBoolean()).isFalse();
			// 날짜는 고정값이 아니라 요청 기간을 따른다 — 아니면 D-5(누락 날짜 0)가 정상 케이스를 매진으로 만든다
			assertThat(strings(dlx.path("dailyRates"), "date"))
					.containsExactly(IN.toString(), IN.plusDays(1).toString(), IN.plusDays(2).toString());
			assertThat(ints(dlx.path("dailyRates"), "remainingRooms")).containsExactly(3, 1, 5);
			assertThat(ints(dlx.path("dailyRates"), "nightlyRate")).containsExactly(120_000, 150_000, 120_000);
			assertThat(ints(dlx.path("dailyRates"), "taxAmount")).containsExactly(12_000, 15_000, 12_000);

			JsonNode namsan = items.get(3);
			assertThat(namsan.path("hotelCode").asString()).isEqualTo("A-10044");
			assertThat(ints(namsan.path("dailyRates"), "remainingRooms")).containsExactly(2, 0, 4);
		}

		@Test
		void patternsCycleForAnyLengthOfStay() throws Exception {
			// A-10044 STD-DBL [2,0,4]: 1박이면 예약 가능, 2박부터 매진, 4박이면 순환
			assertThat(namsanRooms(1)).containsExactly(2);
			assertThat(namsanRooms(2)).containsExactly(2, 0);
			assertThat(namsanRooms(4)).containsExactly(2, 0, 4, 2);
		}

		@Test
		void filtersRoomTypesByTotalGuests() throws Exception {
			JsonNode three = body(get(aAvailability("A-10023,A-10044", IN, IN.plusDays(1), 3, 0), KEY_A)).path("items");
			assertThat(strings(three, "roomTypeCode")).containsExactly("FAM-STE");

			// 성인+아동 합산 기준 — adults=2, children=1도 3명이다
			JsonNode twoPlusOne = body(get(aAvailability("A-10023", IN, IN.plusDays(1), 2, 1), KEY_A)).path("items");
			assertThat(strings(twoPlusOne, "roomTypeCode")).containsExactly("FAM-STE");

			JsonNode five = body(get(aAvailability("A-10023,A-10044", IN, IN.plusDays(1), 3, 2), KEY_A)).path("items");
			assertThat(five.size()).isZero();
		}

		@Test
		void ignoresUnknownHotelCodes() throws Exception {
			HttpResponse<String> res = get(aAvailability("A-10023,ZZZ-404", IN, IN.plusDays(1), 2, 0), KEY_A);
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(strings(body(res).path("items"), "hotelCode")).containsOnly("A-10023");
		}

		private List<Integer> namsanRooms(int nights) throws Exception {
			JsonNode items = body(get(aAvailability("A-10044", IN, IN.plusDays(nights), 2, 0), KEY_A)).path("items");
			return ints(items.get(0).path("dailyRates"), "remainingRooms");
		}
	}

	// ── Supplier A 거절: HTTP 상태 코드로 ────────────────────────────────

	@Nested
	class SupplierARejections {

		@Test
		void missingOrWrongApiKeyIs401() throws Exception {
			assertThat(get("/a/v1/hotels", null).statusCode()).isEqualTo(401);
			HttpResponse<String> wrong = get("/a/v1/hotels", "nope");
			assertThat(wrong.statusCode()).isEqualTo(401);
			assertThat(body(wrong).path("error").asString()).isEqualTo("UNAUTHORIZED");
		}

		@Test
		void fiftyCodesPassAndFiftyOneAreRejected() throws Exception {
			assertThat(get(aAvailability(codes(50), IN, IN.plusDays(1), 2, 0), KEY_A).statusCode()).isEqualTo(200);

			HttpResponse<String> res = get(aAvailability(codes(51), IN, IN.plusDays(1), 2, 0), KEY_A);
			assertThat(res.statusCode()).isEqualTo(400);
			assertThat(body(res).path("error").asString()).isEqualTo("TOO_MANY_HOTEL_CODES");
		}

		@Test
		void checkOutNotAfterCheckInIsInvalidDateRange() throws Exception {
			for (LocalDate out : List.of(IN, IN.minusDays(1))) {
				HttpResponse<String> res = get(aAvailability("A-10023", IN, out, 2, 0), KEY_A);
				assertThat(res.statusCode()).isEqualTo(400);
				assertThat(body(res).path("error").asString()).isEqualTo("INVALID_DATE_RANGE");
			}
		}

		@Test
		void malformedOrMissingParametersAreInvalidParameter() throws Exception {
			String base = "/a/v1/availability?hotelCodes=A-10023";
			for (String tail : List.of(
					"&checkIn=not-a-date&checkOut=" + IN.plusDays(1) + "&adults=2&children=0",
					"&checkOut=" + IN.plusDays(1) + "&adults=2&children=0",              // checkIn 누락
					"&checkIn=" + IN + "&checkOut=" + IN.plusDays(1) + "&adults=-1&children=0",
					"&checkIn=" + IN + "&checkOut=" + IN.plusDays(1) + "&adults=two&children=0")) {
				HttpResponse<String> res = get(base + tail, KEY_A);
				assertThat(res.statusCode()).as(tail).isEqualTo(400);
				assertThat(body(res).path("error").asString()).as(tail).isEqualTo("INVALID_PARAMETER");
			}
		}

		@Test
		void rejectionsIgnoreMode() throws Exception {
			post("/control/a/mode?value=error");
			assertThat(get("/a/v1/hotels", "nope").statusCode()).isEqualTo(401);
			post("/control/a/mode?value=no-response");
			assertThat(get(aAvailability(codes(51), IN, IN.plusDays(1), 2, 0), KEY_A).statusCode()).isEqualTo(400);
		}
	}

	// ── Supplier B 정상 ───────────────────────────────────────────────────

	@Nested
	class SupplierBNormal {

		@Test
		void propertiesAreWrappedInEnvelope() throws Exception {
			HttpResponse<String> res = get("/b/api/properties", KEY_B);
			assertThat(res.statusCode()).isEqualTo(200);
			JsonNode root = body(res);
			assertThat(root.path("resultCode").asString()).isEqualTo("0000");
			assertThat(root.path("resultMessage").asString()).isEqualTo("SUCCESS");
			JsonNode items = root.path("data").path("items");
			assertThat(items.size()).isEqualTo(1);
			assertThat(items.get(0).path("propertyId").asString()).isEqualTo("B77120");
			assertThat(strings(items.get(0).path("rooms"), "roomId")).containsExactly("R-401", "R-402");
		}

		@Test
		void searchGivesGrossTotalScaledByNightsAndNoNightlyBreakdown() throws Exception {
			JsonNode threeNights = body(get(bSearch("B77120", IN, IN.plusDays(3), 2, 0), KEY_B)).path("data").path("items");
			assertThat(threeNights.size()).isEqualTo(2);

			JsonNode r401 = threeNights.get(0);
			assertThat(r401.path("roomId").asString()).isEqualTo("R-401");
			assertThat(r401.path("totalPrice").asLong()).isEqualTo(155_000L * 3);
			assertThat(r401.path("taxIncluded").asBoolean()).isTrue();
			assertThat(r401.path("breakfastIncluded").asBoolean()).isTrue();
			assertThat(ints(r401.path("inventory"), "remainingRooms")).containsExactly(3, 1, 5);
			assertThat(strings(r401.path("inventory"), "date")).containsExactly(IN.toString(), IN.plusDays(1).toString(), IN.plusDays(2).toString());
			// B에는 날짜별 요금이 없다 — 스펙 그대로
			assertThat(r401.path("inventory").get(0).path("nightlyRate").isMissingNode()).isTrue();

			JsonNode oneNight = body(get(bSearch("B77120", IN, IN.plusDays(1), 2, 0), KEY_B)).path("data").path("items");
			assertThat(oneNight.get(0).path("totalPrice").asLong()).isEqualTo(155_000L);
		}

		@Test
		void filtersRoomsByTotalGuests() throws Exception {
			JsonNode items = body(get(bSearch("B77120", IN, IN.plusDays(1), 4, 0), KEY_B)).path("data").path("items");
			assertThat(strings(items, "roomId")).containsExactly("R-402");
		}
	}

	// ── Supplier B 거절: 항상 HTTP 200 ───────────────────────────────────

	@Nested
	class SupplierBRejections {

		@Test
		void wrongApiKeyIs200WithE401() throws Exception {
			HttpResponse<String> res = get("/b/api/properties", "nope");
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(body(res).path("resultCode").asString()).isEqualTo("E401");
			assertThat(body(res).path("data").isNull()).isTrue();
		}

		@Test
		void tooManyCodesIs200WithE400() throws Exception {
			HttpResponse<String> res = get(bSearch(codes(51), IN, IN.plusDays(1), 2, 0), KEY_B);
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(body(res).path("resultCode").asString()).isEqualTo("E400");
		}

		@Test
		void malformedDateIs200WithE400NotSpring400() throws Exception {
			// 파라미터를 String으로 받는 유일한 이유. LocalDate로 받았으면 Spring이 여기서 400을 줬다.
			HttpResponse<String> res = get("/b/api/search?propertyIds=B77120&checkIn=garbage&checkOut=" + IN + "&adults=2&children=0", KEY_B);
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(body(res).path("resultCode").asString()).isEqualTo("E400");
			assertThat(body(res).path("data").isNull()).isTrue();
		}

		@Test
		void invalidDateRangeIs200WithE400() throws Exception {
			HttpResponse<String> res = get(bSearch("B77120", IN, IN, 2, 0), KEY_B);
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(body(res).path("resultCode").asString()).isEqualTo("E400");
		}
	}

	// ── 모드 ─────────────────────────────────────────────────────────────

	@Nested
	class Modes {

		@Test
		void errorModeIsExpressedDifferentlyPerSupplier() throws Exception {
			post("/control/a/mode?value=error");
			post("/control/b/mode?value=error");

			HttpResponse<String> a = get(aAvailability("A-10023", IN, IN.plusDays(1), 2, 0), KEY_A);
			assertThat(a.statusCode()).isEqualTo(503);
			assertThat(body(a).path("error").asString()).isEqualTo("SERVICE_UNAVAILABLE");

			// 둘을 같게 만들면 어댑터의 "실패 판정 통일"을 증명할 수 없다
			HttpResponse<String> b = get(bSearch("B77120", IN, IN.plusDays(1), 2, 0), KEY_B);
			assertThat(b.statusCode()).isEqualTo(200);
			assertThat(body(b).path("resultCode").asString()).isEqualTo("E503");
			assertThat(body(b).path("data").isNull()).isTrue();
		}

		@Test
		void errorOnOneSupplierLeavesTheOtherIntact() throws Exception {
			post("/control/a/mode?value=error");
			assertThat(body(get("/b/api/properties", KEY_B)).path("resultCode").asString()).isEqualTo("0000");
		}

		@Test
		void errorScopedToCatalogLeavesAvailabilityWorking() throws Exception {
			// D-8: 동기화(숙소 목록)만 실패하고 검색(재고·요금)은 살아 있는 상황
			post("/control/a/mode?value=error&api=catalog");
			assertThat(get("/a/v1/hotels", KEY_A).statusCode()).isEqualTo(503);
			assertThat(get(aAvailability("A-10023", IN, IN.plusDays(1), 2, 0), KEY_A).statusCode()).isEqualTo(200);
		}

		@Test
		void noResponseNeverAnswersWithinClientTimeout() throws Exception {
			post("/control/a/mode?value=no-response");
			assertThatThrownBy(() -> get("/a/v1/hotels", KEY_A, Duration.ofSeconds(2)))
					.isInstanceOf(HttpTimeoutException.class);
		}

		@Test
		void noResponseDoesNotOccupyServerThreads() throws Exception {
			// 톰캣 스레드는 4개. Thread.sleep 구현이었다면 아래 20개가 스레드를 전부 붙잡아 B 요청이 밀린다.
			post("/control/a/mode?value=no-response");
			List<CompletableFuture<HttpResponse<String>>> held = IntStream.range(0, 20)
					.mapToObj(i -> http.sendAsync(request("/a/v1/hotels", KEY_A, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString()))
					.toList();
			Thread.sleep(300);
			assertThat(held).noneMatch(CompletableFuture::isDone);

			long started = System.nanoTime();
			HttpResponse<String> b = get("/b/api/properties", KEY_B);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;

			assertThat(b.statusCode()).isEqualTo(200);
			assertThat(elapsedMs).isLessThan(1_000);
		}

		@Test
		void resetReleasesPendingNoResponseWith503() throws Exception {
			post("/control/a/mode?value=no-response");
			CompletableFuture<HttpResponse<String>> pending = http.sendAsync(
					request("/a/v1/hotels", KEY_A, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString());
			Thread.sleep(300);
			assertThat(pending.isDone()).isFalse();

			post("/control/reset");

			HttpResponse<String> released = pending.get(3, TimeUnit.SECONDS);
			assertThat(released.statusCode()).isEqualTo(503);
		}

		@Test
		void delayModeAnswersNormallyAfterTheDelay() throws Exception {
			post("/control/b/mode?value=delay&delayMs=700");
			long started = System.nanoTime();
			HttpResponse<String> res = get("/b/api/properties", KEY_B);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;

			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(body(res).path("resultCode").asString()).isEqualTo("0000");
			assertThat(elapsedMs).isBetween(650L, 3_000L);
		}
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private HttpResponse<String> get(String path, String apiKey) throws Exception {
		return get(path, apiKey, Duration.ofSeconds(10));
	}

	private HttpResponse<String> get(String path, String apiKey, Duration timeout) throws Exception {
		return http.send(request(path, apiKey, timeout), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest request(String path, String apiKey, Duration timeout) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(timeout).GET();
		if (apiKey != null) {
			b.header("X-Api-Key", apiKey);
		}
		return b.build();
	}

	private HttpResponse<String> post(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.POST(HttpRequest.BodyPublishers.noBody()).build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode body(HttpResponse<String> res) {
		return json.readTree(res.body());
	}

	private static String aAvailability(String codes, LocalDate in, LocalDate out, int adults, int children) {
		return "/a/v1/availability?hotelCodes=" + codes + "&checkIn=" + in + "&checkOut=" + out + "&adults=" + adults + "&children=" + children;
	}

	private static String bSearch(String codes, LocalDate in, LocalDate out, int adults, int children) {
		return "/b/api/search?propertyIds=" + codes + "&checkIn=" + in + "&checkOut=" + out + "&adults=" + adults + "&children=" + children;
	}

	private static String codes(int n) {
		return IntStream.rangeClosed(1, n).mapToObj(i -> "X" + i).collect(Collectors.joining(","));
	}

	private static List<String> strings(JsonNode array, String field) {
		List<String> out = new ArrayList<>();
		for (JsonNode n : array) out.add(n.path(field).asString());
		return out;
	}

	private static List<Integer> ints(JsonNode array, String field) {
		List<Integer> out = new ArrayList<>();
		for (JsonNode n : array) out.add(n.path(field).asInt());
		return out;
	}
}
