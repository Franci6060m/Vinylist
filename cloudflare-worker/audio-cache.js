// cloudflare-worker/audio-cache.js
//
// Replaces the old Supabase `audio-proxy` Edge Function. ccMixter blocks
// hotlinked audio unless the request's Referer is ccmixter.org, so this
// Worker fetches the file server-side with the right Referer and streams
// it back — same trick the Supabase function did. The difference is this
// caches each track at Cloudflare's edge, so repeat plays are served from
// cache with zero egress cost and no repeat hit on ccMixter's servers.
//
// Deploy: paste this into your Worker's editor and hit Save and deploy.
// Then set AUDIO_CACHE_WORKER_URL in www/index.html to this Worker's URL.

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Browsers may preflight with OPTIONS before the real GET — respond
    // so that doesn't fail outright.
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
          'Access-Control-Allow-Headers': 'Range',
        },
      });
    }

    const target = url.searchParams.get('url');
    if (!target) return new Response('Missing url param', { status: 400 });

    let targetUrl;
    try {
      targetUrl = new URL(target);
    } catch {
      return new Response('Invalid url param', { status: 400 });
    }

    // Only ever proxy ccMixter — this Worker isn't a general-purpose open
    // proxy, and shouldn't be usable as one.
    if (!targetUrl.hostname.endsWith('ccmixter.org')) {
      return new Response('Only ccmixter.org URLs are allowed', { status: 403 });
    }

    // Cache the full file once, keyed on the target URL itself (not the
    // whole proxy request, and not the Range header) — so every request
    // for the same track, seek or not, hits the same cache entry.
    const cacheKey = new Request(targetUrl.toString(), { method: 'GET' });
    const cache = caches.default;

    let cached = await cache.match(cacheKey);

    if (!cached) {
      const originRes = await fetch(targetUrl.toString(), {
        headers: { Referer: 'http://ccmixter.org/' },
      });
      if (!originRes.ok) {
        return new Response('Upstream fetch failed', { status: 502 });
      }
      const stored = new Response(originRes.body, originRes);
      stored.headers.set('Cache-Control', 'public, max-age=604800'); // 7 days — ccMixter files don't change once published
      stored.headers.set('Accept-Ranges', 'bytes');
      ctx.waitUntil(cache.put(cacheKey, stored.clone()));
      cached = stored;
    }

    // Serve Range requests out of the cached copy so seeking in the
    // <audio> element works properly instead of always sending the whole
    // file. Cloudflare's fetch cache doesn't do this for us automatically
    // once we've re-wrapped the Response above, so it's handled by hand.
    const rangeHeader = request.headers.get('Range');
    if (rangeHeader) {
      const buf = await cached.clone().arrayBuffer();
      const total = buf.byteLength;
      const match = /bytes=(\d*)-(\d*)/.exec(rangeHeader);
      if (match) {
        const start = match[1] ? parseInt(match[1], 10) : 0;
        const end = match[2] ? parseInt(match[2], 10) : total - 1;
        const chunk = buf.slice(start, end + 1);
        return new Response(chunk, {
          status: 206,
          headers: {
            'Content-Type': cached.headers.get('Content-Type') || 'audio/mpeg',
            'Content-Range': `bytes ${start}-${end}/${total}`,
            'Content-Length': String(chunk.byteLength),
            'Accept-Ranges': 'bytes',
            'Access-Control-Allow-Origin': '*',
            'Cache-Control': 'public, max-age=604800',
          },
        });
      }
    }

    const response = new Response(cached.body, cached);
    response.headers.set('Access-Control-Allow-Origin', '*');
    return response;
  },
};
