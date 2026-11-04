#!/usr/bin/env python3
"""
winrelais.py - lecture / ecriture / generation de fichiers WinRelais (.xrs)

Format (deduit par analyse, non documente officiellement) :
  - texte Latin-1, fins de ligne CRLF
  - sections  ¤#Nom¤  suivies de lignes de champs separes par '·' (0xB7)
  - unites : 1/100 mm, origine en haut a gauche, Y vers le bas (A4 = 29700 x 21000)
  - chaque symbole embarque sa propre geometrie (Contour, Arc, Broche...) en
    coordonnees ABSOLUES : placer un symbole = translater toutes ses coordonnees
  - liaisons electriques = coincidence de coordonnees (extremite de fil / broche)
  - folio d'un symbole : champ 8 de la ligne d'en-tete de #Autre ; pour FilBus,
    Jonction, Numero, Texte, DSansTexte : champ folio documente dans FOLIO_FIELD

Usage :
    python winrelais.py check  fichier.xrs          # relecture/reecriture octet par octet
    python winrelais.py info   fichier.xrs          # resume
    python winrelais.py preview fichier.xrs dossier # PNG de chaque folio
"""
import copy
import sys

SEP = "·"
MARK = "¤"
ENC = "latin-1"
NL = "\r\n"

# champ (index) contenant le n° de folio, par section de fin de fichier
FOLIO_FIELD = {"FilBus": 4, "Jonction": 2, "Numero": 3, "Texte": 4, "DSansTexte": 5}


# --------------------------------------------------------------------------
# Couche 1 : sections brutes (lecture / ecriture sans perte)
# --------------------------------------------------------------------------
class Section:
    """Une section ¤#tag¤ et ses lignes (chaque ligne = liste de champs str)."""

    def __init__(self, tag, rows=None):
        self.tag = tag
        self.rows = rows or []

    @property
    def name(self):
        return self.tag.split(SEP)[0]

    def copy(self):
        return copy.deepcopy(self)


def _split(line):
    return line.split(SEP)


def _join(fields):
    return SEP.join(fields)


class Doc:
    def __init__(self, sections, trailing_nl=True):
        self.sections = sections          # liste plate de Section
        self.trailing_nl = trailing_nl

    # ---- E/S -------------------------------------------------------------
    @classmethod
    def load(cls, path):
        raw = open(path, "rb").read().decode(ENC)
        return cls.parse(raw)

    @classmethod
    def parse(cls, text):
        trailing = text.endswith(NL)
        lines = text.split(NL)
        if trailing:
            lines = lines[:-1]
        # 1re ligne = titre "<< Schéma WinRelais >>" : section speciale
        secs = [Section("@Titre", [[lines[0]]])]
        cur = None
        for ln in lines[1:]:
            if ln.startswith(MARK + "#") and ln.endswith(MARK):
                cur = Section(ln[2:-1])
                secs.append(cur)
            else:
                cur.rows.append(_split(ln))
        return cls(secs, trailing)

    def dumps(self):
        out = []
        for s in self.sections:
            if s.tag == "@Titre":
                out.append(s.rows[0][0])
                continue
            out.append(MARK + "#" + s.tag + MARK)
            out.extend(_join(r) for r in s.rows)
        return NL.join(out) + (NL if self.trailing_nl else "")

    def save(self, path):
        with open(path, "wb") as f:
            f.write(self.dumps().encode(ENC))

    # ---- acces par bloc ----------------------------------------------------
    def blocks(self, kind):
        """Liste de (debut, fin_exclue) des blocs ¤#Kind·N¤ ... ¤#FIN¤."""
        res, i, n = [], 0, len(self.sections)
        while i < n:
            if self.sections[i].name == kind and SEP in self.sections[i].tag:
                j = i
                while self.sections[j].tag != "FIN":
                    j += 1
                res.append((i, j + 1))
                i = j + 1
            else:
                i += 1
        return res

    def first(self, tag):
        for s in self.sections:
            if s.tag == tag:
                return s
        return None

    # ---- vues haut niveau ------------------------------------------------
    @property
    def folios(self):
        return [Folio(self, a, b) for a, b in self.blocks("Folio")]

    @property
    def symbols(self):
        return [Symbol(self.sections[a:b]) for a, b in self.blocks("Symbole")]

    def tail(self, name):
        """Section de fin de fichier (FilBus, Jonction, Numero, RenvoiF, Texte, DSansTexte)."""
        return self.first(name)


