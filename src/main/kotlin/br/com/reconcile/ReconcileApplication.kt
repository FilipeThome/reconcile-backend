package br.com.reconcile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ReconcileApplication

fun main(args: Array<String>) {
    runApplication<ReconcileApplication>(*args)
}
