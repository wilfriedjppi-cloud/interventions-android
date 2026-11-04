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
import math
import os
import re
import sys

import winrelais as wr

VERSION = "1.1"

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

CLES_RACINE = {"societe", "adresse", "dessinateur", "date", "titre_dessin", "folios"}
CLES_FOLIO = {"titre", "composants", "liaisons", "fils", "jonctions", "reperes",
              "textes", "cadres"}
CLES_COMPOSANT = {"type", "ref", "id", "x", "y", "textes", "broches", "famille", "prefixe"}

# Caractères typographiques courants absents du Latin-1 -> équivalents simples
SUBST = {
    "\u2014": "-", "\u2013": "-", "\u2011": "-", "\u2012": "-", "\u2212": "-",
    "\u2018": "'", "\u2019": "'", "\u201a": ",", "\u201c": '"', "\u201d": '"',
    "\u201e": '"', "\u2026": "...", "\u0153": "oe", "\u0152": "OE", "\u20ac": "EUR",
    "\u00a0": " ", "\u202f": " ", "\u2009": " ", "\u2002": " ", "\u2003": " ",
    "\u03a9": "ohm", "\u2126": "ohm", "\u2264": "<=", "\u2265": ">=", "\u2192": "->",
    "\u2190": "<-", "\u2022": "-", "\u00b7": ".", "\u2260": "<>", "\u03c6": "phi",
    "\u2248": "~", "\u221e": "inf", "\u2713": "OK", "\u2714": "OK",
}


def _clean(obj, seen):
    """Remplace récursivement les caractères non Latin-1 dans toutes les chaînes."""
    if isinstance(obj, str):
        out = []
        for ch in obj:
            if ch == wr.SEP or ord(ch) >= 256 or ch in "\r\n\x00":
                rep = SUBST.get(ch, " " if ch in "\r\n\x00" else
                                ("." if ch == wr.SEP else "?"))
                seen[ch] = rep
                out.append(rep)
            else:
                out.append(ch)
        return "".join(out)
    if isinstance(obj, list):
        return [_clean(o, seen) for o in obj]
    if isinstance(obj, dict):
        return {k: _clean(v, seen) for k, v in obj.items()}
    return obj


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


def convert(data, biblio=None, snap=True, garder_cadre=False):
    """data = dict JSON déjà chargé. Retourne (Doc, [avertissements])."""
    warns, errs = [], []
    if not isinstance(data, dict) or not data.get("folios"):
        raise ConversionError("JSON invalide : clé « folios » absente ou vide.")
    seen = {}
    data = _clean(data, seen)
    for ch, rep in seen.items():
        warns.append("caractère %r (U+%04X) remplacé par %r (non supporté par WinRelais)"
                     % (ch, ord(ch), rep))
    if _est_dessin(data):
        return _convert_dessin(data, biblio, warns, garder_cadre)
    inconnues = sorted(set(data) - CLES_RACINE)
    if inconnues:
        warns.append("clés ignorées à la racine : %s" % ", ".join(inconnues))

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
        if not isinstance(fol, dict):
            errs.append("%s : doit être un objet JSON" % where)
            continue
        inc = sorted(set(fol) - CLES_FOLIO)
        if inc:
            warns.append("%s : clés ignorées : %s" % (where, ", ".join(inc)))
        for c in fol.get("composants", []):
            if isinstance(c, dict):
                inc = sorted(set(c) - CLES_COMPOSANT)
                if inc:
                    warns.append("%s, composant %s : clés ignorées : %s"
                                 % (where, c.get("ref", "?"), ", ".join(inc)))
                    break
        if not any(fol.get(k) for k in ("composants", "liaisons", "fils", "textes",
                                       "reperes", "jonctions", "cadres")):
            warns.append("%s : aucun élément reconnu (folio vide)" % where)

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
    if not b.symbols and not b.wires:
        raise ConversionError(
            "Rien à convertir : aucun composant ni fil reconnu.\n"
            "Clés attendues par folio : composants, liaisons, fils, jonctions, reperes, "
            "textes, cadres (voir LISEZMOI.txt).\n" + "\n".join(warns))
    return b.build(), warns


# --------------------------------------------------------------------------
# Import d'un DESSIN vectoriel ("schema-electrique/1.x") : lignes, rectangles,
# cercles, chemins, textes par folio  ->  dessin libre WinRelais
# --------------------------------------------------------------------------
def _est_dessin(data):
    return isinstance(data, dict) and (
        str(data.get("format", "")).startswith("schema-electrique")
        or any(isinstance(f, dict) and "elements" in f for f in data.get("folios", [])))


def _arc_points(x1, y1, rx, ry, phi, fa, fs, x2, y2, n=14):
    """Arc SVG (parametrage par extremites) -> liste de points."""
    if rx == 0 or ry == 0:
        return [(x1, y1), (x2, y2)]
    ph = math.radians(phi)
    cp, sp = math.cos(ph), math.sin(ph)
    dx, dy = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        co = -co
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a
    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    pts = []
    for k in range(n + 1):
        t = t1 + dt * k / n
        pts.append((cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp,
                    cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp))
    return pts


