package com.cagritasoz.ingestion_service.controller;

import com.cagritasoz.ingestion_service.dto.EnergyUsageDto;
import com.cagritasoz.ingestion_service.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/v1/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void ingest(@Valid @RequestBody EnergyUsageDto energyUsageDto) {

        ingestionService.ingestEnergyUsage(energyUsageDto);

    }
}
