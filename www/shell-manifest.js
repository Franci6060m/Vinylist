/*
 * Single source of truth for "what counts as the offline app shell".
 * Both sw.js (via importScripts) and index.html (via <script src>) load
 * this same file, so they can never disagree on the cache name or the
 * asset list again. Bump CACHE_NAME whenever CORE_ASSETS changes, or
 * whenever you ship a change to index.html/sw.js itself — that's what
 * forces every installed device to re-download the new shell instead of
 * quietly keeping the stale cached one forever.
 */
(function (root) {
  var SHELL = {
    CACHE_NAME: 'vinylist-shell-v12',
    CORE_ASSETS: [
      './',
      './index.html',
      './manifest.json',
      './shell-manifest.js',
      './native-media.js',
      './chess-bot-worker.js',
      './icons/icon-192.png',
      './icons/icon-512.png',
      './vendor/react.production.min.js',
      './vendor/react-dom.production.min.js',
      './vendor/lucide-react.js',
      './vendor/babel.min.js',
      './vendor/chess.min.js',
      'https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,600;1,9..144,500&family=Manrope:wght@400;500;600;700;800&display=swap',
    ],
    // esm.sh/unpkg dropped: React, ReactDOM, lucide-react and Babel are now
    // self-hosted under ./vendor/ (see index.html) so the shell never depends
    // on a CDN being reachable to boot. Google Fonts stays best-effort —
    // fonts.gstatic.com woff2 files get cached opportunistically the first
    // time they're fetched, and the UI falls back to system fonts if they
    // were never cached, so a miss there degrades gracefully instead of
    // blanking the app.
    CACHEABLE_HOSTS: ['fonts.googleapis.com', 'fonts.gstatic.com'],
  };
  root.VINYLIST_SHELL = SHELL; // works for both `self` (service worker) and `window` (page)
})(typeof self !== 'undefined' ? self : this);
