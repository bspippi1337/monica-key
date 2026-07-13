import { DurableObject } from "cloudflare:workers";

export interface Env {
  CHANNELS: DurableObjectNamespace<ChannelRoom>;
  RATE_GATES: DurableObjectNamespace<RateGate>;
}

type Role = "PIPPI" | "MONICA";
type ChallengePurpose = "claim" | "revoke" | "ws:PIPPI" | "ws:MONICA";

type ChannelMeta = {
  version: 2;
  id: string;
  claimPublicKey: string;
  pippiDevicePublicKey: string;
  monicaDevicePublicKey: string;
  revokePublicKey: string;
  claimed: boolean;
  revoked: boolean;
  createdAt: number;
  claimedAt?: number;
  revokedAt?: number;
  acceptedPackets: number;
  rejectedPackets: number;
};

type ChallengeRecord = {
  id: string;
  purpose: ChallengePurpose;
  nonce: string;
  expiresAt: number;
};

type ConnectionAttachment = {
  peer: Role;
  connectedAt: number;
  keyFingerprint: string;
};

type RateState = {
  windowStartedAt: number;
  count: number;
};

const TOKEN = /^[A-Za-z0-9_-]+$/;
const MAX_BODY_BYTES = 64 * 1024;
const FIXED_PACKET_BYTES = 8192;
const CHALLENGE_TTL_MS = 30_000;
const CLOCK_SKEW_MS = 30_000;
const META_KEY = "channel-meta-v2";
const MAX_SOCKETS_PER_ROLE = 2;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/healthz") {
      return json(200, {
        ok: true,
        protocol: 2,
        service: "monica-key-relay",
        runtime: "cloudflare-durable-objects",
        packetBytes: FIXED_PACKET_BYTES,
        time: Date.now(),
      });
    }

    const route = classifyRoute(request.method, url);
    if (!route) return error(404, "not found");

    const limited = await enforceRateLimit(env, request, route.action);
    if (limited) return limited;

    if (route.channelId) {
      return room(env, route.channelId).fetch(request);
    }

    if (route.action === "create") {
      const parsed = await readJson<{ channelId?: unknown }>(request.clone());
      if (!parsed.ok) return parsed.response;
      const channelId = stringValue(parsed.value.channelId);
      if (!validToken(channelId, 24, 128)) return error(400, "invalid channel id");
      return room(env, channelId).fetch(request);
    }

    return error(404, "not found");
  },
} satisfies ExportedHandler<Env>;

function classifyRoute(
  method: string,
  url: URL,
): { action: "create" | "challenge" | "claim" | "revoke" | "websocket"; channelId?: string } | null {
  if (method === "POST" && url.pathname === "/v2/channels") {
    return { action: "create" };
  }

  const match = url.pathname.match(
    /^\/v2\/channels\/([A-Za-z0-9_-]{24,128})\/(challenge|claim|revoke|ws)$/,
  );
  if (!match) return null;

  const action = match[2] === "ws" ? "websocket" : match[2] as "challenge" | "claim" | "revoke";
  if (action === "websocket" && method !== "GET") return null;
  if (action !== "websocket" && method !== "POST") return null;
  return { action, channelId: match[1] };
}

function room(env: Env, channelId: string): DurableObjectStub<ChannelRoom> {
  return env.CHANNELS.get(env.CHANNELS.idFromName(channelId));
}

async function enforceRateLimit(
  env: Env,
  request: Request,
  action: "create" | "challenge" | "claim" | "revoke" | "websocket",
): Promise<Response | null> {
  const rules = {
    create: { limit: 10, windowMs: 60_000 },
    challenge: { limit: 60, windowMs: 60_000 },
    claim: { limit: 12, windowMs: 60_000 },
    revoke: { limit: 12, windowMs: 60_000 },
    websocket: { limit: 40, windowMs: 60_000 },
  } as const;

  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const bucketName = `${action}:${(await sha256Hex(ip)).slice(0, 32)}`;
  const gate = env.RATE_GATES.get(env.RATE_GATES.idFromName(bucketName));
  const response = await gate.fetch("https://rate.internal/check", {
    method: "POST",
    body: JSON.stringify(rules[action]),
    headers: { "Content-Type": "application/json" },
  });
  return response.status === 204 ? null : response;
}

