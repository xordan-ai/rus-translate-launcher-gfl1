#!/usr/bin/env python3
"""
gfl_ru_tool.py — конвейер русификации Girls' Frontline EN (Unity, Android).

Требуется:  pip install UnityPy

Рабочая папка `files/` — это копия Android/data/com.sunborn.girlsfrontline.en/files
(внутри должна лежать папка Android с .ab-файлами).

Команды:
  scan     <files_dir>                      что найдено в бандлах (TextAsset / Font)
  strings  <files_dir> -o strings.tsv       собрать строки для перевода (повторный запуск
                                            сохраняет уже сделанный перевод)
  verify   strings.tsv                      проверить теги/плейсхолдеры/ключи в переводе
  build    <files_dir> strings.tsv -o patch.zip [--font X.ttf] [--version N] [--key K]
                                            собрать патч (zip с manifest.json) для лаунчера

Формат strings.tsv: «исходная строка<TAB>перевод». Единица перевода — целая строка файла,
поэтому инструмент не зависит от конкретного формата таблиц. Табы и обратные слэши внутри
строк экранируются как \\t и \\\\. Пустой перевод = строка остаётся оригинальной.

Положите готовый patch.zip в app/src/main/assets/patch.zip, и APK будет со встроенным переводом.
"""
import argparse
import json
import os
import re
import sys
import zipfile
from collections import Counter

LATIN_WORD = re.compile(r"[A-Za-z]{3,}")
# теги и плейсхолдеры, которые нельзя ломать при переводе
PROTECTED = re.compile(
    r"<[^<>\n]{1,80}>|\{[^{}\n]{0,40}\}|%[0-9.\-+# ]*[sdfxX]|\\n|\[[0-9A-Fa-f]{6}\]|\[-\]|\[/?[a-zA-Z]{1,3}\]"
)
# ключ строки табличного формата (должен содержать цифру), например 10001, или item_12,
KEY = re.compile(r"^(?=[\w.\-]*\d)[\w.\-]{1,40}(?=[,\t|=:;])")
BUNDLE_EXT = {".ab", ".unity3d", ".bundle", ".assets"}


# ---------- чистые функции (проверяются тестами без UnityPy) ----------

def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r")


def unesc(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s) and s[i + 1] in "tr\\":
            out.append({"t": "\t", "r": "\r", "\\": "\\"}[s[i + 1]])
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_lines(text):
    """-> список (тело, окончание '\\r' или ''); соединяется обратно через '\\n'."""
    res = []
    for piece in text.split("\n"):
        if piece.endswith("\r"):
            res.append((piece[:-1], "\r"))
        else:
            res.append((piece, ""))
    return res


def translatable(line):
    return bool(LATIN_WORD.search(line))


def translate_text(text, table):
    bom = text.startswith("\ufeff")
    if bom:
        text = text[1:]
    out, n = [], 0
    for body, end in split_lines(text):
        t = table.get(body)
        if t:
            out.append(t + end)
            n += 1
        else:
            out.append(body + end)
    return ("\ufeff" if bom else "") + "\n".join(out), n


def check_pair(src, dst):
    problems = []
    if Counter(PROTECTED.findall(src)) != Counter(PROTECTED.findall(dst)):
        problems.append("теги/плейсхолдеры не совпадают")
    ks, kd = KEY.match(src), KEY.match(dst)
    if (ks.group(0) if ks else None) != (kd.group(0) if kd else None):
        problems.append("изменён ключ в начале строки")
    if "\t" in dst or "\n" in dst:
        problems.append("в переводе табуляция или перенос строки")
    return problems


def load_tsv(path):
    table = {}
    if not os.path.isfile(path):
        return table
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if not ln:
                continue
            src, _, dst = ln.partition("\t")
            table[unesc(src)] = unesc(dst)
    return table


def save_tsv(path, table):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for src, dst in table.items():
            f.write(esc(src) + "\t" + esc(dst) + "\n")


# ---------- работа с Unity ----------

def unity():
    try:
        import UnityPy
    except ImportError:
        sys.exit("Установите UnityPy:  pip install UnityPy")
    return UnityPy


def candidates(root):
    for d, _, files in os.walk(root):
        for name in sorted(files):
            p = os.path.join(d, name)
            ext = os.path.splitext(name)[1].lower()
            try:
                with open(p, "rb") as f:
                    magic = f.read(5)
            except OSError:
                continue
            if ext in BUNDLE_EXT or magic == b"Unity":
                yield p


def load_env(path):
    try:
        return unity().load(path)
    except Exception as e:  # не Unity-файл или зашифрован
        print(f"  пропуск {path}: {e}", file=sys.stderr)
        return None


