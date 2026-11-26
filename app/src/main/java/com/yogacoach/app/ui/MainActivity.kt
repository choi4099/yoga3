package com.yogacoach.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.yogacoach.app.R
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.yogacoach.app.databinding.ActivityMainBinding
import com.yogacoach.app.databinding.ItemPoseBinding
import com.yogacoach.app.model.Level
import com.yogacoach.app.model.PoseRepository
import com.yogacoach.app.model.YogaPose

/** 첫 화면: 입문 / 초급 / 중급 / 고급 탭으로 요가 동작 목록 표시 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = PoseAdapter { pose ->
        startActivity(Intent(this, PracticeActivity::class.java).putExtra(PracticeActivity.EXTRA_POSE_ID, pose.id))
    }
    private var all: List<YogaPose> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        all = PoseRepository.all(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        Level.values().forEach { lv ->
            val count = all.count { it.level == lv }
            b.tabs.addTab(b.tabs.newTab().setText("${lv.ko} ($count)").setTag(lv))
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(tab.tag as Level)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        show(Level.INTRO)
        askShortcutOnFirstRun()
    }

    // ------------------------------------------------------------------ 메뉴: 바로가기 / 삭제
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SHORTCUT, 0, "홈 화면에 바로가기 추가")
        menu.add(0, MENU_UNINSTALL, 1, "앱 삭제(언인스톨)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SHORTCUT -> { addHomeShortcut(); true }
        MENU_UNINSTALL -> { confirmUninstall(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** 처음 실행할 때 한 번 홈 화면 바로가기 추가를 물어봅니다 */
    private fun askShortcutOnFirstRun() {
        val prefs = getSharedPreferences("yoga", Context.MODE_PRIVATE)
        if (prefs.getBoolean("shortcut_asked", false)) return
        prefs.edit().putBoolean("shortcut_asked", true).apply()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) return
        AlertDialog.Builder(this)
            .setTitle("홈 화면 바로가기")
            .setMessage("휴대폰 홈 화면에 '요가' 바로가기 아이콘을 추가할까요?\n(나중에 오른쪽 위 메뉴에서도 추가할 수 있어요)")
            .setPositiveButton("추가") { _, _ -> addHomeShortcut() }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun addHomeShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "이 홈 화면 앱은 바로가기 추가를 지원하지 않습니다. 앱 목록에서 아이콘을 길게 눌러 홈 화면으로 끌어다 놓으세요.", Toast.LENGTH_LONG).show()
            return
        }
        val info = ShortcutInfoCompat.Builder(this, "yoga_coach_home")
            .setShortLabel(getString(R.string.app_name))
            .setLongLabel("요가 - 자세 채점")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        val ok = ShortcutManagerCompat.requestPinShortcut(this, info, null)
        Toast.makeText(this, if (ok) "홈 화면에 추가 요청을 보냈어요. 나타나는 창에서 '추가'를 눌러 주세요." else "바로가기를 추가하지 못했습니다.", Toast.LENGTH_LONG).show()
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle("앱 삭제")
            .setMessage("요가 앱을 휴대폰에서 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        private const val MENU_SHORTCUT = 1
        private const val MENU_UNINSTALL = 2
    }

    private fun show(level: Level) {
        adapter.submit(all.filter { it.level == level })
        b.list.scrollToPosition(0)
    }
}

class PoseAdapter(private val onClick: (YogaPose) -> Unit) : RecyclerView.Adapter<PoseAdapter.VH>() {
    private var items: List<YogaPose> = emptyList()

    fun submit(list: List<YogaPose>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemPoseBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPoseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.b.name.text = p.name
        h.b.nameEn.text = p.nameEn
        h.b.meta.text = "${p.level.ko} · ${if (p.sideView) "옆으로 서기" else "정면 보기"} · ${p.holdSeconds}초 유지"
        h.b.desc.text = p.description
        h.b.thumb.setPose(p.template)
        h.b.root.setOnClickListener { onClick(p) }
    }
}
