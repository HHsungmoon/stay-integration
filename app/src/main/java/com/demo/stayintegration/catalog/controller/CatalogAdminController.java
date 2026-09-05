package com.demo.stayintegration.catalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.demo.stayintegration.catalog.dto.response.CatalogSummary;
import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.service.CatalogSyncService;

import lombok.RequiredArgsConstructor;

// 재동기화 트리거는 D-8에서 확정한 동작이다. 비범위인 "관리자 기능"은 UI·권한을 뜻한다.
// 성공 응답에 envelope를 씌우지 않는다 — SyncReport가 곧 응답 계약이다. 에러만 GlobalExceptionHandler가 통일한다.
@RestController
@RequestMapping("/admin/catalog")
@RequiredArgsConstructor
public class CatalogAdminController {

	private final CatalogSyncService catalogSyncService;

	@PostMapping("/sync")
	public SyncReport sync() {
		return catalogSyncService.sync();
	}

	@GetMapping
	public CatalogSummary summary() {
		return catalogSyncService.summary();
	}
}