def _path_polylines(d, warns):
    """Chemin SVG (M L H V A Z, absolu/relatif) -> liste de polylignes."""
    toks = re.findall(r"[MmLlHhVvAaZzCcSsQqTt]|-?\d*\.?\d+(?:[eE][-+]?\d+)?", d)
    out, cur, i = [], [], 0
    x = y = sx = sy = 0.0
    cmd = None

    def num(k):
        return float(toks[k])
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                if cur:
                    cur.append((sx, sy))
                    out.append(cur)
                    cur = []
                x, y = sx, sy
                continue
        if cmd in ("M", "m"):
            if cur:
                out.append(cur)
            nx, ny = num(i), num(i + 1)
            i += 2
            x, y = (nx, ny) if cmd == "M" else (x + nx, y + ny)
            sx, sy = x, y
            cur = [(x, y)]
            cmd = "L" if cmd == "M" else "l"
        elif cmd in ("L", "l"):
            nx, ny = num(i), num(i + 1)
            i += 2
            x, y = (nx, ny) if cmd == "L" else (x + nx, y + ny)
            cur.append((x, y))
        elif cmd in ("H", "h"):
            v = num(i)
            i += 1
            x = v if cmd == "H" else x + v
            cur.append((x, y))
        elif cmd in ("V", "v"):
            v = num(i)
            i += 1
            y = v if cmd == "V" else y + v
            cur.append((x, y))
        elif cmd in ("A", "a"):
            rx, ry, ph, fa, fs, ex, ey = (num(i + k) for k in range(7))
            i += 7
            if cmd == "a":
                ex, ey = x + ex, y + ey
            cur += _arc_points(x, y, rx, ry, ph, int(fa), int(fs), ex, ey)[1:]
            x, y = ex, ey
        else:
            warns.append("chemin SVG : commande %r non gérée, chemin ignoré" % cmd)
            return out
    if cur:
        out.append(cur)
    return out


