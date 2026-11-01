package ru.gflru.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.*
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

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("tree", uri.toString()).apply()
            log("Папка выбрана: $uri")
        }
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val f = File(cacheDir, "patch.zip")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            install(f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root.addView(TextView(this).apply {
            text = "Girls' Frontline EN — русификатор"
            textSize = 20f
        })

        button("1. Выбрать папку игры (SAF)") {
            val hint = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Android/data/$GAME_PKG/files")
            pickTree.launch(hint)
        }
        rootSwitch = Switch(this).apply {
            text = "Использовать root (su)"
            isChecked = prefs.getBoolean("root", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("root", c).apply() }
        }
        root.addView(rootSwitch)

        urlEdit = EditText(this).apply {
            hint = "https://…/gfl_ru_patch.zip"
            setText(prefs.getString("url", ""))
        }
        root.addView(urlEdit)

        button("2. Скачать и установить перевод") {
            val u = urlEdit.text.toString().trim()
            prefs.edit().putString("url", u).apply()
            background { install(download(u)) }
        }
        button("Установить встроенный перевод") { background { installBuiltin() } }
        button("2б. Установить из zip-файла") { pickZip.launch(arrayOf("application/zip", "*/*")) }
        button("Откатить на оригинал") { background { restore() } }
        button("Запустить игру") {
            val i = packageManager.getLaunchIntentForPackage(GAME_PKG)
            if (i != null) startActivity(i) else log("Игра ($GAME_PKG) не установлена")
        }

        logView = TextView(this)
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)
        log("Установленная версия перевода: ${prefs.getInt("ver", 0)}")
        if (android.os.Build.VERSION.SDK_INT >= 33)
            log("Android 13+: выбор Android/data через SAF закрыт системой — нужен root или копирование с ПК (см. README).")
        if (packageManager.getLaunchIntentForPackage(GAME_PKG) == null)
            log("Игра ($GAME_PKG) не найдена на устройстве.")
    }

    // ---------- вспомогательное ----------

    private fun log(s: String) = runOnUiThread { logView.append(s + "\n") }

    private fun background(block: () -> Unit) = thread {
        try { block() } catch (e: Exception) { log("Ошибка: ${e.message}") }
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

    /** Формат архива: manifest.json {"version":1,"files":[{"src":"a.ab","dst":"Android/New/a.ab"}]} */
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
