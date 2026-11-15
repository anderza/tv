package app.tvdoctor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistent log of every change made to the TV, with the commands that undo it. */
class Journal(private val file: File) {

    data class Entry(
        val id: Long,
        val time: Long,
        val device: String,
        val title: String,
        val done: List<String>,
        val undo: List<String>,
        var undone: Boolean,
    )

    private val entries = ArrayList<Entry>()

    init { load() }

    @Synchronized fun all(): List<Entry> = entries.sortedByDescending { it.time }

    @Synchronized fun add(device: String, title: String, done: List<String>, undo: List<String>): Entry {
        val e = Entry(System.nanoTime(), System.currentTimeMillis(), device, title, done, undo, false)
        entries += e
        save()
        return e
    }

    @Synchronized fun markUndone(id: Long) {
        entries.firstOrNull { it.id == id }?.undone = true
        save()
    }

    @Synchronized fun clear() { entries.clear(); save() }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries += Entry(
                    o.getLong("id"), o.getLong("time"), o.optString("device"), o.getString("title"),
                    o.getJSONArray("done").strings(), o.getJSONArray("undo").strings(), o.optBoolean("undone"),
                )
            }
        } catch (_: Exception) { /* corrupted journal: start fresh, keep the file for inspection */ }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun save() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("time", e.time); put("device", e.device); put("title", e.title)
                put("done", JSONArray(e.done)); put("undo", JSONArray(e.undo)); put("undone", e.undone)
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(1))
        tmp.renameTo(file)
    }
}
