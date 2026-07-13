# Monica Key

To separate native Android-apper fra samme private kodebase.

- `Pippi Key` logger og sender live-posisjon.
- `Monica Key` mottar, setter hjem og holder revokasjonsnøkkelen.
- Begge viser ETA, kryptert chat og lyd.
- Ingen Google Maps, Firebase, Google-konto, PC-server eller self-hosted runner.

## Gratis relay

Relayen kjører på Cloudflare Workers Free med én SQLite-basert Durable Object per privat kanal. WebSocket Hibernation lar forbindelsene bli stående mens relayen sover mellom meldinger.

Relayen ser bare krypterte pakker. AES-nøkkelen ligger på telefonene. Monica-appen lagrer den eneste private revokasjonsnøkkelen.

## Engangsaktivering

Opprett en gratis Cloudflare-konto og et API-token med tillatelsen `Edit Cloudflare Workers`. Kjør deretter:

```bash
cd ~/monica-key
git pull --ff-only
chmod +x cloudflare-enable.sh
./cloudflare-enable.sh
```

Skriptet lar `gh secret set` lese Account ID og API-token direkte. Verdiene lagres som krypterte GitHub-secrets og skrives ikke til repoet.

Etter engangsaktiveringen skjer dette automatisk ved push til `main`:

1. Cloudflare-relayen valideres og deployes.
2. `/healthz` må svare før byggingen fortsetter.
3. Pippi Key og Monica Key bygges med den faktiske `workers.dev`-adressen.
4. Begge APK-ene publiseres i den rullerende `latest`-releasen.

## Hent APK-ene

```bash
cd ~/monica-key
./termux-build.sh
```

Filer:

```text
~/storage/downloads/PippiKey-debug.apk
~/storage/downloads/MonicaKey-debug.apk
```