class Folio:
    def __init__(self, doc, a, b):
        self.doc, self.a, self.b = doc, a, b

    @property
    def secs(self):
        return self.doc.sections[self.a:self.b]

    @property
    def title(self):
        for s in self.secs:
            if s.tag == "CartoucheTexte":
                for r in s.rows:
                    if len(r) > 3 and r[3] == "TITRE FOLIO":
                        return r[2]

    @property
    def size(self):
        for s in self.secs:
            if s.tag == "Autre":
                return int(s.rows[0][0]), int(s.rows[0][1])


class Symbol:
    """Un symbole = liste de Section (Autre, Contour, Arc, Broche, RefCroise...)."""

    def __init__(self, secs):
        self.secs = secs                 # secs[0] = ¤#Symbole·N¤ ... dernier = FIN

    def sec(self, name):
        for s in self.secs:
            if s.tag == name:
                return s

    @property
    def autre(self):
        return self.sec("Autre").rows

    @property
    def x(self):
        return int(self.autre[0][0])

    @property
    def y(self):
        return int(self.autre[0][1])

    @property
    def header(self):
        return self.autre[1]

    @property
    def family(self):          # ex. 'borne - standard'
        return self.header[1]

    @property
    def description(self):
        return self.header[2]

    @property
    def prefix(self):          # ex. 'X', 'K', 'Q'
        return self.header[4]

    @property
    def folio(self):
        return int(self.header[8])

    @folio.setter
    def folio(self, v):
        self.header[8] = str(v)

    @property
    def texts(self):
        return self.autre[2:]

    @property
    def repere(self):
        return self.texts[0][0]

    @property
    def pins(self):
        s = self.sec("Broche")
        return [] if s is None else [(r[0], int(r[1]), int(r[2])) for r in s.rows]

    def set_text(self, row, text, visible=None):
        r = self.texts[row]
        r[0] = text
        if visible is not None:
            r[3] = "1" if visible else "0"

    def pin(self, name):
        """Coordonnees (x, y) du point de connexion de la broche `name`."""
        for n, x, y in self.pins:
            if n.strip() == name.strip():
                return x, y
        raise KeyError("%s : broche %r inconnue (broches : %s)"
                       % (self.repere, name, [p[0] for p in self.pins]))

    def rename_pins(self, mapping):
        """Renomme des broches, ex. {'U': 'W', 'W': 'U'} (echange simultane)."""
        for r in self.sec("Broche").rows:
            if r[0].strip() in mapping:
                r[0] = mapping[r[0].strip()]

    def moved(self, dx, dy):
        """Copie translatee de (dx, dy)."""
        new = Symbol([s.copy() for s in self.secs])
        for s in new.secs:
            _translate_section(s, dx, dy)
        return new

    def at(self, x, y):
        """Copie dont l'origine est en (x, y)."""
        return self.moved(x - self.x, y - self.y)

    def anchored(self, x, y):
        """Copie dont le point d'ancrage est en (x, y).
        Ancrage = origine du symbole si elle coincide avec une broche (cas normal),
        sinon la 1re broche (symboles dont l'origine a ete deplacee)."""
        p = self.pins
        if p and not any((px, py) == (self.x, self.y) for _, px, py in p):
            return self.moved(x - p[0][1], y - p[0][2])
        return self.at(x, y)


def _add(r, idx, d):
    """Translate r[idx...] ; la position dans idx donne la parite (0,2,4=x ; 1,3,5=y)."""
    for k, i in enumerate(idx):
        r[i] = str(int(r[i]) + d[k % 2])


