@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package org.springframework.transaction.support

fun interface TransactionCallback<T> { fun doInTransaction(status: Any): T? }
open class TransactionTemplate(transactionManager: org.springframework.transaction.PlatformTransactionManager) {
    fun <T> execute(action: TransactionCallback<T>): T? = TODO()
}
