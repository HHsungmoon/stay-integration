package com.demo.stayintegration.catalog;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "catalog")
public record CatalogProperties(
		// 테스트는 false로 둔다 — 컨텍스트가 뜰 때마다 공급사를 호출하면 테스트 간 간섭이 생긴다
		@DefaultValue("true") boolean syncOnStartup,
		// 공급사별 응답 타임아웃(2s)보다 길고, 검색 데드라인(D-7 3s)과 같은 값. 여기서 넘으면 해당 공급사만 실패 처리
		@DefaultValue("3s") Duration syncDeadline) {}