export class RateGate extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "POST") return error(405, "method not allowed");
    const parsed = await readJson<{ limit?: unknown; windowMs?: unknown }>(request);
    if (!parsed.ok) return parsed.response;

    const limit = integerValue(parsed.value.limit);
    const windowMs = integerValue(parsed.value.windowMs);
    if (limit < 1 || limit > 1000 || windowMs < 1000 || windowMs > 3_600_000) {
      return error(400, "invalid rate rule");
    }

    const now = Date.now();
    let state = await this.ctx.storage.get<RateState>("rate");
    if (!state || now - state.windowStartedAt >= windowMs) {
      state = { windowStartedAt: now, count: 0 };
    }

    if (state.count >= limit) {
      const retrySeconds = Math.max(1, Math.ceil((windowMs - (now - state.windowStartedAt)) / 1000));
      return new Response(JSON.stringify({ message: "too many requests" }), {
        status: 429,
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Retry-After": String(retrySeconds),
          "Cache-Control": "no-store",
        },
      });
    }

    state.count += 1;
    await this.ctx.storage.put("rate", state);
    return new Response(null, { status: 204 });
  }
}

export class ChannelRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/v2/channels") {
      return this.createChannel(request);
    }

    const match = url.pathname.match(
      /^\/v2\/channels\/([A-Za-z0-9_-]{24,128})\/(challenge|claim|revoke|ws)$/,
    );
    if (!match) return error(404, "not found");

    const channelId = match[1];
    switch (match[2]) {
      case "challenge":
        return request.method === "POST"
          ? this.createChallenge(channelId, request)
          : error(405, "method not allowed");
      case "claim":
        return request.method === "POST"
          ? this.claimChannel(channelId, request)
          : error(405, "method not allowed");
      case "revoke":
        return request.method === "POST"
          ? this.revokeChannel(channelId, request)
          : error(405, "method not allowed");
      case "ws":
        return request.method === "GET"
          ? this.openSocket(channelId, url, request)
          : error(405, "method not allowed");
      default:
        return error(404, "not found");
    }
  }

  private async createChannel(request: Request): Promise<Response> {
    const parsed = await readJson<{
      channelId?: unknown;
      claimPublicKey?: unknown;
      pippiDevicePublicKey?: unknown;
      timestamp?: unknown;
      signature?: unknown;
    }>(request);
    if (!parsed.ok) return parsed.response;

    const channelId = stringValue(parsed.value.channelId);
    const claimPublicKey = stringValue(parsed.value.claimPublicKey);
    const pippiDevicePublicKey = stringValue(parsed.value.pippiDevicePublicKey);
    const timestamp = integerValue(parsed.value.timestamp);
    const signature = stringValue(parsed.value.signature);

    if (!validToken(channelId, 24, 128) || !fresh(timestamp) || !signature) {
      return error(400, "invalid create request");
    }

    try {
      await importP256PublicKey(claimPublicKey);
      await importP256PublicKey(pippiDevicePublicKey);
    } catch {
      return error(400, "invalid public key");
    }

    const canonical = canonicalLines(
      "create",
      "2",
      channelId,
      claimPublicKey,
      pippiDevicePublicKey,
      String(timestamp),
    );
    if (!(await verifyJavaEcdsa(pippiDevicePublicKey, canonical, signature).catch(() => false))) {
      return error(403, "invalid device signature");
    }

    const existing = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (existing) {
      if (existing.revoked) return error(410, "channel is permanently revoked");
      if (
        existing.id === channelId &&
        existing.claimPublicKey === claimPublicKey &&
        existing.pippiDevicePublicKey === pippiDevicePublicKey
      ) {
        return json(200, { message: "channel already ready", protocol: 2 });
      }
      return error(409, "channel already exists");
    }

    const meta: ChannelMeta = {
      version: 2,
      id: channelId,
      claimPublicKey,
      pippiDevicePublicKey,
      monicaDevicePublicKey: "",
      revokePublicKey: "",
      claimed: false,
      revoked: false,
      createdAt: Date.now(),
      acceptedPackets: 0,
      rejectedPackets: 0,
    };
    await this.ctx.storage.put(META_KEY, meta);
    return json(201, { message: "private channel created", protocol: 2 });
  }

  private async createChallenge(channelId: string, request: Request): Promise<Response> {
    const parsed = await readJson<{ purpose?: unknown; peer?: unknown }>(request);
    if (!parsed.ok) return parsed.response;

    const purposeText = stringValue(parsed.value.purpose);
    const peerText = stringValue(parsed.value.peer);
    let purpose: ChallengePurpose;
    if (purposeText === "claim") purpose = "claim";
    else if (purposeText === "revoke") purpose = "revoke";
    else if (purposeText === "ws" && (peerText === "PIPPI" || peerText === "MONICA")) {
      purpose = `ws:${peerText}` as ChallengePurpose;
    } else {
      return error(400, "invalid challenge purpose");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId) return error(404, "channel not found");
    if (meta.revoked) return error(410, "channel is revoked");
    if (purpose === "claim" && meta.claimed) return error(409, "channel already claimed");
    if ((purpose === "revoke" || purpose === "ws:MONICA") && !meta.claimed) {
      return error(409, "channel has not been claimed");
    }

    await this.deleteExpiredChallenges();
    const id = randomToken(18);
    const record: ChallengeRecord = {
      id,
      purpose,
      nonce: randomToken(32),
      expiresAt: Date.now() + CHALLENGE_TTL_MS,
    };
    await this.ctx.storage.put(`challenge:${id}`, record);
    return json(201, record);
  }

  private async claimChannel(channelId: string, request: Request): Promise<Response> {
    const parsed = await readJson<{
      challengeId?: unknown;
      timestamp?: unknown;
      claimSignature?: unknown;
      monicaDevicePublicKey?: unknown;
      revokePublicKey?: unknown;
    }>(request);
    if (!parsed.ok) return parsed.response;

    const challengeId = stringValue(parsed.value.challengeId);
    const timestamp = integerValue(parsed.value.timestamp);
    const claimSignature = stringValue(parsed.value.claimSignature);
    const monicaDevicePublicKey = stringValue(parsed.value.monicaDevicePublicKey);
    const revokePublicKey = stringValue(parsed.value.revokePublicKey);
    if (!challengeId || !fresh(timestamp) || !claimSignature) {
      return error(400, "invalid claim request");
    }

    try {
      await importP256PublicKey(monicaDevicePublicKey);
      await importP256PublicKey(revokePublicKey);
    } catch {
      return error(400, "invalid Monica public key");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId) return error(404, "channel not found");
    if (meta.revoked) return error(410, "channel is revoked");
    if (meta.claimed) {
      if (
        meta.monicaDevicePublicKey === monicaDevicePublicKey &&
        meta.revokePublicKey === revokePublicKey
      ) {
        return json(200, { message: "Monica already holds the key", protocol: 2 });
      }
      return error(409, "channel already claimed");
    }

    const challenge = await this.consumeChallenge(challengeId, "claim");
    if (!challenge) return error(403, "invalid or expired challenge");

    const canonical = canonicalLines(
      "claim",
      "2",
      channelId,
      challenge.id,
      challenge.nonce,
      String(timestamp),
      monicaDevicePublicKey,
      revokePublicKey,
    );
    if (!(await verifyJavaEcdsa(meta.claimPublicKey, canonical, claimSignature).catch(() => false))) {
      return error(403, "claim capability rejected");
    }

    meta.monicaDevicePublicKey = monicaDevicePublicKey;
    meta.revokePublicKey = revokePublicKey;
    meta.claimed = true;
    meta.claimedAt = Date.now();
    await this.ctx.storage.put(META_KEY, meta);
    this.broadcastControl({ kind: "claimed", protocol: 2 });
    return json(200, { message: "Monica now controls revocation", protocol: 2 });
  }

  private async revokeChannel(channelId: string, request: Request): Promise<Response> {
    const parsed = await readJson<{
      challengeId?: unknown;
      timestamp?: unknown;
      signature?: unknown;
    }>(request);
    if (!parsed.ok) return parsed.response;

    const challengeId = stringValue(parsed.value.challengeId);
    const timestamp = integerValue(parsed.value.timestamp);
    const signature = stringValue(parsed.value.signature);
    if (!challengeId || !fresh(timestamp) || !signature) {
      return error(400, "invalid revoke request");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId) return error(404, "channel not found");
    if (meta.revoked) return json(200, { message: "channel already revoked" });
    if (!meta.claimed || !meta.revokePublicKey) {
      return error(409, "channel has not been claimed");
    }

    const challenge = await this.consumeChallenge(challengeId, "revoke");
    if (!challenge) return error(403, "invalid or expired challenge");

    const canonical = canonicalLines(
      "revoke",
      "2",
      channelId,
      challenge.id,
      challenge.nonce,
      String(timestamp),
    );
    if (!(await verifyJavaEcdsa(meta.revokePublicKey, canonical, signature).catch(() => false))) {
      return error(403, "revocation signature rejected");
    }

    meta.revoked = true;
    meta.revokedAt = Date.now();
    await this.ctx.storage.put(META_KEY, meta);
    this.broadcastControl({ kind: "revoked", protocol: 2 });
    for (const socket of this.ctx.getWebSockets()) {
      try {
        socket.close(4001, "revoked by Monica");
      } catch {
        // Already closing.
      }
    }
    return json(200, { message: "channel permanently revoked" });
  }

  private async openSocket(
    channelId: string,
    url: URL,
    request: Request,
  ): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return error(426, "expected websocket upgrade");
    }

    const peer = url.searchParams.get("peer") as Role | null;
    const challengeId = url.searchParams.get("challenge") ?? "";
    const timestamp = integerValue(url.searchParams.get("timestamp"));
    const signature = url.searchParams.get("signature") ?? "";
    if ((peer !== "PIPPI" && peer !== "MONICA") || !challengeId || !fresh(timestamp) || !signature) {
      return error(400, "invalid websocket authorization");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId || meta.revoked) {
      return error(410, "channel is unavailable");
    }
    if (peer === "MONICA" && !meta.claimed) return error(409, "channel not claimed");

    const key = peer === "PIPPI" ? meta.pippiDevicePublicKey : meta.monicaDevicePublicKey;
    if (!key) return error(403, "device key not registered");

    const purpose = `ws:${peer}` as ChallengePurpose;
    const challenge = await this.consumeChallenge(challengeId, purpose);
    if (!challenge) return error(403, "invalid or expired challenge");

    const canonical = canonicalLines(
      "websocket",
      "2",
      channelId,
      peer,
      challenge.id,
      challenge.nonce,
      String(timestamp),
    );
    if (!(await verifyJavaEcdsa(key, canonical, signature).catch(() => false))) {
      return error(403, "device signature rejected");
    }

    const currentForRole = this.ctx.getWebSockets().filter((socket) => {
      const attachment = socket.deserializeAttachment() as ConnectionAttachment | null;
      return attachment?.peer === peer;
    }).length;
    if (currentForRole >= MAX_SOCKETS_PER_ROLE) {
      return error(429, "too many active sockets for this device role");
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      peer,
      connectedAt: Date.now(),
      keyFingerprint: (await sha256Hex(key)).slice(0, 16),
    } satisfies ConnectionAttachment);

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(
    sender: WebSocket,
    message: string | ArrayBuffer,
  ): Promise<void> {
    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.revoked) {
      try {
        sender.close(4001, "channel unavailable");
      } catch {
        // Ignore.
      }
      return;
    }

    if (!(message instanceof ArrayBuffer) || message.byteLength !== FIXED_PACKET_BYTES) {
      meta.rejectedPackets += 1;
      await this.ctx.storage.put(META_KEY, meta);
      return;
    }

    meta.acceptedPackets += 1;
    if ((meta.acceptedPackets & 63) === 0) await this.ctx.storage.put(META_KEY, meta);

    const senderState = sender.deserializeAttachment() as ConnectionAttachment | null;
    if (!senderState) return;

    for (const target of this.ctx.getWebSockets()) {
      if (target === sender) continue;
      const targetState = target.deserializeAttachment() as ConnectionAttachment | null;
      if (!targetState || targetState.peer === senderState.peer) continue;
      try {
        target.send(message.slice(0));
      } catch {
        // Skip stale socket.
      }
    }
  }

  async webSocketClose(socket: WebSocket, code: number, reason: string): Promise<void> {
    try {
      socket.close(code, reason);
    } catch {
      // Cloudflare may already have completed the close handshake.
    }
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    try {
      socket.close(1011, "relay websocket error");
    } catch {
      // Ignore duplicate close.
    }
  }

  private async consumeChallenge(
    id: string,
    expectedPurpose: ChallengePurpose,
  ): Promise<ChallengeRecord | null> {
    const key = `challenge:${id}`;
    const record = await this.ctx.storage.get<ChallengeRecord>(key);
    await this.ctx.storage.delete(key);
    if (!record) return null;
    if (record.purpose !== expectedPurpose || record.expiresAt < Date.now()) return null;
    return record;
  }

  private async deleteExpiredChallenges(): Promise<void> {
    const listed = await this.ctx.storage.list<ChallengeRecord>({ prefix: "challenge:" });
    const now = Date.now();
    const expired: string[] = [];
    for (const [key, record] of listed) {
      if (record.expiresAt < now) expired.push(key);
    }
    if (expired.length > 0) await this.ctx.storage.delete(expired);
  }

  private broadcastControl(payload: Record<string, unknown>): void {
    const encoded = JSON.stringify(payload);
    for (const socket of this.ctx.getWebSockets()) {
      try {
        socket.send(encoded);
      } catch {
        // Skip stale socket.
      }
    }
  }
}

