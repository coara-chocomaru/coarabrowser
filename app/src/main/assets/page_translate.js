(function () {
    var S = window.__coaraTr;
    var first = false;
    var re;
    try {
        re = new RegExp('\\p{L}', 'u');
    } catch (e) {
        re = /[A-Za-zÀ-ÖØ-öø-ÿĀ-žЀ-ӿ一-龯぀-ヿ가-힣ء-يऀ-ॿก-๛]/;
    }
    var kana = /[\u3040-\u30ff\u31f0-\u31ff]/;
    var latin = /[A-Za-z]/;
    var skipKana = window.__coaraTrTarget === 'ja';
    if (!S) {
        first = true;
        S = window.__coaraTr = {
            nodes: [],
            orig: new WeakMap(),
            tr: new WeakMap(),
            done: new WeakSet(),
            active: true,
            dirty: 0,
            obs: null,
            sk: 'script,style,noscript,textarea,code,pre,kbd,samp,svg,math,select,[translate="no"],.notranslate,[contenteditable=""],[contenteditable="true"]'
        };
        S.apply = function (base, arr) {
            var i, n, t, o, lead, trail, v;
            for (i = 0; i < arr.length; i++) {
                n = S.nodes[base + i];
                t = arr[i];
                if (!n || !t || !n.isConnected) {
                    continue;
                }
                o = n.nodeValue;
                lead = o.match(/^\s*/)[0];
                trail = o.match(/\s*$/)[0];
                v = lead + t + trail;
                S.orig.set(n, o);
                S.tr.set(n, v);
                n.nodeValue = v;
            }
        };
        S.restore = function () {
            var i, n;
            if (S.obs) {
                S.obs.disconnect();
            }
            for (i = 0; i < S.nodes.length; i++) {
                n = S.nodes[i];
                if (n && n.isConnected && S.tr.has(n) && S.orig.has(n) && n.nodeValue === S.tr.get(n)) {
                    n.nodeValue = S.orig.get(n);
                }
            }
            S.active = false;
            S.nodes = [];
            delete window.__coaraTr;
        };
        if (window.MutationObserver) {
            S.obs = new MutationObserver(function (ms) {
                var i, m, n;
                for (i = 0; i < ms.length; i++) {
                    m = ms[i];
                    if (m.type === 'characterData') {
                        n = m.target;
                        if (S.tr.has(n)) {
                            if (n.nodeValue !== S.tr.get(n)) {
                                S.tr.delete(n);
                                S.done.delete(n);
                                S.dirty = 1;
                            }
                        } else if (!S.done.has(n) && n.nodeValue && re.test(n.nodeValue)) {
                            S.dirty = 1;
                        }
                    } else if (m.addedNodes && m.addedNodes.length) {
                        S.dirty = 1;
                    }
                }
            });
            S.obs.observe(document.documentElement, {childList: true, subtree: true, characterData: true});
        }
    }
    S.dirty = 0;
    for (var k = 0; k < S.nodes.length; k++) {
        if (S.nodes[k] && !S.nodes[k].isConnected) {
            S.nodes[k] = null;
        }
    }
    var root = document.body || document.documentElement;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var vis = [];
    var rest = [];
    var sampleCandidates = [];
    var sampleOrder = 0;
    var vh = window.innerHeight || 800;
    var node, v, p, r, visible, clean, mainArea, blockArea, chromeArea, sampleScore, langNode, sampleLang, sampleMinIndex;
    while ((node = walker.nextNode())) {
        if (S.done.has(node)) {
            continue;
        }
        v = node.nodeValue;
        if (!v || !re.test(v)) {
            continue;
        }
        p = node.parentElement;
        if (!p || p.closest(S.sk)) {
            continue;
        }
        r = p.getBoundingClientRect();
        visible = !!(r.width > 0 && r.height > 0 && r.bottom >= 0 && r.top <= vh * 2);
        if (visible) {
            vis.push(node);
        } else {
            rest.push(node);
        }
        clean = v.replace(/\s+/g, ' ').trim();
        if (clean.length >= 12 && r.width > 0 && r.height > 0 && re.test(clean) && !(skipKana && kana.test(clean) && !latin.test(clean))) {
            mainArea = p.closest('main,article,[role="main"],[itemprop="articleBody"],.article-content,.post-content,.entry-content,.article-body,.post-body,.content-body');
            blockArea = p.closest('p,h1,h2,h3,h4,h5,h6,blockquote,li,dd,dt');
            chromeArea = p.closest('nav,header,footer,aside,[role="navigation"],[role="menu"],[role="search"],form,[aria-label="breadcrumb"]');
            sampleScore = (mainArea ? 120 : 0) + (blockArea ? 40 : 0) + (visible ? 18 : 0) + Math.min(clean.length, 300) / 12;
            if (chromeArea) sampleScore -= 110;
            if (clean.length < 12) sampleScore -= 18;
            if (!chromeArea || sampleScore > 20) {
                langNode = p.closest('[lang]:not(html):not(body)');
                sampleLang = langNode ? (langNode.getAttribute('lang') || '') : '';
                var candidate = {text: clean, score: sampleScore, order: sampleOrder++, lang: sampleLang};
                if (sampleCandidates.length < 64) {
                    sampleCandidates.push(candidate);
                    if (sampleCandidates.length === 64) {
                        sampleMinIndex = 0;
                        for (var si = 1; si < sampleCandidates.length; si++) {
                            if (sampleCandidates[si].score < sampleCandidates[sampleMinIndex].score) sampleMinIndex = si;
                        }
                    }
                } else if (candidate.score > sampleCandidates[sampleMinIndex].score) {
                    sampleCandidates[sampleMinIndex] = candidate;
                    sampleMinIndex = 0;
                    for (var si = 1; si < sampleCandidates.length; si++) {
                        if (sampleCandidates[si].score < sampleCandidates[sampleMinIndex].score) sampleMinIndex = si;
                    }
                }
            }
        }
    }
    var all = vis.concat(rest);
    var out = [];
    var base = S.nodes.length;
    var chars = 0;
    var i, t;
    for (i = 0; i < all.length; i++) {
        node = all[i];
        t = node.nodeValue.replace(/\s+/g, ' ').trim();
        if (!t) {
            continue;
        }
        if (skipKana && kana.test(t) && !latin.test(t)) {
            S.done.add(node);
            continue;
        }
        if (out.length >= 3000 || chars + t.length > 150000) {
            S.dirty = 1;
            break;
        }
        chars += t.length;
        S.done.add(node);
        S.nodes.push(node);
        out.push(t);
    }
    sampleCandidates.sort(function (a, b) {
        return b.score === a.score ? a.order - b.order : b.score - a.score;
    });
    var langSample = '';
    var contentLang = '';
    for (var si = 0; si < sampleCandidates.length && langSample.length < 1000; si++) {
        var part = sampleCandidates[si].text;
        var remain = 1000 - langSample.length;
        if (part.length > remain) part = part.substring(0, remain);
        if (part) langSample += (langSample ? ' ' : '') + part;
        if (!contentLang && sampleCandidates[si].lang) contentLang = sampleCandidates[si].lang;
    }
    if (!langSample) {
        for (var si = 0; si < out.length && langSample.length < 1000; si++) {
            var part = out[si];
            var remain = 1000 - langSample.length;
            if (part.length > remain) part = part.substring(0, remain);
            if (part) langSample += (langSample ? ' ' : '') + part;
        }
    }
    return {base: base, texts: out, lang: document.documentElement.lang || '', contentLang: contentLang, sample: langSample, first: first};
})()
