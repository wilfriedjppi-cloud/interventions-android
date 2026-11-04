#!/usr/bin/env python3
"""
Convertisseur schéma JSON  ->  fichier WinRelais (.xrs)

  convertisseur.exe                       -> interface graphique
  convertisseur.exe schema.json           -> écrit schema.xrs à côté
  convertisseur.exe schema.json -o x.xrs [-b bibliotheque.xrs]
  convertisseur.exe --types [-b biblio.xrs]   -> types de symboles et broches

Les coordonnées du JSON sont en millimètres, origine en haut à gauche du folio
(A4 paysage = 297 x 210).  La position d'un composant est celle de sa 1re broche.
Voir LISEZMOI.txt pour le format.
"""
import datetime
import json
import os
import sys

import winrelais as wr

VERSION = "1.0"

# Noms courts -> (famille, préfixe) de la bibliothèque
ALIAS = {
    "borne": ("borne - standard", "X"),
    "bobine": ("bobine", "K"),
    "bobine_tempo": ("--- m bobine", "T"),
    "contact": ("contact", "S"),
    "contact_inverseur": ("--- x contact", "KA"),
    "contact_tempo": ("contacteur - auxiliaire", "KT"),
    "contacteur_3p": ("--- x contact contacteur", "KM"),
    "disjoncteur_moteur": ("protection", "QF"),
    "sectionneur_pole": ("unifilaire - haute tension", "Q"),
    "fusible": ("fusible", "F"),
    "transfo": ("transformateur", "T"),
    "terre": ("terre", "G"),
    "voyant": ("signalisation et voyant", "H"),
    "voyant_bp": ("bp & commut & voyant", "HL"),
    "bouton": ("bp & commut & voyant", "SB"),
    "arret_urgence": ("bp & commut & voyant", "AU"),
    "moteur_3": ("moteur - triphasé", "M"),
    "moteur_mono": ("moteur - monophasé", "V"),
    "ventilateur": ("moteur - monophasé", "V"),
}

GRID = 200          # 2 mm : pas de la grille WinRelais


class ConversionError(Exception):
    pass


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def _u(v, snap=True):
    """mm -> 1/100 mm, calé sur la grille de 2 mm."""
    u = int(round(float(v) * 100))
    return int(round(u / GRID)) * GRID if snap else u


def types_disponibles(biblio=None):
    b = wr.Builder(biblio or resource("bibliotheque.xrs"))
    lines = []
    for (fam, pre), sym in sorted(b.templates.items()):
        alias = ", ".join(k for k, v in ALIAS.items() if v == (fam, pre)) or "-"
        lines.append("%-26s préfixe %-3s alias: %-34s broches: %s"
                     % (fam, pre, alias, " ".join(p[0].strip() for p in sym.pins) or "-"))
    return lines


