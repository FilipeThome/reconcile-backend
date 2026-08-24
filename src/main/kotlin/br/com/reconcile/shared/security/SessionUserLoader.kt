package br.com.reconcile.shared.security

fun interface SessionUserLoader {
    fun hydrate(parsed: AuthUser): AuthUser?
}
