package com.khalsatorrent.cs3builder.network

/** JavaScript injected into pages. Kept in raw strings so it reads as plain JS. */
object CaptureScripts {

    /**
     * Hooks fetch, XMLHttpRequest, WebSocket and <video>/<audio> src so request + response bodies,
     * response headers and media URLs reach the app. Runs in every frame (iframes / embed players
     * included) when document-start injection is supported. Idempotent.
     */
    val HOOK = """
(function () {
  if (window.__cs3Hooked) return;
  window.__cs3Hooked = true;
  var B = window.CS3Bridge;
  if (!B) return;
  var MAX = 60000;

  function send(o) {
    try { o.page = location.href; B.onRequestComplete(JSON.stringify(o)); } catch (e) {}
  }
  function abs(u) {
    try { return new URL(String(u), location.href).href; } catch (e) { return String(u); }
  }
  function bodyStr(b) {
    if (b == null) return null;
    try {
      if (typeof b === 'string') return b.substring(0, MAX);
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return b.toString().substring(0, MAX);
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        var p = [];
        b.forEach(function (v, k) { p.push(encodeURIComponent(k) + '=' + encodeURIComponent(typeof v === 'string' ? v : '[file]')); });
        return p.join('&');
      }
      if (typeof Blob !== 'undefined' && b instanceof Blob) return '[blob ' + b.size + ' bytes]';
      if (b instanceof ArrayBuffer || ArrayBuffer.isView(b)) return '[binary ' + (b.byteLength || 0) + ' bytes]';
      return JSON.stringify(b).substring(0, MAX);
    } catch (e) { return '[body]'; }
  }
  function hdrs(h) {
    var o = {};
    if (!h) return o;
    try {
      if (typeof Headers !== 'undefined' && h instanceof Headers) h.forEach(function (v, k) { o[k] = v; });
      else if (Array.isArray(h)) h.forEach(function (p) { o[p[0]] = String(p[1]); });
      else for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) o[k] = String(h[k]);
    } catch (e) {}
    return o;
  }
  function skipBody(ct) { return /video|audio|image|font|octet-stream|zip|pdf/i.test(ct || ''); }

  /* ---- fetch ---- */
  var _fetch = window.fetch;
  if (_fetch) {
    window.fetch = function (input, init) {
      var t0 = Date.now(), url = '', method = 'GET', headers = {}, body = null;
      try {
        if (typeof input === 'string' || (typeof URL !== 'undefined' && input instanceof URL)) url = abs(input);
        else if (input && input.url) { url = input.url; method = input.method || 'GET'; headers = hdrs(input.headers); }
        if (init) {
          if (init.method) method = init.method;
          if (init.headers) { var extra = hdrs(init.headers); for (var k in extra) headers[k] = extra[k]; }
          if (init.body != null) body = bodyStr(init.body);
        }
      } catch (e) {}
      method = String(method).toUpperCase();
      var p = _fetch.apply(this, arguments);
      p.then(function (res) {
        var ct = '';
        try { ct = res.headers.get('content-type') || ''; } catch (e) {}
        var o = { url: url, method: method, headers: headers, body: body, type: 'fetch',
                  responseCode: res.status, responseHeaders: hdrs(res.headers), contentType: ct,
                  duration: Date.now() - t0 };
        if (skipBody(ct)) { send(o); return; }
        try {
          res.clone().text().then(function (t) { o.responseBody = t.substring(0, MAX); o.size = t.length; send(o); },
                                  function () { send(o); });
        } catch (e) { send(o); }
      }, function (err) {
        send({ url: url, method: method, headers: headers, body: body, type: 'fetch', responseCode: 0,
               error: String(err), duration: Date.now() - t0 });
      });
      return p;
    };
  }

  /* ---- XMLHttpRequest ---- */
  var XP = XMLHttpRequest.prototype, _open = XP.open, _send = XP.send, _set = XP.setRequestHeader;
  XP.open = function (m, u) {
    this.__cs3 = { m: String(m || 'GET').toUpperCase(), u: abs(u), h: {} };
    return _open.apply(this, arguments);
  };
  XP.setRequestHeader = function (n, v) {
    if (this.__cs3) this.__cs3.h[n] = String(v);
    return _set.apply(this, arguments);
  };
  XP.send = function (data) {
    var x = this, c = this.__cs3, t0 = Date.now();
    if (c) {
      this.addEventListener('loadend', function () {
        var rh = {}, ct = '', text = null;
        try {
          (x.getAllResponseHeaders() || '').trim().split(/[\r\n]+/).forEach(function (line) {
            var i = line.indexOf(':');
            if (i > 0) rh[line.substring(0, i).trim()] = line.substring(i + 1).trim();
          });
          ct = x.getResponseHeader('content-type') || '';
        } catch (e) {}
        try {
          if (!skipBody(ct)) {
            if (!x.responseType || x.responseType === 'text') text = x.responseText;
            else if (x.responseType === 'json') text = JSON.stringify(x.response);
            else if (x.responseType === 'document' && x.response) text = x.response.documentElement.outerHTML;
          }
        } catch (e) {}
        send({ url: c.u, method: c.m, headers: c.h, body: bodyStr(data), type: 'xhr',
               responseCode: x.status, responseHeaders: rh, contentType: ct,
               responseBody: text ? String(text).substring(0, MAX) : null,
               size: text ? String(text).length : -1, duration: Date.now() - t0 });
      });
    }
    return _send.apply(this, arguments);
  };

  /* ---- WebSocket ---- */
  var _WS = window.WebSocket;
  if (_WS) {
    var W = function (u, p) {
      send({ url: abs(u), method: 'WS', type: 'websocket', responseCode: 0 });
      return p === undefined ? new _WS(u) : new _WS(u, p);
    };
    W.prototype = _WS.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { W[k] = _WS[k]; });
    window.WebSocket = W;
  }

  /* ---- <video>/<audio> src ---- */
  try {
    var MP = HTMLMediaElement.prototype, d = Object.getOwnPropertyDescriptor(MP, 'src');
    if (d && d.set) {
      Object.defineProperty(MP, 'src', {
        configurable: true, enumerable: d.enumerable, get: d.get,
        set: function (v) {
          if (v && !/^(blob|data):/i.test(String(v))) send({ url: abs(v), method: 'GET', type: 'media', responseCode: 0 });
          return d.set.call(this, v);
        }
      });
    }
  } catch (e) {}
})();
""".trimIndent()

