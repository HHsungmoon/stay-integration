package com.demo.stayintegration.catalog.service;

public class SyncAlreadyRunningException extends RuntimeException {

	public SyncAlreadyRunningException() {
		super("catalog sync is already running");
	}
}
