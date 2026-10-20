(function () {
    var S = window.__coaraTr;
    var first = false;
    var re;
    var kana = /[\u3040-\u30ff\u31f0-\u31ff]/;
    var latin = /[A-Za-z]/;
    var skipTargetJapanese = window.__coaraTrTarget === 'ja';
    try {
        re = new RegExp('\\p{L}', 'u');
    } catch (e) {
        re = /[A-Za-zÀ-ÖØ-öø-ÿĀ-žЀ-ӿ一-龯぀-ヿ가-힣ء-يऀ-ॿก-๛]/;
    }
    if (!S) {
        first = true;
        S = window.__coaraTr = {
            nodes: [],
            orig: new WeakMap(),
            tr: new WeakMap(),
            done: new WeakSet(),
            active: true,
            dirty: 0,
            observers: [],
            observedRoots: new WeakSet(),
            frameHandlers: new WeakSet(),
            roots: [],
            sk: 'script,style,noscript,textarea,code,pre,kbd,samp,svg,math,select,[translate="no"],[contenteditable=""],[contenteditable="true"],[contenteditable="plaintext-only"]'
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
            for (i = 0; i < S.observers.length; i++) {
                try {
                    S.observers[i].disconnect();
                } catch (e) {
                }
            }
            for (i = 0; i < S.nodes.length; i++) {
                n = S.nodes[i];
                if (n && n.isConnected && S.tr.has(n) && S.orig.has(n) && n.nodeValue === S.tr.get(n)) {
                    n.nodeValue = S.orig.get(n);
                }
            }
            S.active = false;
            S.nodes = [];
            S.roots = [];
            delete window.__coaraTr;
        };
        S.registerRoot = function (root) {
            if (!root || S.observedRoots.has(root)) {
                return;
            }
            S.observedRoots.add(root);
            S.roots.push(root);
            if (!window.MutationObserver) {
                return;
            }
            var observer = new MutationObserver(function (ms) {
                var i, j, m, n;
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
                        } else if (n.nodeValue && re.test(n.nodeValue)) {
                            S.done.delete(n);
                            S.dirty = 1;
                        }
                    } else if (m.addedNodes && m.addedNodes.length) {
                        S.dirty = 1;
                        for (j = 0; j < m.addedNodes.length; j++) {
                            S.discover(m.addedNodes[j]);
                        }
                    } else if (m.type === 'attributes') {
                        S.dirty = 1;
                    }
                }
            });
            observer.observe(root, {
                childList: true,
                subtree: true,
                characterData: true,
                attributes: true,
                attributeFilter: ['class', 'hidden', 'aria-hidden', 'translate', 'lang']
            });
            S.observers.push(observer);
        };
        S.bindFrame = function (frame) {
            if (!frame || S.frameHandlers.has(frame)) return;
            S.frameHandlers.add(frame);
            frame.addEventListener('load', function () {
                try {
                    var frameDoc = frame.contentDocument;
                    if (frameDoc && frameDoc.documentElement) {
                        S.registerRoot(frameDoc);
                        S.discover(frameDoc.documentElement);
                        S.dirty = 1;
                    }
                } catch (e) {
                }
            }, true);
        };
        S.discover = function (start) {
            if (!start) {
                return;
            }
            var stack = [start];
            var current, elements, i, el, doc;
            while (stack.length) {
                current = stack.pop();
                if (!current) {
                    continue;
                }
                if (current.nodeType === 1) {
                    el = current;
                    if (el.shadowRoot) {
                        S.registerRoot(el.shadowRoot);
                        stack.push(el.shadowRoot);
                    }
                    if (el.tagName === 'IFRAME') {
                        S.bindFrame(el);
                        try {
                            doc = el.contentDocument;
                            if (doc && doc.documentElement) {
                                S.registerRoot(doc);
                                if (doc.documentElement) stack.push(doc.documentElement);
                            }
                        } catch (e) {
                        }
                    }
                }
                if (current.querySelectorAll) {
                    elements = current.querySelectorAll('*');
                    for (i = 0; i < elements.length; i++) {
                        el = elements[i];
                        if (el.shadowRoot) {
                            S.registerRoot(el.shadowRoot);
                            stack.push(el.shadowRoot);
                        }
                        if (el.tagName === 'IFRAME') {
                            S.bindFrame(el);
                            try {
                                doc = el.contentDocument;
                                if (doc && doc.documentElement) {
                                    S.registerRoot(doc);
                                    stack.push(doc.documentElement);
                                }
                            } catch (e) {
                            }
                        }
                    }
                }
            }
        };
        S.registerRoot(document);
        S.discover(document.documentElement);
        if (window.addEventListener) {
            window.addEventListener('load', function () {
                S.discover(document.documentElement);
                S.dirty = 1;
            }, true);
        }
    }
    if (!S.active) {
        return {base: S.nodes.length, texts: [], groups: [], groupIds: [], lang: document.documentElement.lang || '', first: first};
    }
    S.dirty = 0;
    var roots = S.roots.length ? S.roots.slice() : [document];
    var vis = [];
    var rest = [];
    var sampleCandidates = [];
    var sampleOrder = 0;
    var vh = window.innerHeight || 800;
    var node, v, p, r, visible, clean, mainArea, blockArea, chromeArea, sampleScore, langNode, sampleLang, sampleMinIndex;
    var rootIndex, walker, doc, view, range, rects, rect, current, style, hidden, styleDepth;
    var visibilityCache = new WeakMap();
    function textBounds(textNode) {
        var parent = textNode.parentElement;
        if (!parent) return null;
        var bounds = parent.getBoundingClientRect();
        if (bounds.width > 0 && bounds.height > 0) return bounds;
        try {
            doc = textNode.ownerDocument || document;
            range = doc.createRange();
            range.selectNodeContents(textNode);
            rects = range.getClientRects();
            for (var ri = 0; ri < rects.length; ri++) {
                if (rects[ri].width > 0 && rects[ri].height > 0) return rects[ri];
            }
        } catch (e) {
        }
        return bounds;
    }
    function elementVisible(element, localView) {
        if (!element) return true;
        if (visibilityCache.has(element)) return visibilityCache.get(element);
        var chain = [];
        var cursor = element;
        var visibleState = true;
        while (cursor && chain.length < 40) {
            if (visibilityCache.has(cursor)) {
                visibleState = visibilityCache.get(cursor);
                break;
            }
            chain.push(cursor);
            if (cursor.hasAttribute('hidden')) {
                visibleState = false;
                break;
            }
            try {
                var computed = localView.getComputedStyle(cursor);
                if (computed.display === 'none' || computed.visibility === 'hidden' || computed.visibility === 'collapse' || computed.contentVisibility === 'hidden' || computed.opacity === '0') {
                    visibleState = false;
                    break;
                }
            } catch (e) {
            }
            cursor = cursor.parentElement || (cursor.getRootNode && cursor.getRootNode().host) || null;
        }
        for (var ci = 0; ci < chain.length; ci++) visibilityCache.set(chain[ci], visibleState);
        return visibleState;
    }
    function renderable(textNode) {
        var localDoc = textNode.ownerDocument || document;
        var localView = localDoc.defaultView || window;
        if (!elementVisible(textNode.parentElement, localView)) return false;
        var b = textBounds(textNode);
        return !!(b && b.width > 0 && b.height > 0);
    }
    function nearestBlock(element) {
        if (!element) return null;
        return element.closest('[role="comment"],[itemprop*="comment"],[data-comment-id],[class*="comment-text"],[class*="comment-body"],[class*="comment-content"],[class*="comment-item"]') || element.closest('p,h1,h2,h3,h4,h5,h6,blockquote,li,dd,dt,td,th,figcaption,summary,article,section,div') || element;
    }
    for (rootIndex = 0; rootIndex < roots.length; rootIndex++) {
        var root = roots[rootIndex];
        if (!root) continue;
        walker = (root.ownerDocument || root.nodeType === 9 && root || document).createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        while ((node = walker.nextNode())) {
            if (S.done.has(node)) continue;
            v = node.nodeValue;
            if (!v || !re.test(v)) continue;
            p = node.parentElement;
            if (!p || p.closest(S.sk)) continue;
            if (skipTargetJapanese && kana.test(v) && !latin.test(v)) {
                S.done.add(node);
                continue;
            }
            if (!renderable(node)) continue;
            r = textBounds(node);
            visible = !!(r && r.width > 0 && r.height > 0 && r.bottom >= 0 && r.top <= vh * 2);
            if (visible) vis.push(node);
            else rest.push(node);
            clean = v.replace(/\s+/g, ' ').trim();
            if (clean.length >= 8) {
                mainArea = p.closest('main,article,[role="main"],[itemprop="articleBody"],.article-content,.post-content,.entry-content,.article-body,.post-body,.content-body');
                blockArea = p.closest('p,h1,h2,h3,h4,h5,h6,blockquote,li,dd,dt,td,th,figcaption,summary');
                chromeArea = p.closest('nav,header,footer,aside,[role="navigation"],[role="menu"],[role="search"],form,[aria-label="breadcrumb"]');
                sampleScore = (mainArea ? 120 : 0) + (blockArea ? 40 : 0) + (visible ? 18 : 0) + Math.min(clean.length, 300) / 12;
                if (chromeArea) sampleScore -= 110;
                if (!chromeArea || sampleScore > 20) {
                    langNode = p.closest('[lang]');
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
    }
    var all = vis.concat(rest);
    var out = [];
    var groupIds = [];
    var groups = [];
    var groupMap = new WeakMap();
    var base = S.nodes.length;
    var chars = 0;
    var i, t, block, gid, contextText, contextLang;
    for (i = 0; i < all.length; i++) {
        node = all[i];
        t = node.nodeValue.replace(/\s+/g, ' ').trim();
        if (!t) {
            S.done.add(node);
            continue;
        }
        if (out.length >= 3000 || chars + t.length > 150000) {
            S.dirty = 1;
            break;
        }
        gid = -1;
        block = nearestBlock(node.parentElement);
        if (block) {
            langNode = node.parentElement.closest('[lang]:not(html):not(body)');
            var groupElement = langNode && block.contains(langNode) ? langNode : block;
            gid = groupMap.has(groupElement) ? groupMap.get(groupElement) : -1;
            if (gid < 0 && groups.length >= 400) {
                S.dirty = 1;
                break;
            }
            if (gid < 0) {
                gid = groups.length;
                groupMap.set(groupElement, gid);
                contextText = '';
                try {
                    var contextElement = groupElement === langNode ? groupElement : block;
                    contextText = (typeof contextElement.innerText === 'string' ? contextElement.innerText : contextElement.textContent || '').replace(/\s+/g, ' ').trim();
                } catch (e) {
                    contextText = (groupElement.textContent || '').replace(/\s+/g, ' ').trim();
                }
                if (contextText.length > 320) contextText = contextText.substring(0, 320);
                contextLang = groupElement === langNode ? (langNode.getAttribute('lang') || '') : '';
                groups.push({text: contextText, lang: contextLang});
            }
        }
        chars += t.length;
        S.done.add(node);
        S.nodes.push(node);
        out.push(t);
        groupIds.push(gid);
    }
    sampleCandidates.sort(function (a, b) {
        return b.score === a.score ? a.order - b.order : b.score - a.score;
    });
    var langSample = '';
    var contentLang = '';
    for (var si = 0; si < sampleCandidates.length && langSample.length < 1200; si++) {
        var part = sampleCandidates[si].text;
        var remain = 1200 - langSample.length;
        if (part.length > remain) part = part.substring(0, remain);
        if (part) langSample += (langSample ? ' ' : '') + part;
        if (!contentLang && sampleCandidates[si].lang) contentLang = sampleCandidates[si].lang;
    }
    if (!langSample) {
        for (var si = 0; si < out.length && langSample.length < 1200; si++) {
            var part = out[si];
            var remain = 1200 - langSample.length;
            if (part.length > remain) part = part.substring(0, remain);
            if (part) langSample += (langSample ? ' ' : '') + part;
        }
    }
    return {base: base, texts: out, groupIds: groupIds, groups: groups, lang: document.documentElement.lang || '', contentLang: contentLang, sample: langSample, first: first};
})()
