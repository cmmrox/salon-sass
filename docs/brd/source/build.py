"""Assemble the BRD parts into one HTML file and render it to PDF with Chrome.

Two passes: the first render yields the PDF outline, which gives the page of
every heading; the second render fills those page numbers into the contents.
"""
import html
import pathlib
import re
import subprocess
import sys

from pypdf import PdfReader

HERE = pathlib.Path(__file__).parent
OUT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "brd.pdf"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PARTS = ["p1.html", "p2.html", "p3.html", "p4.html"]

PRI = {"M": ("m", "Must"), "S": ("s", "Should"), "C": ("c", "Could")}
COV = {"Y": ("y", "&#9679; Yes"), "P": ("p", "&#9680; Partial"), "N": ("n", "&#9675; New")}


def cells(line):
    return [c.strip() for c in line.split(" | ")]


def reqs(match):
    rows = []
    for line in match.group(1).strip().splitlines():
        if not line.strip():
            continue
        rid, text, pri, rel, cov = cells(line)
        pc, pl = PRI[pri]
        cc, cl = COV[cov]
        rows.append(
            f'<tr><td class="id">{rid}</td><td>{text}</td>'
            f'<td class="c"><span class="pill {pc}">{pl}</span></td>'
            f'<td class="c"><span class="pill rel">{rel}</span></td>'
            f'<td class="cov {cc}">{cl}</td></tr>'
        )
    return (
        '<table class="req"><colgroup><col style="width:9%"><col style="width:60%">'
        '<col style="width:9%"><col style="width:9%"><col style="width:13%"></colgroup>'
        "<thead><tr><th>ID</th><th>Requirement</th><th>Priority</th><th>Release</th>"
        "<th>In prototype</th></tr></thead><tbody>" + "".join(rows) + "</tbody></table>"
    )


def ptable(match):
    attrs, body = match.group(1), match.group(2)
    head = re.search(r'head="([^"]*)"', attrs).group(1).split("|")
    widths = re.search(r'widths="([^"]*)"', attrs)
    cls = re.search(r'class="([^"]*)"', attrs)
    cols = ""
    if widths:
        cols = "<colgroup>" + "".join(
            f'<col style="width:{w}%">' for w in widths.group(1).split(",")
        ) + "</colgroup>"
    rows = []
    for line in body.strip().splitlines():
        if not line.strip():
            continue
        cs = cells(line)
        rows.append("<tr>" + "".join(f"<td>{c}</td>" for c in cs) + "</tr>")
    return (
        f'<table class="{cls.group(1) if cls else ""}">{cols}<thead><tr>'
        + "".join(f"<th>{h.strip()}</th>" for h in head)
        + "</tr></thead><tbody>" + "".join(rows) + "</tbody></table>"
    )


def plain(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", fragment))).strip()


def assemble(pages):
    body = "".join((HERE / p).read_text() for p in PARTS)
    body = re.sub(r"<reqs>(.*?)</reqs>", reqs, body, flags=re.S)
    body = re.sub(r"<ptable([^>]*)>(.*?)</ptable>", ptable, body, flags=re.S)
    toc = []
    for level, hid, inner in re.findall(r'<h([12]) id="([^"]+)"[^>]*>(.*?)</h\1>', body, flags=re.S):
        title = plain(inner)
        if title == "Contents":
            continue
        page = pages.get(title, "")
        toc.append(
            f'<a class="toc l{level}" href="#{hid}"><span class="tt">{html.escape(title)}</span>'
            f'<span class="dots"></span><span class="pg">{page}</span></a>'
        )
    body = body.replace("<!--TOC-->", "".join(toc))
    css = (HERE / "style.css").read_text()
    doc = (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        "<title>Business Requirements Document - White-Label Multi-Tenant Salon SaaS</title>"
        f"<style>{css}</style></head><body>{body}</body></html>"
    )
    (HERE / "brd.html").write_text(doc)


def render():
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
         "--generate-pdf-document-outline", f"--print-to-pdf={OUT}",
         f"file://{HERE / 'brd.html'}"],
        check=True, capture_output=True,
    )


def outline_pages():
    reader = PdfReader(str(OUT))
    pages = {}

    def walk(items):
        for item in items:
            if isinstance(item, list):
                walk(item)
            else:
                pages.setdefault(plain(item.title), reader.get_destination_page_number(item) + 1)

    walk(reader.outline)
    return pages, len(reader.pages)


assemble({})
render()
pages, _ = outline_pages()
assemble(pages)
render()
pages2, count = outline_pages()
drift = [t for t in pages if pages[t] != pages2.get(t)]
print(f"pages={count} headings={len(pages2)} drift={drift}")