def asset_text(data):
    raw = data.m_Script
    raw = raw.encode("utf-8", "surrogateescape") if isinstance(raw, str) else bytes(raw)
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None


def rel(root, p):
    return os.path.relpath(p, root).replace(os.sep, "/")


def set_key(args):
    if getattr(args, "key", None):
        unity().set_assetbundle_decrypt_key(args.key)


def cmd_scan(args):
    set_key(args)
    for p in candidates(args.files_dir):
        env = load_env(p)
        if env is None:
            continue
        texts, fonts, other = [], 0, 0
        for obj in env.objects:
            t = obj.type.name
            if t == "TextAsset":
                texts.append(obj.read().m_Name)
            elif t == "Font":
                fonts += 1
            else:
                other += 1
        if texts or fonts:
            print(f"{rel(args.files_dir, p)}: TextAsset={len(texts)} Font={fonts} прочее={other}")
            print("   примеры:", ", ".join(texts[:5]))


def cmd_strings(args):
    set_key(args)
    table = load_tsv(args.out)
    found = 0
    for p in candidates(args.files_dir):
        env = load_env(p)
        if env is None:
            continue
        for obj in env.objects:
            if obj.type.name != "TextAsset":
                continue
            text = asset_text(obj.read())
            if text is None:
                continue
            for body, _ in split_lines(text.lstrip("\ufeff")):
                if translatable(body):
                    found += 1
                    table.setdefault(body, "")
    save_tsv(args.out, table)
    done = sum(1 for v in table.values() if v)
    print(f"Строк в файле: {len(table)}, переведено: {done}, встреч в бандлах: {found}")


def cmd_verify(args):
    table = load_tsv(args.tsv)
    bad = 0
    for src, dst in table.items():
        if not dst:
            continue
        for pr in check_pair(src, dst):
            bad += 1
            print(f"[{pr}]\n  EN: {src}\n  RU: {dst}")
    left = sum(1 for v in table.values() if not v)
    print(f"Проблем: {bad}, не переведено: {left} из {len(table)}")
    return 1 if bad else 0


def cmd_build(args):
    table = {k: v for k, v in load_tsv(args.tsv).items() if v}
    if not table and not args.font:
        sys.exit("В strings.tsv нет переведённых строк")
    set_key(args)
    font_bytes = open(args.font, "rb").read() if args.font else None
    files, total, fonts_n = [], 0, 0
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        for p in candidates(args.files_dir):
            env = load_env(p)
            if env is None:
                continue
            changed = 0
            for obj in env.objects:
                t = obj.type.name
                if t == "TextAsset":
                    data = obj.read()
                    text = asset_text(data)
                    if text is None:
                        continue
                    new, n = translate_text(text, table)
                    if n:
                        data.m_Script = new
                        data.save()
                        changed += n
                        total += n
                elif t == "Font" and font_bytes:
                    data = obj.read()
                    data.m_FontData = font_bytes
                    data.save()
                    changed += 1
                    fonts_n += 1
            if not changed:
                continue
            try:
                blob = env.file.save(packer="lz4")
            except TypeError:
                blob = env.file.save()
            r = rel(args.files_dir, p)
            z.writestr(r, blob)
            files.append({"src": r, "dst": r})
            print(f"  {r}: изменено {changed}")
        manifest = {"version": args.version, "files": files}
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"Готово: {args.out} — файлов {len(files)}, строк {total}, шрифтов {fonts_n}")
    if font_bytes and not fonts_n:
        print("ВНИМАНИЕ: объектов Font не найдено. Если игра на TextMeshPro, нужен font asset с кириллицей.")
    if not files:
        print("ВНИМАНИЕ: ничего не изменено — проверьте путь, ключ шифрования и тексты в strings.tsv.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    a = sp.add_parser("scan"); a.add_argument("files_dir"); a.add_argument("--key")
    a.set_defaults(fn=cmd_scan)
    a = sp.add_parser("strings"); a.add_argument("files_dir"); a.add_argument("-o", "--out", default="strings.tsv"); a.add_argument("--key")
    a.set_defaults(fn=cmd_strings)
    a = sp.add_parser("verify"); a.add_argument("tsv")
    a.set_defaults(fn=cmd_verify)
    a = sp.add_parser("build"); a.add_argument("files_dir"); a.add_argument("tsv")
    a.add_argument("-o", "--out", default="patch.zip"); a.add_argument("--font"); a.add_argument("--key")
    a.add_argument("--version", type=int, default=1)
    a.set_defaults(fn=cmd_build)
    args = ap.parse_args()
    sys.exit(args.fn(args) or 0)


if __name__ == "__main__":
    main()
