package com.salonplatform.platform.dto;

import java.time.Instant;

public record SupportExpiryPass(Instant at, int ended) {}
