import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * Comparison charts for the Modrinth page (1600x900, drawn at 2x: sizes below are double the screen-pixel specs).
 * usage: ChartMaker <chart_data.properties> <out dir>
 */
public class ChartMaker {
    static final int W = 1600, H = 900, PAD = 72;
    static final Color SURFACE = new Color(0x1a1a19), TEXT = new Color(0xf0efec), TEXT2 = new Color(0xc3c2b7),
            MUTED = new Color(0x8e8d86), GRID = new Color(0x302f2d), AXIS = new Color(0x4a4945);
    static final Color A = new Color(0x3987e5), B = new Color(0xd95926);   // validated pair, dark surface
    static final String NAME_A = "Cobblemon 1.8.4 alone", NAME_B = "With Cobblemon Faster Snacks";
    static final String FONT = "Segoe UI";
    static final int BAR = 44, GAP = 4, R = 8;
    static Properties p = new Properties();

    public static void main(String[] a) throws Exception {
        p.load(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8));
        File out = new File(a[1]);
        attemptTime(new File(out, "1-snack-attempt-time.png"));
        byBiome(new File(out, "2-seasoned-snack-by-biome.png"));
        field(new File(out, "3-snack-field-tick-spikes.png"));
        platform(new File(out, "4-sky-platform.png"));
    }

    static double d(String k) { return Double.parseDouble(p.getProperty(k)); }

    // ------------------------------------------------------------------ charts

    static void attemptTime(File f) throws Exception {
        Graphics2D g = canvas();
        int y = header(g, "Time the server spends on one snack spawn attempt",
                "Poké Snack on natural ground, median over the biomes tested. Lower is better.");
        y = legend(g, y);
        int labelW = 330, x0 = PAD + labelW, x1 = W - PAD - 260;
        double max = 40;
        int plotTop = y + 30, plotBottom = H - PAD - 60;
        String[][] rows = {{"Plain snack", p.getProperty("plain.n") + " biomes", "plain", ""}, {"Seasoned snack", p.getProperty("seasoned.n") + " biomes", "seasoned", "Wacan, Kelpsy, Oran"}};
        int rowH = (plotBottom - plotTop) / rows.length;
        gridX(g, x0, x1, plotTop, plotBottom, max, 10, " ms");
        for (int i = 0; i < rows.length; i++) {
            int cy = plotTop + rowH * i + rowH / 2;
            int ya = cy - BAR - GAP / 2, yb = cy + GAP / 2;
            text(g, rows[i][0], PAD, cy - 6, 34, TEXT, Font.BOLD, false);
            text(g, rows[i][1], PAD, cy + 30, 24, TEXT2, Font.PLAIN, false);
            if (!rows[i][3].isEmpty()) text(g, rows[i][3], PAD, cy + 62, 24, TEXT2, Font.PLAIN, false);
            double va = d(rows[i][2] + ".a"), vb = d(rows[i][2] + ".b");
            hbar(g, x0, ya, scale(va, max, x0, x1), A);
            hbar(g, x0, yb, scale(vb, max, x0, x1), B);
            text(g, fmt(va) + " ms", scale(va, max, x0, x1) + 14, ya + BAR / 2 + 10, 28, TEXT, Font.BOLD, false);
            text(g, fmt(vb) + " ms", scale(vb, max, x0, x1) + 14, yb + BAR / 2 + 10, 28, TEXT, Font.BOLD, false);
            text(g, String.format(Locale.US, "%.1f× faster", d(rows[i][2] + ".x")), W - PAD, cy + 12, 34, TEXT, Font.BOLD, true);
        }
        footer(g, "Speed-up: median of the per-biome ratios. Both versions measured alternately on the same dedicated server, same snack, same spot.");
        save(g, f);
    }

    static void byBiome(File f) throws Exception {
        Graphics2D g = canvas();
        int y = header(g, "Seasoned snack, biome by biome",
                "Time per spawn attempt (Wacan + Kelpsy + Oran). Lower is better.");
        y = legend(g, y);
        int labelW = 220, x0 = PAD + labelW, x1 = W - PAD - 150;
        double max = 50;
        int n = Integer.parseInt(p.getProperty("biomes"));
        int plotTop = y + 24, plotBottom = H - PAD - 70;
        int rowH = (plotBottom - plotTop) / n, bar = Math.min(20, (rowH - 14) / 2);
        gridX(g, x0, x1, plotTop, plotBottom, max, 10, " ms");
        for (int i = 0; i < n; i++) {
            int cy = plotTop + rowH * i + rowH / 2;
            double va = d("biome." + i + ".a"), vb = d("biome." + i + ".b");
            text(g, p.getProperty("biome." + i + ".name"), PAD, cy + 9, 26, TEXT, Font.PLAIN, false);
            hbarT(g, x0, cy - bar - GAP / 2, scale(va, max, x0, x1), bar, A);
            hbarT(g, x0, cy + GAP / 2, scale(vb, max, x0, x1), bar, B);
            text(g, String.format(Locale.US, "%.1f×", va / vb), W - PAD, cy + 10, 28, TEXT, Font.BOLD, true);
        }
        text(g, "faster", W - PAD, plotTop - 10, 22, TEXT2, Font.PLAIN, true);
        footer(g, "11 natural biomes, two rounds each, versions alternated. Dedicated test server, Cobblemon 1.8.4.");
        save(g, f);
    }

    static void field(File f) throws Exception {
        Graphics2D g = canvas();
        int y = header(g, "64 snacks working at once: server lag spikes",
                "Snack field with 20× faster snack ticks, 4,800 server ticks per version. Lower is better.");
        y = legend(g, y);
        int top = y + 40, bottom = H - PAD - 90, mid = W / 2;
        panelColumns(g, "Ticks longer than 50 ms (the game stutters)", PAD, mid - 40, top, bottom,
                d("field.over50.a"), d("field.over50.b"), 70, 10, "", "%.0f", -1);
        panelColumns(g, "99th percentile server tick", mid + 40, W - PAD, top, bottom,
                d("field.p99.a"), d("field.p99.b"), 60, 10, " ms", "%.1f ms", 50);
        footer(g, "A tick has 50 ms to finish at 20 TPS. Plain snacks, both versions measured on the same dedicated server.");
        save(g, f);
    }

    static void platform(File f) throws Exception {
        Graphics2D g = canvas();
        int y = header(g, "Sky farm platform, 128 seasoned snacks",
                "Snacks at 20× speed; snack attempts alternate between both versions in the same run.");
        y = legend(g, y);
        int top = y + 40, bottom = H - PAD - 90, mid = W / 2;
        panelGroups(g, "Average attempt (ms)", PAD, mid - 40, top, bottom,
                new String[]{"2 Pokémon/chunk", "15 Pokémon/chunk"},
                new double[]{d("PS_STD20.mean_ms.a"), d("PS_INT.mean_ms.a")}, new double[]{d("PS_STD20.mean_ms.b"), d("PS_INT.mean_ms.b")}, 1.2, 0.2, "%.2f");
        panelGroups(g, "Worst attempt (ms)", mid + 40, W - PAD, top, bottom,
                new String[]{"2 Pokémon/chunk", "15 Pokémon/chunk"},
                new double[]{d("PS_STD20.max_ms.a"), d("PS_INT.max_ms.a")}, new double[]{d("PS_STD20.max_ms.b"), d("PS_INT.max_ms.b")}, 50, 10, "%.1f");
        footer(g, "On a full platform most attempts stop early, so the average gain is small; the mod mainly cuts the spikes.");
        save(g, f);
    }

    // ------------------------------------------------------------------ panels

    /** Two columns (A, B) on their own axis. refLine >= 0 draws a labelled reference line. */
    static void panelColumns(Graphics2D g, String title, int left, int right, int top, int bottom, double va, double vb,
                             double max, double step, String unit, String fmt, double refLine) {
        text(g, title, left, top, 28, TEXT, Font.BOLD, false);
        int axisW = 70, x0 = left + axisW, plotTop = top + 40;
        gridY(g, x0, right, plotTop, bottom, max, step, unit);
        int colW = 2 * BAR, band = (right - x0) / 2;
        int[] cx = {x0 + band / 2, x0 + band + band / 2};
        double[] v = {va, vb};
        Color[] c = {A, B};
        for (int i = 0; i < 2; i++) {
            int yTop = (int) Math.round(bottom - v[i] / max * (bottom - plotTop));
            if (v[i] > 0) vbar(g, cx[i] - colW / 2, yTop, colW, bottom - yTop, c[i]);
            text(g, String.format(Locale.US, fmt, v[i]), cx[i], yTop - 14, 30, TEXT, Font.BOLD, false, true);
        }
        if (refLine >= 0) {
            int yr = (int) Math.round(bottom - refLine / max * (bottom - plotTop));
            g.setColor(TEXT2);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x0, yr, right, yr);
            text(g, "50 ms = a full tick", right, yr - 10, 22, TEXT2, Font.PLAIN, true);
        }
    }

    /** Groups of two columns (A, B) on one axis. */
    static void panelGroups(Graphics2D g, String title, int left, int right, int top, int bottom, String[] groups,
                            double[] va, double[] vb, double max, double step, String fmt) {
        text(g, title, left, top, 28, TEXT, Font.BOLD, false);
        int axisW = 80, x0 = left + axisW, plotTop = top + 40, plotBottom = bottom - 40;
        gridY(g, x0, right, plotTop, plotBottom, max, step, step < 1 ? "" : "");
        int band = (right - x0) / groups.length, colW = BAR + 20, sep = 14;
        for (int i = 0; i < groups.length; i++) {
            int c = x0 + band * i + band / 2;
            double[] v = {va[i], vb[i]};
            Color[] col = {A, B};
            for (int k = 0; k < 2; k++) {
                int x = k == 0 ? c - colW - sep / 2 : c + sep / 2;
                int yTop = (int) Math.round(plotBottom - v[k] / max * (plotBottom - plotTop));
                vbar(g, x, yTop, colW, plotBottom - yTop, col[k]);
                text(g, String.format(Locale.US, fmt, v[k]), x + colW / 2, yTop - 12, 24, TEXT, Font.BOLD, false, true);
            }
            text(g, groups[i], c, plotBottom + 34, 24, TEXT2, Font.PLAIN, false, true);
        }
    }

    // ------------------------------------------------------------------ chrome

    static Graphics2D canvas() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(SURFACE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        current = img;
        return g;
    }

    static BufferedImage current;

    static int header(Graphics2D g, String title, String subtitle) {
        text(g, title, PAD, PAD + 40, 46, TEXT, Font.BOLD, false);
        text(g, subtitle, PAD, PAD + 88, 28, TEXT2, Font.PLAIN, false);
        return PAD + 120;
    }

    static int legend(Graphics2D g, int y) {
        int x = PAD;
        for (int i = 0; i < 2; i++) {
            g.setColor(i == 0 ? A : B);
            g.fill(new RoundRectangle2D.Double(x, y + 6, 28, 28, 8, 8));
            String s = i == 0 ? NAME_A : NAME_B;
            text(g, s, x + 40, y + 30, 26, TEXT, Font.PLAIN, false);
            x += 40 + width(g, s, 26, Font.PLAIN) + 48;
        }
        return y + 40;
    }

    static void footer(Graphics2D g, String s) { text(g, s, PAD, H - PAD + 30, 22, MUTED, Font.PLAIN, false); }

    static void gridX(Graphics2D g, int x0, int x1, int top, int bottom, double max, double step, String unit) {
        g.setStroke(new BasicStroke(2f));
        for (double v = 0; v <= max + 1e-9; v += step) {
            int x = scale(v, max, x0, x1);
            g.setColor(v == 0 ? AXIS : GRID);
            g.drawLine(x, top, x, bottom);
            text(g, fmt0(v) + unit, x, bottom + 34, 22, MUTED, Font.PLAIN, false, true);
        }
    }

    static void gridY(Graphics2D g, int x0, int x1, int top, int bottom, double max, double step, String unit) {
        g.setStroke(new BasicStroke(2f));
        for (double v = 0; v <= max + 1e-9; v += step) {
            int y = (int) Math.round(bottom - v / max * (bottom - top));
            g.setColor(v == 0 ? AXIS : GRID);
            g.drawLine(x0, y, x1, y);
            text(g, fmt0(v) + unit, x0 - 14, y + 8, 22, MUTED, Font.PLAIN, true);
        }
    }

    /** Horizontal bar: square at the baseline, rounded data end. */
    static void hbar(Graphics2D g, int x0, int y, int x1, Color c) { hbarT(g, x0, y, x1, BAR, c); }

    static void hbarT(Graphics2D g, int x0, int y, int x1, int h, Color c) {
        int w = Math.max(2, x1 - x0), r = Math.min(R, w);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0, y);
        p.lineTo(x0 + w - r, y);
        p.quadTo(x0 + w, y, x0 + w, y + r);
        p.lineTo(x0 + w, y + h - r);
        p.quadTo(x0 + w, y + h, x0 + w - r, y + h);
        p.lineTo(x0, y + h);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    /** Column: square at the baseline, rounded top. */
    static void vbar(Graphics2D g, int x, int yTop, int w, int h, Color c) {
        int r = Math.min(R, h);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, yTop + h);
        p.lineTo(x, yTop + r);
        p.quadTo(x, yTop, x + r, yTop);
        p.lineTo(x + w - r, yTop);
        p.quadTo(x + w, yTop, x + w, yTop + r);
        p.lineTo(x + w, yTop + h);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    static int scale(double v, double max, int x0, int x1) { return (int) Math.round(x0 + v / max * (x1 - x0)); }

    static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }

    static String fmt0(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v); }

    static int width(Graphics2D g, String s, int size, int style) {
        return g.getFontMetrics(new Font(FONT, style, size)).stringWidth(s);
    }

    static void text(Graphics2D g, String s, int x, int y, int size, Color c, int style, boolean rightAlign) {
        text(g, s, x, y, size, c, style, rightAlign, false);
    }

    static void text(Graphics2D g, String s, int x, int y, int size, Color c, int style, boolean rightAlign, boolean centre) {
        Font font = new Font(FONT, style, size);
        g.setFont(font);
        g.setColor(c);
        int w = g.getFontMetrics().stringWidth(s);
        int tx = rightAlign ? x - w : centre ? x - w / 2 : x;
        g.drawString(s, tx, y);
    }

    static void save(Graphics2D g, File f) throws Exception {
        g.dispose();
        ImageIO.write(current, "png", f);
        System.out.println("wrote " + f);
    }
}
