package com.salonplatform.shared.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1 + "/meta")
@Tag(name = "shared")
class MetaController {

    private final MetaService metaService;

    MetaController(MetaService metaService) {
        this.metaService = metaService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMeta", summary = "The service's name, version, commit and time.")
    MetaResponse meta() {
        return MetaResponse.from(metaService.describe());
    }
}
