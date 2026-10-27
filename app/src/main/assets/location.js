/*
 * PriceCompare location sync — injected at document start on every platform page
 * (hidden search pages and the visible in-app browser).
 *
 * Needs window.__PC_LOC_CFG = {lat, lng, acc, pincode, area, city, state, address, fp}
 *
 *  1. Geolocation: navigator.geolocation answers instantly with the phone's location,
 *     and permission queries report "granted" — so every "detect my location" uses it.
 *  2. Amazon: sets the delivery pincode through the same request Amazon's pincode popup makes.
 *  3. Auto-filler: when a site shows a location prompt, clicks its "use current location"
 *     button or types the pincode / area into it, then confirms. It only confirms right after
 *     its own action, never clicks anything else, and stops after a few actions.
 *
 * Exposes window.__PCLOC = {acted, lastActionAt, log}
 */
(function () {
  if (window.__PCLOC) return;
  var L = window.__PC_LOC_CFG;
  var S = { acted: 0, lastActionAt: 0, log: [], done: false };
  window.__PCLOC = S;
  if (!L || typeof L.lat !== "number" || typeof L.lng !== "number") return;

  var MAX_ACTIONS = 10;
  var RUN_FOR_MS = 45000;
  var t0 = Date.now();

  function note(m) { S.log.push(m); if (S.log.length > 30) S.log.shift(); }
  function acted(m) { S.acted++; S.lastActionAt = Date.now(); note(m); }

  // ---------- 1. geolocation ----------
  function position() {
    var c = {
      latitude: L.lat, longitude: L.lng, accuracy: L.acc || 25,
      altitude: null, altitudeAccuracy: null, heading: null, speed: null,
    };
    c.toJSON = function () { return { latitude: L.lat, longitude: L.lng, accuracy: L.acc || 25 }; };
    var p = { coords: c, timestamp: Date.now() };
    p.toJSON = function () { return { coords: c.toJSON(), timestamp: p.timestamp }; };
    return p;
  }
  var watchId = 0;
  var geo = {
    getCurrentPosition: function (ok) { if (typeof ok === "function") setTimeout(function () { ok(position()); }, 30); },
    watchPosition: function (ok) { if (typeof ok === "function") setTimeout(function () { ok(position()); }, 30); return ++watchId; },
    clearWatch: function () {},
  };
  try {
    if (window.Geolocation && Geolocation.prototype) {
      Geolocation.prototype.getCurrentPosition = geo.getCurrentPosition;
      Geolocation.prototype.watchPosition = geo.watchPosition;
      Geolocation.prototype.clearWatch = geo.clearWatch;
    }
  } catch (e) {}
  try {
    if (!navigator.geolocation) Object.defineProperty(navigator, "geolocation", { value: geo, configurable: true });
    else {
      navigator.geolocation.getCurrentPosition = geo.getCurrentPosition;
      navigator.geolocation.watchPosition = geo.watchPosition;
    }
  } catch (e) {}
  try {
    var perms = navigator.permissions;
    if (perms && perms.query) {
      var oq = perms.query.bind(perms);
      perms.query = function (d) {
        if (d && d.name === "geolocation") {
          return Promise.resolve({ name: "geolocation", state: "granted", onchange: null,
            addEventListener: function () {}, removeEventListener: function () {}, dispatchEvent: function () { return true; } });
        }
        return oq(d);
      };
    }
  } catch (e) {}

  // ---------- helpers ----------
  var VIS = window.__PCLOC_TEST ? function (el) { return !el.hidden && !(el.style && el.style.display === "none"); } : function (el) {
    if (!el || !el.getClientRects || el.getClientRects().length === 0) return false;
    var st = getComputedStyle(el);
    return st.visibility !== "hidden" && st.display !== "none" && +st.opacity !== 0;
  };
  function txt(el) {
    var t = (el.innerText != null ? el.innerText : el.textContent) || "";
    t = t || el.getAttribute("aria-label") || el.getAttribute("title") || el.value || "";
    return String(t).replace(/\s+/g, " ").trim();
  }
  function attrs(el) {
    return [el.getAttribute("placeholder"), el.getAttribute("name"), el.id, el.getAttribute("aria-label"),
      el.getAttribute("data-testid"), el.getAttribute("autocomplete")].filter(Boolean).join(" ");
  }
  var done = new WeakSet();
  function clickable() {
    return document.querySelectorAll('button, a, [role="button"], [role="option"], [role="menuitem"], li, div[tabindex], span[tabindex], div[class*="button" i], div[class*="btn" i], div[class*="location" i], div[class*="Location"], div[class*="detect" i], div[class*="current" i], input[type="submit"], input[type="button"]');
  }
  function click(el, why) {
    done.add(el);
    try {
      el.scrollIntoView && el.scrollIntoView({ block: "center" });
      ["pointerdown", "mousedown", "pointerup", "mouseup"].forEach(function (t) {
        el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
      });
      el.click();
    } catch (e) {}
    acted(why + ": " + txt(el).slice(0, 40));
  }
  function setValue(el, v) {
    var proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, "value");
    try { el.focus(); } catch (e) {}
    if (d && d.set) d.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }
  function pressEnter(el) {
    ["keydown", "keypress", "keyup"].forEach(function (t) {
      el.dispatchEvent(new KeyboardEvent(t, { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true }));
    });
    var f = el.form;
    if (f) { try { f.requestSubmit ? f.requestSubmit() : f.submit(); } catch (e) {} }
  }
  // Is this element part of a location / address UI (dialog, popup, or a block that talks about location)?
  var CTX_ATTR = /modal|dialog|popup|pop-up|overlay|drawer|sheet|location|address|pincode|pin-code|glow|delivery|portal/i;
  var CTX_TEXT = /location|address|pincode|pin code|deliver|locality|your area/i;
  function inLocationContext(el) {
    var cur = el, depth = 0;
    while (cur && cur !== document.body && depth < 10) {
      var a = (typeof cur.className === "string" ? cur.className : "") + " " + (cur.id || "") + " " + (cur.getAttribute && (cur.getAttribute("role") || "") + " " + (cur.getAttribute("aria-modal") || "") + " " + (cur.getAttribute("aria-label") || ""));
      if (CTX_ATTR.test(a) || / dialog |true/.test(" " + a + " ")) return true;
      if (depth <= 6) {
        var t = (cur.textContent || "");
        if (t.length < 700 && CTX_TEXT.test(t)) return true;
      }
      cur = cur.parentElement; depth++;
    }
    return false;
  }

  // ---------- 3. auto-filler ----------
  var DETECT = /(detect|use|enable|allow|locate|fetch|get|share)\s+(my\s+|your\s+)?(current\s+|device\s+|exact\s+)?location|current location|locate me|use gps|detect location|auto[- ]?detect/i;
  var OPENER = /^(select|set|choose|enter|add)\s+(your\s+)?(delivery\s+)?(location|address|pincode|pin code|area)$|^(location|address) not set$|^(where should we deliver|deliver to\??)$/i;
  var CONFIRM = /^(confirm( (location|address|pincode))?|confirm (&|and) (continue|proceed|save)|continue|proceed|done|save( (address|location))?|apply|submit|check|update|use this (location|address)|set (this )?location|deliver here|go|ok|okay|start shopping)$/i;
  var PIN_INPUT = /pin\s*-?code|postal|zip|(^|\W)pin(\W|$)/i;
  var AREA_INPUT = /(search|enter|type|find).{0,25}(location|area|address|locality|city|society|street|building|apartment)|(location|area|locality|address)\s*search|search\s*(for\s*)?(area|locality|location)/i;
  var openedOnce = false, typedArea = 0;

  function inputs() { return document.querySelectorAll("input, textarea"); }

  function tryDetect() {
    var els = clickable();
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (done.has(el) || !VIS(el)) continue;
      var t = txt(el);
      if (t.length > 0 && t.length < 60 && DETECT.test(t)) {
        // skip containers that hold more than the button itself
        if (el.querySelector && Array.prototype.some.call(el.querySelectorAll("button, a"), function (c) { return c !== el && DETECT.test(txt(c)); })) continue;
        click(el, "detect");
        return true;
      }
    }
    return false;
  }

  function tryPincode() {
    if (!L.pincode) return false;
    var ins = inputs();
    for (var i = 0; i < ins.length; i++) {
      var el = ins[i];
      if (done.has(el) || !VIS(el) || el.disabled || el.readOnly) continue;
      var a = attrs(el);
      var looksPin = PIN_INPUT.test(a) || (/tel|number/.test(el.type || "") && +el.getAttribute("maxlength") === 6);
      if (!looksPin || /search for products|search product/i.test(a)) continue;
      if (el.value === L.pincode) { done.add(el); continue; }
      done.add(el);
      setValue(el, L.pincode);
      acted("pincode");
      setTimeout(function () { confirmNear(el) || pressEnter(el); }, 450);
      return true;
    }
    return false;
  }

  function tryArea() {
    var q = [L.area, L.city].filter(Boolean).join(", ");
    if (!q || typedArea >= 1) return false;
    var ins = inputs();
    for (var i = 0; i < ins.length; i++) {
      var el = ins[i];
      if (done.has(el) || !VIS(el) || el.disabled || el.readOnly) continue;
      var a = attrs(el);
      if (!AREA_INPUT.test(a) || /product|item|brand|grocer|store|dish|restaurant/i.test(a)) continue;
      done.add(el); typedArea++;
      setValue(el, q);
      acted("area");
      setTimeout(function () { pickSuggestion(el); }, 1500);
      return true;
    }
    return false;
  }

  function pickSuggestion(input) {
    var want = (L.area || L.city || "").toLowerCase().split(/[ ,]+/)[0];
    if (!want) return;
    var els = document.querySelectorAll('li, [role="option"], [role="button"], button, a, div[class*="suggest" i], div[class*="result" i], div[class*="prediction" i], div[class*="item" i]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === input || done.has(el) || !VIS(el) || el.contains(input)) continue;
      var t = txt(el).toLowerCase();
      if (t.length > 4 && t.length < 200 && t.indexOf(want) !== -1 && !el.querySelector("li, [role=option]")) {
        click(el, "suggestion");
        return true;
      }
    }
    return false;
  }

  function confirmNear(from) {
    var root = from;
    for (var d = 0; d < 6 && root && root.parentElement; d++) {
      root = root.parentElement;
      var btns = root.querySelectorAll('button, a, [role="button"], input[type="submit"], div[class*="button" i], div[class*="btn" i]');
      for (var i = 0; i < btns.length; i++) {
        var b = btns[i];
        if (done.has(b) || !VIS(b) || b.disabled) continue;
        if (CONFIRM.test(txt(b))) { click(b, "confirm"); return true; }
      }
    }
    return false;
  }

  // Confirm buttons are only pressed shortly after one of our own location actions.
  function tryConfirm() {
    if (!S.lastActionAt || Date.now() - S.lastActionAt > 15000) return false;
    var els = clickable();
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (done.has(el) || !VIS(el) || el.disabled) continue;
      var t = txt(el);
      if (t.length && t.length < 40 && CONFIRM.test(t) && inLocationContext(el)) { click(el, "confirm"); return true; }
    }
    return false;
  }

  function tryOpener() {
    if (openedOnce) return false;
    var els = clickable();
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (done.has(el) || !VIS(el)) continue;
      var t = txt(el);
      if (t.length && t.length < 40 && OPENER.test(t)) { openedOnce = true; click(el, "open"); return true; }
    }
    return false;
  }

  function step() {
    if (S.done) return;
    if (S.acted >= MAX_ACTIONS || Date.now() - t0 > RUN_FOR_MS) { S.done = true; return; }
    if (!document.body) return;
    // order matters: GPS button beats typing; typing beats opening a prompt
    tryDetect() || tryPincode() || tryConfirm() || tryArea() || tryOpener();
  }

  // ---------- 2. Amazon pincode ----------
  function amazon() {
    if (!/(^|\.)amazon\.in$/.test(location.hostname) || !L.pincode) return;
    var key = "__pc_amz_pin";
    try { if (localStorage.getItem(key) === L.pincode) return; } catch (e) {}
    var html = (document.documentElement ? document.documentElement.innerHTML : "").replace(/&quot;/g, '"').replace(/&#34;/g, '"');
    var m = html.match(/anti-csrftoken-a2z["']?\s*[:=]\s*["']([^"']{20,})["']/);
    var tok1 = m ? m[1] : null;
    var of = window.__pcOrigFetch || window.fetch;
    var base = "/portal-migration/hz/glow/";
    var h1 = { "Accept": "text/html,*/*" };
    if (tok1) h1["anti-csrftoken-a2z"] = tok1;
    of.call(window, base + "get-rendered-address-selections?deviceType=web&pageType=Search&storeContext=NoStoreName&actionSource=desktop-modal", { credentials: "include", headers: h1 })
      .then(function (r) { return r.text(); })
      .catch(function () { return ""; })
      .then(function (t) {
        var m2 = String(t).match(/CSRF_TOKEN\s*[:=]\s*["']([^"']{20,})["']/);
        var tok = (m2 && m2[1]) || tok1;
        if (!tok) { note("amazon: no token"); return; }
        return of.call(window, base + "address-change?actionSource=glow", {
          method: "POST", credentials: "include",
          headers: { "Content-Type": "application/json", "anti-csrftoken-a2z": tok, "X-Requested-With": "XMLHttpRequest" },
          body: JSON.stringify({ locationType: "LOCATION_INPUT", zipCode: L.pincode, storeContext: "generic", deviceType: "web", pageType: "Search", actionSource: "glow" }),
        }).then(function (r) {
          if (!r.ok) { note("amazon: " + r.status); return; }
          try { localStorage.setItem(key, L.pincode); } catch (e) {}
          acted("amazon pincode " + L.pincode);
          var rk = "__pc_amz_reload";
          try { if (sessionStorage.getItem(rk) === L.pincode) return; sessionStorage.setItem(rk, L.pincode); } catch (e) {}
          setTimeout(function () { try { location.reload(); } catch (e) {} }, 300);
        });
      })
      .catch(function (e) { note("amazon: " + e); });
  }

  // keep an untouched fetch for our own calls
  try { window.__pcOrigFetch = window.fetch; } catch (e) {}

  function start() {
    try { amazon(); } catch (e) {}
    var pending = false;
    try {
      new MutationObserver(function () {
        if (pending || S.done) return;
        pending = true;
        setTimeout(function () { pending = false; step(); }, 350);
      }).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    step();
    var iv = setInterval(function () { step(); if (S.done) clearInterval(iv); }, 1500);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
  else start();

  S._step = step; // for tests
})();
