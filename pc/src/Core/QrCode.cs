using System;
using System.Text;

namespace EQ
{
    /// <summary>
    /// Свой QR-генератор без библиотек (как на Android): байтовый режим, коррекция M, версии 1–10 (до 213 байт),
    /// маска выбирается по штрафам стандарта ISO/IEC 18004. Алгоритм по мотивам открытого генератора Nayuki (MIT).
    /// </summary>
    sealed class QrCode
    {
        public const int MaxVersion = 10;

        // коррекция M: байт коррекции на блок и число блоков, по версиям 1–10
        static readonly int[] EccPerBlock = { -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
        static readonly int[] Blocks = { -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };
        const int FormatM = 0;

        readonly int version;
        readonly int size;
        readonly bool[,] modules;
        readonly bool[,] isFunction;

        QrCode(int version)
        {
            this.version = version;
            size = version * 4 + 17;
            modules = new bool[size, size];
            isFunction = new bool[size, size];
        }

        /// <summary>Матрица [y, x] (true — тёмный модуль) или null, если текст не влезает в версию 10.</summary>
        public static bool[,] Encode(string text)
        {
            return Encode(Encoding.UTF8.GetBytes(text), -1);
        }

        public static bool[,] Encode(byte[] data, int mask)
        {
            int ver = 1;
            for (; ver <= MaxVersion; ver++)
            {
                int cc = ver < 10 ? 8 : 16;
                if (4 + cc + data.Length * 8 <= DataCodewords(ver) * 8)
                {
                    break;
                }
            }
            if (ver > MaxVersion)
            {
                return null;
            }
            int ccBits = ver < 10 ? 8 : 16;
            int capacity = DataCodewords(ver) * 8;

            // поток бит: режим 0100 (байты), длина, данные, терминатор, выравнивание, заполнители
            var bb = new Bits(capacity);
            bb.Append(4, 4);
            bb.Append(data.Length, ccBits);
            foreach (var b in data)
            {
                bb.Append(b, 8);
            }
            bb.Append(0, Math.Min(4, capacity - bb.Len));
            bb.Append(0, (8 - bb.Len % 8) % 8);
            for (int pad = 0xEC; bb.Len < capacity; pad ^= 0xEC ^ 0x11)
            {
                bb.Append(pad, 8);
            }
            var codewords = new byte[bb.Len / 8];
            for (int i = 0; i < bb.Len; i++)
            {
                if (bb.B[i])
                {
                    codewords[i >> 3] |= (byte)(1 << (7 - (i & 7)));
                }
            }

            var q = new QrCode(ver);
            q.DrawFunctionPatterns();
            q.DrawCodewords(q.AddEccAndInterleave(codewords));
            if (mask < 0)
            {
                int best = 0, bestScore = int.MaxValue;
                for (int m = 0; m < 8; m++)
                {
                    q.ApplyMask(m);
                    q.DrawFormatBits(m);
                    int score = q.Penalty();
                    if (score < bestScore)
                    {
                        bestScore = score;
                        best = m;
                    }
                    q.ApplyMask(m);   // XOR — второй раз снимает маску
                }
                mask = best;
            }
            q.ApplyMask(mask);
            q.DrawFormatBits(mask);
            return q.modules;
        }

        static int RawModules(int ver)
        {
            int r = (16 * ver + 128) * ver + 64;
            if (ver >= 2)
            {
                int align = ver / 7 + 2;
                r -= (25 * align - 10) * align - 55;
                if (ver >= 7)
                {
                    r -= 36;
                }
            }
            return r;
        }

        static int DataCodewords(int ver)
        {
            return RawModules(ver) / 8 - EccPerBlock[ver] * Blocks[ver];
        }

        void Set(int x, int y, bool dark)
        {
            modules[y, x] = dark;
            isFunction[y, x] = true;
        }

        void DrawFunctionPatterns()
        {
            for (int i = 0; i < size; i++)
            {
                Set(6, i, i % 2 == 0);
                Set(i, 6, i % 2 == 0);
            }
            Finder(3, 3);
            Finder(size - 4, 3);
            Finder(3, size - 4);
            var pos = AlignmentPositions();
            int n = pos.Length;
            for (int i = 0; i < n; i++)
            {
                for (int j = 0; j < n; j++)
                {
                    if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)
                    {
                        continue;
                    }
                    for (int dy = -2; dy <= 2; dy++)
                    {
                        for (int dx = -2; dx <= 2; dx++)
                        {
                            Set(pos[i] + dx, pos[j] + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
                        }
                    }
                }
            }
            DrawFormatBits(0);
            DrawVersion();
        }

        void Finder(int x, int y)
        {
            for (int dy = -4; dy <= 4; dy++)
            {
                for (int dx = -4; dx <= 4; dx++)
                {
                    int dist = Math.Max(Math.Abs(dx), Math.Abs(dy));
                    int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && xx < size && yy >= 0 && yy < size)
                    {
                        Set(xx, yy, dist != 2 && dist != 4);
                    }
                }
            }
        }

