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
    CACHE_NAME: 'vinylist-shell-v8',
    CORE_ASSETS: [
      './',
      './index.html',
      './manifest.json',
      './shell-manifest.js',
      './icons/icon-192.png',
      './icons/icon-512.png',
      'https://esm.sh/react@18.3.1',
      'https://esm.sh/react-dom@18.3.1?external=react',
      'https://esm.sh/react-dom@18.3.1/client?external=react',
      'https://esm.sh/lucide-react@0.383.0?external=react',
      'https://unpkg.com/@babel/standalone@7.24.7/babel.min.js',
      'https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400;0,9..144,600;1,9..144,500&family=Manrope:wght@400;500;600;700;800&display=swap',
    ],
    CACHEABLE_HOSTS: ['esm.sh', 'unpkg.com', 'fonts.googleapis.com', 'fonts.gstatic.com'],
  };
  root.VINYLIST_SHELL = SHELL; // works for both `self` (service worker) and `window` (page)
})(typeof self !== 'undefined' ? self : this);
