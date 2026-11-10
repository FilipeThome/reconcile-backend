package br.com.reconcile.integration.oauth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "reconcile.oauth")
class MarketplaceOAuthProperties {
    var frontendRedirect: String = "http://localhost:5173/integracoes"
    var mercadoLivre: ProviderProperties = ProviderProperties()
    var shopee: ProviderProperties = ProviderProperties()
    var amazon: ProviderProperties = ProviderProperties()
    var olx: ProviderProperties = ProviderProperties()
}

class ProviderProperties {
    var clientId: String = ""
    var clientSecret: String = ""
    var redirectUri: String = ""
    var authorizeUrl: String = ""
    var tokenUrl: String = ""
    var apiUrl: String = ""
    var pkce: Boolean = true
    var awsAccessKey: String = ""
    var awsSecretKey: String = ""
    var awsSessionToken: String = ""
    var awsRegion: String = "us-east-1"
    var marketplaceId: String = ""
    var scope: String = ""
    var webhookUrl: String = ""
    var webhookSecret: String = ""
    var deleteSoldAds: Boolean = false
}
