package com.yogacoach.app.model

import android.content.Context
import org.json.JSONObject

/** assets/poses.json 에서 요가 동작 목록을 읽어 옵니다. 동작 추가·수정은 이 JSON 만 고치면 됩니다. */
object PoseRepository {
    @Volatile
    private var cache: List<YogaPose>? = null

    fun all(context: Context): List<YogaPose> {
        cache?.let { return it }
        val text = context.assets.open("poses.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val arr = root.getJSONArray("poses")
        val list = ArrayList<YogaPose>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pts = o.getJSONObject("points")
            val tpl = HashMap<Joint, Pt>()
            for (j in Joint.REAL) {
                val a = pts.getJSONArray(j.name)
                tpl[j] = Pt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            }
            val tipsArr = o.getJSONArray("tips")
            val tips = (0 until tipsArr.length()).map { tipsArr.getString(it) }
            list += YogaPose(
                id = o.getString("id"),
                name = o.getString("name"),
                nameEn = o.getString("en"),
                level = Level.values()[o.getInt("level").coerceIn(0, 3)],
                sideView = o.getString("view") == "side",
                description = o.getString("desc"),
                tips = tips,
                holdSeconds = o.optInt("hold", 30),
                template = tpl,
            )
        }
        cache = list
        return list
    }

    fun byId(context: Context, id: String): YogaPose? = all(context).firstOrNull { it.id == id }
}
