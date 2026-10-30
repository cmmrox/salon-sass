package com.salonplatform.platform.dto;

public record OutboxCounts(long waiting, long delivered, long cancelled, long dead) {}
