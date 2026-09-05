package com.demo.stayintegration.catalog.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.CatalogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 기동 시 1회(D-8). ApplicationReadyEvent인 이유: 앱이 완전히 뜬 뒤라 여기서 무슨 일이 나도 기동은 이미 성공이다.
// 공급사가 전부 죽어 있어도 앱은 뜬다 — 매핑이 비어 검색 결과가 빌 뿐이고, 그건 장애가 아니라 "동기화 전 상태"다.
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogSyncRunner {

	private final CatalogSyncService catalogSyncService;
	private final CatalogProperties catalogProperties;

	@EventListener(ApplicationReadyEvent.class)
	public void syncOnStartup() {
		if (!catalogProperties.syncOnStartup()) {
			log.info("catalog sync on startup is disabled (catalog.sync-on-startup=false)");
			return;
		}
		try {
			catalogSyncService.sync();
		} catch (RuntimeException e) {
			// sync()는 실패를 값으로 돌려주므로 여기 올 일은 없어야 하지만, 기동 로그를 예외 스택으로 더럽히지 않기 위해 보수적으로 잡는다
			log.warn("startup catalog sync failed — serving with existing mappings", e);
		}
	}
}
