# PriceCompare (Android)

Search one product and compare price, MRP, discount and **₹ per kg / L / piece** across up to
14 shopping apps in four categories (Grocery, Apparel, Beauty, Electronics), all set to your phone's current location.

## Install on your phone (no computer needed)

Every push to `main` builds the app on GitHub. On your phone, open this repository's
**Releases → Latest build** and download `PriceCompare.apk`, then open it and allow
"Install unknown apps" for your browser when asked.
From version 1.1 on, new builds install over the old one (same signing key), keeping your sign-ins.

## Build it yourself

1. Install **Android Studio** (any recent version).
2. *File → Open* → choose this `PriceCompare` folder. Let Gradle sync; accept any suggested plugin upgrades.
3. Turn on *Developer options → USB debugging* on your phone, plug it in, press **Run ▶**.
   Or *Build → Build App Bundle(s) / APK(s) → Build APK(s)* and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Minimum Android 8.0.

## Categories

| Category | Apps | Ranked by |
|---|---|---|
| Grocery | Blinkit, Zepto, Instamart, Flipkart Minutes, DMart Ready, Amazon Fresh, Amazon, Flipkart | ₹ per kg / L / pc |
| Apparel | Myntra, AJIO, Amazon (fashion), Flipkart | price |
| Beauty | Nykaa, Purplle, Blinkit, Zepto, Instamart, Myntra, Amazon (beauty), Flipkart | ₹ per ml-g / pc |
| Electronics | Croma, Reliance Digital, Amazon (electronics), Flipkart, Flipkart Minutes, Blinkit, Zepto, Instamart | price |

Turn an app on/off per category in **Setup**.

## Location (automatic)

The app takes the phone's location (GPS / network) and turns it into a pincode + area. The bar at the top shows it;
**↻** re-reads it, **✎** lets you type a pincode instead. That one location is applied to every app — on hidden
searches *and* when you tap an app or product to open it:

- every site's "detect my location" receives the phone's coordinates instantly (`location.js` overrides geolocation);
- location popups are filled in automatically: the "use current location" button is pressed, or the pincode / area
  is typed and confirmed (only right after its own action, max 10 actions per page);
- Amazon / Amazon Fresh: the delivery pincode is set through Amazon's own pincode request;
- Blinkit and Swiggy: location cookies are pre-set;
- when you move to a new pincode, Blinkit, Zepto and DMart forget their old location so they take the new one.

Still needed once: **sign in to Flipkart** (Minutes needs a saved address near you) and Amazon if Fresh asks.

## First-time setup (in the app)

1. Allow location when asked.
2. Optional: **Setup → Open / sign in** for Flipkart and Amazon.
3. Pick a category, search.

## When an app stops working

Sites change. In order of effort:

1. **Search URL changed** → Setup → *Search URL*: search on the site, copy the results page address,
   replace your search words with `{q}`, save. No rebuild needed.
2. **Prices look 100× off** → set `priceScale = 0.01` for that platform in `Platforms.kt`
   (auto-detection usually handles this).
3. **Nothing found but the page shows products** → the generic parsers missed the site's format; add
   key names to `NAME_KEYS` / `SP_KEYS` / `MRP_KEYS` / `QTY_KEYS` in `app/src/main/assets/extractor.js`.

Tests (Node 18+): `cd tools && npm i jsdom && node extractor.test.js ../app/src/main/assets/extractor.js && node location.test.js ../app/src/main/assets/location.js`

## Files

| File | What it does |
|---|---|
| `assets/location.js` | Phone location into each site: geolocation, popup filler, Amazon pincode |
| `Location.kt` | GPS fix, pincode lookup, location cookies, reset after moving |
| `assets/extractor.js` | API capture, SSR + DOM extraction, pack-size and unit-price parsing |
| `ScrapeEngine.kt` | Off-screen WebViews, script injection, waits for results to settle |
| `SearchController.kt` | Runs searches in parallel, ranking, phone location lookup |
| `Platforms.kt` | Categories, the 14 apps, their URLs, saved settings |
| `Screens.kt` | Search, Setup and in-app browser screens |

## Notes

- For personal use. Scraping is against these platforms' terms of service, and heavy automated use can get an
  account rate-limited. The app only makes the same page loads you'd make by searching each app yourself.
- Apps that don't serve your pincode (often Flipkart Minutes, DMart Ready, Amazon Fresh) show *Not in your area*
  or *No match*.
- Delivery fees, handling charges and coupons aren't included in the comparison.
