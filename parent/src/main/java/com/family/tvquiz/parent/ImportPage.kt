package com.family.tvquiz.parent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 拍照出题：① 拍照 → ② Claude 识别出题 → ③ 逐题核对后提交到电视。
 * 发送前用底部面板说明将发送的内容；存疑的题默认不采用。
 */
class ImportPage(act: MainActivity) : Page(act) {

    companion object {
        const val REQ_CAMERA = 11
        const val REQ_PICK = 12
        private const val MAX_PHOTOS = 6
        private const val MAX_EDGE = 1568
        private val SUBJECTS = listOf("语文", "数学", "英语")
    }

    private enum class Stage { FORM, LOADING, REVIEW }
    private var stage = Stage.FORM

    private val root = FrameLayout(act)
    override val view: View = root
    private val col = K.vbox(act, 14)
    private val scroll = K.page(act, col, bottomPad = 110)
    private val bottomBar = K.hbox(act, 12).apply {
        setBackgroundColor(K.CARD); elevation = K.dp(act, 8).toFloat()
        setPadding(K.dp(act, 20), K.dp(act, 12), K.dp(act, 20), K.dp(act, 12))
    }

    private var catalog: JSONObject? = null
    private var material = 1               // 0 课本 1 作业/试卷
    private var book = ""
    private var subject = "数学"
    private var unitIdx = 0
    private var note = ""
    private val photos = ArrayList<ByteArray>()
    private var pendingCamera: File? = null

    private var meta: Claude.Meta? = null
    private var metaUi = 0
    private var notes = ""

    /** 待核对的题（家长可修改）。 */
    private class Q(var stem: String, var options: MutableList<String>, var answer: String, var explain: String,
                    val lesson: String, val kind: String, val uncertain: Boolean, val reason: String, var use: Boolean)
    private val qs = ArrayList<Q>()

    init {
        root.addView(scroll, FrameLayout.LayoutParams(K.MATCH, K.MATCH))
        root.addView(bottomBar, FrameLayout.LayoutParams(K.MATCH, K.WRAP, Gravity.BOTTOM))
        bottomBar.visibility = View.GONE
    }

    override fun onShow() {
        if (catalog == null) {
            col.removeAllViews(); col.addView(K.h1(act, "拍照出题")); col.addView(K.muted(act, "读取中…", 15f))
            act.task({ store.api().get("/api/catalog") to store.api().get("/api/settings") }, fail = act::handleError) { (c, s) ->
                catalog = c
                if (book.isEmpty()) book = s.optString("book")
                render()
            }
        } else render()
    }

    /** 返回键：核对页回到表单。 */
    fun onBack(): Boolean {
        if (stage == Stage.REVIEW) {
            Ui.confirm(act, "放弃这些题？", "返回后需要重新识别。") { stage = Stage.FORM; render() }
            return true
        }
        return false
    }

    private var renderedStage: Stage? = null

    private fun render() {
        // 同一阶段内重绘时保持滚动位置（例如核对页改答案）
        val keepY = if (renderedStage == stage) scroll.scrollY else 0
        renderedStage = stage
        bottomBar.visibility = if (stage == Stage.REVIEW) View.VISIBLE else View.GONE
        col.removeAllViews()
        when (stage) {
            Stage.FORM -> renderForm()
            Stage.LOADING -> renderLoading()
            Stage.REVIEW -> renderReview()
        }
        scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun steps(active: Int) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf("1 拍照", "2 识别出题", "3 核对提交").forEachIndexed { i, s ->
            val on = i <= active
            val item = K.vbox(act, 6)
            item.addView(View(act).apply { background = K.shape(act, if (on) K.PRIMARY else K.BORDER, 2) }, LinearLayout.LayoutParams(K.MATCH, K.dp(act, 4)))
            item.addView(K.text(act, s, 13f, if (i == active) K.PRIMARY else K.MUTED, bold = i == active))
            addView(item, LinearLayout.LayoutParams(0, K.WRAP, 1f).apply { if (i > 0) marginStart = K.dp(act, 6) })
        }
    }

    // ---------- ① 表单 ----------

    private fun books(): List<String> = catalog?.getJSONArray("books")?.let { a -> List(a.length()) { a.getJSONObject(it).getString("book") } } ?: emptyList()