def convert(data, biblio=None, snap=True):
    """data = dict JSON déjà chargé. Retourne (Doc, [avertissements])."""
    warns, errs = [], []
    if not isinstance(data, dict) or not data.get("folios"):
        raise ConversionError("JSON invalide : clé « folios » absente ou vide.")

    b = wr.Builder(
        biblio or resource("bibliotheque.xrs"),
        company=data.get("societe") or "",
        address=data.get("adresse") or "",
        drawer=data.get("dessinateur") or "",
        date=data.get("date") or datetime.date.today().strftime("%d/%m/%Y"),
        drawing_title=data.get("titre_dessin") or "",
    )
    U = lambda v: _u(v, snap)  # noqa: E731

    for fi, fol in enumerate(data["folios"]):
        b.add_folio(fol.get("titre", "Folio %d" % (fi + 1)))
        where = "folio %d" % (fi + 1)
        placed = {}                                  # id -> Symbol

        # ---- composants --------------------------------------------------
        for c in fol.get("composants", []):
            ref = c.get("ref", "?")
            cid = c.get("id", ref)
            if "famille" in c and "prefixe" in c:
                key = (c["famille"], c["prefixe"])
            else:
                key = ALIAS.get(c.get("type", ""))
            if key is None or key not in b.templates:
                errs.append("%s, composant %s : type %r inconnu (voir --types)"
                            % (where, ref, c.get("type", c.get("famille"))))
                continue
            if cid in placed:
                errs.append("%s : identifiant %r en double (ajoute « id »)" % (where, cid))
                continue
            try:
                x, y = U(c["x"]), U(c["y"])
            except (KeyError, ValueError):
                errs.append("%s, composant %s : x / y manquants ou invalides" % (where, ref))
                continue
            sym = b.place(key[0], key[1], fi, x, y, repere=ref)
            for i, r in enumerate(sym.texts):          # nettoie les textes du gabarit
                if i >= 1 and r[0] != "Valeur":
                    r[0], r[3] = "", "0"
            for row, txt in (c.get("textes") or {}).items():
                try:
                    sym.set_text(int(row), str(txt), True)
                except (ValueError, IndexError):
                    errs.append("%s, composant %s : ligne de texte %r invalide (0 à %d)"
                                % (where, ref, row, len(sym.texts) - 1))
            if c.get("broches"):
                sym.rename_pins(c["broches"])
            placed[cid] = sym
            if not (0 <= x <= 29700 and 0 <= y <= 21000):
                warns.append("%s, %s : hors du folio (x=%s, y=%s mm)" % (where, ref, c["x"], c["y"]))

        # ---- liaisons entre broches -------------------------------------------
        def endpoint(txt):
            cid, _, pin = str(txt).partition(":")
            if cid not in placed:
                raise KeyError("composant %r inconnu" % cid)
            return placed[cid], pin, placed[cid].pin(pin)

        for l in fol.get("liaisons", []):
            try:
                sa, pa, (x1, y1) = endpoint(l["de"])
                sb, pb, (x2, y2) = endpoint(l["vers"])
            except KeyError as e:
                errs.append("%s, liaison %s -> %s : %s" % (where, l.get("de"), l.get("vers"),
                                                           e.args[0]))
                continue
            kind = l.get("type", "commande")
            if kind not in wr.WIRE_STYLES:
                errs.append("%s : type de fil %r inconnu (%s)" % (where, kind,
                            ", ".join(wr.WIRE_STYLES)))
                continue
            pts = [(x1, y1)] + [(U(px), U(py)) for px, py in l.get("via", [])]
            if len(pts) == 1 and x1 != x2 and y1 != y2:
                pts.append((x1, y2))                    # coude en L
            pts.append((x2, y2))
            b.wire(fi, pts, kind)

        # ---- éléments libres --------------------------------------------------
        for w in fol.get("fils", []):
            kind = w.get("type", "commande")
            if kind not in wr.WIRE_STYLES:
                errs.append("%s : type de fil %r inconnu" % (where, kind))
                continue
            b.wire(fi, [(U(px), U(py)) for px, py in w["points"]], kind)
        for j in fol.get("jonctions", []):
            b.junction(fi, U(j[0]), U(j[1]))
        for r in fol.get("reperes", []):
            b.number(fi, U(r["x"]), U(r["y"]), str(r["texte"]))
        for t in fol.get("textes", []):
            b.text(fi, _u(t["x"], False), _u(t["y"], False), str(t["texte"]),
                   int(t.get("taille", 400)), bool(t.get("gras", False)),
                   int(t.get("angle", 0)))
        for c in fol.get("cadres", []):
            b.box(fi, U(c["x1"]), U(c["y1"]), U(c["x2"]), U(c["y2"]))

    if errs:
        raise ConversionError("\n".join(errs))
    return b.build(), warns


def convert_file(json_path, out_path=None, biblio=None, snap=True):
    with open(json_path, "r", encoding="utf-8-sig") as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            raise ConversionError("JSON illisible : %s" % e)
    doc, warns = convert(data, biblio, snap)
    out_path = out_path or os.path.splitext(json_path)[0] + ".xrs"
    try:
        doc.save(out_path)
    except UnicodeEncodeError as e:
        raise ConversionError("Caractère non supporté par le format (Latin-1) : %s" % e)
    nsym = len(doc.symbols)
    return out_path, "%d folio(s), %d symbole(s), %d fil(s)" % (
        len(doc.folios), nsym, len(doc.tail("FilBus").rows)), warns


