package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"staffSeats", "branches", "messageAllowance"})
public record Limits(int staffSeats, int branches, int messageAllowance) {}