def _convert_dessin(data, biblio, warns, garder_cadre=False):
    """Dessin vectoriel -> .xrs (traits, rectangles, polygones, textes en dessin libre)."""
    proj = data.get("projet", {}) or {}
    date = str(proj.get("date", ""))
    m = re.match(r"(\d{4})-(\d{2})-(\d{2})", date)
    if m:
        date = "%s/%s/%s" % (m.group(3), m.group(2), m.group(1))
    client = str(proj.get("client", "")).split(" - ")[0].split(",")[0].strip()
    titre = " - ".join(x for x in (proj.get("objet"),
                                   ("dossier " + str(proj["dossier"])) if proj.get("dossier") else None,
                                   client or None) if x)
    b = wr.Builder(biblio or resource("bibliotheque.xrs"),
                   company=data.get("societe") or proj.get("societe") or "",
                   address=data.get("adresse") or proj.get("adresse") or "",
                   drawer=data.get("dessinateur") or proj.get("auteur") or "",
                   date=date or datetime.date.today().strftime("%d/%m/%Y"),
                   drawing_title=data.get("titre_dessin") or titre)
    W, H = b.folio_tpl.size
    page = (data.get("unites", {}) or {}).get("page", {}) or {}
    pl, ph = float(page.get("l", 420)), float(page.get("h", 297))
    k = min(W / (pl * 100.0), H / (ph * 100.0))
    if abs(k - 1.0) > 0.01:
        warns.append("page du JSON %g x %g mm -> folio WinRelais %g x %g mm : "
                     "dessin réduit à %d %%" % (pl, ph, W / 100.0, H / 100.0, round(k * 100)))

    def X(v):
        return int(round(float(v) * 100 * k))

    def width(style):
        try:
            sw = float(style.get("stroke-width", 0.35))
        except ValueError:
            sw = 0.35
        return sw, max(10, int(round(sw * 100 * k / 5.0)) * 5)

    def points(e):
        t = e.get("type")
        if t == "ligne":
            return [(e["x1"], e["y1"]), (e["x2"], e["y2"])]
        if t == "rectangle":
            return [(e["x"], e["y"]), (e["x"] + e["l"], e["y"] + e["h"])]
        if t == "texte":
            return [(e["x"], e["y"])]
        return []

    def est_cadre(e):
        """Cadre exterieur/interieur, bandeau des colonnes, cartouche propres au JSON."""
        p = points(e)
        if not p:
            return False
        ys = [y for _, y in p]
        if e.get("type") == "rectangle":
            (x0, y0), (x1, y1) = p
            if x0 <= 10 and x1 >= pl - 10:
                return True
            if x0 <= 14 and x1 >= pl - 14 and y0 <= 14:
                return True
        if 7 <= min(ys) and max(ys) <= 18.5:
            return True
        return min(ys) >= ph - 40

    stats = {"cadre": 0, "trait": 0, "rect": 0, "cercle": 0, "texte": 0, "chemin": 0, "ignore": 0}
    for fi, fol in enumerate(data["folios"]):
        b.add_folio(fol.get("titre", "Folio %d" % (fi + 1)))

        def seg(x1, y1, x2, y2, st):
            sw, w = width(st)
            dash = 2 if st.get("stroke-dasharray") else 0
            b.line(fi, X(x1), X(y1), X(x2), X(y2), w, dash)

        def poly(pts, st):
            for (xa, ya), (xb, yb) in zip(pts, pts[1:]):
                seg(xa, ya, xb, yb, st)

        for e in fol.get("elements", []):
            if not garder_cadre and est_cadre(e):
                stats["cadre"] += 1
                continue
            t, st = e.get("type"), e.get("style", {}) or {}
            sw = width(st)[0]
            sans_trait = st.get("stroke") in (None, "none") or sw == 0
            if t == "ligne":
                if sans_trait and "stroke" not in st:
                    pass
                seg(e["x1"], e["y1"], e["x2"], e["y2"], st)
                stats["trait"] += 1
            elif t == "rectangle":
                if sans_trait:               # simple aplat de couleur : non supporté
                    stats["ignore"] += 1
                    continue
                _, w = width(st)
                b.box(fi, X(e["x"]), X(e["y"]), X(e["x"] + e["l"]), X(e["y"] + e["h"]),
                      w, 2 if st.get("stroke-dasharray") else 0)
                stats["rect"] += 1
            elif t == "cercle":
                if sans_trait:
                    stats["ignore"] += 1
                    continue
                r = float(e["r"])
                n = 12 if r < 1.5 else 20 if r < 5 else 32
                pts = [(e["cx"] + r * math.cos(2 * math.pi * j / n),
                        e["cy"] + r * math.sin(2 * math.pi * j / n)) for j in range(n + 1)]
                poly(pts, st)
                stats["cercle"] += 1
            elif t == "polyligne":
                poly([tuple(p) for p in e["points"]], st)
                stats["trait"] += 1
            elif t == "chemin_svg":
                for pl_ in _path_polylines(e["d"], warns):
                    poly(pl_, st)
                stats["chemin"] += 1
            elif t == "texte":
                f = float(e.get("taille", 2.0))
                size = max(100, int(round(f * 125 * k / 10.0)) * 10)
                align = {"start": 0, "middle": 6, "end": 2}.get(e.get("ancrage", "start"), 0)
                b.text(fi, X(e["x"]), int(round(float(e["y"]) * 100 * k - 0.72 * size)),
                       str(e["texte"]), size, bool(e.get("gras", False)), 0, align)
                stats["texte"] += 1
            else:
                warns.append("folio %d : élément %r ignoré" % (fi + 1, t))
    warns.append("converti : %(trait)d traits, %(rect)d rectangles, %(cercle)d cercles, "
                 "%(chemin)d chemins, %(texte)d textes ; %(ignore)d aplats sans contour et "
                 "%(cadre)d éléments de cadre/cartouche du JSON ignorés"
                 % stats)
    if data.get("composants") or data.get("liaisons"):
        warns.append("nomenclature (%d composants) et liste de liaisons (%d) du JSON non "
                     "importées : seul le dessin l'est"
                     % (len(data.get("composants", [])), len(data.get("liaisons", []))))
    return b.build(), warns


def convert_file(json_path, out_path=None, biblio=None, snap=True, garder_cadre=False):
    with open(json_path, "r", encoding="utf-8-sig") as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            raise ConversionError("JSON illisible : %s" % e)
    doc, warns = convert(data, biblio, snap, garder_cadre)
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
    root.geometry("800x560")
    pad = dict(padx=8, pady=4)
    v_json, v_out, v_lib = tk.StringVar(), tk.StringVar(), tk.StringVar(value="(intégrée)")
    v_snap = tk.BooleanVar(value=True)
    v_cadre = tk.BooleanVar(value=False)

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

    ttk.Checkbutton(root, text="Dessin vectoriel : conserver le cadre et le cartouche du JSON",
                    variable=v_cadre).grid(row=4, column=1, sticky="w", **pad)
    log = scrolledtext.ScrolledText(root, height=18, font=("Consolas", 9))
    log.grid(row=6, column=0, columnspan=3, sticky="nsew", **pad)
    root.rowconfigure(6, weight=1)
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
                                              lib(), v_snap.get(), v_cadre.get())
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
    bar.grid(row=5, column=0, columnspan=3, sticky="e", **pad)
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
    ap.add_argument("--garder-cadre", action="store_true",
                    help="dessin vectoriel : conserve cadre et cartouche du JSON")
    a = ap.parse_args(argv)
    if a.types:
        print("\n".join(types_disponibles(a.bibliotheque)))
        return 0
    if not a.json:
        ap.print_help()
        return 2
    try:
        out, resume, warns = convert_file(a.json, a.sortie, a.bibliotheque, not a.sans_grille,
                                        a.garder_cadre)
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
