package cn.ppps.forwarder.database.viewmodel

import androidx.lifecycle.ViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import cn.ppps.forwarder.database.dao.MsgDao
import cn.ppps.forwarder.database.entity.MsgAndLogs
import cn.ppps.forwarder.database.ext.ioThread
import cn.ppps.forwarder.utils.Log
import com.xuexiang.xutil.data.DateUtils
import kotlinx.coroutines.flow.Flow

class MsgViewModel(private val dao: MsgDao) : ViewModel() {
    private var type: String = "sms"
    private var filter: MutableMap<String, Any> = mutableMapOf()

    fun setType(type: String): MsgViewModel {
        this.type = type
        return this
    }

    fun setFilter(filter: MutableMap<String, Any>): MsgViewModel {
        this.filter = filter
        return this
    }

    /**
     * 【修复】原来这里是 val allMsg = Pager(...).flow.cachedIn(viewModelScope)，
     * 数据流在 ViewModel 创建时就定死了，且 cachedIn 会把第一次（短信）的分页结果缓存并回放，
     * 导致切换“短信/通话/应用”时拿到的永远是同一份数据。
     * 改为函数：每次调用都按当前 type/filter 重新建 Pager，切换页签即可真正重新查询。
     */
    fun allMsg(): Flow<PagingData<MsgAndLogs>> = Pager(
        config = PagingConfig(
            pageSize = 10,
            enablePlaceholders = false,
            initialLoadSize = 10
        )
    ) {
        Log.d("MsgViewModel", "build PagingSource, type=$type, filter=$filter")
        if (filter.isEmpty()) {
            dao.pagingSource(type)
        } else {
            val sb = StringBuilder().apply {
                append("SELECT * FROM Msg WHERE type = '$type'")
                append(getOtherCondition())
                append(" ORDER BY id DESC")
            }

            //Log.d("MsgViewModel", "sql: $sb")
            val query = SimpleSQLiteQuery(sb.toString())
            dao.pagingSource(query)
        }

    }.flow

    fun delete(id: Long) = ioThread {
        dao.delete(id)
    }

    fun deleteAll() = ioThread {
        val sb = StringBuilder().apply {
            append("DELETE FROM Msg WHERE type = '$type'")
            if (filter.isNotEmpty()) {
                append(getOtherCondition())
            }
        }

        Log.d("MsgViewModel", "sql: $sb")
        val query = SimpleSQLiteQuery(sb.toString())
        dao.deleteAll(query)
    }

    private fun getOtherCondition(): String {
        return StringBuilder().apply {
            filter["from"]?.toString()?.takeIf { it.isNotEmpty() }?.let { append(" AND `from` LIKE '%$it%'") }
            filter["content"]?.toString()?.takeIf { it.isNotEmpty() }?.let { append(" AND content LIKE '%$it%'") }
            filter["title"]?.toString()?.takeIf { it.isNotEmpty() }?.let { append(" AND sim_info LIKE '%$it%'") }
            filter["start_time"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
                val date = DateUtils.string2Date(it, DateUtils.yyyyMMddHHmmss.get())
                append(" AND time >= '${date.time}'")
            }
            filter["end_time"]?.toString()?.takeIf { it.isNotEmpty() }?.let {
                val date = DateUtils.string2Date(it, DateUtils.yyyyMMddHHmmss.get())
                append(" AND time <= '${date.time}'")
            }
            if (filter["sim_slot"] is Int && filter["sim_slot"] != -1) {
                append(" AND sim_slot = ${filter["sim_slot"]}")
            }
            val callTypeFilter = filter["call_type"] as? MutableList<*>
            if (!callTypeFilter.isNullOrEmpty()) {
                val callTypeString = callTypeFilter.joinToString(",") { it.toString() }
                append(" AND call_type IN ($callTypeString)")
            }
            val forwardStatusFilter = filter["forward_status"] as? MutableList<*>
            if (!forwardStatusFilter.isNullOrEmpty()) {
                val forwardStatusString = forwardStatusFilter.joinToString(",") { it.toString() }
                val subSql = "SELECT DISTINCT msg_id FROM Logs WHERE type = '$type' and forward_status IN ($forwardStatusString)"
                append(" AND id in ($subSql)")
            }
        }.toString()
    }

}
