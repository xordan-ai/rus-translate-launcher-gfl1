# -*- coding: utf-8 -*-
"""
Girls' Frontline - Автоматический русификатор и лаунчер (Windows & Android)
Поддерживает как графический интерфейс (GUI), так и консольный режим (CLI).
"""

import os
import sys
import shutil

try:
    import UnityPy
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "UnityPy"])
    import UnityPy

VERSION = "1.0.0"

# --- СЛОВАРИ ПЕРЕВОДА ---

WEEKLY_RU = {
    "weekly-10000001": "Еженедельный квест: Обычные юниты",
    "weekly-20000001": "Уничтожьте в общей сложности 50 обычных юнитов",
    "weekly-10000002": "Еженедельный квест: Боссы",
    "weekly-20000002": "Уничтожьте 1 босса",
    "weekly-10000003": "Еженедельный квест: Победа",
    "weekly-20000003": "Одержите 8 побед с рангом S",
    "weekly-10000004": "Еженедельный квест: Исследование Серой Зоны",
    "weekly-20000004": "Захватите 15 узлов в Исследовании Серой Зоны",
    "weekly-10000005": "Еженедельный квест: Расширение арсенала",
    "weekly-20000005": "Произведите 8 любых единиц (T-Doll//c Снаряжение или Фея)",
    "weekly-10000006": "Еженедельный квест: Логистика",
    "weekly-20000006": "Завершите 10 логистических миссий",
    "weekly-10000007": "Еженедельный квест: Симуляция боя",
    "weekly-20000007": "Завершите 12 боевых симуляций или коалиционных учений",
    "weekly-10000008": "Еженедельный квест: Передовой лагерь",
    "weekly-20000008": "Проведите 5 экспедиций Передового лагеря",
    "weekly-10000009": "Еженедельный квест: Закупка снаряжения",
    "weekly-20000009": "Сделайте 1 крутку в гаче снабжения",
    "weekly-10000010": "Еженедельный квест: Эвакуация",
    "weekly-20000010": "Спасите 3 T-Doll в боевых миссиях"
}

TIPS_RU = {
    "tips_info-10000001": "Выберите <color=#e57006>Эшелон</color>//c чтобы открыть дополнительные боевые опции",
    "tips_info-10000002": "Выберите персонажа//c чтобы провести <color=#e57006>Замену</color>",
    "tips_info-10000003": "Настройте <color=#e57006>Боевой строй</color>",
    "tips_info-10000004": "Смените <color=#e57006>снаряжение куклы</color>",
    "tips_info-10000005": "Множество уникальных <color=#e57006>комнат</color>",
    "tips_info-10000006": "<color=#e57006>Наряжайте</color> любимых T-Doll",
    "tips_info-10000007": "Обустройте собственное общежитие",
    "tips_info-10000008": "Загляните в общежития других командиров",
    "tips_info-10000009": "<color=#e57006>Покупайте мебель</color>//c чтобы украсить общежитие",
    "tips_info-10000010": "Храните <color=#e57006>мебель</color> и <color=#e57006>подарки</color>"
}

AVG_RU = {
    "0-1-1": """()||<BIN>9</BIN><BGM>BGM_Empty</BGM>:Месяц назад, спорная территория в Секторе S09...
M4A1()<Speaker>M4A1</Speaker>||:Текущее время 05:59, небо чистое.
M16A1()<Speaker>M16A1</Speaker>||<黑屏1>:За работу, M4.
M4A1(0)<Speaker>M4A1</Speaker>||<BGM>BGM_Sneak</BGM><BIN>7</BIN><黑屏2><黑屏1>:Персика, на связи отряд AR.+Мы достигли координат V6J69-HJYRV, точки сбора А на территории Сангвис. Ветра нет, видимость отличная.+Повторяю цель операции——найти все исторические записи и данные исследований за подписью «Лайко» и доставить их в 16LAB.
SOPII(0)<Speaker>M4 SOPMOD II</Speaker>||<BIN>10</BIN><黑屏2>:И... с чего начнем?
AR15(0)<Speaker>ST AR-15</Speaker>||:Сначала захватим командный пункт и взломаем базу данных Сангвис, чтобы выгрузить информацию.
M4A1(0)<Speaker>M4A1</Speaker>||:Наша цель — вон тот командный пункт. Отряд AR, операцию начать.""",

    "0-1-2": """M4A1(0)<Speaker>M4A1</Speaker>||<BGM>BGM_Room</BGM><BIN>7</BIN>:Командный пункт успешно захвачен. Взламываю базу данных.
AR15(0)<Speaker>ST AR-15</Speaker>||:...Вы вообще задумывались о цели нашего задания на этот раз?
M16(0)<Speaker>M16A1</Speaker>;AR15(0)||:Есть что сказать, AR-15?
M16(0);AR15(2)<Speaker>ST AR-15</Speaker>||:Персика изначально просила просто «собрать исследовательские данные».+Но теперь мы зашли глубоко на территорию Сангвис в поисках вещей, оставшихся после какого-то покойника...
M4A1(0)<Speaker>M4A1</Speaker>;AR15(2)||:Мы тактические T-Doll, наше дело — выполнять приказы.
M4A1(0);AR15(0)<Speaker>ST AR-15</Speaker>||:Да знаю... Просто любопытно.
SOPII(0)<Speaker>M4 SOPMOD II</Speaker>;AR15(0)||:Эй, я пробилась в базу данных! Быстро сюда, взгляните!
M16(0)<Speaker>M16A1</Speaker>;AR15(0)||:...Ого?+В этом здании, обозначенном как Укрытие 3, находится мейнфрейм, на котором Лайко хранил данные при жизни.
M16(0);M4A1(0)<Speaker>M4A1</Speaker>||:Отлично. Цель подтверждена. Продолжаем операцию."""
}

