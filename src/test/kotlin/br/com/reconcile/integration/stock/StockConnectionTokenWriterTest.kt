package br.com.reconcile.integration.stock

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@SpringJUnitConfig(StockConnectionTokenWriterTest.Config::class)
class StockConnectionTokenWriterTest {
    @Autowired
    private lateinit var probe: TokenRollbackProbe

    @Autowired
    private lateinit var transactions: RecordingTransactionManager

    @Test
    fun `a failed stock call keeps the refreshed token committed`() {
        val connection = ConnectionEntity(tenantId = TestAuth.tenantId, externalId = "ml", accessToken = "new-token")
        assertThrows<UnprocessableException> { probe.fail(connection) }
        assertEquals(1, transactions.commits.get())
        assertEquals(1, transactions.rollbacks.get())
    }

    @Configuration
    @EnableTransactionManagement
    class Config {
        @Bean
        fun transactionManager() = RecordingTransactionManager()

        @Bean
        fun connections(): ConnectionRepository = Mockito.mock(ConnectionRepository::class.java)

        @Bean
        fun writer(connections: ConnectionRepository) = StockConnectionTokenWriter(connections)

        @Bean
        fun probe(writer: StockConnectionTokenWriter) = TokenRollbackProbe(writer)
    }
}

@Service
class TokenRollbackProbe(private val writer: StockConnectionTokenWriter) {
    @Transactional
    fun fail(connection: ConnectionEntity) {
        writer.persist(connection)
        throw UnprocessableException("Falha ao consultar o Mercado Livre")
    }
}

class RecordingTransactionManager : AbstractPlatformTransactionManager() {
    val commits = AtomicInteger()
    val rollbacks = AtomicInteger()
    private val active = ThreadLocal<Tx?>()

    override fun doGetTransaction(): Any = active.get() ?: Tx()

    override fun isExistingTransaction(transaction: Any): Boolean = (transaction as Tx).started

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        val tx = transaction as Tx
        tx.started = true
        active.set(tx)
    }

    override fun doSuspend(transaction: Any): Any {
        val suspended = active.get() ?: Tx()
        active.remove()
        return suspended
    }

    override fun doResume(transaction: Any?, suspendedResources: Any) {
        active.set(suspendedResources as Tx)
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        commits.incrementAndGet()
        active.remove()
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        rollbacks.incrementAndGet()
        active.remove()
    }

    class Tx(var started: Boolean = false)
}
