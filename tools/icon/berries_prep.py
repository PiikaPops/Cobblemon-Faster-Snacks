"""Turns Cobblemon berry data into a flat scene file for IconMaker2, and computes the snack tint.
usage: berries_prep.py <berries3d dir> <out scene.txt> berry1 berry2 berry3
The tint follows FlavourColourHelper.getColourMixFromColors (first colour 70 %, the others share 30 %) over
FoodColourComponent's dye colours; the berry at index i uses its pokeSnackPositionings[i] (3 berries)."""
import json, sys, os

DYE = {  # FoodColourComponent.COLORS (r, g, b)
    'WHITE': (255, 255, 255), 'ORANGE': (255, 195, 175), 'MAGENTA': (175, 140, 255), 'LIGHT_BLUE': (120, 195, 235),
    'YELLOW': (255, 225, 175), 'PINK': (225, 160, 255), 'LIME': (205, 255, 175), 'GRAY': (71, 79, 82),
    'LIGHT_GRAY': (157, 157, 151), 'CYAN': (135, 235, 215), 'PURPLE': (145, 145, 255), 'BLUE': (120, 165, 255),
    'BROWN': (131, 84, 50), 'GREEN': (175, 255, 180), 'RED': (255, 175, 215), 'BLACK': (0, 0, 0)}

d, out, names = sys.argv[1], sys.argv[2], sys.argv[3:]
colours = []
lines = []
for i, name in enumerate(names):
    data = json.load(open(os.path.join(d, f'{name}_data.json'), encoding='utf-8'))
    colours.append(DYE[data['colour']])
    pos = data['pokeSnackPositionings'][i] if len(names) >= 3 else data['pokeSnackPositionings'][0]
    geo = json.load(open(os.path.join(d, f'{name}_berry.geo.json'), encoding='utf-8'))['minecraft:geometry'][0]
    desc = geo['description']
    p, r = pos['position'], pos['rotation']
    lines.append(f"berry {name} {os.path.join(d, name + '.png')} {desc['texture_width']} {desc['texture_height']} "
                 f"{p['x']} {p['y']} {p['z']} {r['x']} {r['y']} {r['z']}")
    for bone in geo['bones']:
        for c in bone.get('cubes', []):
            o, s, uv = c['origin'], c['size'], c['uv']
            pv = c.get('pivot', [0, 0, 0]); rt = c.get('rotation', [0, 0, 0])
            lines.append('cube ' + ' '.join(str(v) for v in o + s + uv + pv + rt))
if len(colours) == 1:
    tint = colours[0]
else:
    other = 0.3 / (len(colours) - 1)
    tint = tuple(int(sum(c[k] * (0.7 if j == 0 else other) for j, c in enumerate(colours))) for k in range(3))
lines.insert(0, 'tint %02x%02x%02x' % tint)
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('tint', tint, 'berries', names)
