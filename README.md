# ApkShare

Application Android (Kotlin) pour transférer des fichiers, notamment des APK, d'un smartphone à l'autre via Google Nearby Connections (Bluetooth / Wi-Fi Direct, sans internet).

## Utilisation
1. Sur les deux téléphones : ouvrir ApkShare.
2. Téléphone récepteur : « Recevoir ». Téléphone émetteur : « Envoyer ».
3. Comparer le code affiché et accepter sur les deux appareils.
4. Émetteur : « Choisir un fichier à envoyer ».
5. À la réception d'un APK, l'appli propose de l'installer. Si l'autorisation « Installer des applications inconnues » manque, les paramètres Android correspondants s'ouvrent automatiquement ; au retour, l'installation reprend.

## Envoyer plus tard
Le bouton « Envoyer plus tard » permet de mettre des fichiers de côté (sélection multiple). Au prochain envoi (« Envoyer » puis connexion acceptée), ils partent automatiquement, une seule fois : chaque fichier est retiré de la file dès qu'il est bien reçu. Un fichier dont l'envoi échoue reste en attente. On peut retirer des fichiers de la file à tout moment.

## Gestionnaire de fichiers
Le bouton « Gestionnaire de fichiers » ouvre la liste des fichiers reçus (taille, date). Toucher un fichier propose : ouvrir / installer, partager avec une autre appli, renommer, informations, supprimer. « Tout supprimer » vide le dossier des fichiers reçus.

## Compilation
Ouvrir le dossier dans Android Studio (Ladybug ou plus récent), laisser Gradle se synchroniser, puis Run.
Prérequis : Google Play Services sur les deux téléphones, Android 8.0+ (minSdk 26).
