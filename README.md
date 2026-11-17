# Fichio

**Gestionnaire de fichiers Material You, 100 % local, qui remplace « Fichiers » d'Honor.**

Rapide, beau, complet. Pas de compte, pas de serveur distant, pas d'analytics : tout reste
sur ton appareil. Accès à `Android/data` et `Android/obb` via **Shevery** (fork de Shizuku).

> **Statut :** v0.1.0 — développement actif. Le cœur de l'app est en place (navigation,
> opérations, corbeille, recherche, aperçus, archives, APK, Shevery, analyse du stockage,
> transfert local, widget, onboarding, mise à jour in-app). Voir le
> [changelog](CHANGELOG.md) et la [feuille de route](#feuille-de-route).

---

## Fonctionnalités

### Accueil
- Jauge d'espace animée (utilisé / libre, répartition par type) et compteurs animés.
- Catégories (images, vidéos, audio, documents, APK, archives, téléchargements) avec
  nombre d'éléments et taille.
- Fichiers récents, dossiers favoris, accès rapide au stockage interne, à la clé USB OTG,
  à `Android/data` / `Android/obb` et à la corbeille.

### Navigation
- Fil d'Ariane cliquable et défilable, retour prédictif dossier par dossier.
- Vues **liste**, **grille** et **grandes miniatures**, réglables par dossier.
- Tri par nom, date, taille ou type ; dossiers en premier ; affichage des fichiers cachés.
- Onglets (jusqu'à 5), dossiers épinglés, favoris réordonnables, raccourcis d'appli
  (`ShortcutManager`) et raccourcis de dossier sur l'écran d'accueil.
- Miniatures (Coil, vidéos et pochettes audio), icônes par type aux couleurs dynamiques.

### `Android/data` et `Android/obb` via Shevery
- API Shizuku (compatible Shevery) avec un `UserService` pour lister, lire, copier et
  modifier `Android/data` et `Android/obb`.
- Si Shevery n'est pas actif, Fichio l'indique clairement avec un bouton « Réessayer ».
  **Le reste de l'app fonctionne normalement.**

### Opérations
- Copier, couper, coller, déplacer, renommer, supprimer, créer un fichier ou un dossier,
  propriétés (taille, dates, chemin, hachage MD5/SHA-256 à la demande).
- Multi-sélection (appui long puis glissement pour une plage, tout sélectionner, inverser).
- Renommage en lot (préfixe, suffixe, numérotation, rechercher-remplacer, casse) avec aperçu.
- Corbeille : suppression vers un dossier caché de Fichio, métadonnées dans Room (chemin
  d'origine, date), restauration, vidage automatique (30 jours, réglable, WorkManager).
- Opérations longues en **service au premier plan** : file d'attente, pause, annulation,
  gestion des conflits (remplacer, ignorer, garder les deux, appliquer à tous),
  notification de progression.
- Annuler la dernière opération via une snackbar.

### Recherche
- Recherche instantanée dans le dossier courant ou partout, filtres type / taille / date /
  extension.
- Index local **Room FTS** mis à jour en arrière-plan (WorkManager) et après chaque
  opération, pour des résultats immédiats.

### Aperçus et ouverture
- Images (zoom, glisser pour la suivante), vidéos et audio (Media3 ExoPlayer, mini-lecteur),
  PDF (`PdfRenderer`), texte et code (coloration syntaxique, recherche dans le fichier).
- Petit éditeur de texte (enregistrer, annuler, police à chasse fixe, retour à la ligne).
- « Ouvrir avec » (appli par défaut ou choix). Images dans **Polaro** (`com.karelisio.polaro`)
  si installé (option).
- Fichio gère `ACTION_VIEW`, `ACTION_GET_CONTENT` et `ACTION_OPEN_DOCUMENT` : il peut servir
  de **sélecteur de fichiers** pour les autres applis.

### Archives
- Ouvrir une archive comme un dossier : **zip, 7z, rar, tar, gz** (zip4j, Apache Commons
  Compress + xz, junrar). Extraire tout ou une sélection, archives protégées par mot de passe.
- Créer un zip (mot de passe AES en option) ou un 7z, niveau de compression réglable.

### APK
- Infos d'un APK : icône, nom, package, version, signature, autorisations, taille, compatibilité.
- Installation via `PackageInstaller`.
- Extraction de l'APK d'une appli installée (split APK rassemblés en `.apks`).

### Analyse du stockage
- Carte visuelle des dossiers (graphique en soleil cliquable).
- Gros fichiers (seuil réglable), doublons (même taille puis même hachage), fichiers anciens
  jamais ouverts, dossiers vides, avec suppression groupée vers la corbeille.

### Transfert local
- Serveur HTTP sur le Wi-Fi local (Ktor CIO), activé à la demande : QR code avec l'adresse et
  un code d'accès à usage unique.
- Page web Material You responsive côté PC : parcourir, télécharger, envoyer par glisser-déposer,
  créer un dossier.
- Sécurité : réseau local uniquement, jeton obligatoire, arrêt automatique après 10 min
  d'inactivité ou à la sortie du Wi-Fi, **pas d'accès à `Android/data`** par ce biais, journal
  des transferts.

### Widget et raccourcis
- Widget **Glance** : jauge d'espace libre et 2 à 6 dossiers favoris cliquables, redimensionnable.
- Raccourcis d'appli vers le stockage interne, les téléchargements et la corbeille.

### Material You (strict)
- Material 3 Expressive : couleurs dynamiques (`dynamicColorScheme`) avec fallback bleu ardoise,
  formes variées (MaterialShapes), typographie expressive, mode sombre automatique.
- Widget Glance aux couleurs dynamiques (`GlanceTheme`), coins arrondis système.
- Icône adaptative avec version monochrome (dossier aux coins arrondis, onglet en vague, feuille).

### Onboarding et autorisations
- Onboarding animé puis demande des autorisations une par une : accès à tous les fichiers
  (`MANAGE_EXTERNAL_STORAGE`), notifications, Shevery/Shizuku (facultatif), installation d'APK,
  exclusion de l'optimisation batterie.
- Écran « Autorisations » dans les réglages avec l'état de chacune et un bouton de correction.

### Mise à jour in-app
- `VERSION` à la racine = source unique de la version.
- Vérifie `releases/latest` au lancement (au plus toutes les 6 h), bottom sheet avec changelog,
  téléchargement avec progression animée, installation via `PackageInstaller`.

---

## Architecture

Kotlin + Jetpack Compose, architecture en couches avec un service-locator manuel (pas de DI
lourde — voir `FichioApp` / `AppContainer`).

```
app/src/main/java/com/karelisio/fichio/
├── data/
│   ├── db/            # Room : entités, DAO, base (FichioDatabase)
│   ├── prefs/         # DataStore (SettingsRepository)
│   └── trash/         # Corbeille (TrashManager)
├── domain/
│   ├── file/          # FileSystem, FileItem, FileCategory, FileSorter
│   ├── operations/    # FileOperations, FileTransferEngine, Clipboard, Hashing
│   ├── scan/          # StorageScanner (catégories, récents, jauge)
│   └── search/        # SearchIndexer (index FTS)
├── service/           # FileOperationService (foreground, file d'attente, conflits)
├── shizuku/           # FichioUserService + IUserService.aidl (Shevery)
├── transfer/          # TransferServerService (Ktor CIO + QR code)
├── ui/
│   ├── home/          # Accueil (jauge, catégories, récents, favoris)
│   ├── browser/       # Navigateur de fichiers (fil d'Ariane, vues, tri)
│   ├── search/        # Recherche
│   ├── trash/         # Corbeille
│   ├── components/    # FileThumbnail, SpaceGauge
│   ├── navigation/    # FichioNavHost
│   └── theme/         # Material 3 (Color, Shapes, Type, Theme)
├── widget/            # Widget Glance
└── worker/            # WorkManager (purge corbeille, index, reschedule au boot)
```

### Persistance
- **Room** : favoris, dossiers épinglés, onglets ouverts, corbeille (métadonnées), index de
  recherche (FTS), réglages par dossier, historique des transferts.
- **DataStore** : préférences générales.
- **Fichiers** : via `MANAGE_EXTERNAL_STORAGE` (stockage partagé) et **Shizuku `UserService`**
  (pour `Android/data` et `Android/obb`).

### Stack
| Domaine | Techno |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Base de données | Room (+ FTS) |
| Préférences | DataStore |
| Tâches de fond | WorkManager |
| Miniatures | Coil (+ coil-video) |
| Lecture média | Media3 ExoPlayer |
| Archives | zip4j, Apache Commons Compress + xz, junrar |
| Serveur local | Ktor (CIO) + ZXing (QR) |
| Widget | Glance |
| Shevery | Shizuku API / provider |
| Mise à jour | GitHub Releases API + PackageInstaller |

---

## Prérequis et build local

- **JDK 17**, **Android SDK 36** (Build Tools 35.0.0), **Gradle 8.14.3** (wrapper fourni).
- L'app cible `minSdk 26`, `compileSdk`/`targetSdk 36`.

```bash
# Build de debug (signé avec le keystore debug par défaut)
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk

# Build release signé (voir ci-dessous pour le keystore)
./gradlew assembleRelease

# Tests unitaires
./gradlew testDebugUnitTest
```

> Le build release lit le keystore depuis les variables d'environnement `FICHIO_KEYSTORE_*`
> ou depuis un fichier `keystore.properties` local, avec repli sur le keystore debug pour
> pouvoir builder sans secrets.

---

## Étapes manuelles sur l'appareil

### Accès à tous les fichiers
Au premier lancement, Fichio demande l'accès à tous les fichiers
(`MANAGE_EXTERNAL_STORAGE`). C'est une autorisation spéciale : elle ouvre l'écran
**« Accès spécial aux applications »** des réglages. Active le commutateur **Fichio** puis
reviens dans l'app (la vérification se fait au retour). Sans elle, l'app ne peut pas gérer
les fichiers du stockage partagé.

### Shevery (accès à `Android/data` et `Android/obb`)
1. Installe **Shevery** (fork de Shizuku).
2. Démarre Shevery et active son service (ADB, sans-fil ou root, selon ton appareil).
3. Dans Fichio, autorise Shevery quand l'invite apparaît.
4. Si Shevery n'est pas actif, Fichio affiche un bandeau avec « Réessayer » — tout le reste
   de l'app continue de fonctionner.

### Réglages MagicOS (Honor)
Pour un fonctionnement optimal sur MagicOS, autorise manuellement :
- **Lancement d'applis en manuel** : Réglages → Applis → Fichio → Lancement → gestion manuelle
  (les trois options activées), pour que les tâches de fond (purge corbeille, index) survivent.
- **Exclusion de l'optimisation batterie** : Réglages → Batterie → Lancement des applis →
  Fichio → « Ne pas optimiser », pour que les longues opérations se terminent.
- **Notifications** : active les notifications pour suivre la progression des opérations.

---

## Publier une version

La version est pilotée par **un seul fichier** : `VERSION` à la racine (contenu `X.Y.Z`).
Le `versionName` est lu depuis ce fichier ; le `versionCode` est `github.run_number` sur CI.

### 1. Keystore (une seule fois — garde-le précieusement)
```bash
keytool -genkeypair -v -keystore fichio.keystore -alias fichio \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fichio.keystore   # → à coller dans FICHIO_KEYSTORE_BASE64
```

### 2. Secrets GitHub
Dans le dépôt : **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Contenu |
|---|---|
| `FICHIO_KEYSTORE_BASE64` | Le fichier `fichio.keystore` encodé en base64 |
| `FICHIO_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `FICHIO_KEY_ALIAS` | Alias de la clé (ex. `fichio`) |
| `FICHIO_KEY_PASSWORD` | Mot de passe de la clé |

> ⚠️ Configure ces secrets **avant** le premier push, sinon l'étape `assembleRelease`
> échouera (le build debug et les tests passeront quand même).

### 3. Publier
1. Mets à jour `VERSION` (ex. `0.2.0`).
2. Ajoute une section `## [0.2.0] - unreleased` dans `CHANGELOG.md`.
3. `git commit` puis `git push origin main`.

À chaque push sur `main`, [GitHub Actions](.github/workflows/release.yml) :
- lit `VERSION` ;
- si le tag `vX.Y.Z` **n'existe pas** : build l'APK **release signé**, crée le tag et publie
  une **GitHub Release** avec l'APK + la section correspondante du changelog ;
- si le tag **existe déjà** : build de vérification uniquement (debug + tests).

Le dépôt est privé ? Publie les APK dans un **dépôt public dédié** pour que la vérification de
mise à jour in-app (`releases/latest`) fonctionne.

---

## Tests

Tests unitaires dans `app/src/test/` :
- `FileOperationsTest` — opérations, conflits, renommage en lot, corbeille, annulation.
- `FileTransferEngineTest` — file d'attente, pause/annulation, stratégies de conflit, jeton du
  serveur local.

```bash
./gradlew testDebugUnitTest
```

---

## Feuille de route

Ordre d'implémentation (l'essentiel de la v0.1.0 est en place) :

1. Accès aux fichiers, navigation, vues, tri, miniatures ✅
2. Opérations de base, multi-sélection, conflits, service au premier plan, notification ✅
3. Corbeille et annulation ✅
4. Recherche et index ✅
5. Aperçus, éditeur, ouverture, sélecteur de fichiers ✅
6. Archives et APK ✅
7. `Android/data` via Shevery ✅
8. Analyse du stockage ✅
9. Transfert local ✅
10. Widget, raccourcis, intégrations Bulle et Polaro ⏳ (partiel : widget + raccourcis)
11. Onboarding, autorisations ⏳ (partiel)
12. Animations signature et polish ⏳ (continu)
13. Mise à jour in-app, CI, README ✅

---

## Licence

Projet privé — tous droits réservés. Aucune donnée n'est collectée, envoyée ou analysée :
Fichio est **100 % local**.
