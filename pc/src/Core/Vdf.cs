using System;
using System.Collections.Generic;
using System.Text;

namespace EQ
{
    /// <summary>Текстовый формат Steam (KeyValues / VDF): "ключ" "значение" и "ключ" { … }.</summary>
    sealed class Vdf
    {
        public readonly Dictionary<string, string> Values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public readonly Dictionary<string, Vdf> Children = new Dictionary<string, Vdf>(StringComparer.OrdinalIgnoreCase);

        public string this[string key]
        {
            get { return Values.TryGetValue(key, out var v) ? v : null; }
        }

        public Vdf Child(string key)
        {
            return Children.TryGetValue(key, out var v) ? v : null;
        }

        /// <summary>Путь вида "UserLocalConfigStore/Software/Valve/Steam/apps".</summary>
        public Vdf Path(string path)
        {
            var node = this;
            foreach (var part in path.Split('/'))
            {
                node = node?.Child(part);
            }
            return node;
        }

        public static Vdf Parse(string text)
        {
            int i = 0;
            var root = new Vdf();
            ParseInto(root, text, ref i);
            return root;
        }

        static void ParseInto(Vdf node, string s, ref int i)
        {
            while (true)
            {
                var key = Token(s, ref i);
                if (key == null || key == "}")
                {
                    return;
                }
                var val = Token(s, ref i);
                if (val == null)
                {
                    return;
                }
                if (val == "{")
                {
                    var child = new Vdf();
                    ParseInto(child, s, ref i);
                    node.Children[key] = child;
                }
                else
                {
                    node.Values[key] = val;
                }
            }
        }

        /// <summary>Следующая строка в кавычках, «{» или «}»; null — конец текста.</summary>
        static string Token(string s, ref int i)
        {
            while (i < s.Length)
            {
                char c = s[i];
                if (char.IsWhiteSpace(c))
                {
                    i++;
                }
                else if (c == '/' && i + 1 < s.Length && s[i + 1] == '/')
                {
                    while (i < s.Length && s[i] != '\n')
                    {
                        i++;
                    }
                }
                else
                {
                    break;
                }
            }
            if (i >= s.Length)
            {
                return null;
            }
            if (s[i] == '{' || s[i] == '}')
            {
                return s[i++].ToString();
            }
            var sb = new StringBuilder();
            if (s[i] == '"')
            {
                i++;
                while (i < s.Length && s[i] != '"')
                {
                    if (s[i] == '\\' && i + 1 < s.Length)
                    {
                        i++;
                        sb.Append(s[i] == 'n' ? '\n' : s[i] == 't' ? '\t' : s[i]);
                    }
                    else
                    {
                        sb.Append(s[i]);
                    }
                    i++;
                }
                i++;
                return sb.ToString();
            }
            while (i < s.Length && !char.IsWhiteSpace(s[i]) && s[i] != '{' && s[i] != '}' && s[i] != '"')
            {
                sb.Append(s[i++]);
            }
            return sb.ToString();
        }
    }
}
