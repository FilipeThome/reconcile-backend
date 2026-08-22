package br.com.reconcile.shared.config

import br.com.reconcile.fiscal.infra.FiscalAdapter
import br.com.reconcile.fiscal.infra.StubFiscalAdapter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DomainConfig {
    @Bean
    fun fiscalAdapter(): FiscalAdapter = StubFiscalAdapter()
}
