package ru.gflru.launcher

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlin.concurrent.thread

const val GAME_PKG = "com.sunborn.girlsfrontline.en"

/** Куда писать файлы: пути относительны папке Android/data/<пакет>/files */
interface Target {
    fun get(rel: String, dst: OutputStream): Boolean
    fun put(rel: String, src: InputStream)
}

/** Доступ через Storage Access Framework (Android 11–12 с выбором папки; на 13+ закрыт системой). */
class SafTarget(private val ctx: Context, private val root: DocumentFile) : Target {
    private fun find(rel: String, create: Boolean): DocumentFile? {
        var cur: DocumentFile = root
        val parts = rel.split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            val last = i == parts.lastIndex
            var next = cur.findFile(p)
            if (next == null && create) {
                next = if (last) cur.createFile("application/octet-stream", p)
                else cur.createDirectory(p)
            }
            cur = next ?: return null
        }
        return cur
    }

    override fun get(rel: String, dst: OutputStream): Boolean {
        val f = find(rel, false) ?: return false
        val input = ctx.contentResolver.openInputStream(f.uri) ?: return false
        input.use { it.copyTo(dst) }
        return true
    }

    override fun put(rel: String, src: InputStream) {
        val f = find(rel, true) ?: throw IOException("Не удалось создать $rel")
        val out = ctx.contentResolver.openOutputStream(f.uri, "wt")
            ?: throw IOException("Не удалось открыть $rel на запись")
        out.use { src.copyTo(it) }
    }
}

/** Доступ через root (su). Права и владелец копируются с папки игры. */
class RootTarget(private val cache: File, private val base: String) : Target {
    private fun su(cmd: String): Process = ProcessBuilder("su", "-c", cmd).start()

    override fun get(rel: String, dst: OutputStream): Boolean {
        if (su("test -f '$base/$rel'").waitFor() != 0) return false
        val p = su("cat '$base/$rel'")
        p.inputStream.use { it.copyTo(dst) }
        return p.waitFor() == 0
    }

