package com.khalsatorrent.cs3builder.network

/** JavaScript for the browser's element picker and JS console. */
object InspectorScripts {

    /**
     * Turns on pick mode: taps highlight an element instead of clicking it, and report a
     * generalised CSS selector (one that matches the element and its look-alike siblings, e.g.
     * every movie card), a unique selector, the match count, samples and useful attributes to
     * `CS3Inspector.onPick`. Calling it again turns pick mode off.
     */
    val PICKER = """
(function () {
  if (window.__cs3Picker) { window.__cs3Picker.stop(); return 'off'; }
  var BAD = /^(active|current|selected|show|hide|hidden|open|clearfix|row|col|col-.*|d-.*|p-.*|m-.*|mb-.*|mt-.*|px-.*|py-.*|lazy|lazyload(ed)?|loaded|swiper-slide-.*|slick-.*|owl-.*|js-.*|is-.*|has-.*|w-.*|h-.*|flex.*|grid.*|text-.*|bg-.*|hover.*|focus.*)$/;
  var box = document.createElement('div');
  box.setAttribute('style', 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #ff2d55;background:rgba(255,45,85,.18);border-radius:4px;display:none');
  var tag = document.createElement('div');
  tag.setAttribute('style', 'position:fixed;pointer-events:none;z-index:2147483647;background:#ff2d55;color:#fff;font:12px monospace;padding:2px 5px;border-radius:3px;display:none;max-width:90vw;overflow:hidden;white-space:nowrap');
  document.documentElement.appendChild(box); document.documentElement.appendChild(tag);

  function esc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/([^a-zA-Z0-9_-])/g, '\\$1'); }
  function classes(el) {
    return Array.prototype.filter.call(el.classList || [], function (c) {
      return c.length < 40 && !BAD.test(c) && (c.match(/\d/g) || []).length <= 3 && /^-?[_a-zA-Z]/.test(c);
    }).slice(0, 2);
  }
  function part(el) {
    var s = el.tagName.toLowerCase(), c = classes(el);
    if (c.length) s += c.map(function (x) { return '.' + esc(x); }).join('');
    return s;
  }
  function count(sel) { try { return document.querySelectorAll(sel).length; } catch (e) { return 0; } }
  // Generalised: walk up adding ancestors until the selector is anchored (has a class) and not too broad
  function general(el) {
    var parts = [], cur = el;
    for (var depth = 0; cur && cur.nodeType === 1 && cur !== document.body && depth < 4; depth++) {
      parts.unshift(part(cur));
      var sel = parts.join(' ');
      var n = count(sel);
      var anchored = /[.#]/.test(sel);
      if (anchored && n >= 1 && n <= 300) return sel;
      cur = cur.parentElement;
    }
    return parts.join(' ');
  }
  function unique(el) {
    if (el.id && /^[a-zA-Z][\w-]*$/.test(el.id) && count('#' + el.id) === 1) return '#' + el.id;
    var path = [], cur = el;
    while (cur && cur.nodeType === 1 && cur !== document.documentElement) {
      var p = part(cur), parent = cur.parentElement;
      if (cur.id && /^[a-zA-Z][\w-]*$/.test(cur.id)) { path.unshift('#' + cur.id); break; }
      if (parent) {
        var same = Array.prototype.filter.call(parent.children, function (x) { return x.tagName === cur.tagName; });
        if (same.length > 1) p += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
      }
      path.unshift(p);
      if (count(path.join(' > ')) === 1) break;
      cur = parent;
    }
    return path.join(' > ');
  }
  function attrs(el) {
    var o = {}, names = ['href', 'src', 'data-src', 'data-original', 'data-lazy', 'data-lazy-src', 'srcset', 'alt', 'title', 'data-id', 'data-url', 'data-embed', 'data-link', 'data-video', 'data-post', 'data-nume'];
    names.forEach(function (n) { var v = el.getAttribute && el.getAttribute(n); if (v) o[n] = n === 'href' || n === 'src' ? (el[n] || v) : v; });
    var img = el.tagName === 'IMG' ? el : el.querySelector && el.querySelector('img');
    if (img && img !== el) ['src', 'data-src', 'data-original', 'data-lazy', 'data-lazy-src'].forEach(function (n) { var v = img.getAttribute(n); if (v && !/^data:/.test(v)) o['img ' + n] = v; });
    var a = el.closest && el.closest('a[href]');
    if (a && !o.href) o['link'] = a.href;
    return o;
  }
  function cssProps(el) {
    var KEYS = ['display','position','width','height','margin','padding','background','background-color',
      'color','font-size','font-weight','font-family','border','border-radius','box-shadow',
      'flex','flex-direction','align-items','justify-content','grid-template-columns',
      'overflow','opacity','visibility','z-index','cursor','text-decoration','line-height',
      'white-space','text-overflow','max-width','min-width','max-height','min-height',
      'transform','transition','animation','content','list-style'];
    try {
      var cs = window.getComputedStyle(el);
      var out = {};
      KEYS.forEach(function(k) {
        var v = cs.getPropertyValue(k);
        if (v && v !== '' && v !== 'none' && v !== 'normal' && v !== 'auto' && v !== '0px' && v !== 'initial' && v !== 'inherit' && v !== 'transparent' && v !== 'rgba(0, 0, 0, 0)') {
          out[k] = v;
        }
      });
      return out;
    } catch(e) { return {}; }
  }
  function report(el) {
    if (!el || el.nodeType !== 1) return;
    window.__cs3Last = el;
    var g = general(el), samples = [];
    try { Array.prototype.slice.call(document.querySelectorAll(g), 0, 4).forEach(function (m) { samples.push((m.innerText || m.getAttribute('alt') || m.getAttribute('src') || '').trim().replace(/\s+/g, ' ').substring(0, 80)); }); } catch (e) {}
    var r = { general: g, generalCount: count(g), unique: unique(el), tag: el.tagName.toLowerCase(),
              text: (el.innerText || '').trim().replace(/\s+/g, ' ').substring(0, 160), attrs: attrs(el), samples: samples,
              html: (el.outerHTML || '').substring(0, 800), page: location.href, css: cssProps(el) };
    try { CS3Inspector.onPick(JSON.stringify(r)); } catch (e) {}
  }
  function show(el) {
    if (!el || el.nodeType !== 1) return;
    var r = el.getBoundingClientRect();
    box.style.display = 'block'; box.style.left = r.left + 'px'; box.style.top = r.top + 'px';
    box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
    tag.style.display = 'block'; tag.textContent = part(el) + '  ' + Math.round(r.width) + '×' + Math.round(r.height);
    tag.style.left = Math.max(0, r.left) + 'px'; tag.style.top = Math.max(0, r.top - 20) + 'px';
  }
  function at(e) { var t = e.touches && e.touches[0] || e.changedTouches && e.changedTouches[0] || e; return document.elementFromPoint(t.clientX, t.clientY); }
  function block(e) { e.preventDefault(); e.stopPropagation(); if (e.stopImmediatePropagation) e.stopImmediatePropagation(); }
  function onMove(e) { show(at(e)); }
  function onClick(e) { block(e); var el = at(e); show(el); report(el); return false; }
  var evs = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchend'];
  window.addEventListener('touchstart', onMove, { capture: true, passive: true });
  window.addEventListener('touchmove', onMove, { capture: true, passive: true });
  window.addEventListener('click', onClick, true);
  evs.forEach(function (n) { window.addEventListener(n, block, true); });
  window.__cs3Picker = {
    stop: function () {
      window.removeEventListener('touchstart', onMove, true); window.removeEventListener('touchmove', onMove, true);
      window.removeEventListener('click', onClick, true);
      evs.forEach(function (n) { window.removeEventListener(n, block, true); });
      box.remove(); tag.remove(); window.__cs3Picker = null;
    },
    parent: function () { var p = window.__cs3Last && window.__cs3Last.parentElement; if (p && p !== document.body) { show(p); report(p); } },
    child: function () { var c = window.__cs3Last && window.__cs3Last.firstElementChild; if (c) { show(c); report(c); } }
  };
  return 'on';
})();
""".trimIndent()

