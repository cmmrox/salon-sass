package com.salonplatform.platform.dto;

import java.util.List;
import java.util.UUID;

public record SubscriptionPass(
        UUID salonId, List<String> issued, List<String> reminders, List<String> charges, List<String> states) {}
