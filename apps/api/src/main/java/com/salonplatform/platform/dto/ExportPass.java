package com.salonplatform.platform.dto;

import java.util.UUID;

public record ExportPass(UUID salonId, UUID exportId, int tablesStored, boolean ready) {}
