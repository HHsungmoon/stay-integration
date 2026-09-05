package com.demo.stayintegration.supplier.port;

import java.time.LocalDate;

public record NightlyRate(LocalDate date, long net, long tax, int remainingRooms) {}
