# PDF Tampon

Application Android (Kotlin, Jetpack Compose) qui ajoute une même image (PNG, JPG, JPEG) sur la première page d'un lot de PDF.

- 100 % hors ligne, sans compte, sans serveur, sans SDK de suivi.
- Originaux ouverts en lecture seule, jamais écrasés, jamais supprimés.
- Image ajoutée comme objet graphique superposé : aucune conversion en image, aucune reconstruction du document.
- Bibliothèque PDF : **PdfBox-Android** (`com.tom-roush:pdfbox-android`), licence **Apache 2.0**.

## Statut de vérification

> **Ce projet n'a pas été compilé ni testé.** L'environnement de rédaction ne peut pas joindre `dl.google.com` ni `repo.maven.apache.org` (refus de politique réseau), donc ni le SDK Android ni les dépendances ne sont disponibles. Aucun APK n'a été produit. Le code doit être compilé et testé avant toute utilisation sur des documents réels.

## Compilation via GitHub Actions (recommandé)

Le workflow `.github/workflows/build-apk.yml` compile l'APK de débogage à chaque push, pull request, ou sur demande (onglet **Actions > Build APK > Run workflow**).

1. Dans l'onglet **Actions**, ouvrir l'exécution réussie.
2. Télécharger l'artifact **PDF_Tampon-debug-apk** : il contient `PDF_Tampon-debug.apk` et son empreinte `PDF_Tampon-debug.apk.sha256`.
3. Sur le Samsung, autoriser l'installation depuis une source inconnue pour l'application qui ouvre l'APK, puis installer.

L'artifact est conservé 14 jours.

## Compilation locale

Prérequis : Android Studio récent (Ladybug ou plus récent), JDK 17, SDK Android 34.

1. Ouvrir le dossier `PDFTampon/` dans Android Studio.
2. Laisser la synchronisation Gradle télécharger les dépendances.
3. Si Android Studio signale l'absence du wrapper Gradle, générer-le :
   ```
   gradle wrapper --gradle-version 8.9
   ```
4. Compiler un APK de test : menu **Build > Build APK(s)**, ou :
   ```
   ./gradlew assembleRelease
   ```
   L'APK est produit dans `app/build/outputs/apk/release/`. Il est signé avec la clé de débogage (voir « Signature »).
5. Empreinte SHA-256 de l'APK :
   ```
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```
6. Vérifier les permissions réellement déclarées :
   ```
   aapt dump permissions app/build/outputs/apk/release/app-release.apk
   ```
   La sortie doit être vide (aucune permission).

### Signature

Le build release utilise la clé de débogage pour les tests. Pour une diffusion, créer une clé de publication distincte (`keytool -genkeypair ...`), la conserver hors du dépôt, et la référencer dans un `signingConfigs` local non versionné. Ne jamais committer de clé ni de mot de passe.

## Points à vérifier à la compilation

Ces points sont rédigés à partir de la documentation connue des APIs, mais n'ont pas pu être contrôlés :

- `PDPageContentStream`, `PDExtendedGraphicsState`, `LosslessFactory.createFromImage(doc, bitmap)` : signatures PdfBox-Android 2.0.27.0.
- `PDDocument.signatureDictionaries` et `PDDocument.isEncrypted` : accessibles en Kotlin.
- `change.consume()` dans `Screens.kt` : appel membre sur `PointerInputChange`. Si le compilateur le signale, remplacer par `change.consumePositionChange()`.
- Le rendu de l'aperçu (`PdfRenderer`) doit respecter la rotation `/Rotate` de la page. Sinon, l'aperçu sera tourné et ne correspondra plus aux coordonnées (test 5 ci-dessous).

## Logique de positionnement

- Origine : coin supérieur gauche de la page **telle qu'affichée** (rotation `/Rotate` incluse).
- Unités : millimètres, convertis en points (1 mm = 72/25,4 pt).
- Le mappage vers l'espace PDF est fait dans `PdfStamper.kt` (classe `Geo`) pour les rotations 0, 90, 180 et 270°, à partir de la `CropBox`.
- Les coordonnées sont indépendantes du zoom de l'aperçu.

