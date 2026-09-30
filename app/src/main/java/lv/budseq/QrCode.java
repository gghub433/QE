package lv.budseq;

/**
 * Свой QR-генератор без библиотек: байтовый режим, коррекция M, версии 1–10 (до 213 байт),
 * маска выбирается по штрафам стандарта ISO/IEC 18004. Чистая Java — картинку рисует MainActivity.
 * Алгоритм по мотивам открытого генератора Nayuki (MIT).
 */
public final class QrCode {
    public static final int MAX_VERSION = 10;

    // коррекция M: байт коррекции на блок и число блоков, по версиям 1–10
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0;   // биты уровня M в формате

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        this.version = version;
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    /** Матрица [y][x] (true — тёмный модуль) или null, если текст не влезает в версию 10. */
    public static boolean[][] encode(String text) {
        try {
            return encode(text.getBytes("UTF-8"), -1);
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /** mask -1 — выбрать лучшую; 0–7 — принудительно (для проверки). */
    public static boolean[][] encode(byte[] data, int mask) {
        int ver = 1;
        for (; ver <= MAX_VERSION; ver++) {
            int ccBits = ver < 10 ? 8 : 16;
            if (4 + ccBits + data.length * 8 <= dataCodewords(ver) * 8) break;
        }
        if (ver > MAX_VERSION) return null;
        int ccBits = ver < 10 ? 8 : 16;
        int capacity = dataCodewords(ver) * 8;

        // поток бит: режим 0100 (байты), длина, данные, терминатор, выравнивание, заполнители
        Bits bb = new Bits(capacity);
        bb.append(4, 4);
        bb.append(data.length, ccBits);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.len / 8];
        for (int i = 0; i < bb.len; i++) {
            if (bb.get(i)) codewords[i >>> 3] |= 1 << (7 - (i & 7));
        }

        QrCode q = new QrCode(ver);
        q.drawFunctionPatterns();
        q.drawCodewords(q.addEccAndInterleave(codewords));
        if (mask < 0) {
            int best = 0, bestScore = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                q.applyMask(m);
                q.drawFormatBits(m);
                int score = q.penalty();
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                }
                q.applyMask(m);   // XOR — второй раз снимает маску
            }
            mask = best;
        }
        q.applyMask(mask);
        q.drawFormatBits(mask);
        return q.modules;
    }

    // =====================================================================
    // Ёмкость
    // =====================================================================

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            r -= (25 * align - 10) * align - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    // =====================================================================
    // Служебные узоры
    // =====================================================================

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        drawFormatBits(0);   // место под формат (перерисуется после маски)
        drawVersion();
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);   // всегда тёмный модуль
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            set(a, c, b);
            set(c, a, b);
        }
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // =====================================================================
    // Данные: Рид — Соломон, перемежение, укладка змейкой
    // =====================================================================

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[] div = rsDivisor(eccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            byte[] ecc = rsRemainder(dat, div);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;   // вертикальная полоса синхронизации
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= inv & !isFunction[y][x];
            }
        }
    }

    // =====================================================================
    // Штрафы маски
    // =====================================================================

    private int penalty() {
        int result = 0;
        int[] hist = new int[7];
        for (int pass = 0; pass < 2; pass++) {       // 0 — строки, 1 — столбцы
            for (int a = 0; a < size; a++) {
                boolean runColor = false;
                int run = 0;
                java.util.Arrays.fill(hist, 0);
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? modules[a][b] : modules[b][a];
                    if (c == runColor) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        addHistory(run, hist);
                        if (!runColor) result += finderLike(hist) * 40;
                        runColor = c;
                        run = 1;
                    }
                }
                if (runColor) {
                    addHistory(run, hist);
                    run = 0;
                }
                addHistory(run + size, hist);
                result += finderLike(hist) * 40;
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean c = modules[y][x];
                if (c) dark++;
                if (x < size - 1 && y < size - 1 && c == modules[y][x + 1]
                        && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + k * 10;
    }

    private void addHistory(int run, int[] hist) {
        if (hist[0] == 0) run += size;   // светлая рамка слева
        System.arraycopy(hist, 0, hist, 1, hist.length - 1);
        hist[0] = run;
    }

    private static int finderLike(int[] h) {
        int n = h[1];
        boolean core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    }

    /** Буфер бит фиксированной ёмкости. */
    private static final class Bits {
        final boolean[] b;
        int len;

        Bits(int capacity) {
            b = new boolean[capacity];
        }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) b[len++] = ((val >>> i) & 1) != 0;
        }

        boolean get(int i) {
            return b[i];
        }
    }
}