async function readJson<T>(
  request: Request,
): Promise<{ ok: true; value: T } | { ok: false; response: Response }> {
  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    return { ok: false, response: error(413, "request body too large") };
  }

  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BODY_BYTES) {
    return { ok: false, response: error(413, "request body too large") };
  }

  try {
    const value = JSON.parse(text) as T;
    if (!value || typeof value !== "object") throw new Error("not an object");
    return { ok: true, value };
  } catch {
    return { ok: false, response: error(400, "invalid JSON") };
  }
}

function validToken(value: string, min: number, max: number): boolean {
  return value.length >= min && value.length <= max && TOKEN.test(value);
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function integerValue(value: unknown): number {
  if (typeof value === "number" && Number.isSafeInteger(value)) return value;
  if (typeof value === "string" && /^\d{1,16}$/.test(value)) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : 0;
  }
  return 0;
}

function fresh(timestamp: number): boolean {
  return timestamp > 0 && Math.abs(Date.now() - timestamp) <= CLOCK_SKEW_MS;
}

function canonicalLines(...parts: string[]): string {
  for (const part of parts) {
    if (part.includes("\n") || part.includes("\r")) throw new Error("invalid canonical value");
  }
  return parts.join("\n");
}

function randomToken(bytes: number): string {
  const value = new Uint8Array(bytes);
  crypto.getRandomValues(value);
  return encodeBase64Url(value);
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function importP256PublicKey(encoded: string): Promise<CryptoKey> {
  const raw = decodeBase64Url(encoded);
  if (raw.length < 80 || raw.length > 256) throw new Error("invalid SPKI length");
  return crypto.subtle.importKey(
    "spki",
    raw,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
}

async function verifyJavaEcdsa(
  publicKeyEncoded: string,
  text: string,
  signatureEncoded: string,
): Promise<boolean> {
  const key = await importP256PublicKey(publicKeyEncoded);
  const signature = derEcdsaToP1363(decodeBase64Url(signatureEncoded));
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    signature,
    new TextEncoder().encode(text),
  );
}

function derEcdsaToP1363(signature: Uint8Array): Uint8Array {
  if (signature.length === 64) return signature;
  let offset = 0;
  if (signature[offset++] !== 0x30) throw new Error("invalid ECDSA sequence");
  const sequence = readDerLength(signature, offset);
  offset = sequence.next;
  if (offset + sequence.length !== signature.length) throw new Error("invalid ECDSA length");

  if (signature[offset++] !== 0x02) throw new Error("invalid ECDSA r");
  const rLength = readDerLength(signature, offset);
  offset = rLength.next;
  const r = signature.slice(offset, offset + rLength.length);
  offset += rLength.length;

  if (signature[offset++] !== 0x02) throw new Error("invalid ECDSA s");
  const sLength = readDerLength(signature, offset);
  offset = sLength.next;
  const s = signature.slice(offset, offset + sLength.length);

  const raw = new Uint8Array(64);
  raw.set(normalizeDerInteger(r), 0);
  raw.set(normalizeDerInteger(s), 32);
  return raw;
}

function readDerLength(bytes: Uint8Array, offset: number): { length: number; next: number } {
  const first = bytes[offset++];
  if (first === undefined) throw new Error("missing DER length");
  if ((first & 0x80) === 0) return { length: first, next: offset };
  const count = first & 0x7f;
  if (count < 1 || count > 2) throw new Error("unsupported DER length");
  let length = 0;
  for (let index = 0; index < count; index++) {
    const value = bytes[offset++];
    if (value === undefined) throw new Error("truncated DER length");
    length = (length << 8) | value;
  }
  return { length, next: offset };
}

function normalizeDerInteger(value: Uint8Array): Uint8Array {
  let start = 0;
  while (value.length - start > 32 && value[start] === 0) start += 1;
  const trimmed = value.slice(start);
  if (trimmed.length > 32) throw new Error("ECDSA integer too large");
  const normalized = new Uint8Array(32);
  normalized.set(trimmed, 32 - trimmed.length);
  return normalized;
}

function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function securityHeaders(): HeadersInit {
  return {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Cross-Origin-Resource-Policy": "same-origin",
  };
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: securityHeaders() });
}

function error(status: number, message: string): Response {
  return json(status, { message, protocol: 2 });
}
