// node location.test.js ../app/src/main/assets/location.js
const fs = require("fs");
const { JSDOM } = require("jsdom");
const SRC = fs.readFileSync(process.argv[2], "utf8");
let fails = 0;
const ok = (label, cond, extra = "") => { if (!cond) fails++; console.log((cond ? "PASS " : "FAIL ") + label + (cond ? "" : "  " + extra)); };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const CFG = { lat: 12.9352, lng: 77.6245, acc: 15, pincode: "560034", area: "Koramangala", city: "Bengaluru", state: "Karnataka", fp: "560034" };

function page(html, { url = "https://shop.example/", cfg = CFG, fetchImpl } = {}) {
  const dom = new JSDOM(`<!doctype html><html><body>${html}</body></html>`, { runScripts: "outside-only", url, pretendToBeVisual: true });
  const w = dom.window;
  if (fetchImpl) w.fetch = fetchImpl;
  w.__PCLOC_TEST = true;
  w.__PC_LOC_CFG = cfg;
  // jsdom has no geolocation; give it a native-looking one to be replaced
  w.eval("window.Geolocation = function(){}; Geolocation.prototype.getCurrentPosition = function(){ throw new Error('native') };" +
    "Object.defineProperty(navigator, 'geolocation', { value: new Geolocation(), configurable: true });" +
    "navigator.permissions = { query: function(){ return Promise.resolve({state:'prompt'}) } };");
  w.eval(SRC);
  return w;
}

(async () => {
  // 1. geolocation + permissions
  {
    const w = page("<div></div>");
    const pos = await new Promise((res) => w.navigator.geolocation.getCurrentPosition(res));
    ok("geolocation lat", pos.coords.latitude === CFG.lat);
    ok("geolocation lng", pos.coords.longitude === CFG.lng);
    const perm = await w.navigator.permissions.query({ name: "geolocation" });
    ok("permission granted", perm.state === "granted");
  }

  // 2. location popup with "Detect my location" then a map with "Confirm location"
  {
    const w = page(`<div class="modal"><h3>Select delivery location</h3>
        <button id="detect">Detect my location</button><input placeholder="Search delivery location"></div>`);
    const clicks = [];
    w.document.getElementById("detect").addEventListener("click", () => {
      clicks.push("detect");
      // site reacts: shows confirm
      const d = w.document.createElement("div");
      d.className = "location-confirm";
      d.innerHTML = `<p>Koramangala, Bengaluru</p><button id="cf">Confirm & Continue</button>`;
      w.document.body.appendChild(d);
      w.document.getElementById("cf").addEventListener("click", () => clicks.push("confirm"));
    });
    await wait(1200);
    ok("detect clicked", clicks[0] === "detect", JSON.stringify(clicks));
    ok("confirm clicked after detect", clicks[1] === "confirm", JSON.stringify(clicks));
    ok("area box not typed when detect worked", w.document.querySelector("input").value === "");
  }

  // 3. pincode popup (e.g. Amazon/Flipkart/DMart style)
  {
    const w = page(`<header><input placeholder="Search for products, brands"></header>
      <div role="dialog"><p>Enter your pincode to check delivery</p>
        <form><input id="pin" type="tel" placeholder="Enter pincode" maxlength="6"><button id="apply" type="button">Apply</button></form></div>`);
    let applied = null;
    w.document.getElementById("apply").addEventListener("click", () => { applied = w.document.getElementById("pin").value; });
    await wait(1200);
    ok("pincode typed", w.document.getElementById("pin").value === "560034");
    ok("apply pressed with pincode", applied === "560034", String(applied));
    ok("product search box untouched", w.document.querySelector("header input").value === "");
  }

  // 4. "Select Location" header opens a modal, then detect is used
  {
    const w = page(`<div class="header"><button id="open">Select Location</button></div>`);
    const seq = [];
    w.document.getElementById("open").addEventListener("click", () => {
      seq.push("open");
      const m = w.document.createElement("div");
      m.setAttribute("role", "dialog");
      m.innerHTML = `<button id="cur">Use my current location</button>`;
      w.document.body.appendChild(m);
      w.document.getElementById("cur").addEventListener("click", () => seq.push("current"));
    });
    await wait(2000);
    ok("opener then current location", seq.join(",") === "open,current", seq.join(","));
  }

  // 5. ordinary product page: nothing clicked
  {
    const w = page(`<div class="grid"><div class="card">Amul Butter 500 g ₹285 <button class="add">ADD</button></div>
      <div class="filters"><button id="ap">Apply</button><button id="cont">Continue</button></div>
      <a href="/cart" id="cart">Proceed to checkout</a></div>`);
    let any = false;
    w.document.querySelectorAll("button, a").forEach((b) => b.addEventListener("click", (e) => { e.preventDefault(); any = true; }));
    await wait(2000);
    ok("no clicks on normal page", !any && w.__PCLOC.acted === 0, JSON.stringify(w.__PCLOC.log));
  }

  // 6. area search box with suggestions
  {
    const w = page(`<div class="location-modal"><input id="a" placeholder="Search for area, street name..."><ul id="s"></ul></div>`);
    let picked = null;
    w.document.getElementById("a").addEventListener("input", () => {
      w.document.getElementById("s").innerHTML = `<li id="o1">Koramangala 4th Block, Bengaluru</li><li>Koramangala Club</li>`;
      w.document.getElementById("o1").addEventListener("click", () => (picked = "o1"));
    });
    await wait(2300);
    ok("area typed", w.document.getElementById("a").value === "Koramangala, Bengaluru");
    ok("first suggestion picked", picked === "o1", String(picked));
  }

  // 7. Amazon pincode via glow endpoints
  {
    const calls = [];
    const fetchImpl = (url, opt = {}) => {
      calls.push({ url: String(url), opt });
      if (/get-rendered-address-selections/.test(url)) return Promise.resolve({ ok: true, text: () => Promise.resolve('<script>var x = { CSRF_TOKEN : "tok_second_abcdefghijklmnopqrstuvwxyz" }</script>') });
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve("{}") });
    };
    const w = page(`<span id="nav-global-location-data-modal-action" data-a-modal='{"ajaxHeaders":{"anti-csrftoken-a2z":"tok_first_abcdefghijklmnopqrstuvwxyz"}}'></span>`,
      { url: "https://www.amazon.in/s?k=salt", fetchImpl });
    let reloaded = false;
    try { w.location.reload = () => { reloaded = true; }; } catch (e) {}
    await wait(500);
    const post = calls.find((c) => /address-change/.test(c.url));
    ok("amazon first call sends page token", calls[0] && calls[0].opt.headers["anti-csrftoken-a2z"] === "tok_first_abcdefghijklmnopqrstuvwxyz");
    ok("amazon address-change posted", !!post);
    ok("amazon uses second token", post && post.opt.headers["anti-csrftoken-a2z"] === "tok_second_abcdefghijklmnopqrstuvwxyz");
    ok("amazon body has pincode", post && JSON.parse(post.opt.body).zipCode === "560034");
    ok("amazon remembers pincode", w.localStorage.getItem("__pc_amz_pin") === "560034");
  }

  // 8. no config -> does nothing
  {
    const w = page("<button>Detect my location</button>", { cfg: null });
    await wait(300);
    ok("no config: inert", w.__PCLOC.acted === 0);
  }

  console.log(fails ? `\n${fails} FAILED` : "\nALL PASSED");
  process.exit(fails ? 1 : 0);
})();
