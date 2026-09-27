package br.com.reconcile.shared.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

class ApiLocaleResolver : AcceptHeaderLocaleResolver() {
    init {
        setDefaultLocale(Locale.forLanguageTag("pt-BR"))
    }

    override fun resolveLocale(request: HttpServletRequest): Locale =
        resolveApiLocale(request.getHeader("Accept-Language"))
}

@Configuration
class ApiLocaleConfig {
    @Bean
    fun localeResolver(): LocaleResolver = ApiLocaleResolver()
}
