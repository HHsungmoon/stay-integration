package com.demo.stayintegration.catalog.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierId;

public record SyncReport(Instant startedAt, long durationMs, List<SupplierSyncResult> suppliers) {

	public record SupplierSyncResult(String supplier, Status status, SyncOutcome outcome, FailureInfo failure) {

		public enum Status { SUCCESS, FAILED, SKIPPED }

		public record FailureInfo(String kind, String detail) {}

		public static SupplierSyncResult success(SupplierId supplier, SyncOutcome outcome) {
			return new SupplierSyncResult(supplier.value(), Status.SUCCESS, outcome, null);
		}

		public static SupplierSyncResult failed(SupplierId supplier, FailureKind kind, String detail) {
			return new SupplierSyncResult(supplier.value(), Status.FAILED, null, new FailureInfo(kind.name(), detail));
		}

		public static SupplierSyncResult failed(SupplierId supplier, String kind, String detail) {
			return new SupplierSyncResult(supplier.value(), Status.FAILED, null, new FailureInfo(kind, detail));
		}

		public static SupplierSyncResult skipped(SupplierId supplier, String reason) {
			return new SupplierSyncResult(supplier.value(), Status.SKIPPED, null, new FailureInfo("SKIPPED", reason));
		}
	}

	public String summaryLine() {
		return suppliers.stream()
				.map(s -> s.supplier() + "=" + s.status()
						+ (s.outcome() != null ? "(+" + s.outcome().created() + " ~" + s.outcome().updated()
								+ " ^" + s.outcome().reactivated() + " -" + s.outcome().deactivated() + ")" : ""))
				.collect(Collectors.joining(", ")) + " in " + durationMs + "ms";
	}
}
