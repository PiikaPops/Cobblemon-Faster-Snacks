import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mod icon (128x128): bg.png, a warm glow, speed streaks, a seasoned Poké Snack rendered in 3D as the game draws it
 * (block model tinted by the seasoning colours, the berries' own 3D models placed by their pokeSnackPositionings),
 * then border.png and mod.png on top.
 * usage: IconMaker2 <frames dir> <snack textures dir> <scene.txt> <out.png>
 */
public class IconMaker2 {
    static final int OUTLINE = 0xFF1E1A26;
    static final int W = 128, H = 128;
    static final int[] colour = new int[W * H];
    static final double[] depth = new double[W * H];
    static double scale = 4.45, cx = 64, cy = 64;
    // toward the viewer for Minecraft's GUI angles (30° pitch, 45° yaw), in block space (y up)
    static final double[] VIEW = {Math.cos(Math.toRadians(30)) / Math.sqrt(2), Math.sin(Math.toRadians(30)), Math.cos(Math.toRadians(30)) / Math.sqrt(2)};

    record Cube(double ox, double oy, double oz, double sx, double sy, double sz, double u, double v,
                double px, double py, double pz, double rx, double ry, double rz) {}
    record Berry(String name, BufferedImage tex, double x, double y, double z, double rx, double ry, double rz, List<Cube> cubes) {}

    public static void main(String[] a) throws Exception {
        File frames = new File(a[0]), tex = new File(a[1]);
        BufferedImage bg = ImageIO.read(new File(frames, "bg.png"));
        BufferedImage border = ImageIO.read(new File(frames, "border.png"));
        BufferedImage mod = ImageIO.read(new File(frames, "mod.png"));
        BufferedImage top = ImageIO.read(new File(tex, "poke_snack_top.png"));
        BufferedImage side = ImageIO.read(new File(tex, "poke_snack_side.png"));
        int tint = 0xFFFFFFFF;
        List<Berry> berries = new ArrayList<>();
        Berry current = null;
        for (String line : Files.readAllLines(new File(a[2]).toPath(), StandardCharsets.UTF_8)) {
            String[] t = line.trim().split("\\s+");
            if (t[0].equals("tint")) tint = 0xFF000000 | Integer.parseInt(t[1], 16);
            else if (t[0].equals("berry")) {
                current = new Berry(t[1], ImageIO.read(new File(t[2])), d(t[5]), d(t[6]), d(t[7]), d(t[8]), d(t[9]), d(t[10]), new ArrayList<>());
                berries.add(current);
            } else if (t[0].equals("cube") && current != null) {
                current.cubes().add(new Cube(d(t[1]), d(t[2]), d(t[3]), d(t[4]), d(t[5]), d(t[6]), d(t[7]), d(t[8]),
                        d(t[9]), d(t[10]), d(t[11]), d(t[12]), d(t[13]), d(t[14])));
            }
        }

        Arrays.fill(depth, -1e9);
        snack(top, side, tint);
        for (Berry b : berries) berry(b);
        BufferedImage layer = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        layer.setRGB(0, 0, W, H, colour, 0, W);
        BufferedImage subject = outline(crop(layer), OUTLINE);

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        draw(out, bg, 0, 0);
        glow(out, bg, 66, 64, 58, 0xFFCC4D);
        draw(out, subject, 67 - subject.getWidth() / 2, 63 - subject.getHeight() / 2);
        draw(out, border, 0, 0);
        draw(out, mod, 0, 0);
        ImageIO.write(out, "png", new File(a[3]));
    }

    static double d(String s) { return Double.parseDouble(s); }

    // ------------------------------------------------------------------ scene

    /** The poke_snack block model (from 1,0,1 to 15,7,15), tinted, as the block colour provider tints it. */
    static void snack(BufferedImage top, BufferedImage side, int tint) {
        double[] up = {0, 1, 0}, south = {0, 0, 1}, east = {1, 0, 0};
        face(new double[][]{{1, 7, 1}, {15, 7, 1}, {15, 7, 15}, {1, 7, 15}}, up, top, 1, 1, 14, 14, tint);
        face(new double[][]{{1, 7, 15}, {15, 7, 15}, {15, 0, 15}, {1, 0, 15}}, south, side, 1, 9, 14, 7, tint);
        face(new double[][]{{15, 7, 15}, {15, 7, 1}, {15, 0, 1}, {15, 0, 15}}, east, side, 1, 9, 14, 7, tint);
    }

    /**
     * A berry the way PokeSnackBlockEntityRenderer draws it: Bedrock cube (with its own rotation about its pivot),
     * converted to a ModelPart (y flipped), rotated ZYX by the positioning's rotation, moved to its position.
     */
    static void berry(Berry b) {
        for (Cube c : b.cubes()) {
            double x0 = c.ox(), x1 = c.ox() + c.sx(), y0 = c.oy(), y1 = c.oy() + c.sy(), z0 = c.oz(), z1 = c.oz() + c.sz();
            double dd = c.sz(), w = c.sx(), h = c.sy(), u0 = c.u(), v0 = c.v();
            // Bedrock box UV
            bface(b, c, new double[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, u0 + dd, v0, w, dd);             // up
            bface(b, c, new double[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, u0 + dd + w, v0, w, dd);         // down
            bface(b, c, new double[][]{{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}}, u0, v0 + dd, dd, h);             // east
            bface(b, c, new double[][]{{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}}, u0 + dd, v0 + dd, w, h);         // north
            bface(b, c, new double[][]{{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}}, u0 + dd + w, v0 + dd, dd, h);    // west
            bface(b, c, new double[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}}, u0 + 2 * dd + w, v0 + dd, w, h); // south
        }
    }

    static void bface(Berry b, Cube c, double[][] corners, double us, double vs, double uw, double vh) {
        if (uw <= 0 || vh <= 0) return;
        double[] right = sub(corners[1], corners[0]), down = sub(corners[3], corners[0]);
        double[] n = cross(down, right);
        if (len(n) < 1e-9) return;
        double[][] world = new double[4][];
        for (int i = 0; i < 4; i++) world[i] = toBlock(b, c, corners[i], true);
        double[] nw = toBlock(b, c, n, false);
        face(world, normalize(nw), b.tex(), us, vs, uw, vh, 0xFFFFFFFF);
    }

    /** Bedrock local point (or direction) -> block space pixels. */
    static double[] toBlock(Berry b, Cube c, double[] p, boolean point) {
        double[] v = p.clone();
        if (c.rx() != 0 || c.ry() != 0 || c.rz() != 0) {
            if (point) v = sub(v, new double[]{c.px(), c.py(), c.pz()});
            v = rotZ(rotY(rotX(v, c.rx()), c.ry()), c.rz());
            if (point) v = add(v, new double[]{c.px(), c.py(), c.pz()});
        }
        v = new double[]{v[0], -v[1], v[2]};                          // ModelPart space (y down)
        v = rotZ(rotY(rotX(v, b.rx()), b.ry()), b.rz());              // rotationZYX(z, y, x): X first, then Y, then Z
        return point ? add(v, new double[]{b.x(), b.y(), b.z()}) : v;
    }

    // ------------------------------------------------------------------ rasteriser

    static double[] project(double[] p) {
        double x = p[0] - 8, y = p[1] - 4.5, z = p[2] - 8;
        double sx = cx + (x - z) * scale / Math.sqrt(2);
        double sy = cy + (x + z) * scale * 0.5 / Math.sqrt(2) - y * scale * Math.cos(Math.toRadians(30));
        double dz = y * VIEW[1] + (x + z) * VIEW[0];
        return new double[]{sx, sy, dz};
    }

    /** A textured quad (corners TL, TR, BR, BL as seen from outside), culled if it faces away, shaded like Minecraft. */
    static void face(double[][] corners, double[] normal, BufferedImage tex, double us, double vs, double uw, double vh, int tint) {
        if (dot(normal, VIEW) <= 1e-6) return;
        double nx = normal[0], ny = normal[1], nz = normal[2];
        double shade = Math.min(1.0, nx * nx * 0.6 + ny * ny * ((3.0 + ny) / 4.0) + nz * nz * 0.8);
        double[][] s = new double[4][];
        for (int i = 0; i < 4; i++) s[i] = project(corners[i]);
        double[][] uv = {{us, vs}, {us + uw, vs}, {us + uw, vs + vh}, {us, vs + vh}};
        int[] box = {(int) Math.floor(us + 1e-6), (int) Math.floor(vs + 1e-6), (int) Math.ceil(us + uw - 1e-6), (int) Math.ceil(vs + vh - 1e-6)};
        tri(s[0], s[1], s[2], uv[0], uv[1], uv[2], tex, box, tint, shade);
        tri(s[0], s[2], s[3], uv[0], uv[2], uv[3], tex, box, tint, shade);
    }

    static void tri(double[] a, double[] b, double[] c, double[] ta, double[] tb, double[] tc, BufferedImage tex, int[] box, int tint, double shade) {
        double area = edge(a, b, c[0], c[1]);
        if (Math.abs(area) < 1e-9) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), maxX = Math.min(W - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), maxY = Math.min(H - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int py = minY; py <= maxY; py++) for (int px = minX; px <= maxX; px++) {
            double x = px + 0.5, y = py + 0.5;
            double w0 = edge(b, c, x, y) / area, w1 = edge(c, a, x, y) / area, w2 = edge(a, b, x, y) / area;
            if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
            double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
            int i = py * W + px;
            if (z <= depth[i]) continue;
            double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0], v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
            int iu = Math.max(box[0], Math.min(box[2] - 1, (int) Math.floor(u)));
            int iv = Math.max(box[1], Math.min(box[3] - 1, (int) Math.floor(v)));
            if (iu < 0 || iv < 0 || iu >= tex.getWidth() || iv >= tex.getHeight()) continue;
            int t = tex.getRGB(iu, iv);
            if ((t >>> 24) < 128) continue;
            depth[i] = z;
            colour[i] = 0xFF000000 | (scale(multiply(t, tint), shade) & 0xFFFFFF);
        }
    }

    static double edge(double[] a, double[] b, double x, double y) { return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]); }

    // ------------------------------------------------------------------ vectors

    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] normalize(double[] a) { double l = len(a); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] rotX(double[] v, double deg) { double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r); return new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c}; }
    static double[] rotY(double[] v, double deg) { double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r); return new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c}; }
    static double[] rotZ(double[] v, double deg) { double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r); return new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]}; }

    // ------------------------------------------------------------------ 2D effects and helpers

    static void glow(BufferedImage out, BufferedImage bg, int gx, int gy, int radius, int rgb) {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if ((bg.getRGB(x, y) >>> 24) == 0) continue;
            double d = Math.hypot(x - gx, (y - gy) * 1.1) / radius;
            if (d >= 1) continue;
            double t = Math.floor((1 - d) * 4) / 4.0;
            if (t <= 0) continue;
            out.setRGB(x, y, over(out.getRGB(x, y), ((int) (t * t * 0.5 * 255) << 24) | rgb));
        }
    }

    /** Speed streaks just left of the subject, fading to the left, never drawn over it. */
    static void streaks(BufferedImage out, BufferedImage subject, int sx, int sy) {
        int[][] lines = {{-10, 10}, {0, 14}, {10, 9}};   // offset from the subject's middle, length
        int mid = sy + subject.getHeight() / 2;
        for (int[] l : lines) {
            int y = mid + l[0];
            int end = leftEdge(subject, sx, sy, y) - 3;
            for (int i = 0; i < l[1]; i++) {
                int x = end - i;
                if (x < 14) break;
                int alpha = (int) (Math.ceil((1 - (double) i / l[1]) * 3) / 3.0 * 230);
                for (int h = 0; h < 2; h++) if ((out.getRGB(x, y + h) >>> 24) != 0) out.setRGB(x, y + h, over(out.getRGB(x, y + h), (alpha << 24) | 0xFFE9A8));
            }
        }
    }

    static int leftEdge(BufferedImage subject, int sx, int sy, int y) {
        int row = y - sy;
        if (row < 0 || row >= subject.getHeight()) return sx;
        for (int x = 0; x < subject.getWidth(); x++) if ((subject.getRGB(x, row) >>> 24) != 0) return sx + x;
        return sx + subject.getWidth();
    }

    static BufferedImage crop(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) > 0) {
            minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    static BufferedImage outline(BufferedImage src, int c) {
        int w = src.getWidth() + 2, h = src.getHeight() + 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = get(src, x - 1, y - 1);
            if ((p >>> 24) != 0) { out.setRGB(x, y, p); continue; }
            if (op(src, x - 2, y - 1) || op(src, x, y - 1) || op(src, x - 1, y - 2) || op(src, x - 1, y)) out.setRGB(x, y, c);
        }
        return out;
    }

    static boolean op(BufferedImage im, int x, int y) { return (get(im, x, y) >>> 24) != 0; }
    static int get(BufferedImage im, int x, int y) { return x < 0 || y < 0 || x >= im.getWidth() || y >= im.getHeight() ? 0 : im.getRGB(x, y); }

    static void draw(BufferedImage dst, BufferedImage src, int ox, int oy) {
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) {
            int c = src.getRGB(x, y);
            if ((c >>> 24) == 0) continue;
            int dx = ox + x, dy = oy + y;
            if (dx < 0 || dy < 0 || dx >= dst.getWidth() || dy >= dst.getHeight()) continue;
            dst.setRGB(dx, dy, over(dst.getRGB(dx, dy), c));
        }
    }

    static int over(int dst, int src) {
        double sa = (src >>> 24) / 255.0, da = (dst >>> 24) / 255.0, oa = sa + da * (1 - sa);
        if (oa <= 0) return 0;
        int r = (int) Math.round((((src >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (1 - sa)) / oa);
        int g = (int) Math.round((((src >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (1 - sa)) / oa);
        int b = (int) Math.round(((src & 255) * sa + (dst & 255) * da * (1 - sa)) / oa);
        return ((int) Math.round(oa * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    static int multiply(int c, int t) {
        int r = ((c >> 16) & 255) * ((t >> 16) & 255) / 255, g = ((c >> 8) & 255) * ((t >> 8) & 255) / 255, b = (c & 255) * (t & 255) / 255;
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int scale(int c, double f) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * f), g = (int) Math.min(255, ((c >> 8) & 255) * f), b = (int) Math.min(255, (c & 255) * f);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
