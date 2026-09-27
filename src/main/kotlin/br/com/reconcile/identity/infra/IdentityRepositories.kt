package br.com.reconcile.identity.infra

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TenantRepository : JpaRepository<TenantEntity, UUID> {
    fun findByExternalId(externalId: String): TenantEntity?
}

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByTenantIdAndEmail(tenantId: UUID, email: String): UserEntity?
    fun findByEmail(email: String): UserEntity?

    @Query("select u from UserEntity u where lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(email: String): UserEntity?
    fun findByTenantId(tenantId: UUID): List<UserEntity>
}

interface SellerAccountRepository : JpaRepository<SellerAccountEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<SellerAccountEntity>
    fun findByTenantIdAndMarketplace(tenantId: UUID, marketplace: String): SellerAccountEntity?
}
