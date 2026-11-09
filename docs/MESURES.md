# Cobblemon Faster Snacks : diagnostic et mesures

Mesures faites pendant le développement (8 et 9 novembre 2026), sur un serveur dédié de test (Cobblemon 1.8.4,
Minecraft 1.21.1, Fabric). Les versions « intermédiaire » et « finale » désignent l'état du mod pendant ces mesures ;
la version finale est la 1.0.0.

## Ce qui se passe avec un snack (diagnostic)

Un snack appât reçoit des random ticks comme une culture. Tous les 2 random ticks (environ toutes les
2 min 15 en moyenne avec `randomTickSpeed=3`), il tente d'attirer un Pokémon. Chaque tentative lance,
**sur le thread serveur et dans un seul tick**, le pipeline de spawn complet de Cobblemon sur une zone de
17 × 17 × 17 blocs autour du snack :

1. instantané de la zone (4 913 blocs, lumière, ciel) ;
2. résolution des positions de spawn (300 à 1 850 positions selon le terrain) ;
3. sélection : chaque position est testée contre chaque entrée du pool de spawn
   (5 178 entrées dans Cobblemon de base), condition par condition.

L'étape 3 représente plus de 90 % du coût. Une zone de snack est 4,8 fois plus grande qu'une passe du
spawner joueur (8 × 16 × 8), d'où des pics de 15 à 70 ms par tentative. Avec des dizaines de snacks posés
sur un serveur, ces pics s'enchaînent. Le rapport interactif reprend ces mesures avec leurs jeux de données.

## Résultats mesurés (8 et 9 novembre 2026)

> **Correction (9 novembre).** Les deux premières campagnes devaient mesurer un snack assaisonné aux baies Wacan,
> Kelpsy et Oran. Les identifiants d'appât étaient mal écrits (`cobblemon:berries/wacan_berry` au lieu de
> `cobblemon:wacan_berry`) : Cobblemon ne les reconnaissait pas, et ces snacks se sont comportés comme des **snacks
> nature**. Les chiffres ci-dessous restent justes pour un snack nature. Les mesures avec de vrais assaisonnements
> sont dans « Snacks vraiment assaisonnés ».

Serveur dédié de test, Cobblemon 1.8.4, snack nature (voir la correction), mêmes emplacements,
mesures avant/après alternées.

| Mesure | Cobblemon seul | Avec le mod |
|---|---|---|
| Temps d'une tentative de spawn, médiane des 12 biomes | 15,9 ms | 4,2 ms (×3,9) |
| Gain selon le biome | | ×2,2 (marais) à ×6,3 (jungle) |
| Pire tentative observée | 45,5 ms | 15,7 ms |
| Champ de 64 snacks (×20) : tick moyen | 8,8 ms | 3,3 ms |
| Champ de 64 snacks : tick au 99e centile | 52,2 ms | 16,6 ms |
| Champ de 64 snacks : ticks au-delà de 50 ms (4 800 ticks) | 58 | 0 |
| Différences avec le calcul d'origine (mode vérification) | | 0 sur 594 817 985 |

La répartition des spawns choisis avant et après ne diffère pas au-delà du hasard du tirage
(test par permutation, p = 0,75).

### En conditions réelles : plateforme de farm dans le ciel

Plateforme d'herbe de 3 × 3 chunks à y = 180, 128 snacks nature (voir la correction) sur le chunk central, un joueur au centre,
Pokémon jamais tués et retirés toutes les 3 000 ticks, random ticks des snacks ×20 pour émuler l'accélération de
Tropimon, Lithium installé. Deux plafonds : `pokeSnackPokemonPerChunk = 2` (valeur de la config Tropimon,
« standard ») et 15 (« intense », la plateforme se remplit jusqu'à l'espacement de 8 blocs entre Pokémon).

Coût d'une tentative de snack, versions alternées **à chaque tentative** dans un même run (sauvegarde automatique
coupée, échauffement préalable) :

| Scénario, version du mod | Tentatives par côté | Cobblemon seul | Avec le mod | Pire, seul → avec |
|---|---|---|---|---|
| Standard, intermédiaire (mémo seul) | 4 190 | 0,076 ms | 0,066 ms (−13 %) | 18,3 → 8,0 ms |
| Standard, finale | 4 190 | 0,088 ms | 0,065 ms (−26 %) | 33,7 → 5,9 ms |
| Intense, intermédiaire (mémo seul) | 4 169 | 0,923 ms | 0,914 ms (−1 %) | 24,3 → 12,5 ms |
| Intense, finale | 4 169 | 1,011 ms | 0,895 ms (−11 %) | 20,2 → 8,4 ms |

