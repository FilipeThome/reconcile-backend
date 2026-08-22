package br.com.reconcile.shared.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/api/v1/health")
    fun health() = mapOf("status" to "UP")
}