# --------------------------------------------------------------------------
def gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext, ttk

    root = tk.Tk()
    root.title("Convertisseur schéma → WinRelais (.xrs)  v" + VERSION)
    root.geometry("760x520")
    pad = dict(padx=8, pady=4)
    v_json, v_out, v_lib = tk.StringVar(), tk.StringVar(), tk.StringVar(value="(intégrée)")
    v_snap = tk.BooleanVar(value=True)

    def row(r, label, var, cmd):
        ttk.Label(root, text=label).grid(row=r, column=0, sticky="w", **pad)
        ttk.Entry(root, textvariable=var, width=70).grid(row=r, column=1, sticky="we", **pad)
        ttk.Button(root, text="Parcourir…", command=cmd).grid(row=r, column=2, **pad)

    def pick_json():
        p = filedialog.askopenfilename(title="Schéma JSON",
                                       filetypes=[("Schéma JSON", "*.json"), ("Tous", "*.*")])
        if p:
            v_json.set(p)
            v_out.set(os.path.splitext(p)[0] + ".xrs")

    def pick_out():
        p = filedialog.asksaveasfilename(defaultextension=".xrs",
                                         filetypes=[("WinRelais", "*.xrs")])
        if p:
            v_out.set(p)

    def pick_lib():
        p = filedialog.askopenfilename(title="Fichier .xrs contenant les symboles",
                                       filetypes=[("WinRelais", "*.xrs")])
        if p:
            v_lib.set(p)

    row(0, "Schéma (JSON)", v_json, pick_json)
    row(1, "Fichier WinRelais", v_out, pick_out)
    row(2, "Bibliothèque de symboles", v_lib, pick_lib)
    ttk.Checkbutton(root, text="Caler les positions sur la grille de 2 mm",
                    variable=v_snap).grid(row=3, column=1, sticky="w", **pad)

    log = scrolledtext.ScrolledText(root, height=18, font=("Consolas", 9))
    log.grid(row=5, column=0, columnspan=3, sticky="nsew", **pad)
    root.rowconfigure(5, weight=1)
    root.columnconfigure(1, weight=1)

    def say(t):
        log.insert("end", t + "\n")
        log.see("end")

    def lib():
        return None if v_lib.get().startswith("(") else v_lib.get()

    def do_convert():
        log.delete("1.0", "end")
        if not v_json.get():
            messagebox.showwarning("Convertisseur", "Choisis d'abord un fichier JSON.")
            return
        try:
            out, resume, warns = convert_file(v_json.get(), v_out.get() or None,
                                              lib(), v_snap.get())
        except ConversionError as e:
            say("ERREUR :\n" + str(e))
            return
        except Exception as e:  # noqa: BLE001
            say("ERREUR INATTENDUE : %r" % e)
            return
        for w in warns:
            say("Attention : " + w)
        say("OK -> %s\n%s" % (out, resume))

    def show_types():
        log.delete("1.0", "end")
        try:
            for l in types_disponibles(lib()):
                say(l)
        except Exception as e:  # noqa: BLE001
            say("ERREUR : %r" % e)

    bar = ttk.Frame(root)
    bar.grid(row=4, column=0, columnspan=3, sticky="e", **pad)
    ttk.Button(bar, text="Types de symboles", command=show_types).pack(side="left", padx=4)
    ttk.Button(bar, text="Convertir", command=do_convert).pack(side="left", padx=4)
    root.mainloop()


def cli(argv):
    import argparse
    ap = argparse.ArgumentParser(description="Schéma JSON -> WinRelais .xrs")
    ap.add_argument("json", nargs="?")
    ap.add_argument("-o", "--sortie")
    ap.add_argument("-b", "--bibliotheque")
    ap.add_argument("--types", action="store_true", help="liste les symboles disponibles")
    ap.add_argument("--sans-grille", action="store_true")
    a = ap.parse_args(argv)
    if a.types:
        print("\n".join(types_disponibles(a.bibliotheque)))
        return 0
    if not a.json:
        ap.print_help()
        return 2
    try:
        out, resume, warns = convert_file(a.json, a.sortie, a.bibliotheque, not a.sans_grille)
    except ConversionError as e:
        print("ERREUR :\n" + str(e), file=sys.stderr)
        return 1
    for w in warns:
        print("Attention :", w)
    print("OK ->", out, "|", resume)
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1:
        sys.exit(cli(sys.argv[1:]))
    gui()