Tick serveur complet, mod éteint puis allumé par blocs de 100 ticks (version intermédiaire ; blocs contenant le
démarrage, un nettoyage ou la sauvegarde automatique écartés) :

| Scénario | Tick, seul → avec | Tick d'un Pokémon, seul → avec |
|---|---|---|
| Standard ×4 | 0,85 → 0,87 ms | 23,7 → 23,8 µs |
| Standard ×20 | 0,97 → 0,88 ms | 26,0 → 23,1 µs |
| Intense ×20 | 2,08 → 1,95 ms | 21,1 → 19,3 µs |

Ce que ces mesures montrent :

- **Sur une plateforme, un snack coûte peu, même sans le mod** : la zone de 4 913 blocs est presque toute de l'air,
  et une fois la plateforme pleine la plupart des tentatives s'arrêtent tôt. Une tentative coûte 0,08 à 1 ms, contre
  15,9 ms pour un snack posé au sol dans un biome naturel, où le mémo donne son ×3,9.
- **Le reste du temps** : dans le scénario intense, le profileur place 25 % du temps serveur dans la lecture de la
  lumière de la zone, 7 % dans une recherche de joueur inutile (les deux corrigées dans la version finale) et 34 % dans
  le tick des Pokémon.
- **Les pics** : la pire tentative est divisée par 2 à 6 selon le scénario, et le budget de tick reporte les rares
  tentatives au-delà de 10 ms (6 sur 8 504 dans le run intense).
- **IA des Pokémon** : écart de −11 % à +0,2 % selon le run, non établi. Le coût est surtout dans Minecraft (cerveau
  des mobs, déplacements, collisions).
- **Exactitude de la version finale**, mode vérification, scénario intense : 0 différence sur 1 278 240 conditions de
  spawn et 13 476 358 valeurs de lumière.
- Les runs successifs « un démarrage, une version » se sont révélés trop bruités sur la machine de test (jusqu'à
  1,6 fois d'écart sur le tick des Pokémon, que le mod ne peut pas ralentir) : seules les mesures alternées servent
  à comparer les versions.

### Snacks vraiment assaisonnés (9 novembre)

Mêmes protocoles, avec de vrais identifiants d'appât (Wacan, Kelpsy, Oran ; et un mélange rare : pomme dorée
enchantée, Hopo, Enigma). Le serveur de test et un client tournaient en parallèle : les temps absolus de cette
campagne sont plus élevés, seules les comparaisons faites en alternance dans une même série comptent.

- **L'assaisonnement agit bien, mod actif** : sur 150 vrais spawns, Wacan fait passer la part de types Électrik de
  7 % à 27 % en forêt et de 0 % à 11 % en rivière.
- **Exactitude** : 16 runs en mode vérification (12 biomes, deux mélanges) : 0 différence sur 458 232 653
  conditions et sur 15 721 599 valeurs de lumière. Sur le serveur de test avec les mods de Tropimon et ScalableLux :
  0 différence sur 951 559 conditions et 761 514 valeurs de lumière.
- **Coût d'un assaisonnement**, Cobblemon seul, snack nature et snack assaisonné alternés : +16 % à +40 % par
  tentative selon le biome (médiane +25 %).
- **Gain du mod avec un vrai assaisonnement**, 11 biomes au sol, versions alternées : ×3,7 en médiane
  (de ×2,2 en rivière et en marais à ×5,5 en jungle).
- **Plateforme, versions alternées à chaque tentative** :

| Scénario | Tentatives par côté | Cobblemon seul | Avec le mod | Pire, seul → avec |
|---|---|---|---|---|
| Standard ×20 | 4 267 | 0,139 ms | 0,106 ms (−24 %) | 44,0 → 13,0 ms |
| Intense ×20 | 4 168 | 1,086 ms | 0,965 ms (−11 %) | 39,7 → 15,8 ms |

- **Essai écarté** : avec un vrai assaisonnement, Cobblemon recalcule le poids de chaque spawn possible à chaque
  position. Un mémo exact de ce calcul a été écrit et vérifié (0 différence sur 694 321 poids), mais il ne changeait
  rien au temps mesuré (quelques milliers d'appels par tentative) : il a été retiré.
