# RoomScan

Scanner de volume pour Android, basé sur la profondeur brute d'ARCore. Accumule un
nuage de points pendant que tu filmes, l'affiche en direct, et l'exporte soit en 3D
(`.ply`), soit aplati en plan d'étage (`.png` + `.svg`).

---

## Avant tout : ce que ton S24 Ultra sait faire, et ne sait pas faire

Le S24 Ultra **n'a pas de LiDAR ni de capteur ToF**. Samsung a abandonné le ToF après le
S20 Ultra. La profondeur ici est *estimée*, pas *mesurée* : ARCore la déduit de la
parallaxe entre images successives, aidé par les capteurs inertiels.

Conséquences concrètes :

| | Réalité |
|---|---|
| Portée utile | 0,5 à 5 m. Au-delà, le bruit dépasse le signal |
| Précision relative | quelques centimètres sur une pièce, se dégrade avec la distance parcourue |
| Surfaces problématiques | murs blancs, vitres, miroirs, sols brillants, plein soleil |
| Dérive | cumulative — un grand espace se referme mal sur lui-même |

C'est bon pour un plan de pièce, un relevé d'encombrement, un repérage. Ce n'est pas de
la métrologie, et ça ne remplace pas un iPhone Pro à LiDAR pour de la capture fine.

---

## Compiler

1. Android Studio (Hedgehog ou plus récent), ouvre le dossier `RoomScan/`.
2. Android Studio propose de générer le wrapper Gradle s'il manque — accepte.
3. Le projet cible `compileSdk 34`, `minSdk 29`, JDK 17.
4. Branche le téléphone en débogage USB, `Run`.

La dépendance `com.google.ar:core:1.45.0` peut nécessiter une montée de version selon
la date — si le build échoue dessus, vérifie la dernière release ARCore et ajuste
`app/build.gradle.kts`.

Google Play Services for AR doit être installé sur le téléphone ; l'app le propose
automatiquement au premier lancement.

---

## Utiliser

**Réglages d'accueil**

- *Taille de voxel* — la résolution. 3 cm est un bon départ. Descendre à 1 cm quadruple
  le nombre de points et la mémoire consommée, pour un gain réel limité vu le bruit.
- *Portée maximale* — les mesures au-delà sont rejetées. 5 m en intérieur, moins si le
  résultat est bruité.

**Prise de vue**

Le geste compte plus que les réglages. La profondeur vient du déplacement, donc :

- avance à vitesse de marche lente, sans à-coups ;
- balaie latéralement plutôt que de pointer droit devant — un mouvement de translation
  perpendiculaire à l'axe optique donne la meilleure parallaxe ;
- repasse deux fois sur les zones importantes ;
- si le HUD affiche « suivi perdu », recule vers une zone texturée déjà cartographiée.

Le nuage se colore par altitude : bleu en bas, vert au milieu, ambre en haut.

**Exports**

Tout arrive dans `Téléchargements/RoomScan/`.

- **Export 3D** → `scan-*.ply`, binaire little-endian. S'ouvre dans CloudCompare
  (le meilleur pour du nuage brut), MeshLab ou Blender.
- **Plan 2D** → `plan-*.png` et `plan-*.svg`. Le PNG porte la grille métrique, l'échelle
  et la trajectoire du scan en rouge. Le SVG est vectoriel, éditable dans Inkscape.

---

## Comment marche le plan 2D

Aplatir un nuage brut donne une bouillie : le sol et le plafond noient tout. La méthode
retenue ne garde que la **tranche horizontale entre 0,40 m et 2,00 m au-dessus du sol** —
c'est là que se trouvent murs, meubles et rayonnages, et nulle part ailleurs.

Le sol est estimé par percentile bas des altitudes (3 %) plutôt que par détection de
plan : le percentile encaisse les points aberrants que la profondeur par parallaxe
produit inévitablement sous le niveau réel.

Chaque cellule de 5 cm compte ses points ; au-delà de 3 elle est déclarée occupée. Ce
seuil est ce qui sépare un plan lisible d'un nuage de confettis.

Les trois paramètres sont dans `FloorPlanBuilder.Params` — c'est le premier endroit à
toucher si le résultat ne te plaît pas.

---

## Architecture

```
MainActivity ........ réglages, vérification de compatibilité ARCore
ScanActivity ........ cycle de vie de la session, permissions, exports
ScanRenderer ........ boucle GL : update ARCore, capture, rendu
BackgroundRenderer .. flux caméra en texture OES
PointCloudRenderer .. VBO incrémental, coloration par altitude
DepthProcessor ...... déprojection profondeur -> points monde
VoxelCloud .......... déduplication par hachage de voxels (long, sans boxing)
FloorPlanBuilder .... tranche de hauteur -> grille d'occupation -> PNG/SVG
Exporter ............ écriture MediaStore
```

Deux choix structurants méritent d'être signalés, parce qu'ils ne sont pas évidents et
qu'ils sont la raison pour laquelle l'app tient la charge :

**Le VBO est rempli de façon incrémentale.** Seuls les points ajoutés depuis la frame
précédente sont téléversés. Renvoyer un million de points à chaque frame ferait tomber
le rendu bien en dessous du temps réel.

**La table de voxels manipule des `long` primitifs.** Un `HashMap<Long, ...>` boxerait
chaque clé et saturerait le ramasse-miettes bien avant le million de points, d'où la
table à adressage ouvert écrite à la main dans `VoxelCloud`.

---

## Limites connues

- **Portrait uniquement.** Gérer la rotation d'écran avec ARCore demande un
  `DisplayRotationHelper` et complique le cycle de vie ; verrouiller en portrait évite
  toute une classe de bugs. À rouvrir si le besoin se confirme.
- **Pas de couleur RGB.** Les points sont colorés par altitude. Échantillonner la couleur
  demanderait la conversion YUV → RGB de l'image caméra à chaque frame.
- **Pas de fermeture de boucle.** La dérive n'est jamais corrigée. Sur un grand espace,
  le point de départ et le point d'arrivée ne coïncideront pas.
- **Pas de maillage.** Le résultat est un nuage. Pour une surface, passe le `.ply` dans
  la reconstruction Poisson de CloudCompare ou MeshLab.
- **Au-delà de 1,5 million de points**, l'affichage se fige mais l'accumulation continue
  et l'export reste complet.

---

## Pistes d'évolution

Par ordre de rapport valeur / effort :

1. Sauvegarder le nuage en cours dans un fichier pour reprendre un scan interrompu.
2. Détection des segments de mur par transformée de Hough sur la grille d'occupation —
   c'est ce qui transformerait le plan pixelisé en vrai plan à traits.
3. Couleur RGB échantillonnée sur l'image caméra.
4. Ancrages nommés : taper sur l'écran pour poser une étiquette à un point du monde.

Le point 4 est le pont vers ton idée de cartographie de supermarché : une fois qu'on sait
poser une étiquette à une coordonnée, associer un code-barres scanné à cette coordonnée
n'est plus qu'un détail.