def _translate_section(s, dx, dy):
    d = (dx, dy)
    t = s.name
    if t == "Autre":
        _add(s.rows[0], (0, 1), d)
        for r in s.rows[2:]:
            _add(r, (1, 2), d)
    elif t == "Contour":
        for r in s.rows:
            _add(r, (0, 1, 2, 3), d)
    elif t == "Arc":
        for r in s.rows:
            _add(r, (0, 1), d)
    elif t == "Broche":
        for r in s.rows:
            _add(r, (1, 2, 3, 4, 5, 6), d)
    elif t == "RefCroise":
        for r in s.rows:
            _add(r, (5, 6), d)
    elif t == "MDessin":
        for r in s.rows:
            _add(r, (1, 2), d)
    elif t == "ZoneDessin":
        for r in s.rows:
            _add(r, (6, 7, 8, 9, 10, 11, 12, 13), d)


# --------------------------------------------------------------------------
# Couche 2 : generateur
# --------------------------------------------------------------------------
# styles de fils releves dans le fichier d'origine :
#   (nom, couleur, epaisseur, trait)
WIRE_STYLES = {
    "commande": ("Commande 24V", "0", "20", "0"),
    "puissance": ("Puissance > 400V", "33023", "20", "0"),
    "neutre": ("Neutre Puissance", "16711680", "40", "0"),
    "terre": ("Conducteur terre", "0", "60", "1"),
}


