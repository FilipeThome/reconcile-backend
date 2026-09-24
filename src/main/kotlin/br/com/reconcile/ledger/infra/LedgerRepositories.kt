package br.com.reconcile.ledger.infra

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrderRepository : JpaRepository<OrderEntity, UUID> {
    fun findByTenantIdOrderByOrderedAtDesc(tenantId: UUID): List<OrderEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): OrderEntity?
}

interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByTenantIdOrderByPaidAtDesc(tenantId: UUID): List<PaymentEntity>
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): List<PaymentEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): PaymentEntity?
}

interface SettlementRepository : JpaRepository<SettlementEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<SettlementEntity>
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): SettlementEntity?
}

interface PayoutRepository : JpaRepository<PayoutEntity, UUID> {
    fun findByTenantIdOrderByPaidAtDesc(tenantId: UUID): List<PayoutEntity>
}

interface PayoutOrderRepository : JpaRepository<PayoutOrderEntity, PayoutOrderId> {
    fun findByPayoutId(payoutId: UUID): List<PayoutOrderEntity>
    fun findByOrderId(orderId: UUID): List<PayoutOrderEntity>
}

interface FeeRepository : JpaRepository<FeeEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<FeeEntity>
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): List<FeeEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): FeeEntity?
}

interface RefundRepository : JpaRepository<RefundEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<RefundEntity>
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): List<RefundEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): RefundEntity?
}
