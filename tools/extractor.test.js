const fs = require("fs");
const { JSDOM } = require("jsdom");
const SRC = fs.readFileSync(process.argv[2], "utf8");
let fails = 0;
function eq(label, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) fails++;
  console.log((ok ? "PASS " : "FAIL ") + label + (ok ? "" : `\n   got:  ${JSON.stringify(got)}\n   want: ${JSON.stringify(want)}`));
}
function near(label, got, want, tol = 0.01) {
  const ok = got != null && Math.abs(got - want) <= tol;
  if (!ok) fails++;
  console.log((ok ? "PASS " : "FAIL ") + label + (ok ? "" : `  got ${got} want ${want}`));
}

const INLINE = new Set(["SPAN", "B", "S", "DEL", "STRONG", "EM", "I", "SMALL", "A", "SUP"]);
function setup(html, { apiResponses = [], beforeScripts = "" } = {}) {
  const dom = new JSDOM(`<!doctype html><html><body>${html}</body></html>`, { runScripts: "outside-only", url: "https://example.in/s?q=x" });
  const w = dom.window;
  Object.defineProperty(w.HTMLElement.prototype, "innerText", {
    get() {
      const parts = [];
      const rec = (n, out) => {
        for (const c of n.childNodes) {
          if (c.nodeType === 3) out.push(c.nodeValue);
          else if (c.nodeType === 1) {
            if (INLINE.has(c.tagName)) rec(c, out);
            else { out.push("\n"); rec(c, out); out.push("\n"); }
          }
        }
      };
      rec(this, parts);
      return parts.join("").replace(/[ \t]+/g, " ").replace(/\n\s*\n+/g, "\n").trim();
    },
  });
  // fake fetch: page code calls it after our hook is installed
  w.fetch = (url) => {
    const body = apiResponses.shift() || "{}";
    return Promise.resolve({
      headers: { get: () => "application/json" },
      clone() { return { text: () => Promise.resolve(body) }; },
    });
  };
  if (beforeScripts) w.eval(beforeScripts);
  w.eval(SRC);
  return w;
}
const tick = () => new Promise((r) => setTimeout(r, 20));

