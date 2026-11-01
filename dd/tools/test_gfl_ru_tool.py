"""Тесты чистой логики (без UnityPy):  python tools/test_gfl_ru_tool.py"""
import os, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
import gfl_ru_tool as g

# экранирование
for s in ["a\tb", "x\\ny", "\\\\", "end\\", "plain", "back\\tslash\\\\t"]:
    assert g.unesc(g.esc(s)) == s, s

# перевод текста, CRLF, BOM, последняя строка без \n
src = "\ufeff1001,Hello <color=red>world</color>\r\n1002,Skip me\r\n1003,Last"
tab = {"1001,Hello <color=red>world</color>": "1001,Привет <color=red>мир</color>", "1003,Last": "1003,Конец"}
out, n = g.translate_text(src, tab)
assert n == 2 and out == "\ufeff1001,Привет <color=red>мир</color>\r\n1002,Skip me\r\n1003,Конец", repr(out)

# повторное применение к уже переведённому ничего не ломает
out2, n2 = g.translate_text(out, tab)
assert n2 == 0 and out2 == out

# проверки перевода
assert g.check_pair("1,Hi {0} <b>x</b>", "1,Привет {0} <b>х</b>") == []
assert "теги/плейсхолдеры не совпадают" in g.check_pair("1,Hi {0}", "1,Привет")
assert "изменён ключ в начале строки" in g.check_pair("1001,Hi there", "1002,Привет")
assert g.check_pair("Hello, world", "Привет, мир") == []   # не-ключ без цифры
assert g.check_pair("%d items", "%d предметов") == []
assert g.check_pair("a\\nb text", "а\\nб") == []

# tsv туда-обратно
with tempfile.TemporaryDirectory() as d:
    p = os.path.join(d, "s.tsv")
    t = {"a\tb word": "", "c\\d line": "ц\\д", "Plain text": "Текст"}
    g.save_tsv(p, t)
    assert g.load_tsv(p) == t

assert g.translatable("Start battle") and not g.translatable("10001,,,0")
print("OK")
