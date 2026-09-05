package com.demo.stayintegration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.search.dto.request.InvalidSearchRequestException;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;

// D-13 규칙 전부와 D-12(과거 체크인 허용). 검증은 생성자에 있으므로 "만들 수 있으면 유효한 요청"이다.
class SearchRequestTest {

	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);

	@Test
	void validRequestExposesNightsAndBuildsTheQuery() {
		SearchRequest request = new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 1);

		assertThat(request.nights()).isEqualTo(3);
		AvailabilityQuery query = request.toQuery(List.of("A-1", "A-2"));
		assertThat(query.hotelCodes()).containsExactly("A-1", "A-2");
		assertThat(query.checkIn()).isEqualTo(CHECK_IN);
		assertThat(query.checkOut()).isEqualTo(CHECK_IN.plusDays(3));
		assertThat(query.adults()).isEqualTo(2);
		assertThat(query.children()).isEqualTo(1);
	}

	@Test
	void adultsBelowOneIsRejectedAsInvalidParameter() {
		assertRejected(() -> new SearchRequest(CHECK_IN, CHECK_IN.plusDays(1), 0, 0), "INVALID_PARAMETER", "adults");
	}

	@Test
	void negativeChildrenIsRejectedAsInvalidParameter() {
		assertRejected(() -> new SearchRequest(CHECK_IN, CHECK_IN.plusDays(1), 1, -1), "INVALID_PARAMETER", "children");
	}

	@Test
	void checkOutNotAfterCheckInIsRejectedAsInvalidDateRange() {
		assertRejected(() -> new SearchRequest(CHECK_IN, CHECK_IN, 1, 0), "INVALID_DATE_RANGE", "checkOut");
		assertRejected(() -> new SearchRequest(CHECK_IN, CHECK_IN.minusDays(1), 1, 0), "INVALID_DATE_RANGE", "checkOut");
	}

	@Test
	void thirtyNightsIsTheUpperBound() {
		assertThatCode(() -> new SearchRequest(CHECK_IN, CHECK_IN.plusDays(30), 1, 0)).doesNotThrowAnyException();
		assertRejected(() -> new SearchRequest(CHECK_IN, CHECK_IN.plusDays(31), 1, 0), "TOO_MANY_NIGHTS", "30");
	}

	@Test
	void pastCheckInIsAllowed() {
		// D-12: 막으면 "오늘"에 따라 같은 요청의 성패가 갈려 테스트·문서 예시가 시간이 지나면 깨진다
		LocalDate longAgo = LocalDate.of(2000, 1, 1);
		assertThatCode(() -> new SearchRequest(longAgo, longAgo.plusDays(2), 1, 0)).doesNotThrowAnyException();
	}

	private static void assertRejected(Runnable construction, String expectedCode, String messageFragment) {
		assertThatThrownBy(construction::run)
				.isInstanceOfSatisfying(InvalidSearchRequestException.class, e -> {
					assertThat(e.code()).isEqualTo(expectedCode);
					assertThat(e.getMessage()).contains(messageFragment);
				});
	}
}