    private fun units(): List<Pair<Int, String>> {
        val books = catalog?.getJSONArray("books") ?: return emptyList()
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            if (b.getString("book") != book) continue
            val subs = b.getJSONArray("subjects")
            for (j in 0 until subs.length()) {
                val s = subs.getJSONObject(j)
                if (s.getString("subject") != subject) continue
                val u = s.getJSONArray("units")
                return List(u.length()) { u.getJSONObject(it).let { x -> x.getInt("ui") to x.getString("name") } }
            }
        }
        return emptyList()
    }

    private fun renderForm() {
        col.addView(K.h1(act, "拍照出题"))
        col.addView(steps(0), K.full())

        val info = K.card(act, 12)
        info.addView(K.segmented(act, listOf("课本", "作业 / 试卷"), material) { material = it; render() }, K.full())
        val row = K.hbox(act, 8)
        row.addView(K.select(act, book.ifEmpty { "选择册次" }) {
            val list = books()
            AlertDialog.Builder(act).setTitle("册次").setItems(list.toTypedArray()) { _, i -> book = list[i]; unitIdx = 0; render() }.show()
        }, K.weight())
        row.addView(K.select(act, subject) {
            AlertDialog.Builder(act).setTitle("学科").setItems(SUBJECTS.toTypedArray()) { _, i -> subject = SUBJECTS[i]; unitIdx = 0; render() }.show()
        }, K.weight())
        info.addView(row, K.full())
        val us = units()
        info.addView(K.select(act, us.getOrNull(unitIdx)?.second ?: "（未分单元）") {
            if (us.isEmpty()) return@select
            AlertDialog.Builder(act).setTitle("单元").setItems(us.map { it.second }.toTypedArray()) { _, i -> unitIdx = i; render() }.show()
        }, K.full())
        col.addView(info, K.full())

        val pc = K.card(act)
        val head = K.hbox(act)
        head.addView(K.h2(act, "照片"), K.weight())
        head.addView(K.muted(act, "${photos.size} / $MAX_PHOTOS"))
        pc.addView(head, K.full())
        val tiles = ArrayList<View>()
        photos.forEachIndexed { i, jpg -> tiles += thumb(jpg, i) }
        if (photos.size < MAX_PHOTOS) tiles += addTile()
        pc.addView(K.grid(act, 3, tiles, heightDp = 132), K.full())
        pc.addView(K.muted(act, "补充说明（可选）"))
        pc.addView(K.input(act, "例如：只出第 3 题的变体", note).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { note = s.toString() }
            })
        }, K.full())
        col.addView(pc, K.full())

        col.addView(K.muted(act, "由 Claude 识别和出题。出好的题会先给你逐题核对，确认后才进电视题库。"))
        col.addView(K.primary(act, "识别并出题") { confirmSend() })
    }

    private fun thumb(jpg: ByteArray, i: Int) = FrameLayout(act).apply {
        val iv = ImageView(act).apply {
            setImageBitmap(BitmapFactory.decodeByteArray(jpg, 0, jpg.size))
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, K.dp(act, 12).toFloat()) }
            }
            clipToOutline = true
        }
        addView(iv, FrameLayout.LayoutParams(K.MATCH, K.MATCH))
        val x = FrameLayout(act).apply {
            background = K.shape(act, 0x99000000.toInt(), 14)
            contentDescription = "移除第 ${i + 1} 张"
            isClickable = true
            setOnClickListener { photos.removeAt(i); render() }
            addView(K.icon(act, R.drawable.ic_close, Color.WHITE, 14), FrameLayout.LayoutParams(K.dp(act, 14), K.dp(act, 14), Gravity.CENTER))
        }
        addView(x, FrameLayout.LayoutParams(K.dp(act, 28), K.dp(act, 28), Gravity.TOP or Gravity.END).apply { setMargins(0, K.dp(act, 6), K.dp(act, 6), 0) })
    }

    private fun addTile() = K.vbox(act, 4).apply {
        gravity = Gravity.CENTER
        background = K.ripple(K.shape(act, K.CARD, 12, 0xFFAEB8CC.toInt(), 2))
        isClickable = true
        contentDescription = "添加照片"
        setOnClickListener {
            AlertDialog.Builder(act).setItems(arrayOf("拍照", "从相册选")) { _, w -> if (w == 0) takePhoto() else pickPhotos() }.show()
        }
        addView(K.icon(act, R.drawable.ic_camera, K.PRIMARY, 26).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        addView(K.text(act, "拍照 / 相册", 13f, K.PRIMARY).apply { gravity = Gravity.CENTER })
    }

    private fun takePhoto() {
        val dir = File(act.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "p${System.currentTimeMillis()}.jpg")
        pendingCamera = f
        val uri = FileProvider.getUriForFile(act, "com.family.tvquiz.parent.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { act.startActivityForResult(i, REQ_CAMERA) } catch (e: Exception) { Ui.toast(act, "打不开相机") }
    }

    private fun pickPhotos() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        act.startActivityForResult(Intent.createChooser(i, "选择照片"), REQ_PICK)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || requestCode !in listOf(REQ_CAMERA, REQ_PICK)) return
        val uris = ArrayList<Uri>()
        if (requestCode == REQ_CAMERA) pendingCamera?.let { uris += Uri.fromFile(it) }
        else {
            data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
            if (uris.isEmpty()) data?.data?.let { uris += it }
        }
        act.task({ uris.take(MAX_PHOTOS - photos.size).mapNotNull { shrink(it) } }) { photos += it; render() }
    }

    /** 缩小到长边 1568 像素并转成 JPEG。 */
    private fun shrink(uri: Uri): ByteArray? {
        val open = { act.contentResolver.openInputStream(uri) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // ---------- ② 发送确认、识别 ----------

    private fun confirmSend() {
        if (photos.isEmpty()) { Ui.toast(act, "先拍照或选图"); return }
        val key = store.apiKey
        if (key.isNullOrBlank()) { act.askApiKey { confirmSend() }; return }
        val unit = units().getOrNull(unitIdx) ?: (0 to "")
        val m = Claude.Meta(book, subject, unit.second, if (material == 0) "课本" else "作业或试卷", note.trim())
        val kb = photos.sumOf { it.size } / 1024

        K.bottomSheet(act) { box, d ->
            box.addView(K.text(act, "将发送给 Claude（Anthropic）", 20f, bold = true))
            box.addView(sheetRow(R.drawable.ic_image, K.PRIMARY, K.PRIMARY_SOFT, "${photos.size} 张照片（共约 $kb KB）"), K.full())
            box.addView(sheetRow(R.drawable.ic_book, K.PRIMARY, K.PRIMARY_SOFT,
                "$book · $subject · ${unit.second.ifEmpty { "未分单元" }} · ${if (material == 0) "课本" else "作业"}" +
                    if (m.note.isNotEmpty()) " · 补充说明" else ""), K.full())
            box.addView(sheetRow(R.drawable.ic_shield, K.OK, K.OK_BG, "不发送答题记录、孩子信息或电视里的其他数据。照片里如有姓名，请先裁掉。"), K.full())
            box.addView(K.primary(act, "发送并识别") { d.dismiss(); extract(key, m, unit.first) })
            box.addView(K.ghost(act, "取消") { d.dismiss() })
        }
    }

    private fun sheetRow(icon: Int, fg: Int, bg: Int, s: String) = K.hbox(act, 12).apply {
        gravity = Gravity.TOP
        val ic = FrameLayout(act).apply {
            background = K.shape(act, bg, 10)
            addView(K.icon(act, icon, fg, 18), FrameLayout.LayoutParams(K.dp(act, 18), K.dp(act, 18), Gravity.CENTER))
        }
        addView(ic, LinearLayout.LayoutParams(K.dp(act, 32), K.dp(act, 32)))
        addView(K.text(act, s, 15f), K.weight())
    }

    private fun extract(key: String, m: Claude.Meta, ui: Int) {
        meta = m; metaUi = ui
        stage = Stage.LOADING; render()
        val shots = photos.toList()
        act.task({ Claude.extract(key, shots, m) }, fail = { e ->
            stage = Stage.FORM; render()
            Ui.alert(act, "识别失败", e.message ?: e.toString())
        }) { r ->
            notes = r.notes
            qs.clear()
            for (c in r.questions) qs += Q(c.stem, c.options.toMutableList(), c.answer, c.explain, c.lesson, c.kind, c.uncertain, c.uncertainReason, !c.uncertain)
            stage = Stage.REVIEW; render()
        }
    }

    private fun renderLoading() {
        col.addView(K.h1(act, "拍照出题"))
        col.addView(steps(1), K.full())
        val c = K.card(act, 12).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(K.dp(act, 16), K.dp(act, 40), K.dp(act, 16), K.dp(act, 40)) }
        c.addView(ProgressBar(act).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(K.PRIMARY) })
        c.addView(K.text(act, "Claude 正在识别和出题", 17f, bold = true).apply { gravity = Gravity.CENTER })
        c.addView(K.muted(act, "通常需要 1 到 3 分钟，请不要离开这个页面", 14f).apply { gravity = Gravity.CENTER })
        col.addView(c, K.full())
    }

    // ---------- ③ 核对 ----------

    private fun renderReview() {
        val m = meta ?: return
        val head = K.hbox(act, 4)
        head.addView(FrameLayout(act).apply {
            isClickable = true; contentDescription = "返回"
            background = K.ripple(android.graphics.drawable.ColorDrawable(0))
            setOnClickListener { onBack() }
            addView(K.icon(act, R.drawable.ic_back, K.TEXT, 22), FrameLayout.LayoutParams(K.dp(act, 22), K.dp(act, 22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(K.dp(act, 44), K.dp(act, 44)))
        head.addView(K.h1(act, "核对题目"), K.weight())
        col.addView(head, K.full())
        col.addView(steps(2), K.full())

        val unsure = qs.count { it.uncertain }
        col.addView(K.muted(act, "${m.book} · ${m.subject} · ${m.unit}。共 ${qs.size} 道" +
            (if (unsure > 0) "，$unsure 道存疑已默认不采用" else "") + "。点选项可改正确答案。", 14f))
        if (notes.isNotBlank()) col.addView(K.muted(act, "Claude 说明：$notes", 14f))
        if (qs.isEmpty()) col.addView(K.ghost(act, "没有出题，返回重拍") { stage = Stage.FORM; render() })

        qs.forEachIndexed { i, q -> col.addView(questionCard(i, q), K.full()) }

        // 底部提交栏
        bottomBar.removeAllViews()
        val n = qs.count { it.use }
        bottomBar.addView(K.muted(act, "已选 $n / ${qs.size}", 14f))
        bottomBar.addView(K.primary(act, "提交 $n 道到电视") { submit() }.apply { isEnabled = n > 0; alpha = if (n > 0) 1f else 0.5f },
            LinearLayout.LayoutParams(0, K.dp(act, 52), 1f))
    }

    private fun questionCard(i: Int, q: Q): LinearLayout {
        val card = K.card(act, 12)
        if (q.uncertain) card.background = K.shape(act, K.CARD, 16, K.WARN_BORDER, 2)

        val head = K.hbox(act, 8)
        val (label, fg, bg) = when (q.kind) {
            "variant" -> Triple("变体", K.PURPLE, K.PURPLE_BG)
            "practice" -> Triple("练习", K.OK, K.OK_BG)
            else -> Triple("原题", K.PRIMARY, K.PRIMARY_SOFT)
        }
        head.addView(K.tag(act, label, fg, bg))
        head.addView(K.muted(act, q.lesson).apply { maxLines = 1 }, K.weight())
        head.addView(Switch(act).apply {
            isChecked = q.use
            contentDescription = "采用第 ${i + 1} 题"
            setOnCheckedChangeListener { _, v -> q.use = v; render() }
        })
        card.addView(head, K.full())

        if (q.uncertain) {
            val warn = K.hbox(act, 8).apply {
                gravity = Gravity.TOP
                background = K.shape(act, K.WARN_BG, 10)
                setPadding(K.dp(act, 12), K.dp(act, 10), K.dp(act, 12), K.dp(act, 10))
            }
            warn.addView(K.icon(act, R.drawable.ic_warn, K.WARN, 18))
            warn.addView(K.text(act, "存疑：${q.reason}。核对后再打开采用。", 14f, K.WARN), K.weight())
            card.addView(warn, K.full())
        }

        card.addView(K.text(act, q.stem, 22f, bold = true))
        val longest = q.options.maxOfOrNull { it.length } ?: 0
        val cols = if (longest <= 4) minOf(4, q.options.size) else if (longest <= 10) 2 else 1
        val chips = q.options.map { o ->
            val ok = o == q.answer
            K.chip(act, o) { q.answer = o; render() }.apply {
                background = K.ripple(if (ok) K.shape(act, K.OK_BG, 12, K.OK, 2) else K.shape(act, K.CARD, 12, K.BORDER, 1))
                setTextColor(if (ok) K.OK else K.TEXT)
                textSize = if (longest <= 4) 18f else 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                contentDescription = if (ok) "$o，正确答案" else o
            }
        }
        card.addView(K.grid(act, cols.coerceAtLeast(1), chips, heightDp = 48), K.full())
        if (q.answer !in q.options) card.addView(K.text(act, "正确答案「${q.answer}」不在选项里，请点一个选项或修改题目。", 14f, K.ERR))
        if (q.explain.isNotBlank()) card.addView(K.muted(act, "解析：${q.explain}"))

        card.addView(K.hbox(act, 6).apply {
            minimumHeight = K.dp(act, 44)
            isClickable = true
            setOnClickListener { editQuestion(q) }
            addView(K.icon(act, R.drawable.ic_edit, K.PRIMARY, 16))
            addView(K.text(act, "修改题目", 14f, K.PRIMARY))
        })
        return card
    }

    private fun editQuestion(q: Q) {
        val box = K.vbox(act, 10).apply { setPadding(K.dp(act, 20), K.dp(act, 8), K.dp(act, 20), 0) }
        val stem = K.input(act, "题干", q.stem, lines = 2)
        val opts = K.input(act, "选项，每行一个（2 到 6 个）", q.options.joinToString("\n"), lines = 4)
        val expl = K.input(act, "解析（可选）", q.explain, lines = 2)
        box.addView(K.muted(act, "题干")); box.addView(stem, K.full())
        box.addView(K.muted(act, "选项（每行一个）")); box.addView(opts, K.full())
        box.addView(K.muted(act, "解析")); box.addView(expl, K.full())
        AlertDialog.Builder(act).setTitle("修改题目").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                q.stem = stem.text.toString().trim()
                q.options = opts.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
                q.explain = expl.text.toString().trim()
                if (q.answer !in q.options) q.answer = q.options.firstOrNull() ?: ""
                render()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun submit() {
        val m = meta ?: return
        val out = JSONArray()
        val problems = ArrayList<String>()
        qs.forEachIndexed { i, q ->
            if (!q.use) return@forEachIndexed
            val err = when {
                q.stem.isEmpty() -> "题干为空"
                q.options.size !in 2..6 -> "选项要 2 到 6 个"
                q.answer !in q.options -> "答案不在选项里"
                else -> null
            }
            if (err != null) { problems += "第 ${i + 1} 题：$err"; return@forEachIndexed }
            out.put(JSONObject()
                .put("sub", m.subject).put("book", m.book).put("ui", metaUi).put("u", m.unit)
                .put("l", q.lesson).put("q", q.stem).put("a", q.answer).put("o", JSONArray(q.options))
                .apply { if (q.explain.isNotEmpty()) put("x", q.explain) })
        }
        if (problems.isNotEmpty()) { Ui.alert(act, "请先改正", problems.joinToString("\n")); return }
        act.task({ store.api().post("/api/custom", JSONObject().put("questions", out)) }, fail = act::handleError) { res ->
            val errs = res.optJSONArray("errors")
            val msg = "已加入电视题库 ${res.optInt("added")} 道。" +
                if (errs != null && errs.length() > 0) "\n未加入：\n" + errs.strings().joinToString("\n") else ""
            act.learn.invalidate()
            AlertDialog.Builder(act).setTitle("完成").setMessage(msg)
                .setPositiveButton("继续拍照") { _, _ -> photos.clear(); note = ""; stage = Stage.FORM; render() }
                .setNegativeButton("查看导入题") { _, _ -> photos.clear(); note = ""; stage = Stage.FORM; act.openRecords(2) }
                .setCancelable(false).show()
        }
    }
}