KNOWN_PATHS = [
    os.path.expandvars(r"%LOCALAPPDATA%\GirlsFrontline\files\Android\New"),
    os.path.expandvars(r"%USERPROFILE%\AppData\LocalLow\Sunborn\GirlsFrontline\files\Android\New"),
    r"C:\LDPlayer\LDPlayer9\vms\leidian0\Android\data\com.sunborn.girlsfrontline.en\files\Android\New",
    r"D:\LDPlayer\LDPlayer9\vms\leidian0\Android\data\com.sunborn.girlsfrontline.en\files\Android\New",
    r"C:\Program Files\Netease\MuMuPlayerGlobal-12.0\vms\MuMuPlayerGlobal-12.0-0\Android\data\com.sunborn.girlsfrontline.en\files\Android\New",
]

def patch_files(source_dir, target_dir, log_func=print):
    files = os.listdir(source_dir)
    table_files = [f for f in files if 'assettexttable.ab' in f]
    avg_files = [f for f in files if 'assettextavg.ab' in f]

    if not table_files and not avg_files:
        raise FileNotFoundError("В указанной папке не найдены файлы assettexttable.ab или assettextavg.ab!")

    patched_count = 0

    # 1. Patch tables
    for tf in table_files:
        src_path = os.path.join(source_dir, tf)
        dst_path = os.path.join(target_dir, tf)

        if source_dir == target_dir:
            bak_path = dst_path + ".bak"
            if not os.path.exists(bak_path):
                shutil.copy2(dst_path, bak_path)
                log_func(f"Создан бэкап: {tf}.bak")

        log_func(f"Применяю перевод таблиц к: {tf}...")
        env = UnityPy.load(src_path)
        for obj in env.objects:
            if obj.type.name == 'TextAsset':
                data = obj.read()
                if data.m_Name == 'weekly':
                    lines = data.m_Script.split('\n')
                    new_lines = [f"{l.split(',', 1)[0]},{WEEKLY_RU.get(l.split(',', 1)[0].strip(), l.split(',', 1)[1] if ',' in l else '')}" if l.split(',', 1)[0].strip() in WEEKLY_RU else l for l in lines]
                    data.m_Script = '\n'.join(new_lines)
                    data.save()
                elif data.m_Name == 'tips_info':
                    lines = data.m_Script.split('\n')
                    new_lines = [f"{l.split(',', 1)[0]},{TIPS_RU.get(l.split(',', 1)[0].strip(), l.split(',', 1)[1] if ',' in l else '')}" if l.split(',', 1)[0].strip() in TIPS_RU else l for l in lines]
                    data.m_Script = '\n'.join(new_lines)
                    data.save()

        with open(dst_path, 'wb') as f:
            f.write(env.file.save(packer='lz4'))
        log_func(f"Таблицы успешно пропатчены: {tf}")
        patched_count += 1

    # 2. Patch AVG story dialogues
    for af in avg_files:
        src_path = os.path.join(source_dir, af)
        dst_path = os.path.join(target_dir, af)

        if source_dir == target_dir:
            bak_path = dst_path + ".bak"
            if not os.path.exists(bak_path):
                shutil.copy2(dst_path, bak_path)
                log_func(f"Создан бэкап: {af}.bak")

        log_func(f"Применяю перевод диалогов (пролог) к: {af}...")
        env = UnityPy.load(src_path)
        for obj in env.objects:
            if obj.type.name == 'TextAsset':
                data = obj.read()
                if data.m_Name in AVG_RU:
                    data.m_Script = AVG_RU[data.m_Name]
                    data.save()

        with open(dst_path, 'wb') as f:
            f.write(env.file.save(packer='lz4'))
        log_func(f"Диалоги успешно пропатчены: {af}")
        patched_count += 1

    return patched_count

