#!/usr/bin/env python3
"""site/ 소개 페이지용 글꼴 서브셋을 만든다.

페이지(site/*.html, site/*.js)에 실제로 쓰인 글자 + 기본 라틴만 남겨 Pretendard 가변
글꼴(2MB)을 수십 KB로 줄인다. Pretendard는 OFL 예약 글꼴 이름이 있어 서브셋은 "WIO Sans"로
이름을 바꿔 낸다 (JetBrains Mono는 예약 이름이 없다). 페이지 문구를 바꾸면 다시 돌린다 — 빠진 글자는 시스템
글꼴로 대체되어 보이므로 깨지지는 않지만 모양이 섞인다.

필요: pip install fonttools brotli

    npm pack pretendard@1.3.9 @fontsource-variable/jetbrains-mono@5.3.0
    mkdir -p pre jbm && tar xzf pretendard-1.3.9.tgz -C pre && tar xzf fontsource-variable-jetbrains-mono-*.tgz -C jbm
    python3 scripts/site-subset-fonts.py \
        pre/package/dist/web/variable/woff2/PretendardVariable.woff2 \
        jbm/package/files/jetbrains-mono-latin-wght-normal.woff2
"""

import html
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
OUT = SITE / "assets" / "fonts"

ASCII = "".join(chr(c) for c in range(0x20, 0x7F))
EXTRA = "·–—‘’“”…→←↑↓↗×−±°©"


def page_text() -> str:
    chunks = []
    for path in sorted(SITE.glob("*.html")) + sorted(SITE.glob("*.js")):
        raw = path.read_text(encoding="utf-8")
        chunks.append(html.unescape(re.sub(r"<[^>]+>", " ", raw)))
        # 속성값(alt, aria-label 등)도 포함된다 — 태그 안 글자까지 통째로 넣는다
        chunks.append(raw)
    return "".join(chunks)


def rename(font: TTFont, family: str) -> None:
    """OFL 3조: 수정본(서브셋)은 예약 글꼴 이름(Pretendard)을 쓸 수 없다 — 이름 테이블을 바꾼다."""
    ps = family.replace(" ", "")
    for rec in font["name"].names:
        if rec.nameID in (1, 16, 21):
            rec.string = family
        elif rec.nameID == 4:
            rec.string = family
        elif rec.nameID in (3, 6, 20):
            rec.string = ps
        elif rec.nameID == 25:
            rec.string = ps
        elif rec.nameID >= 256:
            # 가변 글꼴 named instance의 PostScript 이름
            rec.string = str(rec).replace("PretendardVariable", ps)


def build(src: str, dst: Path, text: str, wght=(400, 800), instance: bool = True, family: str | None = None) -> None:
    font = TTFont(src)
    if instance and "fvar" in font:
        axes = {a.axisTag: a for a in font["fvar"].axes}
        if "wght" in axes:
            lo = max(wght[0], axes["wght"].minValue)
            hi = min(wght[1], axes["wght"].maxValue)
            font = instancer.instantiateVariableFont(font, {"wght": (lo, hi)})
    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["kern", "liga", "calt", "tnum", "ss06", "ccmp", "locl", "mark", "mkmk"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.hinting = False
    sub = subset.Subsetter(opts)
    sub.populate(text=text)
    sub.subset(font)
    if family:
        rename(font, family)
    dst.parent.mkdir(parents=True, exist_ok=True)
    font.flavor = "woff2"
    font.save(dst)
    print(f"{dst.relative_to(ROOT)}  {dst.stat().st_size / 1024:.1f} KB")


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    pretendard, mono = sys.argv[1], sys.argv[2]
    text = page_text()
    chars = sorted(set(text + ASCII + EXTRA) - set("\n\r\t"))
    hangul = sum(1 for c in chars if "가" <= c <= "힣")
    print(f"글자 {len(chars)}개 (한글 음절 {hangul}개)")
    build(pretendard, OUT / "wio-sans.woff2", "".join(chars), family="WIO Sans")
    # fontsource 배포본은 gvar에 빈 글리프 항목이 없어 instancer가 실패한다 — 축은 그대로 두고 글자만 줄인다
    build(mono, OUT / "jetbrains-mono-wio.woff2", ASCII + EXTRA + "".join(c for c in chars if ord(c) < 0x2200), instance=False)


if __name__ == "__main__":
    main()
