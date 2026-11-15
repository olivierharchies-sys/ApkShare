package com.example.apkshare

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.apkshare.databinding.ActivityFileManagerBinding
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Gestionnaire des fichiers reçus : liste, ouverture / installation, partage,
 * renommage, informations et suppression.
 */
class FileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_PATH = "open_path"
    }

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: FileAdapter
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    private val receivedDir: File
        get() = File(filesDir, "received").apply { mkdirs() }

    private inner class FileAdapter :
        ArrayAdapter<File>(this@FileManagerActivity, android.R.layout.simple_list_item_2) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val file = getItem(position)!!
            view.findViewById<TextView>(android.R.id.text1).text = file.name
            view.findViewById<TextView>(android.R.id.text2).text =
                "${Formatter.formatFileSize(context, file.length())} · " +
                    dateFormat.format(Date(file.lastModified()))
            return view
        }
    }

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
            adapter.getItem(position)?.let { showActions(it) }
        }
        binding.fileList.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { showActions(it) }
            true
        }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        binding.closeButton.setOnClickListener { finish() }

        reload()
    }

    private fun reload() {
        val list = receivedDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        adapter.clear()
        adapter.addAll(list)

        val total = list.sumOf { it.length() }
        binding.summaryText.text =
            "${list.size} fichier(s) · ${Formatter.formatFileSize(this, total)}"
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.fileList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.deleteAllButton.isEnabled = list.isNotEmpty()
    }

    private fun showActions(file: File) {
        val isApk = file.name.endsWith(".apk", ignoreCase = true)
        val labels = arrayOf(
            if (isApk) "Installer" else "Ouvrir",
            "Partager avec une autre application",
            "Renommer",
            "Informations",
            "Supprimer"
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> openInMain(file)
                    1 -> share(file)
                    2 -> rename(file)
                    3 -> showInfo(file)
                    4 -> confirmDelete(file)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** L'ouverture / l'installation est gérée par l'écran principal (autorisations incluses). */
    private fun openInMain(file: File) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_PATH, file.absolutePath))
        finish()
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, "Partager ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Aucune application pour partager ce fichier.", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun rename(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(0, file.nameWithoutExtension.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Renommer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() || newName == file.name -> {}
                    newName.contains('/') || newName.contains('\\') || newName.startsWith(".") ->
                        toast("Nom invalide.")
                    File(file.parentFile, newName).exists() ->
                        toast("Un fichier porte déjà ce nom.")
                    file.renameTo(File(file.parentFile, newName)) -> reload()
                    else -> toast("Impossible de renommer.")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showInfo(file: File) {
        val message = "Nom : ${file.name}\n" +
            "Taille : ${Formatter.formatFileSize(this, file.length())} (${file.length()} octets)\n" +
            "Reçu le : ${dateFormat.format(Date(file.lastModified()))}\n" +
            "Emplacement : stockage privé de l'appli"
        AlertDialog.Builder(this)
            .setTitle("Informations")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ?")
            .setMessage(file.name)
            .setPositiveButton("Supprimer") { _, _ ->
                if (!file.delete()) toast("Impossible de supprimer.")
                reload()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Tout supprimer ?")
            .setMessage("Tous les fichiers reçus seront supprimés de cet appareil.")
            .setPositiveButton("Tout supprimer") { _, _ ->
                receivedDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                reload()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
