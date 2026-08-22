package br.com.reconcile.fiscal.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "invoices")
class InvoiceEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
    @Column(name = "document_type") var documentType: String = "NF-e",
    var number: String = "",
    @Column(name = "access_key") var accessKey: String? = null,
    @Column(name = "issued_at") var issuedAt: Instant? = null,
    @Column(name = "total_amount") var totalAmount: BigDecimal = BigDecimal.ZERO,
    var status: String = "pendente",
    @Column(name = "cbs_amount") var cbsAmount: BigDecimal? = null,
    @Column(name = "ibs_amount") var ibsAmount: BigDecimal? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_metadata")
    var taxMetadata: String = "{}",
    @Column(name = "xml_ref") var xmlRef: String? = null,
    @Version var version: Long = 0,
)

interface InvoiceRepository : JpaRepository<InvoiceEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<InvoiceEntity>
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): InvoiceEntity?
}

fun interface FiscalAdapter {
    fun consultStatus(accessKey: String?): String
}

class StubFiscalAdapter : FiscalAdapter {
    override fun consultStatus(accessKey: String?): String = if (accessKey.isNullOrBlank()) "pendente" else "autorizada"
}
