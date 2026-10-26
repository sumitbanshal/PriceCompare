/*
 * PriceCompare extractor — injected into every platform page at document start.
 *
 * Three data sources, merged:
 *   1. "api": JSON responses the page itself fetches (fetch + XHR are wrapped).
 *   2. "ssr": state embedded in the HTML (__NEXT_DATA__, __INITIAL_STATE__, ld+json ...).
 *   3. "dom": fallback scan of visible product cards (text containing ₹ amounts).
 *
 * The Android side calls:
 *   __PC.configure({platform, query, priceScale})
 *   __PC.snapshot()  -> number of items found so far (used to decide when results settled)
 *   __PC.finish()    -> JSON string {status, items:[...], notes:[...]}
 */
(function () {
  if (window.__PC && window.__PC.version) return;

  var PC = {
    version: 1,
    cfg: { platform: "", query: "", priceScale: 0 },
    items: {},       // key -> item
    apiHits: 0,
    errors: [],
  };
  // config injected by the app ahead of this script (so it applies to early API calls)
  try { if (window.__PC_CFG) for (var ck in window.__PC_CFG) PC.cfg[ck] = window.__PC_CFG[ck]; } catch (e) {}

  // ---------- helpers ----------
  function num(v) {
    if (v == null) return null;
    if (typeof v === "number") return isFinite(v) ? v : null;
    if (typeof v === "string") {
      var m = v.replace(/,/g, "").match(/-?\d+(?:\.\d+)?/);
      return m ? parseFloat(m[0]) : null;
    }
    if (typeof v === "object") {
      // nested price objects: {value: 45}, {amount: 45}, {units: 45, nanos: ...}
      var keys = ["value", "amount", "price", "units", "decimalValue", "displayValue", "formattedValue"];
      for (var i = 0; i < keys.length; i++) {
        if (v[keys[i]] != null && typeof v[keys[i]] !== "object") return num(v[keys[i]]);
      }
    }
    return null;
  }

  function text(el) {
    if (!el) return "";
    var t = el.innerText;
    return typeof t === "string" ? t : el.textContent || "";
  }

  function clean(s) {
    return String(s || "").replace(/\s+/g, " ").trim();
  }

  // ---------- quantity + unit price ----------
  var UNIT = {
    kg: ["kg", 1], kgs: ["kg", 1], kilo: ["kg", 1], kilogram: ["kg", 1], kilograms: ["kg", 1],
    g: ["kg", 0.001], gm: ["kg", 0.001], gms: ["kg", 0.001], gr: ["kg", 0.001], gram: ["kg", 0.001], grams: ["kg", 0.001], grm: ["kg", 0.001],
    mg: ["kg", 0.000001],
    l: ["L", 1], lt: ["L", 1], ltr: ["L", 1], ltrs: ["L", 1], litre: ["L", 1], litres: ["L", 1], liter: ["L", 1], liters: ["L", 1],
    ml: ["L", 0.001], mls: ["L", 0.001],
    pc: ["pc", 1], pcs: ["pc", 1], piece: ["pc", 1], pieces: ["pc", 1], unit: ["pc", 1], units: ["pc", 1],
    n: ["pc", 1], nos: ["pc", 1], no: ["pc", 1], egg: ["pc", 1], eggs: ["pc", 1], count: ["pc", 1],
    sachet: ["pc", 1], sachets: ["pc", 1], bar: ["pc", 1], bars: ["pc", 1], tab: ["pc", 1], tabs: ["pc", 1],
    tablet: ["pc", 1], tablets: ["pc", 1], roll: ["pc", 1], rolls: ["pc", 1], sheet: ["pc", 1], sheets: ["pc", 1],
  };
  var UNIT_RE_SRC = Object.keys(UNIT).sort(function (a, b) { return b.length - a.length; }).join("|");
  var NUM_SRC = "(\\d+(?:\\.\\d+)?)";
  var MULTI_RE = new RegExp(NUM_SRC + "\\s*(?:x|×|\\*)\\s*" + NUM_SRC + "\\s*(" + UNIT_RE_SRC + ")\\b", "i");
  var MULTI_RE2 = new RegExp(NUM_SRC + "\\s*(" + UNIT_RE_SRC + ")\\s*(?:x|×|\\*)\\s*" + NUM_SRC + "\\b", "i");
  var SINGLE_RE = new RegExp(NUM_SRC + "\\s*(" + UNIT_RE_SRC + ")\\b", "gi");
  var PACKOF_RE = /pack\s*of\s*(\d+)|(\d+)\s*(?:pack|pkt|packs)\b|combo\s*of\s*(\d+)/i;

  function parseQty(str) {
    if (!str) return null;
    var s = String(str).toLowerCase().replace(/,/g, "");
    var m;
    if ((m = s.match(MULTI_RE))) {
      var u = UNIT[m[3].toLowerCase()];
      return { amount: parseFloat(m[1]) * parseFloat(m[2]) * u[1], base: u[0] };
    }
    if ((m = s.match(MULTI_RE2))) {
      var u2 = UNIT[m[2].toLowerCase()];
      return { amount: parseFloat(m[1]) * parseFloat(m[3]) * u2[1], base: u2[0] };
    }
    // prefer weight/volume over counts
    var found = null, count = null;
    SINGLE_RE.lastIndex = 0;
    while ((m = SINGLE_RE.exec(s))) {
      var unit = UNIT[m[2].toLowerCase()];
      var val = parseFloat(m[1]);
      if (!(val > 0)) continue;
      if (unit[0] === "pc") { if (!count) count = { amount: val, base: "pc" }; }
      else if (!found) found = { amount: val * unit[1], base: unit[0] };
    }
    var pack = s.match(PACKOF_RE);
    var packN = pack ? parseFloat(pack[1] || pack[2] || pack[3]) : 1;
    if (found) {
      if (packN > 1 && packN < 100) found.amount *= packN;
      return found;
    }
    if (count) return count;
    if (packN > 1) return { amount: packN, base: "pc" };
    return null;
  }

  function withUnitPrice(it) {
    var q = parseQty(it.quantity) || parseQty(it.name);
    if (q && q.amount > 0 && it.price > 0) {
      it.unitBase = q.base;
      it.unitAmount = +q.amount.toFixed(6);
      it.unitPrice = +(it.price / q.amount).toFixed(2);
    }
    return it;
  }

  // ---------- relevance ----------
  function tokens(s) {
    return String(s || "").toLowerCase().replace(/[^a-z0-9ऀ-ॿ ]+/g, " ").split(/\s+/)
      .filter(function (t) { return t.length >= 2; })
      .map(function (t) { return t.length > 3 ? t.replace(/(es|s)$/, "") : t; });
  }
  var QTY_TOKEN = new RegExp("^(\\d+(\\.\\d+)?(" + UNIT_RE_SRC + ")?|" + UNIT_RE_SRC + "|litre|liter|pack|of)$", "i");
  function relevance(name, query) {
    var q = tokens(query).filter(function (t) { return !QTY_TOKEN.test(t); });
    if (!q.length) return 1;
    var n = " " + tokens(name).join(" ") + " ";
    var hit = 0;
    q.forEach(function (t) { if (n.indexOf(t) !== -1) hit++; });
    return hit / q.length;
  }

  // ---------- add item ----------
  function add(raw, source) {
    var name = clean(raw.name);
    if (name.length < 3 || name.length > 220) return false;
    var price = raw.price, mrp = raw.mrp;
    if (!(price > 0)) { price = mrp; mrp = null; }
    if (!(price > 0) || price > 500000) return false;
    if (mrp != null && !(mrp > price)) mrp = null;
    var it = {
      name: name,
      price: price,
      mrp: mrp || null,
      quantity: clean(raw.quantity) || null,
      image: raw.image || null,
      url: raw.url || null,
      inStock: raw.inStock === false ? false : true,
      source: source,
      raw: !!raw.rawScale, // price still in platform scale (maybe paise)
    };
    var key = name.toLowerCase() + "|" + (it.quantity || "").toLowerCase() + "|" + source;
    var prev = PC.items[key];
    if (prev && prev.price <= it.price) return false;
    PC.items[key] = it;
    return true;
  }

  // ---------- JSON walker ----------
  var NAME_KEYS = /^(name|product_?name|display_?name|productname|title|item_?name|product_?title)$/i;
  var SP_KEYS = /^(price|sp|selling_?price|offer_?price|final_?price|discounted_?price|discounted_?selling_?price|sale_?price|store_?price|special_?price|deal_?price|our_?price|effective_?price|price_?to_?pay)$/i;
  var MRP_KEYS = /^(mrp|max_?retail_?price|list_?price|original_?price|marked_?price|strike_?price|strikethrough_?price|base_?price|actual_?price)$/i;
  var QTY_KEYS = /^(unit|quantity|weight|size|variant|uom|unit_?of_?measure)$|pack_?size|net_?(quantity|qty|weight)|pack_?desc|unit_?(quantity|string|text|label)|display_?unit|weight_?in|quantity_?(text|label|desc)|sku_?quantity/i;
  var IMG_KEYS = /^(image|image_?url|img|thumbnail|images|image_?urls|media|primary_?image)$/i;
  var STOCK_KEYS = /^(in_?stock|is_?in_?stock|available|is_?available|availability|out_?of_?stock|is_?sold_?out|sold_?out|unavailable)$/i;

  function firstString(v) {
    if (typeof v === "string") return v;
    if (Array.isArray(v) && v.length) return firstString(v[0]);
    if (v && typeof v === "object") return firstString(v.url || v.src || v.path || v.text || v.value);
    return null;
  }

  var MERGE_CHILD = /price|pricing|offer|product|item|info|detail|attribute|variant|sku|meta|inventory|availability|display/i;

  // own props + shallow props of price/product-like child objects (own props win)
  function flatProps(o) {
    var out = [];
    var have = {};
    for (var k in o) {
      if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
      out.push([k, o[k]]); have[k.toLowerCase()] = 1;
    }
    for (var c in o) {
      var child = o[c];
      if (!child || typeof child !== "object" || Array.isArray(child) || !MERGE_CHILD.test(c)) continue;
      for (var ck in child) {
        if (!Object.prototype.hasOwnProperty.call(child, ck) || have[ck.toLowerCase()]) continue;
        var cv = child[ck];
        if (cv && typeof cv === "object" && !SP_KEYS.test(ck) && !MRP_KEYS.test(ck) && !IMG_KEYS.test(ck)) continue;
        out.push([ck, cv]); have[ck.toLowerCase()] = 1;
      }
    }
    return out;
  }

  function examine(o, source, scale) {
    var name = null, sps = [], mrps = [], qty = null, img = null, stock = null;
    var props = flatProps(o);
    for (var pi = 0; pi < props.length; pi++) {
      var k = props[pi][0], v = props[pi][1];
      if (!name && NAME_KEYS.test(k) && typeof v === "string") name = v;
      else if (SP_KEYS.test(k)) { var a = num(v); if (a != null) sps.push(a); }
      else if (MRP_KEYS.test(k)) { var b = num(v); if (b != null) mrps.push(b); }
      else if (!qty && QTY_KEYS.test(k) && (typeof v === "string" || typeof v === "number")) qty = String(v);
      else if (!img && IMG_KEYS.test(k)) img = firstString(v);
      else if (stock == null && STOCK_KEYS.test(k)) {
        if (typeof v === "boolean") stock = /out|sold|unavail/i.test(k) ? !v : v;
        else if (typeof v === "string") stock = !/out|unavail|sold/i.test(v);
      }
    }
    if (!name || (!sps.length && !mrps.length)) return;
    // reject obvious non-products (categories, banners, filters)
    if (/^(all|filters?|sort|offers?|categories)$/i.test(name.trim())) return;
    var sp = sps.length ? Math.min.apply(null, sps.filter(function (x) { return x > 0; }).concat([Infinity])) : null;
    if (sp === Infinity) sp = null;
    var mrp = mrps.length ? Math.max.apply(null, mrps) : null;
    var s = scale || 1;
    add({
      name: name,
      price: sp != null ? sp * s : null,
      mrp: mrp != null ? mrp * s : null,
      quantity: qty && !/^\d+$/.test(qty) ? qty : null,
      image: img,
      inStock: stock,
      rawScale: !PC.cfg.priceScale,
    }, source);
  }

  function walk(root, source) {
    var scale = PC.cfg.priceScale || 1;
    var stack = [[root, 0]], seen = 0;
    while (stack.length && seen < 300000) {
      var pair = stack.pop(), o = pair[0], d = pair[1];
      seen++;
      if (!o || typeof o !== "object" || d > 60) continue;
      if (Array.isArray(o)) {
        for (var i = 0; i < o.length; i++) if (o[i] && typeof o[i] === "object") stack.push([o[i], d + 1]);
        continue;
      }
      try { examine(o, source, scale); } catch (e) {}
      for (var k in o) {
        var v = o[k];
        if (v && typeof v === "object") stack.push([v, d + 1]);
        else if (typeof v === "string" && v.length > 40 && (v[0] === "{" || v[0] === "[")) {
          // some APIs embed JSON as strings
          try { stack.push([JSON.parse(v), d + 1]); } catch (e) {}
        }
      }
    }
  }

  function ingestText(t, source) {
    if (!t || t.length < 20) return;
    var c = t.trim()[0];
    if (c !== "{" && c !== "[") {
      // some endpoints stream several JSON chunks separated by newlines
      if (t.indexOf("\n{") === -1) return;
    }
    var parsed = null;
    try { parsed = JSON.parse(t); } catch (e) {
      t.split(/\n(?=[{\[])/).forEach(function (chunk) {
        try { walk(JSON.parse(chunk), source); } catch (e2) {}
      });
      return;
    }
    PC.apiHits++;
    walk(parsed, source);
  }
  PC.ingestText = ingestText;

  // ---------- network hooks ----------
  var SKIP_URL = /\.(png|jpe?g|webp|gif|svg|css|woff2?|ttf|mp4)(\?|$)|google-analytics|googletagmanager|doubleclick|facebook|clarity|sentry|hotjar|branch\.io|appsflyer|mixpanel|segment|amplitude|moengage|clevertap/i;

  try {
    var ofetch = window.fetch;
    if (ofetch) {
      window.fetch = function (input, init) {
        var url = typeof input === "string" ? input : (input && input.url) || "";
        var p = ofetch.apply(this, arguments);
        if (!SKIP_URL.test(url)) {
          p.then(function (res) {
            try {
              var ct = (res.headers && res.headers.get && res.headers.get("content-type")) || "";
              if (/json|text\/plain|javascript/i.test(ct) || ct === "") {
                res.clone().text().then(function (t) { ingestText(t, "api"); }).catch(function () {});
              }
            } catch (e) {}
          }).catch(function () {});
        }
        return p;
      };
    }
  } catch (e) { PC.errors.push("fetch hook: " + e); }

  try {
    var XHR = window.XMLHttpRequest;
    if (XHR) {
      var oopen = XHR.prototype.open, osend = XHR.prototype.send;
      XHR.prototype.open = function (m, url) { this.__pcUrl = String(url || ""); return oopen.apply(this, arguments); };
      XHR.prototype.send = function () {
        var x = this;
        if (!SKIP_URL.test(x.__pcUrl || "")) {
          x.addEventListener("load", function () {
            try {
              if (x.responseType === "" || x.responseType === "text") ingestText(x.responseText, "api");
              else if (x.responseType === "json" && x.response) { PC.apiHits++; walk(x.response, "api"); }
            } catch (e) {}
          });
        }
        return osend.apply(this, arguments);
      };
    }
  } catch (e) { PC.errors.push("xhr hook: " + e); }

  // ---------- SSR state ----------
  function scanSSR() {
    var globals = ["__NEXT_DATA__", "__INITIAL_STATE__", "__PRELOADED_STATE__", "__APOLLO_STATE__", "__NUXT__", "___INITIAL_STATE__", "__STATE__", "__data"];
    globals.forEach(function (g) {
      try { if (window[g] && typeof window[g] === "object") walk(window[g], "ssr"); } catch (e) {}
    });
    var scripts = document.querySelectorAll('script[type="application/json"], script[type="application/ld+json"], script#__NEXT_DATA__');
    for (var i = 0; i < scripts.length; i++) {
      var t = scripts[i].textContent || "";
      if (t.length > 30 && t.length < 8000000) { try { walk(JSON.parse(t), "ssr"); } catch (e) {} }
    }
    // ld+json uses offers.price — handle explicitly
    var ld = document.querySelectorAll('script[type="application/ld+json"]');
    for (var j = 0; j < ld.length; j++) {
      try {
        var data = JSON.parse(ld[j].textContent);
        (Array.isArray(data) ? data : [data]).forEach(function (d) {
          var list = d && d["@type"] === "ItemList" ? (d.itemListElement || []).map(function (e) { return e.item || e; }) : [d];
          list.forEach(function (p) {
            if (!p || p["@type"] !== "Product") return;
            var off = Array.isArray(p.offers) ? p.offers[0] : p.offers || {};
            add({ name: p.name, price: num(off.price || off.lowPrice), quantity: p.weight && (p.weight.value + " " + (p.weight.unitText || p.weight.unitCode || "")), image: firstString(p.image), url: p.url }, "ssr");
          });
        });
      } catch (e) {}
    }
  }

  // ---------- DOM fallback ----------
  var PRICE_RE = /(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)/gi;
  var SKIP_LINE = /cashback|\bemi\b|coupon|delivery|\bfees?\b|bank|above|min(imum)?\s*order|wallet|handling|platform fee|savings?:/i;
  var JUNK_LINE = /^(add|add to cart|buy now|notify me|out of stock|sold out|bestseller|ad|sponsored|options?|\d+\s*options?|view|more|new|organic|\d+\s*mins?|\d+\s*%.*|.*mins?$|get it by.*|free delivery.*|.*ratings?$|[\d.,\s()★]+)$/i;

  function linePrices(line) {
    if (SKIP_LINE.test(line)) return [];
    var out = [], m;
    PRICE_RE.lastIndex = 0;
    while ((m = PRICE_RE.exec(line))) {
      var after = line.slice(m.index + m[0].length, m.index + m[0].length + 6);
      var before = line.slice(Math.max(0, m.index - 12), m.index);
      if (/^\s*(\/|per\b|off\b)/i.test(after)) continue;          // ₹120/kg, ₹20 off
      if (/(save|saving|extra|get|flat|upto|up to)\s*$/i.test(before)) continue; // Save ₹2
      var v = parseFloat(m[1].replace(/,/g, ""));
      if (v > 0) out.push(v);
    }
    return out;
  }

  function cardPrices(t) {
    var all = [];
    t.split(/\n+/).forEach(function (l) { all = all.concat(linePrices(l)); });
    return all;
  }

  function scanDOM() {
    var body = document.body;
    if (!body) return 0;
    var seeds = [];
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode())) {
      if (/(₹|rs\.?\s*\d)/i.test(n.nodeValue || "") && n.parentElement) seeds.push(n.parentElement);
      if (seeds.length > 3000) break;
    }
    var listCache = new Map();
    function sig(e) { return e.tagName + "." + (typeof e.className === "string" ? e.className.split(/\s+/).filter(function (c) { return !/active|selected|odd|even|first|last|\d/.test(c); }).sort().join(".") : ""); }
    // a "list" is a parent with >=2 similar non-trivial children that each show a price
    function isList(p) {
      if (listCache.has(p)) return listCache.get(p);
      var groups = {}, res = false;
      for (var i = 0; i < p.children.length && !res; i++) {
        var c = p.children[i];
        if (c.querySelectorAll("*").length < 3) continue;
        if (!/(₹|rs\.?\s*\d)/i.test(c.textContent || "")) continue;
        var g = sig(c);
        groups[g] = (groups[g] || 0) + 1;
        if (groups[g] >= 2) res = true;
      }
      listCache.set(p, res);
      return res;
    }
    var cards = new Set();
    seeds.forEach(function (el) {
      var cur = el, steps = 0;
      while (cur.parentElement && cur.parentElement !== body && steps < 15) {
        var p = cur.parentElement;
        if (isList(p)) break;
        if ((p.textContent || "").length > 1500) break;
        if (cardPrices(text(p)).length > 6) break;
        cur = p; steps++;
      }
      cards.add(cur);
    });
    // keep only innermost-distinct cards (drop cards that contain other cards)
    var arr = Array.from(cards);
    arr = arr.filter(function (c) { return !arr.some(function (o) { return o !== c && c.contains(o) && cardPrices(text(o)).length; }); });
    var added = 0;
    arr.forEach(function (card) {
      var t = text(card);
      var lines = t.split(/\n+/).map(clean).filter(Boolean);
      var prices = cardPrices(t);
      if (!prices.length) return;
      var nameLine = null, qtyLine = null;
      lines.forEach(function (l) {
        if (/₹|rs\.?\s*\d/i.test(l) || JUNK_LINE.test(l)) {
          return;
        }
        if (!qtyLine && parseQty(l) && l.length < 30) { qtyLine = l; return; }
        if (/[a-zऀ-ॿ]{3,}/i.test(l) && (!nameLine || l.length > nameLine.length) && l.length < 200) nameLine = l;
      });
      if (!nameLine) {
        var img = card.querySelector("img[alt]");
        if (img && img.alt && img.alt.length > 3) nameLine = img.alt;
      }
      if (!nameLine) return;
      var link = card.querySelector("a[href]") || (card.closest && card.closest("a[href]"));
      var image = card.querySelector("img");
      var sp = Math.min.apply(null, prices), mrp = Math.max.apply(null, prices);
      var oos = /out of stock|sold out|notify me|currently unavailable/i.test(t);
      if (add({ name: nameLine, price: sp, mrp: mrp > sp ? mrp : null, quantity: qtyLine, url: link ? link.href : null, image: image ? image.src : null, inStock: !oos }, "dom")) added++;
    });
    return added;
  }

  // ---------- reconcile API scale against DOM ----------
  function reconcile(list) {
    var dom = list.filter(function (i) { return i.source === "dom"; });
    var api = list.filter(function (i) { return i.source !== "dom" && i.raw; });
    if (!api.length) return list;
    var ratios = [];
    api.forEach(function (a) {
      var best = null, bestScore = 0.6;
      dom.forEach(function (d) {
        var s = relevance(d.name, a.name);
        if (s > bestScore) { bestScore = s; best = d; }
      });
      if (best) ratios.push(a.price / best.price);
    });
    var factor = 1;
    if (ratios.length) {
      ratios.sort(function (a, b) { return a - b; });
      var med = ratios[Math.floor(ratios.length / 2)];
      if (med > 70 && med < 130) factor = 0.01;
    } else {
      // no DOM to compare: assume paise if every API price is a big round-ish integer
      var big = api.filter(function (a) { return a.price >= 1000 && Math.round(a.price) === a.price; }).length;
      if (big === api.length && api.length >= 3) {
        var med2 = api.map(function (a) { return a.price; }).sort(function (a, b) { return a - b; })[Math.floor(api.length / 2)];
        if (med2 >= 2000) factor = 0.01;
      }
    }
    if (factor !== 1) api.forEach(function (a) { a.price *= factor; if (a.mrp) a.mrp *= factor; });
    return list;
  }

  function pageStatus() {
    var t = (document.body && text(document.body).slice(0, 20000)) || "";
    if (/(select|enter|set|choose|detect)\s+(your\s+)?(delivery\s+)?(location|pincode|pin code|address)/i.test(t) && Object.keys(PC.items).length === 0) return "needs_location";
    if (/(login|log in|sign in)\s+(to|with)\s+(continue|proceed|your)/i.test(t) && Object.keys(PC.items).length === 0) return "needs_login";
    if (/(not\s+(serviceable|delivering)|don.t\s+deliver|unserviceable|coming soon to your area)/i.test(t)) return "not_serviceable";
    if (/(no results|no products found|couldn.t find|did not match any)/i.test(t) && Object.keys(PC.items).length === 0) return "no_results";
    return "ok";
  }

  PC.configure = function (c) { for (var k in c) PC.cfg[k] = c[k]; return true; };
  PC.snapshot = function () { return Object.keys(PC.items).length; };
  PC.finish = function () {
    var notes = [];
    try { scanSSR(); } catch (e) { notes.push("ssr: " + e); }
    var hasApi = Object.keys(PC.items).some(function (k) { return PC.items[k].source !== "dom"; });
    try { scanDOM(); } catch (e) { notes.push("dom: " + e); }
    var list = Object.keys(PC.items).map(function (k) { return PC.items[k]; });
    list = reconcile(list);
    // relevance filter
    var q = PC.cfg.query;
    var nq = tokens(q).filter(function (t) { return !QTY_TOKEN.test(t); }).length;
    var minRel = nq <= 2 ? 0.99 : nq === 3 ? 0.66 : 0.6;
    list = list.filter(function (i) { i.relevance = +relevance(i.name, q).toFixed(2); return i.relevance >= minRel; });
    // prefer structured data; drop DOM duplicates of API items
    if (hasApi) {
      var structured = list.filter(function (i) { return i.source !== "dom"; });
      list = structured.concat(list.filter(function (i) {
        return i.source === "dom" && !structured.some(function (s) { return relevance(s.name, i.name) >= 0.8 && Math.abs(s.price - i.price) < 0.5; });
      }));
    }
    // final dedupe by name+qty+price
    var seen = {};
    list = list.filter(function (i) {
      var k = i.name.toLowerCase() + "|" + (i.quantity || "") + "|" + Math.round(i.price);
      if (seen[k]) return false; seen[k] = 1; return true;
    });
    list.forEach(function (i) {
      i.price = +i.price.toFixed(2);
      if (i.mrp) i.mrp = +i.mrp.toFixed(2);
      i.discountPct = i.mrp ? Math.round((1 - i.price / i.mrp) * 100) : null;
      delete i.raw;
      withUnitPrice(i);
    });
    list.sort(function (a, b) { return b.relevance - a.relevance || a.price - b.price; });
    return JSON.stringify({ status: list.length ? "ok" : pageStatus(), apiHits: PC.apiHits, items: list.slice(0, 40), notes: notes.concat(PC.errors) });
  };

  // exposed for tests
  PC._parseQty = parseQty;
  PC._relevance = relevance;
  PC._scanDOM = scanDOM;

  window.__PC = PC;
})();
