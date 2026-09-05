package com.demo.stayintegration.catalog;

import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.controller.CatalogAdminController;
import com.demo.stayintegration.catalog.dto.response.CatalogSummary;
import com.demo.stayintegration.catalog.dto.response.SyncOutcome;
import com.demo.stayintegration.catalog.service.CatalogSyncService;
import com.demo.stayintegration.catalog.service.SyncAlreadyRunningException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult.Status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(CatalogAdminController.class)
class CatalogAdminControllerTest {

	@Autowired MockMvc mvc;
	@MockitoBean CatalogSyncService catalogSyncService;
	private final JsonMapper json = new JsonMapper();

	@Test
	void syncReturnsReportAsIs() throws Exception {
		when(catalogSyncService.sync()).thenReturn(new SyncReport(Instant.parse("2026-10-01T09:00:00Z"), 412, List.of(
				new SupplierSyncResult("A", Status.SUCCESS, new SyncOutcome(4, 0, 0, 0), null),
				new SupplierSyncResult("B", Status.FAILED, null, new SupplierSyncResult.FailureInfo("TRANSIENT", "timeout")))));

		MvcResult res = mvc.perform(post("/admin/catalog/sync")).andReturn();

		assertThat(res.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = json.readTree(res.getResponse().getContentAsString());
		assertThat(body.path("startedAt").asString()).isEqualTo("2026-10-01T09:00:00Z");
		assertThat(body.path("durationMs").asLong()).isEqualTo(412);
		assertThat(body.path("suppliers").size()).isEqualTo(2);
		assertThat(body.path("suppliers").get(0).path("supplier").asString()).isEqualTo("A");
		assertThat(body.path("suppliers").get(0).path("outcome").path("created").asInt()).isEqualTo(4);
		assertThat(body.path("suppliers").get(1).path("status").asString()).isEqualTo("FAILED");
		assertThat(body.path("suppliers").get(1).path("failure").path("kind").asString()).isEqualTo("TRANSIENT");
	}

	@Test
	void concurrentSyncIs409ThroughTheGlobalHandler() throws Exception {
		when(catalogSyncService.sync()).thenThrow(new SyncAlreadyRunningException());

		MvcResult res = mvc.perform(post("/admin/catalog/sync")).andReturn();

		assertThat(res.getResponse().getStatus()).isEqualTo(409);
		JsonNode body = json.readTree(res.getResponse().getContentAsString());
		assertThat(body.path("code").asString()).isEqualTo("SYNC_ALREADY_RUNNING");
	}

	@Test
	void summaryIsExposed() throws Exception {
		when(catalogSyncService.summary()).thenReturn(new CatalogSummary(List.of(new CatalogSummary.SupplierSummary("A", 2, 1, 4, 1))));

		MvcResult res = mvc.perform(get("/admin/catalog")).andReturn();

		assertThat(res.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = json.readTree(res.getResponse().getContentAsString());
		assertThat(body.path("suppliers").get(0).path("inactiveProperties").asLong()).isEqualTo(1);
	}
}
