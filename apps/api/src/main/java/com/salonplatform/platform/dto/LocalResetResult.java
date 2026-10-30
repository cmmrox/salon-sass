package com.salonplatform.platform.dto;

import java.util.List;
import java.util.UUID;

public record LocalResetResult(int removedSalons, List<UUID> salonIds, int jobsRescheduled, String clock) {}
