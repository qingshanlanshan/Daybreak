// Static files are served before this Worker runs. It only sees paths that aren't a file: the Mac
// downloads, which are over Cloudflare's 25 MiB per-file limit and so are published in parts
// (<name>.part0, .part1, …) with a list (<name>.parts.json). This streams the parts back as one file.
export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const name = url.pathname.slice(1);
    const list = name && !name.includes("/") ? await env.ASSETS.fetch(new URL(`/${name}.parts.json`, url)) : null;
    if (!list || !list.ok) return env.ASSETS.fetch(req);
    const { parts, size, type, download, cache } = await list.json();
    const headers = { "content-type": type, "content-length": String(size), "cache-control": cache };
    if (download) headers["content-disposition"] = `attachment; filename="${download}"`;
    if (req.method === "HEAD") return new Response(null, { headers });
    const { readable, writable } = new FixedLengthStream(size);
    ctx.waitUntil((async () => {
      try {
        for (const p of parts) {
          // Reading a stored file occasionally fails right after a deploy: try a few times.
          let r;
          for (let i = 0; i < 5; i++) {
            r = await env.ASSETS.fetch(new URL(`/${p}`, url));
            if (r.ok) break;
            await new Promise((ok) => setTimeout(ok, 300 * (i + 1)));
          }
          if (!r.ok) throw new Error(`missing ${p}`);
          await r.body.pipeTo(writable, { preventClose: true });
        }
        await writable.close();
      } catch (e) {
        await writable.abort(e);
      }
    })());
    return new Response(readable, { headers });
  },
};