    override fun put(rel: String, src: InputStream) {
        val tmp = File(cache, "root_tmp.bin")
        tmp.outputStream().use { src.copyTo(it) }
        val dst = "$base/$rel"
        val dir = dst.substringBeforeLast('/')
        val cmd = "mkdir -p '$dir' && cp '${tmp.path}' '$dst' && " +
            "chown \$(stat -c %u:%g '$base') '$dst' && chmod 660 '$dst'"
        val p = su(cmd)
        val code = p.waitFor()
        tmp.delete()
        if (code != 0) throw IOException(p.errorStream.bufferedReader().readText().ifBlank { "su: код $code" })
    }
}

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("gflru", MODE_PRIVATE) }

    private lateinit var logView: TextView
    private lateinit var urlEdit: EditText
    private lateinit var rootSwitch: Switch
    private lateinit var progress: ProgressBar
    private lateinit var rowGame: TextView
    private lateinit var rowPatch: TextView
    private lateinit var rowAccess: TextView
    private val actionButtons = mutableListOf<Button>()

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("tree", uri.toString()).apply()
            log("Папка выбрана: $uri")
            refreshStatus()
        }
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val f = File(cacheDir, "patch.zip")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            install(f)
        }
    }

    // ---------- оформление ----------

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)
    private fun lp(w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h)

    private fun roundBg(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun ripple(fill: Int, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            roundBg(fill, radiusDp),
            roundBg(Color.WHITE, radiusDp)
        )

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(parent: LinearLayout, title: String?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(col(R.color.card), 16, col(R.color.card_border))
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        if (title != null) {
            c.addView(label(title.uppercase(), 11f, col(R.color.text_dim), true).apply {
                letterSpacing = 0.12f
                setPadding(0, 0, 0, dp(6))
            })
        }
        parent.addView(c, lp().apply { topMargin = dp(12) })
        return c
    }

    private fun statusRow(parent: LinearLayout, name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(label(name, 14f, col(R.color.text_dim)), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = label("…", 14f, col(R.color.text), true)
        row.addView(value)
        parent.addView(row, lp())
        return value
    }

    private fun setStatus(v: TextView, text: String, ok: Boolean) {
        v.text = text
        v.setTextColor(col(if (ok) R.color.ok else R.color.bad))
    }

    private fun button(parent: ViewGroup, text: String, primary: Boolean, action: () -> Unit): Button {
        val b = Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(col(if (primary) R.color.on_accent else R.color.text))
            stateListAnimator = null
            background = ripple(col(if (primary) R.color.accent else R.color.btn), 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(50)
            setOnClickListener { action() }
        }
        parent.addView(b, lp().apply { topMargin = dp(8) })
        actionButtons.add(b)
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(col(R.color.bg))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))

        // шапка: логотип + название
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, lp(dp(72), dp(72)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(label("GFL RU", 28f, col(R.color.text), true))
        titles.addView(label("Русификатор Girls' Frontline · EN", 13f, col(R.color.text_dim)))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(header, lp())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
            indeterminateTintList = ColorStateList.valueOf(col(R.color.accent))
        }
        root.addView(progress, lp().apply { topMargin = dp(14) })

        // состояние
        val statusCard = card(root, "Состояние")
        rowGame = statusRow(statusCard, "Игра")
        rowPatch = statusRow(statusCard, "Перевод")
        rowAccess = statusRow(statusCard, "Доступ к папке")

        // запуск
        button(root, "▶  Запустить игру", true) {
            val i = packageManager.getLaunchIntentForPackage(GAME_PKG)
            if (i != null) startActivity(i) else log("Игра ($GAME_PKG) не установлена")
        }

        // доступ
        val accessCard = card(root, "Доступ к файлам игры")
        button(accessCard, "Выбрать папку игры", false) {
            val hint = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Android/data/$GAME_PKG/files"
            )
            pickTree.launch(hint)
        }
        rootSwitch = Switch(this).apply {
            text = "Использовать root (su)"
            setTextColor(col(R.color.text))
            isChecked = prefs.getBoolean("root", false)
            setOnCheckedChangeListener { _, c ->
                prefs.edit().putBoolean("root", c).apply()
                refreshStatus()
            }
        }
        accessCard.addView(rootSwitch, lp().apply { topMargin = dp(12) })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            accessCard.addView(
                label(
                    "Android 13+: выбор папки Android/data закрыт системой. Нужен root или копирование файлов с ПК.",
                    12f, col(R.color.text_dim)
                ),
                lp().apply { topMargin = dp(8) }
            )
        }

        // перевод
        val patchCard = card(root, "Перевод")
        button(patchCard, "Установить встроенный перевод", false) { background { installBuiltin() } }
        urlEdit = EditText(this).apply {
            hint = "https://…/gfl_ru_patch.zip"
            setHintTextColor(col(R.color.text_dim))
            setTextColor(col(R.color.text))
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = roundBg(col(R.color.bg), 10, col(R.color.card_border))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString("url", ""))
        }
        patchCard.addView(urlEdit, lp().apply { topMargin = dp(12) })
        button(patchCard, "Скачать по ссылке и установить", false) {
            val u = urlEdit.text.toString().trim()
            prefs.edit().putString("url", u).apply()
            background { install(download(u)) }
        }
        button(patchCard, "Установить из zip-файла", false) { pickZip.launch(arrayOf("application/zip", "*/*")) }
        button(patchCard, "Откатить на оригинал", false) { background { restore() } }

        // журнал
        val logCard = card(root, "Журнал")
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(col(R.color.text_dim))
            setTextIsSelectable(true)
        }
        logCard.addView(logView, lp())

        root.addView(
            label("GFL RU Launcher 1.2", 11f, col(R.color.text_dim)).apply { gravity = Gravity.CENTER },
            lp().apply { topMargin = dp(20) }
        )

        setContentView(scroll)
        refreshStatus()
        log("Готов к работе.")
    }

    override fun onResume() {
        super.onResume()
        if (::rowGame.isInitialized) refreshStatus()
    }

    // ---------- вспомогательное ----------

    private fun refreshStatus() = runOnUiThread {
        val gameOk = packageManager.getLaunchIntentForPackage(GAME_PKG) != null
        setStatus(rowGame, if (gameOk) "установлена" else "не найдена", gameOk)
        val ver = prefs.getInt("ver", 0)
        setStatus(rowPatch, if (ver > 0) "версия $ver" else "не установлен", ver > 0)
        val access = when {
            rootSwitch.isChecked -> "root"
            prefs.getString("tree", null) != null -> "папка выбрана"
            else -> "не настроен"
        }
        setStatus(rowAccess, access, access != "не настроен")
    }

    private fun log(s: String) = runOnUiThread { logView.append(s + "\n") }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun setBusy(busy: Boolean) = runOnUiThread {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        for (b in actionButtons) {
            b.isEnabled = !busy
            b.alpha = if (busy) 0.5f else 1f
        }
    }

    private fun background(block: () -> Unit) {
        setBusy(true)
        thread {
            try {
                block()
                toast("Готово")
            } catch (e: Exception) {
                log("Ошибка: ${e.message}")
                toast("Ошибка: ${e.message}")
            }
            setBusy(false)
            refreshStatus()
        }
    }

    private fun target(): Target {
        if (rootSwitch.isChecked)
            return RootTarget(cacheDir, "/sdcard/Android/data/$GAME_PKG/files")
        val s = prefs.getString("tree", null) ?: throw IOException("Сначала выберите папку игры или включите root")
        val doc = DocumentFile.fromTreeUri(this, Uri.parse(s)) ?: throw IOException("Папка недоступна")
        return SafTarget(this, doc)
    }

    private fun download(url: String): File {
        require(url.startsWith("https://")) { "Нужна https-ссылка" }
        log("Скачиваю…")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val f = File(cacheDir, "patch.zip")
        c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Формат архива: manifest.json {"version":1,"files":[{"src":"a.ab","dst":"Android/a.ab"}]} */
    private fun install(zip: File) {
        val t = target()
        ZipFile(zip).use { z ->
            val me = z.getEntry("manifest.json") ?: throw IOException("В архиве нет manifest.json")
            val m = JSONObject(z.getInputStream(me).bufferedReader().readText())
            val arr = m.getJSONArray("files")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val src = o.getString("src")
                val dst = o.getString("dst")
                if (dst.startsWith("/") || dst.contains("..")) throw IOException("Недопустимый путь: $dst")
                val e = z.getEntry(src) ?: throw IOException("В архиве нет $src")
                val b = File(filesDir, "backup/$dst")
                if (!b.exists()) {
                    b.parentFile!!.mkdirs()
                    val ok = b.outputStream().use { t.get(dst, it) }
                    if (!ok) b.delete() else log("Бэкап: $dst")
                }
                z.getInputStream(e).use { t.put(dst, it) }
                log("✔ $dst")
            }
            prefs.edit().putInt("ver", m.optInt("version")).apply()
        }
        log("Готово. Запускайте игру.")
    }

    private fun installBuiltin() {
        val has = try { assets.list("")?.contains("patch.zip") == true } catch (e: IOException) { false }
        if (!has) { log("В этом APK нет встроенного перевода (assets/patch.zip)"); return }
        val f = File(cacheDir, "patch.zip")
        assets.open("patch.zip").use { i -> f.outputStream().use { i.copyTo(it) } }
        install(f)
    }

    private fun restore() {
        val t = target()
        val dir = File(filesDir, "backup")
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) { log("Бэкапа нет"); return }
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            f.inputStream().use { t.put(rel, it) }
            log("↩ $rel")
        }
        prefs.edit().putInt("ver", 0).apply()
        log("Оригиналы восстановлены.")
    }
}
