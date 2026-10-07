/* When I Off 소개 페이지 — 외부 요청 없음, 인라인 스크립트 없음 (CSP script-src 'self'). */
(function () {
    "use strict";

    var reduceMotion =
        window.matchMedia &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    /* ── 1. 출발 안내판 split-flap ─────────────────────────────
       HTML에 최종 글자가 이미 들어 있다. 움직임을 줄이는 설정이면 아무것도 하지 않는다. */
    function flapBoard() {
        if (reduceMotion) return;
        var cells = document.querySelectorAll("[data-flap] .flap");
        var digits = "0123456789";
        Array.prototype.forEach.call(cells, function (cell, i) {
            var final = cell.textContent;
            if (!/[0-9]/.test(final)) return;
            var turns = 6 + i * 2;
            var n = 0;
            cell.textContent = digits[(Number(final) + 4) % 10];
            var timer = window.setInterval(function () {
                n += 1;
                cell.classList.remove("is-flipping");
                // reflow로 애니메이션을 다시 시작한다
                void cell.offsetWidth;
                cell.classList.add("is-flipping");
                if (n >= turns) {
                    cell.textContent = final;
                    window.clearInterval(timer);
                    return;
                }
                cell.textContent = digits[Math.floor(Math.random() * 10)];
            }, 70);
        });
    }

    /* ── 2. 출발 시각 ↔ 제시간 도착 확률 (설명용 합성 모델) ─────
       - 집 → 승강장 소요 ~ N(18분, 2분)
       - 열차 08:02 / 08:14 / 08:26 / 08:38 (12분 간격, 예시)
       - 열차별 '그 열차를 타면 09:00까지 도착할 확률' (하차 후 도보 분포 반영)
       첫 번째로 잡히는 열차 k의 확률 × 그 열차의 제시간 확률을 더한다. */
    var TRAINS = [
        { t: 482, ok: 1.0 },
        { t: 494, ok: 1.0 },
        { t: 506, ok: 0.34 },
        { t: 518, ok: 0.0 },
    ];
    var ACCESS_MU = 18;
    var ACCESS_SD = 2;
    var TARGET = 0.95;
    var X0 = 460; // 07:40
    var X1 = 492; // 08:12

    function erf(x) {
        // Abramowitz–Stegun 7.1.26
        var s = x < 0 ? -1 : 1;
        x = Math.abs(x);
        var t = 1 / (1 + 0.3275911 * x);
        var y =
            1 -
            ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t -
                0.284496736) *
                t +
                0.254829592) *
                t *
                Math.exp(-x * x);
        return s * y;
    }
    function cdf(z) {
        return 0.5 * (1 + erf(z / Math.SQRT2));
    }
    function catchUpTo(k, d) {
        return cdf((TRAINS[k].t - d - ACCESS_MU) / ACCESS_SD);
    }
    function trainShares(d) {
        var prev = 0;
        return TRAINS.map(function (_, k) {
            var q = catchUpTo(k, d);
            var share = Math.max(0, q - prev);
            prev = Math.max(prev, q);
            return share;
        });
    }
    function prob(d) {
        var shares = trainShares(d);
        var p = 0;
        for (var k = 0; k < TRAINS.length; k++) p += shares[k] * TRAINS[k].ok;
        return Math.min(1, Math.max(0, p));
    }
    function likelyTrain(d) {
        var shares = trainShares(d);
        var best = 0;
        for (var k = 1; k < shares.length; k++) if (shares[k] > shares[best]) best = k;
        return TRAINS[best].t;
    }
    function hhmm(m) {
        var h = Math.floor(m / 60);
        var mm = Math.round(m % 60);
        return (h < 10 ? "0" : "") + h + ":" + (mm < 10 ? "0" : "") + mm;
    }
    function pct(p) {
        // 앱과 같은 규칙: 버림 (0.999를 100%로 올리지 않는다)
        return Math.min(99, Math.floor(p * 100 + 1e-9));
    }
    function recommended() {
        var best = X0;
        for (var d = X0; d <= X1; d++) if (pct(prob(d)) >= TARGET * 100) best = d;
        return best;
    }

    var SVGNS = "http://www.w3.org/2000/svg";
    function el(name, attrs, parent, text) {
        var node = document.createElementNS(SVGNS, name);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        if (text != null) node.textContent = text;
        if (parent) parent.appendChild(node);
        return node;
    }

    function initOdds() {
        var root = document.querySelector("[data-odds]");
        if (!root) return;
        var svg = root.querySelector("[data-chart]");
        var input = root.querySelector("#depart");
        var out = {
            time: root.querySelector('[data-out="time"]'),
            prob: root.querySelector('[data-out="prob"]'),
            verdict: root.querySelector('[data-out="verdict"]'),
            train: root.querySelector('[data-out="train"]'),
        };

        var slider = root.querySelector(".odds-slider");
        var title = svg.querySelector("title");
        var L, R, T, B, rec, curLine, curDot, lastW;
        function sx(m) {
            return L + ((m - X0) / (X1 - X0)) * (R - L);
        }
        function sy(p) {
            return B - p * (B - T);
        }

        // 컨테이너 폭 그대로 viewBox를 잡아 글자 크기가 화면 크기와 무관하게 일정하다.
        function draw() {
            var W = Math.round(svg.parentNode.clientWidth) || 800;
            if (W === lastW) return;
            lastW = W;
            var narrow = W < 560;
            var H = narrow ? 280 : Math.min(420, Math.round(W * 0.34));
            L = narrow ? 40 : 52;
            R = W - (narrow ? 8 : 12);
            T = 44;
            B = H - 34;
            svg.setAttribute("viewBox", "0 0 " + W + " " + H);
            while (svg.lastChild && svg.lastChild !== title) svg.removeChild(svg.lastChild);
            // 슬라이더 트랙을 플롯 x축에 맞춘다 (썸 지름 24px)
            slider.style.marginLeft = L - 12 + "px";
            slider.style.marginRight = W - R - 12 + "px";

            var g = el("g", { class: "c-grid" }, svg);
            [0, 0.25, 0.5, 0.75, 1].forEach(function (p) {
                el("line", { x1: L, x2: R, y1: sy(p), y2: sy(p) }, g);
                el("text", { x: L - 8, y: sy(p) + 4, "text-anchor": "end", class: "c-ylab" }, g, Math.round(p * 100) + "%");
            });
            for (var m = X0; m <= X1; m += narrow ? 10 : 5) {
                el("text", { x: sx(m), y: B + 24, "text-anchor": "middle", class: "c-xlab" }, g, hhmm(m));
                el("line", { x1: sx(m), x2: sx(m), y1: B, y2: B + 6, class: "c-tick" }, g);
            }

            // 어떤 열차를 타게 되는지 구간 표시 (경계 = 열차 시각 − 평균 접근 시간)
            var bands = el("g", { class: "c-bands" }, svg);
            TRAINS.forEach(function (tr, k) {
                var from = k === 0 ? X0 : TRAINS[k - 1].t - ACCESS_MU;
                var to = tr.t - ACCESS_MU;
                var a = Math.max(X0, from);
                var b = Math.min(X1, to);
                if (k > 0 && from > X0 && from < X1)
                    el("line", { x1: sx(from), x2: sx(from), y1: T - 26, y2: B, class: "c-band-edge" }, bands);
                if (sx(b) - sx(a) < (narrow ? 44 : 80)) return;
                el(
                    "text",
                    { x: (sx(a) + sx(b)) / 2, y: T - 16, "text-anchor": "middle", class: "c-band-lab" },
                    bands,
                    hhmm(tr.t) + (narrow ? "" : " 열차")
                );
            });

            // 목표선
            el("line", { x1: L, x2: R, y1: sy(TARGET), y2: sy(TARGET), class: "c-target" }, svg);
            el("text", { x: R - 4, y: sy(TARGET) + 18, "text-anchor": "end", class: "c-target-lab" }, svg, "목표 " + Math.round(TARGET * 100) + "%");

            // 곡선
            var pts = [];
            for (var d = X0; d <= X1 + 1e-9; d += 0.2) pts.push([sx(d), sy(prob(d))]);
            var line = pts
                .map(function (p, i) {
                    return (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1);
                })
                .join(" ");
            el("path", { d: line + " L" + R + " " + B + " L" + L + " " + B + " Z", class: "c-area" }, svg);
            el("path", { d: line, class: "c-line" }, svg);

            // 추천 지점
            rec = recommended();
            var rx = sx(rec), ry = sy(prob(rec));
            var rg = el("g", { class: "c-rec" }, svg);
            el("rect", { x: rx - 6, y: ry - 6, width: 12, height: 12, transform: "rotate(45 " + rx + " " + ry + ")" }, rg);
            el("text", { x: rx - 14, y: ry + 26, "text-anchor": "end" }, rg, "추천 " + hhmm(rec));

            // 현재 커서
            var cur = el("g", { class: "c-cursor" }, svg);
            curLine = el("line", { y1: T - 4, y2: B }, cur);
            curDot = el("circle", { r: 7 }, cur);
            update();
        }

        function update() {
            var d = Number(input.value);
            var p = prob(d);
            var P = pct(p);
            var x = sx(d);
            curLine.setAttribute("x1", x);
            curLine.setAttribute("x2", x);
            curDot.setAttribute("cx", x);
            curDot.setAttribute("cy", sy(p));

            var level, text;
            if (P >= TARGET * 100) {
                level = "ok";
                text = d === rec ? "추천 출발 · 마지막 안전선" : "여유 · 추천 범위";
            } else if (P >= 80) {
                level = "tight";
                text = "빠듯함 · 신호 한 번이면 놓침";
            } else if (P >= 30) {
                level = "risk";
                text = "위험 · 다음 차에 걸어야 함";
            } else {
                level = "late";
                text = "지각 · 거의 확실";
            }
            out.time.textContent = hhmm(d);
            out.prob.textContent = String(P);
            out.verdict.textContent = text;
            out.verdict.setAttribute("data-level", level);
            out.train.textContent = "타게 될 차 " + hhmm(likelyTrain(d)) + " GTX-A";
            input.setAttribute(
                "aria-valuetext",
                Math.floor(d / 60) + "시 " + (d % 60) + "분 출발, 제시간 도착 " + P + "%"
            );
        }
        input.addEventListener("input", update);
        rec = recommended();
        draw();
        var raf = 0;
        window.addEventListener("resize", function () {
            window.cancelAnimationFrame(raf);
            raf = window.requestAnimationFrame(draw);
        });
        root.classList.add("is-ready");
    }

    function start() {
        initOdds();
        flapBoard();
    }
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
})();