        int[] AlignmentPositions()
        {
            if (version == 1)
            {
                return new int[0];
            }
            int n = version / 7 + 2;
            int step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
            var r = new int[n];
            r[0] = 6;
            for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step)
            {
                r[i] = p;
            }
            return r;
        }

        void DrawFormatBits(int mask)
        {
            int data = FormatM << 3 | mask;
            int rem = data;
            for (int i = 0; i < 10; i++)
            {
                rem = (rem << 1) ^ ((rem >> 9) * 0x537);
            }
            int bits = (data << 10 | rem) ^ 0x5412;
            for (int i = 0; i <= 5; i++)
            {
                Set(8, i, Bit(bits, i));
            }
            Set(8, 7, Bit(bits, 6));
            Set(8, 8, Bit(bits, 7));
            Set(7, 8, Bit(bits, 8));
            for (int i = 9; i < 15; i++)
            {
                Set(14 - i, 8, Bit(bits, i));
            }
            for (int i = 0; i < 8; i++)
            {
                Set(size - 1 - i, 8, Bit(bits, i));
            }
            for (int i = 8; i < 15; i++)
            {
                Set(8, size - 15 + i, Bit(bits, i));
            }
            Set(8, size - 8, true);
        }

        void DrawVersion()
        {
            if (version < 7)
            {
                return;
            }
            int rem = version;
            for (int i = 0; i < 12; i++)
            {
                rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
            }
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++)
            {
                bool b = Bit(bits, i);
                int a = size - 11 + i % 3, c = i / 3;
                Set(a, c, b);
                Set(c, a, b);
            }
        }

        static bool Bit(int x, int i)
        {
            return ((x >> i) & 1) != 0;
        }

        byte[] AddEccAndInterleave(byte[] data)
        {
            int numBlocks = Blocks[version];
            int eccLen = EccPerBlock[version];
            int raw = RawModules(version) / 8;
            int numShort = numBlocks - raw % numBlocks;
            int shortLen = raw / numBlocks;
            var div = RsDivisor(eccLen);
            var blocks = new byte[numBlocks][];
            for (int i = 0, k = 0; i < numBlocks; i++)
            {
                int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
                var dat = new byte[datLen];
                Array.Copy(data, k, dat, 0, datLen);
                k += datLen;
                var block = new byte[shortLen + 1];
                Array.Copy(dat, 0, block, 0, datLen);
                var ecc = RsRemainder(dat, div);
                Array.Copy(ecc, 0, block, block.Length - eccLen, eccLen);
                blocks[i] = block;
            }
            var result = new byte[raw];
            int o = 0;
            for (int i = 0; i < blocks[0].Length; i++)
            {
                for (int j = 0; j < numBlocks; j++)
                {
                    if (i != shortLen - eccLen || j >= numShort)
                    {
                        result[o++] = blocks[j][i];
                    }
                }
            }
            return result;
        }

        static byte[] RsDivisor(int degree)
        {
            var r = new byte[degree];
            r[degree - 1] = 1;
            int root = 1;
            for (int i = 0; i < degree; i++)
            {
                for (int j = 0; j < r.Length; j++)
                {
                    r[j] = (byte)GfMul(r[j], root);
                    if (j + 1 < r.Length)
                    {
                        r[j] ^= r[j + 1];
                    }
                }
                root = GfMul(root, 0x02);
            }
            return r;
        }

        static byte[] RsRemainder(byte[] data, byte[] div)
        {
            var r = new byte[div.Length];
            foreach (var b in data)
            {
                int factor = b ^ r[0];
                Array.Copy(r, 1, r, 0, r.Length - 1);
                r[r.Length - 1] = 0;
                for (int i = 0; i < r.Length; i++)
                {
                    r[i] ^= (byte)GfMul(div[i], factor);
                }
            }
            return r;
        }

        static int GfMul(int x, int y)
        {
            int z = 0;
            for (int i = 7; i >= 0; i--)
            {
                z = (z << 1) ^ ((z >> 7) * 0x11D);
                z ^= ((y >> i) & 1) * x;
            }
            return z;
        }

        void DrawCodewords(byte[] data)
        {
            int i = 0;
            for (int right = size - 1; right >= 1; right -= 2)
            {
                if (right == 6)
                {
                    right = 5;
                }
                for (int vert = 0; vert < size; vert++)
                {
                    for (int j = 0; j < 2; j++)
                    {
                        int x = right - j;
                        bool upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!isFunction[y, x] && i < data.Length * 8)
                        {
                            modules[y, x] = Bit(data[i >> 3], 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
        }

        void ApplyMask(int m)
        {
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    bool inv;
                    switch (m)
                    {
                        case 0: inv = (x + y) % 2 == 0; break;
                        case 1: inv = y % 2 == 0; break;
                        case 2: inv = x % 3 == 0; break;
                        case 3: inv = (x + y) % 3 == 0; break;
                        case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                        case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    modules[y, x] ^= inv & !isFunction[y, x];
                }
            }
        }

        int Penalty()
        {
            int result = 0;
            var hist = new int[7];
            for (int pass = 0; pass < 2; pass++)
            {
                for (int a = 0; a < size; a++)
                {
                    bool runColor = false;
                    int run = 0;
                    Array.Clear(hist, 0, hist.Length);
                    for (int b = 0; b < size; b++)
                    {
                        bool c = pass == 0 ? modules[a, b] : modules[b, a];
                        if (c == runColor)
                        {
                            run++;
                            if (run == 5)
                            {
                                result += 3;
                            }
                            else if (run > 5)
                            {
                                result++;
                            }
                        }
                        else
                        {
                            AddHistory(run, hist);
                            if (!runColor)
                            {
                                result += FinderLike(hist) * 40;
                            }
                            runColor = c;
                            run = 1;
                        }
                    }
                    if (runColor)
                    {
                        AddHistory(run, hist);
                        run = 0;
                    }
                    AddHistory(run + size, hist);
                    result += FinderLike(hist) * 40;
                }
            }
            int dark = 0;
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    bool c = modules[y, x];
                    if (c)
                    {
                        dark++;
                    }
                    if (x < size - 1 && y < size - 1 && c == modules[y, x + 1] && c == modules[y + 1, x] && c == modules[y + 1, x + 1])
                    {
                        result += 3;
                    }
                }
            }
            int total = size * size;
            int k = (Math.Abs(dark * 20 - total * 10) + total - 1) / total - 1;
            return result + k * 10;
        }

        void AddHistory(int run, int[] hist)
        {
            if (hist[0] == 0)
            {
                run += size;
            }
            Array.Copy(hist, 0, hist, 1, hist.Length - 1);
            hist[0] = run;
        }

        static int FinderLike(int[] h)
        {
            int n = h[1];
            bool core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
            return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
        }

        sealed class Bits
        {
            public readonly bool[] B;
            public int Len;

            public Bits(int capacity)
            {
                B = new bool[capacity];
            }

            public void Append(int val, int n)
            {
                for (int i = n - 1; i >= 0; i--)
                {
                    B[Len++] = ((val >> i) & 1) != 0;
                }
            }
        }
    }
}
