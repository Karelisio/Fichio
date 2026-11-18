# Changelog

Toutes les évolutions notables de Fichio sont listées ici.
Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

## [0.2.2] - 2026-11-18

### Corrigé
- Plantage en quittant l'aperçu d'un PDF (le document était fermé deux fois, et même fermé juste après son ouverture, ce qui laissait les pages blanches).

### Technique
- Le fichier de mapping R8 est joint à chaque release pour décoder les rapports de plantage.

## [0.2.1] - 2026-11-18

### Corrigé
- Fermeture immédiate au lancement après une mise à jour depuis la 0.1.x : l'ancienne base de données (schéma différent, même version) faisait échouer Room. Elle est désormais remplacée proprement.
- Une opération annulée alors qu'elle attendait dans la file pouvait rester bloquée indéfiniment.
- Les erreurs des tâches de fond ne peuvent plus fermer l'appli.

### Ajouté
- Rapport de plantage local : après une fermeture inattendue, Fichio affiche le rapport au lancement suivant (copier / partager). Rien n'est envoyé automatiquement.

## [0.2.0] - 2026-11-18

Refonte complète de l'appli.

### Nouveautés
- Material 3 Expressive partout : couleurs dynamiques (repli bleu ardoise), formes MaterialShapes, typographie expressive, splash animé, nouvelle icône adaptative avec version monochrome.
- Accueil : jauge d'espace vivante (segments par type qui se réorganisent au ressort et changent de forme), accès rapide (stockage, USB, Android/data, Android/obb, corbeille, applis, transfert), catégories, récents en carrousel, favoris réordonnables, épinglés.
- Navigateur : onglets (jusqu'à 5), double volet en paysage avec glisser-déposer, vues liste / grille / grandes miniatures par dossier, tri naturel, fichiers cachés, défilement rapide, sélection par glissement, animation « tiroir » pilotée par le retour prédictif, animation « fichier volant » au copier/déplacer.
- Opérations en arrière-plan avec file d'attente, pause, annulation, conflits et notification ProgressStyle (Android 16) ; annulation de la dernière opération ; renommage en lot avec aperçu ; corbeille avec vidage automatique.
- Recherche instantanée sur index local (Room FTS) avec filtres.
- Aperçus : images, vidéo, audio avec mini-lecteur, PDF, texte et code avec coloration ; éditeur de texte ; Fichio peut servir de sélecteur de fichiers.
- Archives (zip, 7z, rar, tar, gz) et APK (infos, installation, extraction des applis installées).
- Android/data et Android/obb via Shevery.
- Analyse du stockage (carte en soleil, gros fichiers, doublons, anciens fichiers, dossiers vides).
- Transfert local par Wi-Fi avec QR code et code à usage unique.
- Widget Glance, raccourcis d'appli et de dossiers.
- Onboarding animé avec une page par autorisation, écran Autorisations dans les réglages.
- Mise à jour in-app depuis les GitHub Releases.

### Technique
- Kotlin 2.4, AGP 9.5, compileSdk 37, minSdk 30.

## [0.1.1] - 2026-11-17

### Corrigé
- Plantage immédiat au lancement causé par une initialisation circulaire de la corbeille.

## [0.1.0] - 2026-11-17

### Ajouté
- Première version.
