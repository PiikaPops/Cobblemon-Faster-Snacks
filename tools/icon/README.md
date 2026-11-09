# Générateur de l'icône

Rend l'icône 128 × 128 : `bg.png`, un halo doré, un Poké Snack assaisonné en 3D comme le jeu le dessine (modèle du
bloc teinté par les couleurs des assaisonnements, modèles 3D des baies placés par leurs `pokeSnackPositionings`),
puis `border.png` et `mod.png` (cadres d'addon Cobblemon).

1. Extraire du jar Cobblemon, dans un dossier `snack/` : `assets/cobblemon/textures/block/food/poke_snack_top.png` et
   `poke_snack_side.png`.
2. Extraire dans un dossier `berries3d/`, pour chaque baie : `assets/cobblemon/bedrock/berries/<baie>_berry.geo.json`,
   `assets/cobblemon/textures/berries/<baie>.png`, et `data/cobblemon/berries/<baie>_berry.json` renommé
   `<baie>_data.json`.
3. Lancer :

```
python berries_prep.py berries3d scene.txt wacan oran razz
java IconMaker2.java <dossier des cadres> snack scene.txt icon.png
```

La première baie donne 70 % de la teinte du snack, comme dans Cobblemon.