class Builder:
    """
    Construit un schema a partir d'un fichier de reference (base) dont on reprend
    l'en-tete, le style, le cartouche et la bibliotheque de symboles.
    """

    def __init__(self, base_path, title="Schéma", company=None, address=None,
                 drawer=None, date=None, drawing_title=None):
        self.base = Doc.load(base_path)
        # gabarits de symboles : 1er exemplaire de chaque (famille, prefixe)
        self.templates = {}
        for sym in self.base.symbols:
            key = (sym.family, sym.prefix)
            ok = (not sym.pins) or any((px, py) == (sym.x, sym.y) for _, px, py in sym.pins)
            if key not in self.templates or (ok and not self.templates[key][1]):
                self.templates[key] = (sym, ok)
        self.templates = {k: v[0] for k, v in self.templates.items()}
        fl = self.base.folios
        self.folio_tpl = fl[1] if len(fl) > 1 else fl[0]   # gabarit de cartouche
        self.meta = dict(company=company, address=address, drawer=drawer,
                         date=date, drawing_title=drawing_title)
        self.folio_titles = []
        self.symbols = []
        self.wires, self.junctions, self.numbers = [], [], []
        self.texts, self.boxes, self.refs = [], [], []

    # ---- folios ----------------------------------------------------------
    def add_folio(self, title):
        self.folio_titles.append(title)
        return len(self.folio_titles) - 1

    # ---- symboles --------------------------------------------------------
    def template_names(self):
        return sorted(self.templates)

    def place(self, family, prefix, folio, x, y, repere=None, row_texts=None):
        """
        Place un symbole de la bibliotheque (family, prefix) sur le folio ;
        (x, y) = position de sa 1re broche.
        row_texts : {n° de ligne de texte: (texte, visible)} pour renseigner les
                    textes du symbole (ligne 0 = repere).
        """
        tpl = self.templates[(family, prefix)]
        sym = tpl.anchored(x, y)
        sym.folio = folio
        if repere is not None:
            sym.set_text(0, repere, True)
        for row, (txt, vis) in (row_texts or {}).items():
            sym.set_text(row, txt, vis)
        self.symbols.append(sym)
        return sym

    # ---- elements 2D -----------------------------------------------------
    def wire(self, folio, points, kind="commande"):
        name, color, width, dash = WIRE_STYLES[kind]
        for (x1, y1), (x2, y2) in zip(points, points[1:]):
            self.wires.append([str(x1), str(y1), str(x2), str(y2), str(folio),
                               "1", width, color, dash, name,
                               "0", "0", "0", "0", "0", ""])

    def connect(self, folio, a, pin_a, b, pin_b, kind="commande"):
        """Fil droit entre deux broches ; coude en L (vertical puis horizontal) si besoin."""
        (x1, y1), (x2, y2) = a.pin(pin_a), b.pin(pin_b)
        pts = [(x1, y1), (x2, y2)] if x1 == x2 or y1 == y2 else \
            [(x1, y1), (x1, y2), (x2, y2)]
        self.wire(folio, pts, kind)

    def junction(self, folio, x, y, width=20):
        self.junctions.append([str(x), str(y), str(folio), str(width), "0", ""])

    def number(self, folio, x, y, text, orient=2):
        self.numbers.append([str(x), str(y), text, str(folio), str(orient), "Arial", "300",
                             "0", "65535", "0", "0", "0", ""])

    def text(self, folio, x, y, text, size=400, bold=False, angle=0):
        self.texts.append([str(x), str(y), text, "", str(folio), "0", "Arial",
                           str(size), str(angle), "0", "1" if bold else "0", "-1",
                           "0", "0", "", "", "0", ""])

    def box(self, folio, x1, y1, x2, y2):
        self.boxes.append([str(x1), str(y1), str(x2), str(y2), "0", str(folio), "0",
                           "20", "2", "2", "1", "16777215", "0", "0", ""])

    # ---- assemblage ------------------------------------------------------
    def build(self):
        d = copy.deepcopy(self.base)
        S = d.sections
        # 1) coupe : on garde l'en-tete, on reconstruit folios + symboles + fin
        head = [s for s in S if s.tag in ("@Titre", "Version", "Compteur", "Schema",
                                          "StyleNum", "Filtre")]
        out = list(head)
        n = max(1, len(self.folio_titles))
        out.append(Section("NombreFolio", [[str(n), ""]]))
        total = "%02d" % n
        for i in range(n):
            out.extend(self._make_folio(i, total))
        out.append(Section("NombreSymbole", [[str(len(self.symbols)), ""]]))
        for k, sym in enumerate(self.symbols):
            secs = [s.copy() for s in sym.secs]
            secs[0] = Section("Symbole%s%d" % (SEP, k), [])
            out.extend(secs)

        def tail(name, rows):
            out.append(Section(name, rows))
        tail("FilBus", self.wires)
        tail("Jonction", self.junctions)
        tail("Numero", self.numbers)
        tail("RenvoiF", self.refs)
        texts = list(self.texts)
        if self.meta.get("drawing_title"):
            for i in range(n):
                texts.append(["7400", "19400", self.meta["drawing_title"], "", str(i),
                              "0", "Arial", "400", "0", "0", "0", "-1", "0", "0",
                              "", "", "0", ""])
        tail("Texte", texts)
        tail("DSansTexte", self.boxes)
        out.append(Section("FIN", []))
        return Doc(out, True)

    def _make_folio(self, i, total):
        a, b = self.folio_tpl.a, self.folio_tpl.b
        secs = [s.copy() for s in self.base.sections[a:b]]
        secs[0] = Section("Folio%s%d" % (SEP, i), [])
        for s in secs:
            if s.tag == "Autre":
                s.rows[0][9] = str(i)
                s.rows[0][10] = "Folio %d" % i
            elif s.tag == "CartoucheTexte":
                for r in s.rows:
                    r[4] = str(i)
                    lab = r[3]
                    m = self.meta
                    if lab == "TITRE FOLIO":
                        r[2] = self.folio_titles[i]
                    elif lab == "SOCIETE" and m["company"]:
                        r[2] = m["company"]
                    elif lab == "Adresse société" and m["address"]:
                        r[2] = m["address"]
                    elif lab == "NOM DESSINATEUR" and m["drawer"]:
                        r[2] = m["drawer"]
                    elif lab == "DATE DESSIN" and m["date"]:
                        r[2] = m["date"]
                # lignes 1 et 2 = n° de page courante et nombre total de pages
                s.rows[1][2] = "%02d" % (i + 1)
                s.rows[2][2] = total
            elif s.tag == "CartoucheDessinDst":
                for r in s.rows:
                    r[5] = str(i)
        # titre de dessin (ex. "Aspiration Mazères dossier 31842") : ligne Texte globale
        return secs