    /** Returns the rendered DOM of the current frame (capped). */
    const val DOM_SNAPSHOT =
        "(function(){try{return document.documentElement.outerHTML.substring(0, 500000);}catch(e){return '';}})()"

    /** Ad / popup suppression. Re-injected on every page while the blocker is on. */
    val AD_BLOCK = """
(function () {
  if (window.__cs3AdBlock) return;
  window.__cs3AdBlock = true;
  var AD = /doubleclick|googlesyndication|popads|popcash|propellerads|exoclick|adsterra|outbrain|taboola|hilltopads|juicyads|onclick|clkads|onclkds|adcash|trafficjunky|realsrv|a-ads|bidgear/i;
  window.open = function () { return null; };
  var _st = window.setTimeout;
  window.setTimeout = function (fn) {
    if (typeof fn === 'string' && /window\.open|location/.test(fn)) return 0;
    return _st.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a && (AD.test(a.href || '') || a.target === '_blank' && a.href && new URL(a.href, location.href).host !== location.host && !a.textContent.trim())) {
      e.preventDefault(); e.stopPropagation();
    }
  }, true);
  var style = document.createElement('style');
  style.textContent = 'iframe[src*="ads"],iframe[id^="google_ads"],ins.adsbygoogle,[id^="div-gpt-ad"],[class*="popunder"],[class*="ad-overlay"]{display:none!important}';
  (document.head || document.documentElement).appendChild(style);
})();
""".trimIndent()
}
