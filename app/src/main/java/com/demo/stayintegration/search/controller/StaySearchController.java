package com.demo.stayintegration.search.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.service.StaySearchService;

import lombok.RequiredArgsConstructor;

// 파라미터를 @ModelAttribute record로 묶지 않고 넷을 펼친 이유: 누락(MissingServletRequestParameterException)과 형식 오류
// (MethodArgumentTypeMismatchException)를 프레임워크가 구분해 주고, 계약의 파라미터 이름이 시그니처에 그대로 드러난다.
// record 바인딩은 둘 다 필드 오류 하나로 뭉개져 code를 나누려면 오류 코드를 파고들어야 한다. D-13 검증은 SearchRequest 생성자가 한다.
@RestController
@RequestMapping("/api/v1/stays")
@RequiredArgsConstructor
public class StaySearchController {

	private final StaySearchService staySearchService;

	@GetMapping("/search")
	public ResponseEntity<SearchResponse> search(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
			@RequestParam int adults,
			@RequestParam int children) {
		SearchRequest request = new SearchRequest(checkIn, checkOut, adults, children);
		// 유일한 block. 톰캣 워커 위에서, 공급사별 데드라인이 이미 체인 안에 걸려 있어 상한 없이 기다려도 유한하다.
		SearchResponse response = staySearchService.search(request).block();
		ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
		if (!response.cacheable()) {
			builder.cacheControl(CacheControl.noStore());
		}
		return builder.body(response);
	}
}
