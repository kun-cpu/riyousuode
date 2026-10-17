package cn.riyousuode.app

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// One versioned snapshot makes every task/ledger/effect transition an atomic SQLite commit.
// Domain records remain separate typed collections, portable in the same backup format.
@Entity(tableName = "state") data class StateRow(@PrimaryKey val id: Int = 1, val json: String)
@Dao interface StateDao {
    @Query("SELECT * FROM state WHERE id = 1") suspend fun read(): StateRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(row: StateRow)
}
@Database(entities = [StateRow::class], version = 1, exportSchema = false)
abstract class DayDatabase : RoomDatabase() { abstract fun stateDao(): StateDao }

class Store(private val context: Context) {
    private val db = Room.databaseBuilder(context, DayDatabase::class.java, "riyousuode.db").build()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<AppState?>(null)
    val state = mutable.asStateFlow()
    suspend fun refresh() = change { materialize(); syncGoals() }
    suspend fun change(block: Engine.() -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = db.withTransaction {
                val value = db.stateDao().read()?.let { Backup.decode(it.json) } ?: AppState()
                Engine(value).apply { materialize(); block() }
                Backup.validate(value)
                val encoded = Backup.encode(value)
                require(encoded.toByteArray().size <= 10 * 1024 * 1024) { "本机记录达到 10 MB 上限，请先导出备份。原数据未改变。" }
                db.stateDao().write(StateRow(json = encoded))
                value
            }
            mutable.value = next
            Pictures.cleanup(context, next)
        }
    }
    suspend fun restore(text: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val value = Backup.decode(text)
            Engine(value).apply { materialize(); syncGoals() }
            Backup.validate(value)
            val encoded = Backup.encode(value)
            require(encoded.toByteArray().size <= 10 * 1024 * 1024) { "恢复后的数据超过 10 MB，原数据未改变" }
            db.withTransaction { db.stateDao().write(StateRow(json = encoded)) }
            mutable.value = value
        }
    }
    suspend fun saveCountdown(record: Countdown, image: ByteArray?) = change {
        val photo = image?.let { Pictures.put(context, it) } ?: record.photo
        if(photo != null) require(Pictures.directory(context).resolve(photo).isFile) { "背景图片无法读取" }
        saveCountdown(record.copy(photo = photo))
    }
    suspend fun exportBundle(): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            val value = db.stateDao().read()?.let { Backup.decode(it.json) } ?: AppState()
            ArchiveBackup.encode(value, ArchiveBackup.collect(context, value))
        }
    }
    suspend fun restoreBundle(bundle: BackupBundle) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val value = Backup.decode(Backup.encode(bundle.state))
            require(value.countdowns.mapNotNull { it.photo }.toSet() == bundle.images.keys)
            bundle.images.forEach { (name, bytes) -> Pictures.validate(bytes); require(Pictures.name(bytes) == name) }
            Engine(value).materialize()
            Backup.validate(value)
            val encoded = Backup.encode(value)
            require(encoded.toByteArray().size <= 10 * 1024 * 1024)
            val current = db.stateDao().read()?.let { Backup.decode(it.json) } ?: AppState()
            val snapshot = ArchiveBackup.encode(current, ArchiveBackup.collect(context, current))
            val atomic = android.util.AtomicFile(context.filesDir.resolve("before-restore.zip"))
            val output = atomic.startWrite()
            try { output.write(snapshot); atomic.finishWrite(output) } catch(e: Exception) { atomic.failWrite(output); throw e }
            bundle.images.forEach { (_, bytes) -> Pictures.put(context, bytes) }
            db.withTransaction { db.stateDao().write(StateRow(json = encoded)) }
            mutable.value = value
            Pictures.cleanup(context, value)
        }
    }
    suspend fun export(): String = mutex.withLock {
        withContext(Dispatchers.IO) { Backup.encode(db.stateDao().read()?.let { Backup.decode(it.json) } ?: AppState()) }
    }
}

class DayApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val store by lazy { Store(this) }
}
