package com.example.apkshare

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import com.example.apkshare.databinding.ActivityMainBinding
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client by lazy { Nearby.getConnectionsClient(this) }

    private val serviceId = "com.example.apkshare.SERVICE"
    private val strategy = Strategy.P2P_POINT_TO_POINT

    private var endpointId: String? = null
    private var pendingInstall: File? = null

    // Réception : association id de payload -> nom de fichier / payload fichier
    private val incomingNames = mutableMapOf<Long, String>()
    private val incomingFiles = mutableMapOf<Long, Payload>()
    private val completedIds = mutableSetOf<Long>()

    private val receivedFiles = mutableListOf<File>()
    private lateinit var listAdapter: ArrayAdapter<String>

    // ---------- Vérification / correction de la configuration ----------

    private class Check(
        val label: String,
        val ok: Boolean,
        val required: Boolean,
        val fix: () -> Unit
    )

    private var pendingReady: (() -> Unit)? = null   // action à lancer quand tout est prêt
    private var reportMode = false                    // mode « rapport » (bouton Vérifier)
    private var awaitingSettings = false              // on revient d'un écran de réglages
    private var checkDialog: AlertDialog? = null

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT <= 32) {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return perms.toTypedArray()
    }

    private fun buildChecks(): List<Check> {
        val list = mutableListOf<Check>()

        // 1. Permissions de l'appli
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        list += Check("Permissions de l'appli (appareils à proximité)", missing.isEmpty(), true) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        // 2. Bluetooth
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && adapter != null) {
            list += Check("Bluetooth activé", adapter.isEnabled, true) {
                try {
                    bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
        }

        // 3. Wi-Fi
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) && wifi != null) {
            list += Check("Wi-Fi activé", wifi.isWifiEnabled, true) {
                if (Build.VERSION.SDK_INT >= 29) {
                    openSettings(
                        Intent(Settings.Panel.ACTION_WIFI),
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wifi.isWifiEnabled = true
                    binding.root.postDelayed({ resume() }, 1500)
                }
            }
        }

        // 4. Localisation (nécessaire jusqu'à Android 12L pour la recherche d'appareils)
        if (Build.VERSION.SDK_INT <= 32) {
            val lm = getSystemService(LocationManager::class.java)
            if (lm != null) {
                list += Check("Localisation activée", LocationManagerCompat.isLocationEnabled(lm), true) {
                    openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
        }

        // 5. Batterie (recommandé : évite que le système coupe la connexion)
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null) {
            list += Check(
                "Économie de batterie désactivée pour ApkShare (recommandé)",
                pm.isIgnoringBatteryOptimizations(packageName),
                false
            ) {
                openSettings(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        }

        // 6. Installation d'applications inconnues (recommandé)
        list += Check(
            "Installation d'applications inconnues autorisée (recommandé)",
            packageManager.canRequestPackageInstalls(),
            false
        ) { openUnknownSourcesSettings() }

        return list
    }

    private fun manufacturerTips(): String? {
        return when (Build.MANUFACTURER.lowercase()) {
            "samsung" ->
                "Samsung : si l'installation est bloquée, désactivez « Blocage automatique » " +
                    "(Paramètres > Sécurité et confidentialité)."
            "xiaomi", "redmi", "poco" ->
                "Xiaomi : autorisez « Démarrage automatique » pour ApkShare, mettez la batterie sur " +
                    "« Aucune restriction » et vérifiez « Vérification de sécurité » si l'installation échoue."
            else -> null
        }
    }

    /** Lance l'action quand la configuration obligatoire est bonne, sinon propose de corriger. */
    private fun ensureReady(onReady: () -> Unit) {
        reportMode = false
        pendingReady = onReady
        evaluate()
    }

    private fun showReport() {
        pendingReady = null
        reportMode = true
        evaluate()
    }

    private fun resume() {
        if (pendingReady != null || reportMode) evaluate()
    }

    private fun evaluate() {
        val checks = buildChecks()
        val blocking = checks.filter { !it.ok && (it.required || reportMode) }

        if (blocking.isEmpty() && !reportMode) {
            val action = pendingReady
            pendingReady = null
            action?.invoke()
            return
        }

        val sb = StringBuilder()
        for (c in checks) {
            sb.append(if (c.ok) "✓ " else if (c.required) "✗ " else "⚠ ")
                .append(c.label).append('\n')
        }
        if (reportMode) {
            if (blocking.isEmpty()) sb.append("\nTout est configuré.")
            manufacturerTips()?.let { sb.append("\n\n").append(it) }
        }

        checkDialog?.dismiss()
        val builder = AlertDialog.Builder(this)
            .setTitle(if (reportMode) "Configuration du téléphone" else "Configuration nécessaire")
            .setMessage(sb.toString().trim())
            .setCancelable(false)
        if (blocking.isNotEmpty()) {
            builder.setPositiveButton("Corriger") { _, _ -> blocking.first().fix() }
        }
        builder.setNegativeButton(if (reportMode) "Fermer" else "Annuler") { _, _ ->
            pendingReady = null
            reportMode = false
        }
        checkDialog = builder.show()
    }

    private fun openSettings(vararg intents: Intent) {
        for (intent in intents) {
            try {
                awaitingSettings = true
                startActivity(intent)
                return
            } catch (e: Exception) {
                // on essaie l'écran suivant
            }
        }
        awaitingSettings = false
        setStatus("Impossible d'ouvrir les réglages : faites-le à la main dans Paramètres.")
        resume()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                resume()
            } else {
                // « Ne plus demander » : il faut passer par les réglages de l'appli
                val permanent = result.filter { !it.value }.keys
                    .any { !shouldShowRequestPermissionRationale(it) }
                if (permanent) {
                    setStatus("Autorisez les permissions manquantes dans les réglages de l'appli.")
                    openSettings(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    resume()
                }
            }
        }

    private val bluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resume() }

    // ---------- Cycle de vie ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.receivedList.adapter = listAdapter
        binding.receivedList.setOnItemClickListener { _, _, position, _ ->
            openReceived(receivedFiles[position])
        }

        loadReceivedFiles()

        binding.checkButton.setOnClickListener { showReport() }
        binding.receiveButton.setOnClickListener { ensureReady { startReceiving() } }
        binding.sendButton.setOnClickListener { ensureReady { startSending() } }
        binding.pickButton.setOnClickListener {
            try {
                pickFile.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                // Android TV : pas de sélecteur de fichiers sur beaucoup de modèles
                setStatus("Aucun sélecteur de fichiers sur cet appareil : utilisez-le comme récepteur.")
            }
        }
        binding.stopButton.setOnClickListener { stopAll() }

        // Au premier affichage : si quelque chose d'obligatoire manque, on l'indique tout de suite
        if (savedInstanceState == null && buildChecks().any { !it.ok && it.required }) {
            showReport()
        }
    }

    override fun onResume() {
        super.onResume()
        // Retour des paramètres « Sources inconnues » : on relance l'installation
        val file = pendingInstall
        if (file != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            installApk(file)
        }
        // Retour d'un écran de réglages ouvert par la vérification
        if (awaitingSettings) {
            awaitingSettings = false
            resume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        checkDialog?.dismiss()
        stopAll()
    }

    // ---------- Réception (annonce) ----------

    private fun startReceiving() {
        stopAll()
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(Build.MODEL, serviceId, connectionCallback, options)
            .addOnSuccessListener { setStatus("En attente d'un téléphone émetteur…") }
            .addOnFailureListener { setStatus("Erreur d'annonce : ${it.message}") }
    }

    // ---------- Envoi (découverte) ----------

    private fun startSending() {
        stopAll()
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener { setStatus("Recherche d'un téléphone récepteur…") }
            .addOnFailureListener { setStatus("Erreur de recherche : ${it.message}") }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            setStatus("Trouvé : ${info.endpointName}, connexion…")
            client.requestConnection(Build.MODEL, id, connectionCallback)
                .addOnFailureListener { setStatus("Échec de connexion : ${it.message}") }
        }

        override fun onEndpointLost(id: String) {
            setStatus("Appareil perdu.")
        }
    }

    // ---------- Connexion ----------

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            // Sécurité : les deux écrans doivent afficher le même code
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Connexion avec ${info.endpointName}")
                .setMessage("Vérifiez que ce code est identique sur les deux téléphones :\n\n${info.authenticationDigits}")
                .setCancelable(false)
                .setPositiveButton("Accepter") { _, _ ->
                    client.acceptConnection(id, payloadCallback)
                }
                .setNegativeButton("Refuser") { _, _ ->
                    client.rejectConnection(id)
                }
                .show()
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    endpointId = id
                    client.stopAdvertising()
                    client.stopDiscovery()
                    binding.pickButton.isEnabled = true
                    setStatus("Connecté. Vous pouvez envoyer ou recevoir des fichiers.")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    setStatus("Connexion refusée.")
                else -> setStatus("Connexion échouée.")
            }
        }

        override fun onDisconnected(id: String) {
            endpointId = null
            binding.pickButton.isEnabled = false
            setStatus("Déconnecté.")
        }
    }

    private fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointId = null
        binding.pickButton.isEnabled = false
        binding.progressBar.progress = 0
    }

    // ---------- Envoi d'un fichier ----------

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) sendFile(uri)
        }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment ?: "fichier"
    }

    private fun sendFile(uri: Uri) {
        val target = endpointId ?: run {
            setStatus("Pas de connexion.")
            return
        }
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: run {
            setStatus("Impossible d'ouvrir le fichier.")
            return
        }
        val name = queryName(uri)
        val filePayload = Payload.fromFile(pfd)
        // On annonce d'abord le nom associé à l'id du payload fichier
        val header = "${filePayload.id}|$name".toByteArray(Charsets.UTF_8)
        client.sendPayload(target, Payload.fromBytes(header))
        client.sendPayload(target, filePayload)
        setStatus("Envoi de $name…")
    }

    // ---------- Réception d'un fichier ----------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = String(payload.asBytes() ?: return, Charsets.UTF_8)
                    val sep = text.indexOf('|')
                    if (sep > 0) {
                        val payloadId = text.substring(0, sep).toLongOrNull() ?: return
                        incomingNames[payloadId] = text.substring(sep + 1)
                        tryFinish(payloadId)
                    }
                }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (update.totalBytes > 0) {
                        binding.progressBar.progress =
                            (update.bytesTransferred * 100 / update.totalBytes).toInt()
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    binding.progressBar.progress = 100
                    completedIds += update.payloadId
                    tryFinish(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE ->
                    setStatus("Échec du transfert.")
                else -> {}
            }
        }
    }

    private fun tryFinish(payloadId: Long) {
        val payload = incomingFiles[payloadId] ?: return
        val name = incomingNames[payloadId] ?: return
        if (payloadId !in completedIds) return

        incomingFiles.remove(payloadId)
        incomingNames.remove(payloadId)
        completedIds.remove(payloadId)

        val dir = File(filesDir, "received").apply { mkdirs() }
        val dest = uniqueFile(dir, File(name).name) // File(name).name évite les « ../ »
        try {
            val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return
            FileInputStream(pfd.fileDescriptor).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            pfd.close()
        } catch (e: Exception) {
            setStatus("Erreur d'enregistrement : ${e.message}")
            return
        }

        receivedFiles.add(0, dest)
        listAdapter.insert(dest.name, 0)
        setStatus("Reçu : ${dest.name}")

        if (dest.name.endsWith(".apk", ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("APK reçu")
                .setMessage("Installer ${dest.name} maintenant ?")
                .setPositiveButton("Installer") { _, _ -> installApk(dest) }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun loadReceivedFiles() {
        val dir = File(filesDir, "received")
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            receivedFiles.add(it)
            listAdapter.add(it.name)
        }
    }

    // ---------- Ouverture / installation ----------

    private fun openReceived(file: File) {
        if (file.name.endsWith(".apk", ignoreCase = true)) {
            installApk(file)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aucune application pour ouvrir ce fichier.", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Autorisation « Installer des applications inconnues » manquante
            pendingInstall = file
            AlertDialog.Builder(this)
                .setTitle("Autorisation requise")
                .setMessage(
                    "Pour installer un APK, autorisez ApkShare à installer des applications " +
                        "inconnues dans les paramètres qui vont s'ouvrir."
                )
                .setPositiveButton("Ouvrir les paramètres") { _, _ -> openUnknownSourcesSettings() }
                .setNegativeButton("Annuler") { _, _ -> pendingInstall = null }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            setStatus("Impossible de lancer l'installation : ${e.message}")
        }
    }

    // Écran dédié si disponible, sinon repli (certains Android TV n'ont pas cet écran)
    private fun openUnknownSourcesSettings() {
        openSettings(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }
}
