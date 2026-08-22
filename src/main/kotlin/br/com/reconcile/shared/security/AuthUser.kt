package br.com.reconcile.shared.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

data class AuthUser(
    val userId: UUID,
    val tenantId: UUID,
    val tenantExternalId: String,
    val displayName: String,
    val email: String,
    val role: String,
) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_${role.uppercase()}"))

    override fun getPassword(): String = ""
    override fun getUsername(): String = email
}
