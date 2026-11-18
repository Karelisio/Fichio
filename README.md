# Fichio

Gestionnaire de fichiers Android **Material You** (Material 3 Expressive), écrit en Kotlin + Jetpack Compose.
Pensé pour remplacer « Fichiers » d'Honor : rapide, beau, complet, avec l'accès à `Android/data` et
`Android/obb` via **Shevery** (fork de Shizuku).

**100 % locale** : sans compte, sans serveur distant, sans analytics. La seule requête réseau sortante
est la vérification de mise à jour sur l'API publique GitHub (désactivable).

Package : `com.karelisio.fichio` · minSdk 30 (Android 11) · targetSdk 36 · compileSdk 37.

Langues : français (par défaut) et anglais, au choix par appli sur Android 13+ (Réglages système › Applis › Fichio › Langue).

---

## Fonctionnalités

### Accueil
- **Jauge vivante** : anneau d'espace utilisé/libre, segments par type de fichier rangés par taille, qui
  glissent et se redimensionnent au ressort quand l'espace change ; chaque segment porte une « perle » à la
  forme de sa catégorie (MaterialShapes) qui se transforme (morph). Grands chiffres expressifs au centre.
  Mise à jour en direct pendant les opérations.
- Accès rapide : stockage interne, clé USB OTG / carte SD (si montée), `Android/data`, `Android/obb`,
  corbeille, applications (extraction d'APK), transfert Wi-Fi.
- Catégories (images, vidéos, audio, documents, APK, archives, téléchargements) avec nombre et taille.
- Fichiers récents en carrousel, dossiers favoris (réordonnables), dossiers épinglés.

### Navigation
- Fil d'Ariane cliquable et défilable ; retour prédictif **dossier par dossier**.
- Vues liste, grille et grandes miniatures, **réglables par dossier** ; tri par nom (ordre naturel), date,
  taille ou type, dossiers en premier, fichiers cachés en option.
- **Onglets** (jusqu'à 5, restaurés au lancement) et **double volet** en paysage avec glisser-déposer
  d'un volet à l'autre (déplacement sur le même volume, copie sinon).
- Barre de défilement rapide avec lettre pour les gros dossiers ; listes paginées par le système Lazy
  (clés stables) : fluide même sur 10 000 fichiers.
- Miniatures Coil 3 : images, images de vidéos, pochettes audio, icônes d'APK ; icônes par type aux
  couleurs dynamiques (teintes harmonisées avec le fond d'écran).
- Raccourcis d'appli (stockage, téléchargements, recherche, corbeille) et raccourcis de dossier sur
  l'écran d'accueil.

### Android/data et obb via Shevery
UserService Shizuku (compatible Shevery) exécuté dans le processus shell, exposé comme un backend de
fichiers : lister, lire, copier, modifier. Si Shevery n'est pas actif, Fichio l'explique clairement avec
« Réessayer » / « Autoriser » ; le reste de l'appli fonctionne normalement.

### Opérations
- Copier, couper, coller, déplacer, renommer, supprimer, créer fichier/dossier, propriétés
  (taille récursive, dates, chemin, MD5 / SHA-256 à la demande).
- Multi-sélection : appui long puis glissement pour sélectionner une plage, tout sélectionner, inverser.
- Renommage en lot (préfixe, suffixe, numérotation, rechercher-remplacer, casse) avec aperçu.
- Corbeille : dossier caché `.Fichio/trash`, métadonnées Room, restauration, vidage automatique après
  30 jours (WorkManager, délai réglable), vidage avec effet tourbillon.
- Opérations longues en service au premier plan : file d'attente, pause, annulation, conflits
  (remplacer, ignorer, garder les deux, appliquer à tous). Notification `ProgressStyle` (Android 16,
  « live update » promue pour que Bulle l'affiche sur l'île ; repli sur une barre classique).
- Annuler la dernière opération (déplacement, renommage, corbeille) depuis la snackbar.

### Recherche
Recherche instantanée dans le dossier courant ou partout, filtres (type, taille, date, extension).
Index local Room FTS4 mis à jour en arrière-plan (WorkManager) et à chaque opération faite dans l'appli.

### Aperçus et ouverture
Images (zoom, glisser pour passer à la suivante), vidéo et audio (Media3, mini-lecteur, lecture en
arrière-plan), PDF (PdfRenderer), texte et code (coloration syntaxique, recherche), petit éditeur.
« Ouvrir avec », images dans **Polaro** (`com.karelisio.polaro`) si installée. Fichio gère
`ACTION_VIEW`, `ACTION_GET_CONTENT`, `ACTION_OPEN_DOCUMENT` et `ACTION_PICK` (sélecteur pour les autres
applis).

### Archives et APK
- Archives ouvertes comme des dossiers : zip, 7z, rar, tar, gz/bz2/xz ; extraction totale ou partielle,
  mots de passe. Création de zip (AES optionnel) ou 7z, niveau de compression réglable.
- APK : icône, nom, package, version, signature, autorisations, compatibilité ; installation via
  PackageInstaller (y compris `.apks`/`.xapk`) ; extraction des applis installées (splits regroupés en `.apks`).

### Analyse du stockage
Carte en soleil animée et cliquable, gros fichiers, doublons (taille puis hachage), fichiers anciens jamais
ouverts, dossiers vides, suppression groupée vers la corbeille.

### Transfert local
Serveur HTTP (Ktor CIO) sur le Wi-Fi local, activé à la demande : QR code + code d'accès à usage unique,
page web Material You (parcourir, télécharger, envoyer par glisser-déposer, créer un dossier). Réseau local
uniquement, jeton obligatoire, arrêt après 10 min d'inactivité ou à la perte du Wi-Fi, pas d'accès à
`Android/data`, journal des transferts.

### Widget
Widget Glance redimensionnable aux couleurs dynamiques : espace libre + 2 à 6 dossiers favoris.

### Animations signature
1. **Tiroir** : la ligne du dossier se transforme en carte d'en-tête (shared bounds) et son contenu en sort
   en cascade ; le geste retour referme le tiroir en le pilotant (predictive back + `SeekableTransitionState`).
2. **Fichier volant** : au copier/déplacer, la miniature s'envole en arc jusqu'au dossier de destination,
   qui rebondit au ressort en la recevant.
3. **Jauge vivante** (voir Accueil) et corbeille qui se vide en tourbillon.

Toutes les animations respectent le réglage système « Supprimer les animations » et l'option
« Réduire les animations » de Fichio.

---

## Architecture

Un seul module `:app`, découpé par fonctionnalité :

```
com.karelisio.fichio
├── FichioApp.kt          AppContainer (injection manuelle, tout en lazy)
├── MainActivity.kt       splash animé, edge-to-edge, intents (raccourcis, widget, VIEW), sélecteur
├── core/
│   ├── model/            FileEntry, FileType, FileCategory, FileTypes (MIME / extensions)
│   ├── fs/               FileBackend (LocalBackend java.io/nio, ShizukuBackend), FileSystems (routage
│   │                     Android/data → Shevery, flux de changements), StoragePaths, VolumeRepository
│   └── util/             Format (tailles, dates FR), tri naturel
├── data/                 Room (favoris, épinglés, onglets, corbeille, index FTS, réglages par dossier,
│                         journal des transferts, récents) + DataStore (préférences)
├── ops/                  moteur d'opérations pur JVM, file d'attente, service au premier plan,
│                         notifications ProgressStyle, annulation, renommage en lot, dialogues
├── trash/                corbeille + purge WorkManager
├── search/               indexeur FTS (noms + contenu txt/md/code/csv/json/xml/html et PDF via
│                         PdfRenderer sur Android 12+ avec extension S 13 / Android 15), dossiers
│                         intelligents, écran de recherche
├── tags/                 7 étiquettes de couleur nommables (Room tags/file_tags)
├── preview/              images, vidéo/audio (Media3 + MediaSession), PDF, texte/code, éditeur
├── archive/  apk/        lecture/écriture d'archives, inspection/installation/extraction d'APK
├── shizuku/              AIDL + UserService + ShizukuManager
├── analysis/  transfer/  analyse du stockage, serveur Ktor + page web (assets/web)
├── widget/  shortcuts/   Glance, raccourcis
├── onboarding/ settings/ update/
└── ui/                   thème (MaterialExpressiveTheme, couleurs par type, MaterialShapes),
                          composants (miniatures Coil, overlay « fichier volant », micro-interactions),
                          navigation type-safe + éléments partagés, accueil, navigateur, catégories
```

Points clés :
- **FileBackend** abstrait l'accès aux fichiers ; le moteur d'opérations ne parle qu'à cette interface,
  ce qui le rend testable sur la JVM et transparent pour `Android/data` (Shevery).
- Toute opération faite dans l'appli émet un `FsChange` : les volets ouverts, l'index de recherche, le
  widget et l'accueil se mettent à jour sans rescanner.
- Les listings sont mis en cache par dossier dans le volet (`PaneState`) : retour instantané, positions de
  défilement conservées, et le tiroir anime les deux côtés de la transition.

### Stack
Kotlin 2.4, AGP 9.5, Compose BOM 2026.09 + Material 3 1.5 (Expressive), Navigation Compose 2.10
(routes sérialisables), Room 2.8 (KSP), DataStore, WorkManager, Glance 1.2, Coil 3, Media3 1.11,
Ktor 3 (CIO), zip4j, Apache Commons Compress + XZ, junrar, Shizuku API 13, ZXing.

---

## Build local

Prérequis : JDK 17+ et le SDK Android (platform 37, build-tools 36).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk (id .debug)
./gradlew testDebugUnitTest      # tests unitaires + tests de rendu Robolectric
./gradlew assembleRelease        # signé si un keystore est fourni (voir plus bas), sinon clé debug
```

Le build release lit le keystore dans les variables d'environnement `FICHIO_KEYSTORE_PATH`,
`FICHIO_KEYSTORE_PASSWORD`, `FICHIO_KEY_ALIAS`, `FICHIO_KEY_PASSWORD`, ou dans un fichier local
`keystore.properties` (ignoré par git : `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

---

## Étapes manuelles sur le téléphone

L'onboarding guide chaque étape (une page par autorisation) ; l'écran **Réglages → Autorisations**
permet de vérifier et corriger plus tard.

1. **Accès à tous les fichiers** (obligatoire) : l'écran système « Accès à tous les fichiers » s'ouvre,
   active Fichio puis reviens.
2. **Notifications** : pour la progression des opérations (et l'île de Bulle).
3. **Shevery** (facultatif, pour `Android/data` et `Android/obb`) : installe Shevery, démarre son service
   (débogage sans fil ou ADB), puis autorise Fichio quand l'invite apparaît.
4. **Installation d'applis** : pour installer des APK et les mises à jour de Fichio.
5. **Batterie** : « Ne pas optimiser » pour que les longues opérations et le vidage de la corbeille
   aboutissent.
6. **MagicOS (Honor) — Lancement d'applis** : Réglages → Batterie → Lancement d'applis → Fichio →
   désactive « Gérer automatiquement » et active Lancement automatique, Lancement secondaire et
   Exécution en arrière-plan.

---

## Publier une version

La version a **une seule source** : le fichier `VERSION` à la racine (`X.Y.Z`). `versionName` en est lu,
`versionCode` = `github.run_number`.

### Une seule fois : keystore et secrets
```bash
keytool -genkeypair -v -keystore fichio.keystore -alias fichio -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 fichio.keystore   # → valeur du secret FICHIO_KEYSTORE_BASE64
```
Dans GitHub : **Settings → Secrets and variables → Actions** :

| Secret | Contenu |
|---|---|
| `FICHIO_KEYSTORE_BASE64` | le keystore encodé en base64 |
| `FICHIO_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `FICHIO_KEY_ALIAS` | alias de la clé (ex. `fichio`) |
| `FICHIO_KEY_PASSWORD` | mot de passe de la clé |

Garde **toujours le même keystore** (sauvegardé hors du dépôt) : sinon Android refusera les mises à jour.
Rien de sensible n'est commité (`*.keystore`, `*.jks`, `keystore.properties` sont ignorés).

### Publier
1. Modifie `VERSION` (ex. `0.3.0`) et ajoute la section `## [0.3.0]` dans `CHANGELOG.md`.
2. Pousse sur `main`.

À chaque push sur `main`, [le workflow](.github/workflows/release.yml) lit `VERSION` :
- tag `vX.Y.Z` absent → tests, APK release signé, création du tag et de la **GitHub Release** (APK +
  section du changelog) ;
- tag déjà présent → build de vérification uniquement.

L'appli vérifie `releases/latest` au lancement (au plus toutes les 6 h), affiche le changelog dans une
bottom sheet, télécharge l'APK avec une progression animée et l'installe via PackageInstaller. Le dépôt
est public, donc l'API fonctionne sans jeton ; s'il devenait privé, il faudrait publier les APK dans un
dépôt public dédié et changer `UPDATE_REPO` dans `app/build.gradle.kts`.

---

## Tests

`app/src/test/` (JVM + Robolectric, `./gradlew testDebugUnitTest`) : opérations et conflits, renommage
en lot, corbeille, détection des doublons, jeton et garde de chemins du serveur local, archives, versions
de mise à jour, requêtes de recherche, tri naturel, rendu de l'accueil, de la jauge et du navigateur ;
politique de migration Room (aucune migration destructive hors v1, une `Migration` par version > 2 dans
`FichioDatabase.MIGRATIONS`), contraintes batterie des tâches de fond, budget de 150 ms pour lister et
trier 10 000 entrées.

`app/src/androidTest/` (appareil ou émulateur, `./gradlew connectedDebugAndroidTest`) : démarrage à
froid (onboarding / accueil), accueil → navigateur → dossier → retour, aperçus texte et PDF (régression
de la double fermeture), migrations Room avec `MigrationTestHelper` sur les schémas de `app/schemas`, base
v1 étrangère. L'accès à tous les fichiers est accordé par `appops`. En CI, ce job émulateur (API 34) tourne
sur chaque PR et sur `main`, et la publication en dépend.

Profil de référence : `app/src/main/baseline-prof.txt` (écrit à la main) + profil généré par le module
`:baselineprofile` (`./gradlew :app:generateReleaseBaselineProfile`, ou le workflow manuel
« Baseline profile »), installé par `profileinstaller`.
