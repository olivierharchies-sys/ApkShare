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
Le bouton « Gestionnaire de fichiers » ouvre un explorateur complet :
- **Navigation** dans tout le stockage (interne, carte SD, USB) après autorisation « Accès à tous les fichiers » ; sans autorisation, seuls les fichiers reçus par ApkShare sont visibles (bouton « Reçus »).
- **Toucher** un dossier l'ouvre, toucher un fichier l'ouvre (un APK lance l'installation). **Appui long** ou bouton « Sélectionner » : sélection multiple.
- **Actions** sur la sélection : copier, couper, supprimer, partager, renommer, informations, « Envoyer plus tard ». Puis « Coller » dans le dossier voulu.
- **Nouveau dossier**, **tri** (nom, date, taille), bouton « ⬆ Parent » et touche Retour pour remonter.
- Sur Android 10 et moins, l'accès aux fichiers dépend des permissions de stockage classiques.

## Compilation
Ouvrir le dossier dans Android Studio (Ladybug ou plus récent), laisser Gradle se synchroniser, puis Run.
Prérequis : Google Play Services sur les deux téléphones, Android 8.0+ (minSdk 26).
