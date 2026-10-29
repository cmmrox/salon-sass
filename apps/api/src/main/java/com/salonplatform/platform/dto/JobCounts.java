package com.salonplatform.platform.dto;

public record JobCounts(
        long due, long run, long skipped, long failed, java.time.Instant lastRunAt, java.time.Instant lastSkippedAt) {}
