package com.example.apkshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.apkshare.databinding.ActivityFileManagerBinding
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Gestionnaire de fichiers : parcourt les dossiers du stockage, sélection multiple,
 * copier / couper / coller, supprimer, renommer, nouveau dossier, partager, infos,
 * tri, installation des APK et mise en file « Envoyer plus tard ».
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: FileAdapter
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private var currentDir: File = File("/")
    private val selected = linkedSetOf<File>()
    private var selecting = false
    private var clipFiles: List<File> = emptyList()
    private var clipCut = false
    private var sortMode = 0            // 0 = nom, 1 = date, 2 = taille
    private var hadAccess = false
    private var pendingInstall: File? = null
    private var busyDialog: AlertDialog? = null

    private val receivedDir: File
        get() = File(filesDir, "received").apply { mkdirs() }
    private val outboxDir: File
        get() = File(filesDir, "outbox").apply { mkdirs() }

    // ---------- Liste ----------

    private inner class FileAdapter :
        ArrayAdapter<File>(this@FileManagerActivity, android.R.layout.simple_list_item_2) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val file = getItem(position)!!
            val mark = if (selecting) (if (file in selected) "☑ " else "☐ ") else ""
            val icon = if (file.isDirectory) "📁 " else ""
            view.findViewById<TextView>(android.R.id.text1).text = mark + icon + file.name
            val info = if (file.isDirectory) "Dossier" else Formatter.formatFileSize(context, file.length())
            view.findViewById<TextView>(android.R.id.text2).text =
                "$info · ${dateFormat.format(Date(file.lastModified()))}"
            view.setBackgroundColor(if (file in selected) 0x334488FF else 0)
            return view
        }
    }

    // ---------- Accès au stockage ----------

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkAccessChanged()
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    toast("Ouvrez Paramètres > Applications > ApkShare et autorisez l'accès à tous les fichiers.")
                }
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    /** Volumes de stockage (interne, carte SD, USB) : nom + dossier racine. */
    private fun volumes(): List<Pair<String, File>> {
        val list = mutableListOf<Pair<String, File>>()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { v ->
                    val dir = v.directory ?: return@forEach
                    if (v.state == Environment.MEDIA_MOUNTED ||
                        v.state == Environment.MEDIA_MOUNTED_READ_ONLY
                    ) {
                        list += (v.getDescription(this) ?: dir.name) to dir
                    }
                }
            } else {
                getExternalFilesDirs(null).filterNotNull().forEachIndexed { i, f ->
                    val root = f.absolutePath.substringBefore("/Android/")
                    list += (if (i == 0) "Stockage interne" else "Stockage externe $i") to File(root)
                }
            }
        } catch (e: Exception) {
            // on retombe sur le stockage principal ci-dessous
        }
        if (list.isEmpty()) list += "Stockage interne" to Environment.getExternalStorageDirectory()
        return list
    }

    private fun isRoot(dir: File): Boolean = try {
        val c = dir.canonicalFile
        c == receivedDir.canonicalFile || volumes().any { it.second.canonicalFile == c }
    } catch (e: Exception) {
        false
    }

    private fun canGoUp(): Boolean = !isRoot(currentDir) && currentDir.parentFile != null

    // ---------- Cycle de vie ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Affichage plein écran : on décale le contenu sous les barres système
        val root = binding.root
        val padL = root.paddingLeft
        val padT = root.paddingTop
        val padR = root.paddingRight
        val padB = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(padL + bars.left, padT + bars.top, padR + bars.right, padB + bars.bottom)
            insets
        }

        adapter = FileAdapter()
        binding.fileList.adapter = adapter
        binding.fileList.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.getItem(position) ?: return@setOnItemClickListener
            when {
                selecting -> toggle(file)
                file.isDirectory -> navigate(file)
                else -> openFile(file)
            }
        }
        binding.fileList.setOnItemLongClickListener { _, _, position, _ ->
            val file = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            if (!selecting) {
                selecting = true
                selected.add(file)
                adapter.notifyDataSetChanged()
                updateBars()
            } else {
                toggle(file)
            }
            true
        }

        binding.storageButton.setOnClickListener { goToStorage() }
        binding.receivedButton.setOnClickListener { navigate(receivedDir) }
        binding.upButton.setOnClickListener { if (canGoUp()) navigate(currentDir.parentFile!!) }
        binding.newFolderButton.setOnClickListener { newFolder() }
        binding.selectButton.setOnClickListener {
            if (selecting) exitSelection() else {
                selecting = true
                adapter.notifyDataSetChanged()
                updateBars()
            }
        }
        binding.sortButton.setOnClickListener { showSortDialog() }
        binding.pasteButton.setOnClickListener { paste() }
        binding.permissionButton.setOnClickListener { requestAccess() }
        binding.copyButton.setOnClickListener { putInClipboard(cut = false) }
        binding.cutButton.setOnClickListener { putInClipboard(cut = true) }
        binding.deleteButton.setOnClickListener {
            if (selected.isEmpty()) toast("Rien de sélectionné.") else confirmDelete(selected.toList())
        }
        binding.shareButton.setOnClickListener {
            if (selected.isEmpty()) toast("Rien de sélectionné.") else share(selected.toList())
        }
        binding.moreButton.setOnClickListener { showMore() }
        binding.closeButton.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selecting -> exitSelection()
                    canGoUp() -> navigate(currentDir.parentFile!!)
                    else -> finish()
                }
            }
        })

        hadAccess = hasAllFilesAccess()
        navigate(if (hadAccess) volumes().first().second else receivedDir)
    }

    override fun onResume() {
        super.onResume()
        checkAccessChanged()
        // Retour des réglages « applications inconnues » : on relance l'installation
        val file = pendingInstall
        if (file != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            installApk(file)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        busyDialog?.dismiss()
    }

    private fun checkAccessChanged() {
        val access = hasAllFilesAccess()
        if (access != hadAccess) {
            hadAccess = access
            navigate(if (access) volumes().first().second else receivedDir)
        } else if (::adapter.isInitialized) {
            updateBars()
        }
    }

    // ---------- Navigation et affichage ----------

    private fun navigate(dir: File) {
        currentDir = dir
        selected.clear()
        selecting = false
        refresh()
    }

    private fun goToStorage() {
        if (!hasAllFilesAccess()) {
            requestAccess()
            return
        }
        val vols = volumes()
        if (vols.size == 1) {
            navigate(vols[0].second)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Stockage")
                .setItems(vols.map { it.first }.toTypedArray()) { _, which -> navigate(vols[which].second) }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun sorted(list: List<File>): List<File> {
        val byMode: Comparator<File> = when (sortMode) {
            1 -> compareByDescending<File> { it.lastModified() }
            2 -> compareByDescending<File> { it.length() }
            else -> compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return list.sortedWith(compareByDescending<File> { it.isDirectory }.then(byMode))
    }

    private fun refresh() {
        val all = currentDir.listFiles()
        val list = sorted(all?.toList() ?: emptyList())
        adapter.clear()
        adapter.addAll(list)
        binding.emptyText.text = if (all == null) "Dossier inaccessible." else "Dossier vide."
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.fileList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        updateBars()
    }

    private fun updateBars() {
        binding.pathText.text = currentDir.absolutePath
        binding.countText.text = "${adapter.count} élément(s)"
        binding.upButton.isEnabled = canGoUp()
        binding.permissionCard.visibility = if (hasAllFilesAccess()) View.GONE else View.VISIBLE
        binding.pasteButton.visibility = if (clipFiles.isNotEmpty()) View.VISIBLE else View.GONE
        binding.pasteButton.text =
            if (clipCut) "Coller (déplacer ${clipFiles.size})" else "Coller (${clipFiles.size})"
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.selectionText.text = "${selected.size} sélectionné(s)"
        binding.selectButton.text = if (selecting) "Terminer" else "Sélectionner"
    }

    private fun toggle(file: File) {
        if (!selected.remove(file)) selected.add(file)
        adapter.notifyDataSetChanged()
        updateBars()
    }

    private fun exitSelection() {
        selected.clear()
        selecting = false
        adapter.notifyDataSetChanged()
        updateBars()
    }

    private fun showSortDialog() {
        AlertDialog.Builder(this)
            .setTitle("Trier par")
            .setSingleChoiceItems(
                arrayOf("Nom", "Date (récent d'abord)", "Taille (grand d'abord)"), sortMode
            ) { dialog, which ->
                sortMode = which
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Ouverture ----------

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    private fun uriOf(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun openFile(file: File) {
        if (file.name.endsWith(".apk", ignoreCase = true)) {
            installApk(file)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriOf(file), mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Aucune application pour ouvrir ce fichier.")
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            AlertDialog.Builder(this)
                .setTitle("Autorisation requise")
                .setMessage(
                    "Pour installer un APK, autorisez ApkShare à installer des applications " +
                        "inconnues dans les paramètres qui vont s'ouvrir."
                )
                .setPositiveButton("Ouvrir les paramètres") { _, _ ->
                    val intents = listOf(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_SECURITY_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS)
                    )
                    for (i in intents) {
                        try {
                            startActivity(i)
                            break
                        } catch (e: Exception) {
                            // écran suivant
                        }
                    }
                }
                .setNegativeButton("Annuler") { _, _ -> pendingInstall = null }
                .show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriOf(file), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Impossible de lancer l'installation : ${e.message}")
        }
    }

    // ---------- Opérations ----------

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty() || base == name) "$name ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    /** Exécute un travail long hors du thread principal, avec un message d'attente. */
    private fun runBusy(message: String, work: () -> String, done: (String) -> Unit) {
        busyDialog?.dismiss()
        busyDialog = AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()
        Thread {
            val result = try {
                work()
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }
            runOnUiThread {
                busyDialog?.dismiss()
                busyDialog = null
                if (!isDestroyed) done(result)
            }
        }.start()
    }

    private fun putInClipboard(cut: Boolean) {
        if (selected.isEmpty()) {
            toast("Rien de sélectionné.")
            return
        }
        clipFiles = selected.toList()
        clipCut = cut
        exitSelection()
        toast(
            "${clipFiles.size} élément(s) ${if (cut) "à déplacer" else "copié(s)"}. " +
                "Ouvrez le dossier de destination puis « Coller »."
        )
    }

    private fun paste() {
        val items = clipFiles
        val cut = clipCut
        val dest = currentDir
        if (items.isEmpty()) return
        if (!dest.canWrite()) {
            toast("Ce dossier n'est pas modifiable.")
            return
        }
        runBusy(if (cut) "Déplacement…" else "Copie…", {
            var ok = 0
            var fail = 0
            for (src in items) {
                try {
                    if (!src.exists()) {
                        fail++
                        continue
                    }
                    val srcPath = src.canonicalPath
                    val destPath = dest.canonicalPath
                    if (src.isDirectory && (destPath == srcPath || destPath.startsWith("$srcPath/"))) {
                        fail++   // impossible de copier un dossier dans lui-même
                        continue
                    }
                    if (cut && src.parentFile?.canonicalPath == destPath) {
                        ok++     // déjà dans ce dossier
                        continue
                    }
                    val target = uniqueIn(dest, src.name)
                    if (cut) {
                        if (src.renameTo(target)) {
                            ok++
                        } else if (src.copyRecursively(target, overwrite = false) && src.deleteRecursively()) {
                            ok++
                        } else {
                            fail++
                        }
                    } else if (src.copyRecursively(target, overwrite = false)) {
                        ok++
                    } else {
                        fail++
                    }
                } catch (e: Exception) {
                    fail++
                }
            }
            "$ok élément(s) ${if (cut) "déplacé(s)" else "copié(s)"}" +
                if (fail > 0) ", $fail échec(s)" else ""
        }) { message ->
            if (cut) clipFiles = emptyList()
            toast(message)
            refresh()
        }
    }

    private fun confirmDelete(items: List<File>) {
        val what = if (items.size == 1) items[0].name else "${items.size} éléments"
        AlertDialog.Builder(this)
            .setTitle("Supprimer ?")
            .setMessage("$what\n\nCette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ ->
                runBusy("Suppression…", {
                    var fail = 0
                    for (f in items) if (!f.deleteRecursively()) fail++
                    if (fail == 0) "${items.size} élément(s) supprimé(s)" else "$fail échec(s) de suppression"
                }) { message ->
                    toast(message)
                    exitSelection()
                    refresh()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun newFolder() {
        if (!currentDir.canWrite()) {
            toast("Ce dossier n'est pas modifiable.")
            return
        }
        val input = EditText(this).apply {
            hint = "Nom du dossier"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Nouveau dossier")
            .setView(input)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> {}
                    name.contains('/') || name.contains('\\') -> toast("Nom invalide.")
                    File(currentDir, name).exists() -> toast("Ce nom existe déjà.")
                    File(currentDir, name).mkdir() -> refresh()
                    else -> toast("Impossible de créer le dossier.")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun rename(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(0, if (file.isFile) file.nameWithoutExtension.length else file.name.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Renommer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                val target = File(file.parentFile, newName)
                when {
                    newName.isEmpty() || newName == file.name -> {}
                    newName.contains('/') || newName.contains('\\') -> toast("Nom invalide.")
                    target.exists() -> toast("Un élément porte déjà ce nom.")
                    file.renameTo(target) -> {
                        exitSelection()
                        refresh()
                    }
                    else -> toast("Impossible de renommer.")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun share(items: List<File>) {
        val files = items.filter { it.isFile }
        if (files.isEmpty()) {
            toast("Sélectionnez au moins un fichier (les dossiers ne se partagent pas).")
            return
        }
        try {
            val uris = ArrayList(files.map { uriOf(it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND)
                    .setType(mimeOf(files[0]))
                    .putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Partager"))
        } catch (e: Exception) {
            toast("Partage impossible.")
        }
    }

    private fun showInfo(file: File) {
        fun show(detail: String) {
            AlertDialog.Builder(this)
                .setTitle("Informations")
                .setMessage(
                    "Nom : ${file.name}\n" +
                        "Emplacement : ${file.parentFile?.absolutePath ?: "/"}\n" +
                        "$detail\n" +
                        "Modifié : ${dateFormat.format(Date(file.lastModified()))}\n" +
                        "Modifiable : ${if (file.canWrite()) "oui" else "non"}"
                )
                .setPositiveButton("OK", null)
                .show()
        }
        if (file.isFile) {
            show("Taille : ${Formatter.formatFileSize(this, file.length())} (${file.length()} octets)")
        } else {
            runBusy("Calcul de la taille…", {
                var size = 0L
                var count = 0
                file.walkTopDown().forEach {
                    if (it.isFile) {
                        size += it.length()
                        count++
                    }
                }
                "Contenu : $count fichier(s), ${Formatter.formatFileSize(this, size)}"
            }) { detail -> show(detail) }
        }
    }

    /** Copie les fichiers dans la file « Envoyer plus tard » de l'écran principal. */
    private fun queueForLater(items: List<File>) {
        val files = items.filter { it.isFile }
        if (files.isEmpty()) {
            toast("Sélectionnez au moins un fichier.")
            return
        }
        runBusy("Ajout à la file…", {
            var ok = 0
            for (f in files) {
                val target = uniqueIn(outboxDir, f.name)
                try {
                    f.copyTo(target, overwrite = false)
                    ok++
                } catch (e: Exception) {
                    target.delete()
                }
            }
            "$ok fichier(s) ajouté(s) à « Envoyer plus tard »."
        }) { message ->
            toast(message)
            exitSelection()
        }
    }

    private fun showMore() {
        val items = selected.toList()
        if (items.isEmpty()) {
            toast("Rien de sélectionné.")
            return
        }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (items.size == 1) {
            labels += "Renommer"
            actions += { rename(items[0]) }
            labels += "Informations"
            actions += { showInfo(items[0]) }
        }
        labels += "Envoyer plus tard"
        actions += { queueForLater(items) }
        labels += "Tout sélectionner"
        actions += {
            for (i in 0 until adapter.count) adapter.getItem(i)?.let { selected.add(it) }
            adapter.notifyDataSetChanged()
            updateBars()
        }
        AlertDialog.Builder(this)
            .setTitle("${items.size} sélectionné(s)")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
