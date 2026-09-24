package br.com.reconcile.shared.config

import br.com.reconcile.fiscal.infra.FiscalAdapter
import br.com.reconcile.fiscal.infra.StubFiscalAdapter
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MarketplaceOAuthProperties::class)
class DomainConfig {
    @Bean
    fun fiscalAdapter(): FiscalAdapter = StubFiscalAdapter()
}
