package io.vpnshare.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.vpnshare.R

/**
 * 二级页统一骨架：MaterialToolbar + 摘要 + 可滚动容器。
 * 与主界面共用 item_entry / item_detail 两种卡片行，保证视觉一致。
 */
abstract class BaseListActivity : AppCompatActivity() {

    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var summary: TextView
    protected lateinit var container: LinearLayout

    /** 右下角悬浮操作按钮；默认隐藏，需要的页面自己显示 */
    protected lateinit var fab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private val inflater: LayoutInflater get() = LayoutInflater.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        toolbar = findViewById(R.id.toolbar)
        summary = findViewById(R.id.tvSummary)
        container = findViewById(R.id.container)
        fab = findViewById(R.id.fabAction)
        toolbar.title = title
        toolbar.setNavigationOnClickListener { finish() }
    }

    protected fun setSummary(text: String) {
        summary.text = text
        summary.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    protected fun clear() {
        container.removeAllViews()
    }

    protected fun addButton(label: String, style: Int = R.style.Widget_VpnShare_Button_Tonal, onClick: () -> Unit) {
        val b = com.google.android.material.button.MaterialButton(this, null, style).apply {
            text = label
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.card_gap)
        container.addView(b, lp)
    }

    protected fun addSectionHeader(label: String) {
        container.addView(TextView(this).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_VpnShare_Section)
            setPadding(0, resources.getDimensionPixelSize(R.dimen.card_gap) * 2, 0,
                resources.getDimensionPixelSize(R.dimen.card_gap))
        })
    }

    protected fun addEntry(
        iconRes: Int,
        titleText: String,
        subText: String,
        trailText: String = "",
        onClick: (() -> Unit)? = null
    ): View {
        val row = inflater.inflate(R.layout.item_entry, container, false)
        row.findViewById<android.widget.ImageView>(R.id.ivEntryIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tvEntryTitle).text = titleText
        row.findViewById<TextView>(R.id.tvEntrySub).text = subText
        row.findViewById<TextView>(R.id.tvEntryTrail).text = trailText
        onClick?.let { row.setOnClickListener { it() } }
        container.addView(row)
        return row
    }

    protected fun addDetail(titleText: String, bodyText: String, trailText: String = "", onClick: (() -> Unit)? = null) {
        val card = inflater.inflate(R.layout.item_detail, container, false)
        card.findViewById<TextView>(R.id.tvDetailTitle).text = titleText
        card.findViewById<TextView>(R.id.tvDetailBody).text = bodyText
        val trail = card.findViewById<TextView>(R.id.tvDetailTrail)
        if (trailText.isBlank()) trail.visibility = View.GONE else trail.text = trailText
        onClick?.let { card.setOnClickListener { it() } }
        container.addView(card)
    }
}
