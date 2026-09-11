# Obtenir l'APK sans ordinateur

Android Studio est un logiciel de bureau : il ne s'installe pas sur un téléphone. Le
site le détecte et refuse le téléchargement, c'est normal.

Voici trois façons d'obtenir l'APK, de la plus simple à la plus pénible.

---

## Option 1 — GitHub compile à ta place (recommandé)

Tout se fait depuis le navigateur du téléphone. Gratuit, environ 5 minutes de
configuration puis 4 minutes de compilation.

### 1. Créer le dépôt

- Compte sur github.com si tu n'en as pas.
- Bouton `+` en haut à droite → **New repository**.
- Nom : `RoomScan`. Visibilité **Private** si tu préfères, ça marche aussi.
- **Ne coche rien** (pas de README, pas de .gitignore).
- **Create repository**.

### 2. Envoyer les fichiers

Sur la page du dépôt vide, clique **uploading an existing file**.

Dézippe `RoomScan.zip` sur le téléphone (l'appli Fichiers de Samsung sait le faire :
appui long sur le zip → Extraire). Puis glisse **le contenu** du dossier `RoomScan`,
pas le dossier lui-même.

> Ce qui doit se trouver à la racine du dépôt : `settings.gradle.kts`, `build.gradle.kts`,
> `gradle.properties`, et les dossiers `app/` et `.github/`.
> Si `settings.gradle.kts` se retrouve dans un sous-dossier, la compilation échouera.

L'interface web de GitHub accepte les dossiers entiers par glisser-déposer. Si ton
navigateur mobile ne le permet pas, passe en **mode ordinateur** dans le menu de Chrome —
ça débloque généralement le sélecteur de dossiers.

Écris un message quelconque, puis **Commit changes**.

### 3. Récupérer l'APK

L'envoi déclenche la compilation automatiquement.

- Onglet **Actions** → la ligne du haut. Point orange = en cours, coche verte = terminé.
- Une fois vert, onglet **Releases** (colonne de droite de la page d'accueil du dépôt).
- Télécharge `app-debug.apk`.

### 4. Installer

Android bloquera l'installation la première fois. Il proposera un lien vers le réglage
à activer — c'est **Installer des applications inconnues** pour le navigateur ou
l'explorateur de fichiers utilisé. Autorise, puis relance l'installation.

Au premier lancement, l'app demandera l'accès caméra et proposera d'installer
*Google Play Services for AR* s'il manque. Accepte les deux.

### Si la compilation échoue

Onglet Actions → clique sur la ligne rouge → déplie l'étape en erreur. Les deux causes
probables :

- **Dépendance ARCore introuvable** : la version `1.45.0` n'existe plus ou a changé.
  Ouvre `app/build.gradle.kts` sur GitHub (icône crayon), corrige le numéro, valide.
  La compilation repart seule.
- **Fichier à la mauvaise place** : vérifie que `settings.gradle.kts` est bien à la
  racine et non dans un sous-dossier.

Colle-moi le message d'erreur, je te dirai quoi changer.

---

## Option 2 — Un ordinateur, même emprunté

Si tu as accès à un PC ou un Mac une heure : installe Android Studio, `File → Open`,
sélectionne le dossier `RoomScan`, laisse-le télécharger ce qu'il faut, puis
`Build → Build Bundle(s) / APK(s) → Build APK(s)`.

L'APK se trouve dans `app/build/outputs/apk/debug/`. Transfère-le sur le téléphone.

C'est la voie la plus confortable si tu comptes modifier le code ensuite : tu auras le
débogage en direct et les messages d'erreur lisibles.

---

## Option 3 — Compiler sur le téléphone

**AndroidIDE** (androidide.com) est un environnement Gradle qui tourne réellement sur
Android en arm64. Ton S24 Ultra a la puissance nécessaire.

À savoir avant de te lancer : il faut compter une dizaine de Go d'espace libre, la
première compilation est longue, et l'éditeur reste inconfortable sur un écran de
téléphone. À réserver au cas où les options 1 et 2 sont hors de portée.

---

## Ce que je ne peux pas faire

Je n'ai ni Gradle ni le SDK Android dans mon environnement, et l'accès réseau y est
coupé — je ne peux donc ni les installer, ni récupérer la dépendance ARCore. Je peux
écrire et corriger le code, pas produire le binaire.
