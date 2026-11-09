# Changelog

## 1.0.0 (9 novembre 2026)

Première version publique, sous le nom **Cobblemon Faster Snacks** (anciennement CobblemonFixs, version de
développement 0.1.0).

- Mémo exact des conditions de spawn pour les tentatives des Poké Snacks (×3,7 à ×3,9 par tentative au sol).
- Runtime MoLang et listes de blocs voisins construits seulement à la demande.
- Plus de nettoyage inutile des tables après l'unique choix d'un snack.
- Lumière du ciel lue une seule fois par bloc de zone ; recherche de joueur inutile supprimée de la résolution des
  positions (s'applique à tout spawn de zone).
- Budget de 10 ms par tick pour les snacks, sans perte de tentative.
- Recherche de chef de horde court-circuitée pour les espèces sans chef ; contrôle d'évolution par distance sans
  allocation ; préchargement des classes Cobblemon au démarrage.
- Mode vérification (`/fastersnacks snacks verify true`) et statistiques (`/fastersnacks stats`).
- Commandes renommées en `/fastersnacks`, config dans `config/cobblemon_faster_snacks.json` (l'ancien
  `config/cobblemonfixs.json` n'est plus lu).
- Les outils de mesure ne sont plus dans le jar de production (build séparé avec `-Pbench`).