def run_cli():
    print("=" * 60)
    print(f"GIRLS' FRONTLINE - РУСИФИКАТОР v{VERSION} (CLI Mode)")
    print("=" * 60)
    default_dir = os.path.dirname(os.path.abspath(__file__))
    print(f"Текущая папка: {default_dir}")
    print("1. Пропатчить файлы прямо в этой папке")
    print("2. Указать путь к папке игры")
    print("3. Выход")
    choice = input("Выберите действие (1-3) [1]: ").strip() or "1"
    if choice == "1":
        patch_dir = default_dir
    elif choice == "2":
        patch_dir = input("Введите путь к папке (.../Android/New): ").strip()
    else:
        print("Выход.")
        return

    try:
        count = patch_files(patch_dir, patch_dir)
        print(f"\n[УСПЕХ] Готово! Пропатчено файлов: {count}")
    except Exception as e:
        print(f"\n[ОШИБКА] {e}")

def run_gui():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    root = tk.Tk()
    root.title(f"Girls' Frontline - Русификатор v{VERSION}")
    root.geometry("680x480")
    root.resizable(False, False)

    style = ttk.Style()
    style.theme_use('clam')

    header = tk.Frame(root, bg="#1e1e24", height=70)
    header.pack(fill="x")
    title_label = tk.Label(header, text="GIRLS' FRONTLINE: РУСИФИКАТОР", font=("Helvetica", 14, "bold"), fg="#e57006", bg="#1e1e24")
    title_label.pack(pady=8)
    subtitle = tk.Label(header, text="Локализация интерфейса, диалогов и подсказок (Windows / Android)", font=("Helvetica", 9), fg="#cccccc", bg="#1e1e24")
    subtitle.pack()

    content = tk.Frame(root, padx=20, pady=15)
    content.pack(fill="both", expand=True)

    path_label = tk.Label(content, text="Папка с игровыми файлами кэша (.../files/Android/New):", font=("Helvetica", 10, "bold"))
    path_label.pack(anchor="w", pady=(0, 5))

    path_frame = tk.Frame(content)
    path_frame.pack(fill="x", pady=(0, 12))

    path_entry = ttk.Entry(path_frame, font=("Helvetica", 9))
    path_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

    def browse_folder():
        folder = filedialog.askdirectory(title="Выберите папку с файлами игры (New)")
        if folder:
            path_entry.delete(0, "end")
            path_entry.insert(0, folder)

    browse_btn = ttk.Button(path_frame, text="Обзор...", command=browse_folder)
    browse_btn.pack(side="right")

    status_frame = tk.LabelFrame(content, text="Журнал операций", font=("Helvetica", 9))
    status_frame.pack(fill="both", expand=True, pady=(0, 15))

    log_text = tk.Text(status_frame, height=10, font=("Consolas", 9), bg="#f8f9fa")
    log_text.pack(fill="both", expand=True, padx=5, pady=5)

    def log_msg(msg):
        log_text.insert("end", msg + "\n")
        log_text.see("end")

    def auto_detect():
        for p in KNOWN_PATHS:
            if os.path.exists(p):
                path_entry.insert(0, p)
                log_msg(f"Автоматически обнаружена папка игры: {p}")
                return
        log_msg("Укажите папку с кэшем игры через кнопку 'Обзор'.")

    def install_action():
        p = path_entry.get().strip()
        if not p or not os.path.exists(p):
            messagebox.showerror("Ошибка", "Указанная папка не существует!")
            return
        try:
            cnt = patch_files(p, p, log_func=log_msg)
            messagebox.showinfo("Успех", f"Русификатор успешно применен! Пропатчено файлов: {cnt}")
        except Exception as e:
            messagebox.showerror("Ошибка", f"Не удалось применить патч: {e}")

    def export_action():
        p = path_entry.get().strip()
        if not p or not os.path.exists(p):
            messagebox.showerror("Ошибка", "Сначала укажите папку с оригинальными файлами!")
            return
        out_f = filedialog.askdirectory(title="Куда сохранить файлы для Android?")
        if not out_f:
            return
        try:
            cnt = patch_files(p, out_f, log_func=log_msg)
            messagebox.showinfo("Успех", f"Файлы для Android сохранены в:\n{out_f}\n\nСкопируйте их на телефон в папку:\nAndroid/data/com.sunborn.girlsfrontline.en/files/Android/New/")
        except Exception as e:
            messagebox.showerror("Ошибка", f"Ошибка: {e}")

    btn_frame = tk.Frame(content)
    btn_frame.pack(fill="x")

    install_btn = tk.Button(btn_frame, text="УСТАНОВИТЬ В ИГРУ (ПК / Эмулятор)", font=("Helvetica", 10, "bold"),
                            bg="#e57006", fg="white", activebackground="#cc6100", activeforeground="white",
                            padx=12, pady=7, command=install_action)
    install_btn.pack(side="left", padx=(0, 8))

    export_btn = ttk.Button(btn_frame, text="Собрать файлы для Android", command=export_action)
    export_btn.pack(side="left", padx=(0, 8))

    exit_btn = ttk.Button(btn_frame, text="Выход", command=root.quit)
    exit_btn.pack(side="right")

    auto_detect()
    root.mainloop()

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--cli":
        run_cli()
    else:
        try:
            run_gui()
        except (ImportError, Exception):
            run_cli()
