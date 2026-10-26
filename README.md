# PriceCompare (Android)

Search one product and compare price, MRP, discount and **₹ per kg / L / piece** across
Blinkit, Zepto, Swiggy Instamart, Flipkart Minutes, Flipkart, Amazon Fresh, Amazon and DMart Ready,
for your current delivery location.

## Install on your phone (no computer needed)

Every push to `main` builds the app on GitHub. On your phone, open this repository's
**Releases → Latest build** and download `PriceCompare.apk`, then open it and allow
"Install unknown apps" for your browser when asked.

## Build it yourself

1. Install **Android Studio** (any recent version).
2. *File → Open* → choose this `PriceCompare` folder. Let Gradle sync; accept any suggested plugin upgrades.
3. Turn on *Developer options → USB debugging* on your phone, plug it in, press **Run ▶**.
   Or *Build → Build App Bundle(s) / APK(s) → Build APK(s)* and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Minimum Android 8.0.

## First-time setup (in the app)

1. Allow location when asked.
2. Tap the **gear icon → Setup**. For each app tap **Open & set location**:
   - set your delivery address (the sites' "detect my location" buttons use your phone's GPS);
   - sign in to **Amazon** and **Flipkart** (Minutes and Fresh need it); signing in to Swiggy helps too.
3. Back on the main screen, search, e.g. `amul butter 500 g`.

Sign-ins and locations persist (the app keeps its own browser cookies). The top bar shows your phone's area and
pincode so you can check that every site is set to the same place.

## How it works

```
Search ─► for each enabled app (3 at a time)
            hidden WebView loads the site's search page (your session + location)
            extractor.js, injected before the page's own scripts:
              1. api : wraps fetch/XHR and reads the JSON the site fetches for itself
              2. ssr : reads state embedded in the HTML (__NEXT_DATA__, __INITIAL_STATE__, ld+json)
              3. dom : falls back to reading visible product cards (₹ prices, name, pack size)
            merges, fixes paise vs rupees, filters to items matching your words,
            parses pack size ("2 x 500 ml", "Pack of 3, 100 g") and computes ₹/kg, ₹/L or ₹/pc
        ◄─ results ranked by unit price (or by price)
```

- **Best match on each app** – the most relevant in-stock item per app, ranked cheapest per unit.
- **All matches** – everything found, same ranking.
- Status chips show each app's state (*Set location*, *Sign in*, *Not in your area*, *No match*).
  Tap a chip to open that app's page, fix it, then tap **Retry**.
- Tap any product to open it inside the app (with your session) and add it to the cart.

## When an app stops working

Sites change. In order of effort:

1. **Search URL changed** → Setup → *Search URL*: search on the site, copy the results page address,
   replace your search words with `{q}`, save. No rebuild needed.
2. **Prices look 100× off** → set `priceScale = 0.01` for that platform in `Platforms.kt`
   (auto-detection usually handles this).
3. **Nothing found but the page shows products** → the generic parsers missed the site's format; add
   key names to `NAME_KEYS` / `SP_KEYS` / `MRP_KEYS` / `QTY_KEYS` in `app/src/main/assets/extractor.js`.

Extractor tests (Node 18+): `cd tools && npm i jsdom && node extractor.test.js ../app/src/main/assets/extractor.js`

## Files

| File | What it does |
|---|---|
| `assets/extractor.js` | API capture, SSR + DOM extraction, pack-size and unit-price parsing |
| `ScrapeEngine.kt` | Off-screen WebViews, script injection, waits for results to settle |
| `SearchController.kt` | Runs searches in parallel, ranking, phone location lookup |
| `Platforms.kt` | The 8 apps, their URLs, saved settings |
| `Screens.kt` | Search, Setup and in-app browser screens |

## Notes

- For personal use. Scraping is against these platforms' terms of service, and heavy automated use can get an
  account rate-limited. The app only makes the same page loads you'd make by searching each app yourself.
- Apps that don't serve your pincode (often Flipkart Minutes, DMart Ready, Amazon Fresh) show *Not in your area*
  or *No match*.
- Delivery fees, handling charges and coupons aren't included in the comparison.
