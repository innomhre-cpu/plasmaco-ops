@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.room

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [],
)
annotation class Index(vararg val value: String, val orders: Array<Order> = [], val name: String = "", val unique: Boolean = false) { enum class Order { ASC, DESC } }
annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String> = [],
    val childColumns: Array<String> = [],
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false,
) { companion object { const val NO_ACTION = 1; const val RESTRICT = 2; const val SET_NULL = 3; const val SET_DEFAULT = 4; const val CASCADE = 5 } }
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class ColumnInfo(val name: String = "[field-name]", val index: Boolean = false, val defaultValue: String = "[value-unspecified]")
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class Embedded(val prefix: String = "")
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class Relation(
    val entity: KClass<*> = Any::class, val parentColumn: String, val entityColumn: String, val projection: Array<String> = [],
)
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) annotation class Update(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) annotation class Delete(val entity: KClass<*> = Any::class)
@Target(AnnotationTarget.FUNCTION) annotation class Upsert(val entity: KClass<*> = Any::class)
@Target(AnnotationTarget.FUNCTION) annotation class Transaction
@Target(AnnotationTarget.FUNCTION) annotation class TypeConverter
annotation class TypeConverters(vararg val value: KClass<*> = [])
@Target(AnnotationTarget.CLASS) annotation class Database(val entities: Array<KClass<*>> = [], val views: Array<KClass<*>> = [], val version: Int, val exportSchema: Boolean = true)
object OnConflictStrategy { const val NONE = 0; const val REPLACE = 1; const val ROLLBACK = 2; const val ABORT = 3; const val FAIL = 4; const val IGNORE = 5 }

abstract class RoomDatabase {
    open val openHelper: SupportSQLiteOpenHelper get() = TODO()
    fun query(query: String, args: Array<out Any?>?): android.database.Cursor = TODO()
    open fun close() {}
    class Builder<T : RoomDatabase> {
        fun addMigrations(vararg migrations: androidx.room.migration.Migration): Builder<T> = this
        fun build(): T = TODO()
    }
}
object Room {
    @JvmStatic fun <T : RoomDatabase> databaseBuilder(context: Context, klass: Class<T>, name: String?): RoomDatabase.Builder<T> = TODO()
    @JvmStatic fun <T : RoomDatabase> inMemoryDatabaseBuilder(context: Context, klass: Class<T>): RoomDatabase.Builder<T> = TODO()
}
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = block()