(async () => {
  // ---- quantity parser ----
  const w0 = setup("");
  const pq = (s) => { const r = w0.__PC._parseQty(s); return r && { a: +r.amount.toFixed(4), b: r.base }; };
  eq("qty 500 g", pq("500 g"), { a: 0.5, b: "kg" });
  eq("qty 1 kg", pq("Fortune Atta 1 kg"), { a: 1, b: "kg" });
  eq("qty 2 x 500 ml", pq("2 x 500 ml"), { a: 1, b: "L" });
  eq("qty 500ml x 2", pq("500ml x 2"), { a: 1, b: "L" });
  eq("qty 1.5 L", pq("Coca-Cola 1.5 L"), { a: 1.5, b: "L" });
  eq("qty 6 pcs", pq("Eggs 6 pcs"), { a: 6, b: "pc" });
  eq("qty pack of 3 x 100g", pq("Dove soap 100g (Pack of 3)"), { a: 0.3, b: "kg" });
  eq("qty 1 pack (450 g)", pq("1 pack (450 g)"), { a: 0.45, b: "kg" });
  eq("qty weight beats count", pq("12 pcs (360 g)"), { a: 0.36, b: "kg" });
  eq("qty none", pq("Amul Butter"), null);
  eq("qty 1,000 ml", pq("1,000 ml"), { a: 1, b: "L" });

  // ---- 1. Blinkit-like API (rupees) via fetch ----
  {
    const api = JSON.stringify({ response: { snippets: [
      { data: { name: { text: "x" }, product_id: 1, name_str: "y" } },
      { product: { name: "Amul Taaza Toned Fresh Milk", unit: "500 ml", price: 28, mrp: 29, inventory: 10 } },
      { product: { name: "Amul Taaza Toned Fresh Milk", unit: "1 l", price: 56, mrp: 57 } },
      { product: { name: "Nandini Shubham Milk", unit: "500 ml", price: 26, mrp: 26 } },
    ] } });
    const w = setup('<div id="app"></div>', { apiResponses: [api] });
    w.__PC.configure({ platform: "blinkit", query: "amul milk" });
    await w.fetch("/v1/layout/search?q=amul+milk"); await tick();
    const r = JSON.parse(w.__PC.finish());
    eq("blinkit status", r.status, "ok");
    eq("blinkit count (Nandini filtered out)", r.items.length, 2);
    const one = r.items.find((i) => i.quantity === "1 l");
    near("blinkit 1L price", one.price, 56);
    near("blinkit 1L unit price ₹/L", one.unitPrice, 56);
    eq("blinkit unitBase", one.unitBase, "L");
    const half = r.items.find((i) => i.quantity === "500 ml");
    near("blinkit 500ml ₹/L", half.unitPrice, 56);
    eq("blinkit discount", half.discountPct, 3);
  }

  // ---- 2. Zepto-like paise API + DOM cards -> reconcile divides by 100 ----
  {
    const api = JSON.stringify({ layout: [{ data: { resolver: { data: { items: [
      { productResponse: { product: { name: "Aashirvaad Shudh Chakki Atta" }, productVariant: { formattedPacksize: "5 kg" }, discountedSellingPrice: 24900, mrp: 30000 } },
      { productResponse: { product: { name: "Aashirvaad Select Atta" }, productVariant: { formattedPacksize: "1 kg" }, discountedSellingPrice: 7500, mrp: 8000 } },
    ] } } } }] });
    const html = `
      <div class="grid">
        <div class="card"><img alt="Aashirvaad Shudh Chakki Atta"><div>Aashirvaad Shudh Chakki Atta</div><div>5 kg</div><div><span>₹249</span><del>₹300</del></div><button>ADD</button></div>
        <div class="card"><div>Aashirvaad Select Atta</div><div>1 kg</div><div><span>₹75</span> <del>₹80</del></div><button>ADD</button></div>
      </div>`;
    const w = setup(html, { apiResponses: [api] });
    w.__PC.configure({ platform: "zepto", query: "aashirvaad atta" });
    await w.fetch("/api/v3/search"); await tick();
    const r = JSON.parse(w.__PC.finish());
    const five = r.items.find((i) => /Shudh/.test(i.name) && i.source === "api");
    near("zepto paise -> rupees", five && five.price, 249);
    near("zepto mrp", five && five.mrp, 300);
    eq("zepto qty from nested variant", five && five.quantity, "5 kg");
    near("zepto ₹/kg", five && five.unitPrice, 49.8);
    eq("zepto DOM dupes dropped", r.items.length, 2);
  }

  // ---- 3. Amazon-like DOM: per-unit price, savings line, MRP ----
  {
    const html = `
      <div class="s-main-slot">
        <div data-component-type="s-search-result">
          <a href="/dp/B01"><h2><span>Tata Salt Vacuum Evaporated Iodised Salt, 1 kg</span></h2></a>
          <div>4.4 ★ (12,345)</div>
          <div><span>₹28</span><span>(₹28.00/kg)</span></div>
          <div>M.R.P: <del>₹30</del> (7% off)</div>
          <div>Save ₹2 with coupon</div>
          <div>FREE delivery Sat, 25 Oct</div>
        </div>
        <div data-component-type="s-search-result">
          <a href="/dp/B02"><h2><span>Tata Salt Lite, Low Sodium Iodised Salt, 1kg Pouch</span></h2></a>
          <div><span>₹1,045</span><span>(₹1,045.00/kg)</span></div>
          <div>M.R.P: <del>₹1,100</del></div>
        </div>
        <div data-component-type="s-search-result">
          <a href="/dp/B03"><h2><span>Prestige Pressure Cooker 3 L</span></h2></a>
          <div><span>₹1,499</span></div>
        </div>
      </div>`;
    const w = setup(html);
    w.__PC.configure({ platform: "amazon", query: "tata salt" });
    const r = JSON.parse(w.__PC.finish());
    eq("amazon count (cooker filtered)", r.items.length, 2);
    const salt = r.items.find((i) => /Vacuum/.test(i.name));
    near("amazon price ignores per-kg & save", salt && salt.price, 28);
    near("amazon mrp", salt && salt.mrp, 30);
    near("amazon ₹/kg from name", salt && salt.unitPrice, 28);
    const lite = r.items.find((i) => /Lite/.test(i.name));
    near("amazon comma price", lite && lite.price, 1045);
  }

  // ---- 4. Flipkart-like SSR state with nested price objects ----
  {
    const state = { pageDataV4: { page: { data: { 10003: [{ widget: { data: { products: [
      { productInfo: { value: { titles: { title: "Surf Excel Easy Wash Detergent Powder 1 kg" }, pricing: { finalPrice: { value: 128 }, mrp: { value: 140 } }, title: "Surf Excel Easy Wash Detergent Powder 1 kg" } } },
    ] } } }] } } } };
    const w = setup('<div id="container"></div>', { beforeScripts: `window.__INITIAL_STATE__ = ${JSON.stringify(state)};` });
    w.__PC.configure({ platform: "flipkart", query: "surf excel" });
    const r = JSON.parse(w.__PC.finish());
    const it = r.items[0];
    eq("flipkart found", r.items.length, 1);
    near("flipkart finalPrice.value", it && it.price, 128);
    near("flipkart mrp.value", it && it.mrp, 140);
    eq("flipkart source", it && it.source, "ssr");
  }

  // ---- 5. location-needed page ----
  {
    const w = setup("<div>Please select your delivery location to see products</div>");
    w.__PC.configure({ platform: "zepto", query: "milk" });
    const r = JSON.parse(w.__PC.finish());
    eq("needs_location status", r.status, "needs_location");
  }

  // ---- 6. XHR json + ld+json ----
  {
    const ld = { "@context": "https://schema.org", "@type": "ItemList", itemListElement: [
      { "@type": "ListItem", item: { "@type": "Product", name: "Maggi 2-Minute Masala Noodles 280 g", offers: { "@type": "Offer", price: "56.00" } } } ] };
    const w = setup(`<script type="application/ld+json">${JSON.stringify(ld)}</script><div></div>`);
    w.__PC.configure({ platform: "dmart", query: "maggi noodles" });
    const r = JSON.parse(w.__PC.finish());
    near("ld+json price", r.items[0] && r.items[0].price, 56);
    near("ld+json ₹/kg", r.items[0] && r.items[0].unitPrice, 200);
  }


  // ---- 7. realistic quick-commerce grid ----
  {
    const card = (n, q, sp, mrp, extra = "") => `
      <div class="Product__Wrapper tw-relative odd"><div class="tw-flex"><div class="img"><img src="x.jpg" alt="${n}"></div>
        <div class="tw-text-200"><div class="mins">9 MINS</div><div class="Product__Name">${n}</div><div class="variant">${q}</div></div>
        <div class="tw-flex price"><div><div class="sp">₹${sp}</div><div class="mrp"><del>₹${mrp}</del></div></div><div class="btn">ADD</div></div>${extra}</div></div>`;
    const html = `<div class="banner">Free delivery above ₹199 · Handling fee ₹9</div>
      <div class="grid">${card("Amul Pasteurised Butter", "500 g", 285, 290)}${card("Amul Pasteurised Butter", "100 g", 60, 62)}${card("Amul Lite Butter", "200 g", 110, 115, "<div>Out of Stock</div>")}</div>`;
    const w = setup(html);
    w.__PC.configure({ platform: "blinkit", query: "amul butter" });
    const r = JSON.parse(w.__PC.finish());
    eq("grid count", r.items.length, 3);
    const big = r.items.find((i) => i.quantity === "500 g");
    near("grid 500g price", big && big.price, 285);
    near("grid 500g mrp", big && big.mrp, 290);
    near("grid ₹/kg", big && big.unitPrice, 570);
    eq("grid name", big && big.name, "Amul Pasteurised Butter");
    const lite = r.items.find((i) => /Lite/.test(i.name));
    eq("grid out of stock", lite && lite.inStock, false);
  }


  // ---- 8. Myntra-like SSR: brand + productName, was-price ----
  {
    const state = { searchData: { results: { products: [
      { productName: "Men 511 Slim Fit Jeans", brand: "Levis", price: 2199, mrp: 3999 },
      { productName: "Women Skinny Jeans", brand: "Roadster", price: 799, wasPriceData: { value: 1999 } },
    ] } } };
    const w = setup('<div></div>', { beforeScripts: `window.__myx = 1; window.__INITIAL_STATE__ = ${JSON.stringify(state)};` });
    w.__PC.configure({ platform: "myntra", query: "levis jeans" });
    const r = JSON.parse(w.__PC.finish());
    eq("myntra brand joined + filtered", r.items.map((i) => i.name), ["Levis Men 511 Slim Fit Jeans"]);
    near("myntra price", r.items[0] && r.items[0].price, 2199);
    const w2 = setup('<div></div>', { beforeScripts: `window.__INITIAL_STATE__ = ${JSON.stringify(state)};` });
    w2.__PC.configure({ platform: "ajio", query: "roadster jeans" });
    const r2 = JSON.parse(w2.__PC.finish());
    near("wasPriceData as mrp", r2.items[0] && r2.items[0].mrp, 1999);
  }

  console.log(fails ? `\n${fails} FAILED` : "\nALL PASSED");
  process.exit(fails ? 1 : 0);
})();
