package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

class ExpenseWorkingCopyFake(private val owns: (ExpenseAccess) -> Boolean = { true }) :
    ExpenseWorkingCopyRepository {
    data class Key(
        val audience: String,
        val accountId: String,
        val companyId: String,
        val slot: ExpenseWorkingSlot,
    )

    val values = MutableStateFlow<Map<Key, ExpenseWorkingCopy>>(emptyMap())
    var beforeRead: suspend () -> Unit = {}
    var beforeSave: suspend (ExpenseWorkingCopy) -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var afterSave: suspend () -> AppResult.Failed? = { null }
    var failure: AppResult.Failed? = null
    var observers = 0
    var writes = 0
    var attempts = 0
    var removals = 0

    fun key(access: ExpenseAccess, slot: ExpenseWorkingSlot) =
        Key(access.audience, access.accountId, access.companyId, slot)

    fun seed(access: ExpenseAccess, slot: ExpenseWorkingSlot, copy: ExpenseWorkingCopy) {
        values.value += key(access, slot) to copy
    }

    override fun observePage(access: ExpenseAccess, after: String?) = flow {
        beforeRead()
        emitAll(
            values.map { entries ->
                AppResult.Success(
                    dev.fajar.starter.expenses.domain.entities.ExpensePage(
                        entries
                            .filterKeys {
                                it.audience == access.audience &&
                                    it.accountId == access.accountId &&
                                    it.companyId == access.companyId
                            }
                            .values
                            .toList()
                    )
                )
            }
        )
    }

    override fun observe(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
    ): Flow<AppResult<ExpenseWorkingCopy?>> = flow {
        observers++
        try {
            beforeRead()
            emitAll(values.map { AppResult.Success(it[key(access, slot)]) })
        } finally {
            observers--
        }
    }

    override suspend fun save(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String?,
        copy: ExpenseWorkingCopy,
    ): AppResult<ExpenseWorkingCopy?> {
        attempts++
        beforeSave(copy)
        currentCoroutineContext().ensureActive()
        failure?.let {
            return it
        }
        if (!owns(access)) return AppResult.Success(null)
        val key = key(access, slot)
        val old = values.value[key]
        if (old?.revision == copy.revision)
            return AppResult.Success(old.takeIf { it.copy(savedAt = copy.savedAt) == copy })
        if (old?.revision != expectedRevision) return AppResult.Success(null)
        writes++
        values.value += key to copy
        afterSave()?.let {
            return it
        }
        return AppResult.Success(copy)
    }

    override suspend fun remove(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String,
    ): AppResult<Boolean> {
        beforeRemove()
        currentCoroutineContext().ensureActive()
        failure?.let {
            return it
        }
        val key = key(access, slot)
        if (!owns(access) || values.value[key]?.revision != expectedRevision)
            return AppResult.Success(false)
        removals++
        values.value -= key
        return AppResult.Success(true)
    }
}