## Comportement face aux cas particuliers

| Cas | Traitement |
|---|---|
| PDF protégé par mot de passe ou chiffré | Refusé, non modifié, signalé |
| PDF signé numériquement | Refusé par défaut, non modifié, signalé |
| PDF corrompu ou non PDF | Refusé, signalé, le lot continue |
| Fichier > 300 Mo | Refusé (limite `FileIo.MAX_PDF_BYTES`) |
| Image > 30 Mo | Refusée (limite `FileIo.MAX_IMAGE_BYTES`) |
| Doublon de nom | Suffixe ` (2)`, ` (3)`… Aucun écrasement |
| Image hors de la page | Refusée, aucune sortie écrite |

## Tests à exécuter (cahier des charges, section 7)

Aucun de ces tests n'a encore été exécuté. Cocher après essai sur appareil réel (Samsung Galaxy S23/S24 Ultra) :

- [ ] 1. PDF d'une page
- [ ] 2. PDF multi-pages (pages 2 et suivantes inchangées, comparer visuellement)
- [ ] 3. PDF A4 et A5
- [ ] 4. PDF portrait et paysage
- [ ] 5. PDF avec première page tournée (`/Rotate` 90, 180, 270) : l'image doit apparaître où l'aperçu la montre
- [ ] 6. PDF avec tableaux et images
- [ ] 7. PDF avec polices intégrées
- [ ] 8. PNG transparent (transparence visible dans le PDF)
- [ ] 9. Positionnement précis en millimètres
- [ ] 10. Lot de 100 PDF
- [ ] 11. Lot de 500 PDF (vérifier la mémoire et la réactivité)
- [ ] 12. PDF corrompu au milieu d'un lot
- [ ] 13. Annulation pendant un lot
- [ ] 14. Mode avion activé
- [ ] 15. Pages 2 et suivantes visuellement identiques
- [ ] 16. PDF signé numériquement (doit être refusé)
- [ ] 17. PDF protégé par mot de passe (doit être refusé)
- [ ] 18. PDF avec annotations ou formulaires (préservés, à vérifier visuellement)

## Permissions Android

Aucune permission déclarée dans le manifeste. L'accès aux fichiers passe par le Storage Access Framework (sélecteur système), qui accorde un accès limité aux documents choisis.

Interdits et absents : `INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE`, contacts, SMS, téléphone, micro, caméra, localisation, accessibilité, administrateur, root.

Manifeste : `allowBackup="false"`, `usesCleartextTraffic="false"`, aucun composant exporté inutilement (seule l'activité lanceur est exportée).

## Bibliothèques et licences

| Bibliothèque | Version | Licence |
|---|---|---|
| com.tom-roush:pdfbox-android | 2.0.27.0 | Apache 2.0 |
| Kotlin / kotlinx-coroutines | 2.0.20 / 1.8.1 | Apache 2.0 |
| AndroidX Compose BOM | 2024.09.00 | Apache 2.0 |
| androidx.activity, lifecycle, documentfile, exifinterface | voir `app/build.gradle.kts` | Apache 2.0 |

Vérifier les licences transitives avec `./gradlew app:dependencies` avant diffusion.

## Limites connues

- Pas de rastérisation : les PDF très complexes peuvent être réécrits par PdfBox sans préserver l'identité binaire. L'apparence est préservée, la structure peut être normalisée.
- Les signatures numériques sont refusées par défaut pour ne jamais invalider une signature silencieusement.
- Le traitement est séquentiel. 500 fichiers peuvent prendre plusieurs minutes selon leur taille.
- Les droits d'accès aux fichiers sélectionnés sont liés à la session de l'application. Une relance de l'application après un arrêt complet du processus peut nécessiter une nouvelle sélection.
- Le sélecteur de dossier de sortie est persisté ; les fichiers sources ne le sont pas.
