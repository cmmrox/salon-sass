package com.salonplatform.platform.dto;

import java.util.UUID;

public record SalonPass(UUID salonId, int batches, long rows, boolean deleted) {}
