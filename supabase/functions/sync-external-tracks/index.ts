// supabase/functions/sync-external-tracks/index.ts
//
// Pulls tracks from Jamendo + ccMixter and upserts them into the shared
// `tracks` table (source: 'jamendo' | 'ccmixter'). Runs entirely server
// side — the API keys never touch the client, and no user login is
// required to call this (or to see the resulting tracks, since the feed
// read is public).
//
// Deploy:
//   supabase functions deploy sync-external-tracks --no-verify-jwt
//
// Secrets this expects (adjust the names to whatever you already set,
// then update the Deno.env.get(...) calls below to match):
//   JAMENDO_CLIENT_ID   — free at https://devportal.jamendo.com
//   (ccMixter's query API is open and doesn't need a key)
//
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are auto-injected into every
// edge function by Supabase — you don't need to set those yourself.

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const JAMENDO_CLIENT_ID = Deno.env.get('JAMENDO_CLIENT_ID');
const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;

// How many tracks to pull from each source per run.
const LIMIT = 50;

Deno.serve(async (req) => {
  if (req.method !== 'POST' && req.method !== 'GET') {
    return new Response('Method not allowed', { status: 405 });
  }

  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);

  const [jamendoRows, ccmixterRows] = await Promise.all([
    fetchJamendo().catch((err) => {
      console.error('Jamendo fetch failed', err);
      return [];
    }),
    fetchCcMixter().catch((err) => {
      console.error('ccMixter fetch failed', err);
      return [];
    }),
  ]);

  const rows = [...jamendoRows, ...ccmixterRows];

  if (rows.length === 0) {
    return new Response(JSON.stringify({ ok: true, upserted: 0, note: 'No rows fetched from either source.' }), {
      headers: { 'Content-Type': 'application/json' },
    });
  }

  const { error, count } = await supabase
    .from('tracks')
    .upsert(rows, { onConflict: 'source,external_id', count: 'exact' });

  if (error) {
    console.error('Upsert failed', error);
    return new Response(JSON.stringify({ ok: false, error: error.message }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    });
  }

  return new Response(JSON.stringify({ ok: true, upserted: count ?? rows.length }), {
    headers: { 'Content-Type': 'application/json' },
  });
});

// ---- Jamendo -----------------------------------------------------------

async function fetchJamendo() {
  if (!JAMENDO_CLIENT_ID) {
    console.warn('JAMENDO_CLIENT_ID not set — skipping Jamendo sync');
    return [];
  }

  const url = new URL('https://api.jamendo.com/v3.0/tracks/');
  url.searchParams.set('client_id', JAMENDO_CLIENT_ID);
  url.searchParams.set('format', 'json');
  url.searchParams.set('limit', String(LIMIT));
  url.searchParams.set('audioformat', 'mp32');
  url.searchParams.set('include', 'musicinfo');
  url.searchParams.set('order', 'popularity_total');

  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`Jamendo API ${res.status}`);
  const json = await res.json();

  return (json.results || []).map((t: any) => ({
    source: 'jamendo',
    external_id: String(t.id),
    title: t.name,
    artist: t.artist_name,
    album: t.album_name || null,
    audio_url: t.audio,
    cover_url: t.album_image || t.image || null,
    description: null,
    play_count: 0,
    like_count: 0,
  }));
}

// ---- ccMixter -----------------------------------------------------------

async function fetchCcMixter() {
  const url = new URL('http://ccmixter.org/api/query');
  url.searchParams.set('f', 'json');
  url.searchParams.set('limit', String(LIMIT));
  url.searchParams.set('sort', 'rank');
  url.searchParams.set('dataview', 'sf'); // include file/stream info

  const res = await fetch(url.toString());
  if (!res.ok) throw new Error(`ccMixter API ${res.status}`);
  const json = await res.json();

  return (json || [])
    .map((t: any) => {
      // ccMixter nests downloadable file info in file_info / files; the
      // exact key can vary by upload, so fall back gracefully.
      const audioUrl = t.files?.[0]?.download_url || t.file_info?.[0]?.download_url;
      if (!audioUrl) return null;
      return {
        source: 'ccmixter',
        external_id: String(t.upload_id ?? t.sample_id),
        title: t.upload_name,
        artist: t.user_name,
        album: null,
        audio_url: audioUrl,
        cover_url: t.images?.thumb?.url || null,
        description: null,
        play_count: 0,
        like_count: 0,
      };
    })
    .filter(Boolean);
}