    const val PICK_PARENT = "window.__cs3Picker && window.__cs3Picker.parent()"
    const val PICK_CHILD = "window.__cs3Picker && window.__cs3Picker.child()"
    const val PICK_STOP = "window.__cs3Picker && window.__cs3Picker.stop()"

    /** JS that counts matches for [selectorJson] (a JSON string literal) and returns samples. */
    fun testSelector(selectorJson: String) = """
(function(){try{var l=document.querySelectorAll($selectorJson);var s=[];for(var i=0;i<Math.min(l.length,5);i++){s.push((l[i].innerText||l[i].getAttribute('alt')||l[i].getAttribute('src')||'').trim().replace(/\s+/g,' ').substring(0,80));}
return JSON.stringify({count:l.length,samples:s});}catch(e){return JSON.stringify({error:String(e)});}})()
""".trimIndent()

    /** Runs [codeJson] (a JSON string literal holding user code) and returns a printable result. */
    fun console(codeJson: String) = """
(function(){try{var r=(0,eval)($codeJson);if(r&&typeof r.then==='function'){return '[Promise — await is not supported here; use .then(console.log)]';}
if(r===undefined)return 'undefined';if(typeof r==='string')return r;
if(r instanceof Element)return r.outerHTML.substring(0,4000);
if(r&&typeof r.length==='number'&&typeof r!=='function'&&!(r instanceof Array)){r=Array.prototype.slice.call(r).map(function(x){return x instanceof Element?(x.outerHTML||'').substring(0,300):x;});}
return JSON.stringify(r,null,2);}catch(e){return 'Error: '+(e&&e.message||e);}})()
""".trimIndent()

    /** Ready-made console snippets that help reverse engineering a page. */
    val SNIPPETS = linkedMapOf(
        "Video sources on the page" to "[...document.querySelectorAll('video,source,audio')].map(v=>v.currentSrc||v.src).filter(Boolean)",
        "Iframes (embedded players)" to "[...document.querySelectorAll('iframe')].map(f=>f.src||f.dataset.src)",
        "All links on the page" to "[...new Set([...document.querySelectorAll('a[href]')].map(a=>a.href))].slice(0,300)",
        "localStorage" to "JSON.stringify(Object.assign({},localStorage),null,2)",
        "sessionStorage" to "JSON.stringify(Object.assign({},sessionStorage),null,2)",
        "Cookies (JS-visible)" to "document.cookie",
        "Inline JSON data (__NEXT_DATA__, ld+json…)" to "[...document.querySelectorAll('script[type*=json],script#__NEXT_DATA__')].map(s=>s.textContent.substring(0,3000))",
        "Player config globals (jwplayer, Hls, sources)" to "Object.keys(window).filter(k=>/player|jw|hls|dash|video|source|config|stream|token|key/i.test(k))",
        "Page globals added by the site" to "(function(){var f=document.createElement('iframe');f.style.display='none';document.body.appendChild(f);var d=Object.keys(f.contentWindow);f.remove();return Object.keys(window).filter(k=>d.indexOf(k)<0)})()",
        "Meta tags (og:, description…)" to "[...document.querySelectorAll('meta[property],meta[name]')].map(m=>(m.getAttribute('property')||m.name)+' = '+m.content)"
    )
}