# --------------------------------------------------------------------------
# Couche 3 : apercu (verification visuelle de l'interpretation)
# --------------------------------------------------------------------------
def preview(doc, outdir):
    import math
    import os
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    os.makedirs(outdir, exist_ok=True)
    folios = doc.folios
    syms = doc.symbols
    tails = {n: (doc.tail(n).rows if doc.tail(n) else []) for n in FOLIO_FIELD}
    for fi, fol in enumerate(folios):
        W, H = fol.size
        fig, ax = plt.subplots(figsize=(14, 9.9))
        ax.set_xlim(0, W)
        ax.set_ylim(H, 0)
        ax.set_aspect("equal")
        ax.axis("off")
        # cartouche
        for s in fol.secs:
            if s.tag == "CartoucheDessinDst":
                for r in s.rows:
                    ax.plot([int(r[0]), int(r[2])], [int(r[1]), int(r[3])], "k", lw=0.8)
            if s.tag == "CartoucheTexte":
                for r in s.rows:
                    ax.text(int(r[0]), int(r[1]), r[2], fontsize=5, va="top")
        for sym in syms:
            if sym.folio != fi:
                continue
            for s in sym.secs:
                if s.tag == "Contour":
                    for r in s.rows:
                        ax.plot([int(r[0]), int(r[2])], [int(r[1]), int(r[3])], "k", lw=0.7)
                elif s.tag == "Arc":
                    for r in s.rows:
                        cx, cy, rad, a1, a2 = (int(r[0]), int(r[1]), int(r[2]),
                                               int(r[3]), int(r[4]))
                        if a2 <= a1:
                            a2 += 360
                        ts = [math.radians(a1 + (a2 - a1) * k / 40) for k in range(41)]
                        ax.plot([cx + rad * math.cos(t) for t in ts],
                                [cy - rad * math.sin(t) for t in ts], "k", lw=0.7)
                elif s.tag == "Broche":
                    for r in s.rows:
                        ax.plot([int(r[1]), int(r[3])], [int(r[2]), int(r[4])], "k", lw=0.7)
            for r in sym.texts[:1]:
                if r[3] == "1":
                    ax.text(int(r[1]), int(r[2]), r[0], fontsize=4, color="blue")
        for r in tails["FilBus"]:
            if int(r[4]) == fi:
                ax.plot([int(r[0]), int(r[2])], [int(r[1]), int(r[3])],
                        color="#%02x%02x%02x" % tuple(
                            (int(r[7]) >> k) & 255 for k in (0, 8, 16)) if r[7] != "0" else "k",
                        lw=int(r[6]) / 25.0)
        for r in tails["Jonction"]:
            if int(r[2]) == fi:
                ax.plot([int(r[0])], [int(r[1])], "ko", ms=int(r[3]) / 8)
        for r in tails["Numero"]:
            if int(r[3]) == fi:
                ax.text(int(r[0]), int(r[1]), r[2], fontsize=4, color="green")
        for r in tails["Texte"]:
            if int(r[4]) == fi:
                ax.text(int(r[0]), int(r[1]), r[2], fontsize=5, va="top")
        for r in tails["DSansTexte"]:
            if int(r[5]) == fi:
                ax.plot([int(r[0]), int(r[2])], [int(r[1]), int(r[3])], "k--", lw=0.5)
        p = os.path.join(outdir, "folio%d.png" % fi)
        fig.savefig(p, dpi=90, bbox_inches="tight")
        plt.close(fig)
        print(p)


# --------------------------------------------------------------------------
def _cli():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd, path = sys.argv[1], sys.argv[2]
    raw = open(path, "rb").read()
    doc = Doc.load(path)
    if cmd == "check":
        ok = doc.dumps().encode(ENC) == raw
        print("relecture/reecriture identique :", ok, len(raw), "octets")
        sys.exit(0 if ok else 1)
    elif cmd == "info":
        for i, f in enumerate(doc.folios):
            n = sum(1 for s in doc.symbols if s.folio == i)
            print("folio %d : %-40s %d symboles" % (i, f.title, n))
        print("symboles :", len(doc.symbols))
        for n in FOLIO_FIELD:
            s = doc.tail(n)
            print("%-11s %d" % (n, len(s.rows) if s else 0))
    elif cmd == "preview":
        preview(doc, sys.argv[3] if len(sys.argv) > 3 else ".")


if __name__ == "__main__":
    _cli()
