package br.com.reconcile.shared.security

import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.identity.infra.UserRepository
import org.springframework.stereotype.Component

@Component
class DatabaseSessionUserLoader(
    private val users: UserRepository,
    private val tenants: TenantRepository,
) : SessionUserLoader {
    override fun hydrate(parsed: AuthUser): AuthUser? {
        val entity = users.findById(parsed.userId).orElse(null) ?: return null
        if (entity.tenantId != parsed.tenantId) return null
        val tenant = tenants.findById(entity.tenantId).orElse(null) ?: return null
        return AuthUser(entity.id, entity.tenantId, tenant.externalId, entity.name, entity.email, entity.role)
    }
}
