package com.salonplatform.catalogue.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The JSON {@code null} the public tax answers before the salon first saves its tax settings. Named in the contract
 * only; the controller writes a literal {@code null}.
 */
@Schema(
        name = "NoCatalogueTax",
        types = {"null"},
        description = "No tax settings saved yet: nothing is charged.")
public record NoTax() {}
