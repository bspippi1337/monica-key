import { DurableObject } from "cloudflare:workers";

export interface Env {
  CHANNELS: DurableObjectNamespace<ChannelRoom>;
}

type Role = "PIPPI" | "MONICA";

type ChannelMeta = {
  id: string;
  claimHash: string;
  revokePublicKey: string;
  claimed: boolean;
  revoked: boolean;
  createdAt: number;
  claimedAt?: number;
  revokedAt?: number;
};

type ConnectionAttachment = {
  peer: Role;
  connectedAt: number;
};

const TOKEN = /^[A-Za-z0-9_-]+$/;
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_PACKET_BYTES = 256 * 1024;
const META_KEY = "channel-meta";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/healthz") {
      return json(200, {
        ok: true,
        service: "monica-key-relay",
        runtime: "cloudflare-durable-objects",
        time: Date.now(),
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/channels") {
      const parsed = await readJson<{ channelId?: unknown; claimSecret?: unknown }>(
        request.clone(),
      );
      if (!parsed.ok) return parsed.response;

      const channelId = stringValue(parsed.value.channelId);
      const claimSecret = stringValue(parsed.value.claimSecret);
      if (!validToken(channelId, 12, 128) || !validToken(claimSecret, 16, 256)) {
        return error(400, "invalid channel or claim token");
      }

      return room(env, channelId).fetch(request);
    }

    const channelRoute = url.pathname.match(
      /^\/v1\/channels\/([A-Za-z0-9_-]{12,128})\/(claim|revoke)$/,
    );
    if (request.method === "POST" && channelRoute) {
      return room(env, channelRoute[1]).fetch(request);
    }

    if (request.method === "GET" && url.pathname === "/v1/ws") {
      const channelId = url.searchParams.get("channel") ?? "";
      const peer = url.searchParams.get("peer") ?? "";
      if (!validToken(channelId, 12, 128) || (peer !== "PIPPI" && peer !== "MONICA")) {
        return error(400, "invalid websocket parameters");
      }
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
        return error(426, "expected websocket upgrade");
      }
      return room(env, channelId).fetch(request);
    }

    return error(404, "not found");
  },
} satisfies ExportedHandler<Env>;

function room(env: Env, channelId: string): DurableObjectStub<ChannelRoom> {
  const id = env.CHANNELS.idFromName(channelId);
  return env.CHANNELS.get(id);
}

export class ChannelRoom extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/v1/channels") {
      return this.createChannel(request);
    }

    const channelRoute = url.pathname.match(
      /^\/v1\/channels\/([A-Za-z0-9_-]{12,128})\/(claim|revoke)$/,
    );
    if (request.method === "POST" && channelRoute?.[2] === "claim") {
      return this.claimChannel(channelRoute[1], request);
    }
    if (request.method === "POST" && channelRoute?.[2] === "revoke") {
      return this.revokeChannel(channelRoute[1], request);
    }

    if (request.method === "GET" && url.pathname === "/v1/ws") {
      return this.openSocket(url, request);
    }

    return error(404, "not found");
  }

  private async createChannel(request: Request): Promise<Response> {
    const parsed = await readJson<{ channelId?: unknown; claimSecret?: unknown }>(request);
    if (!parsed.ok) return parsed.response;

    const channelId = stringValue(parsed.value.channelId);
    const claimSecret = stringValue(parsed.value.claimSecret);
    if (!validToken(channelId, 12, 128) || !validToken(claimSecret, 16, 256)) {
      return error(400, "invalid channel or claim token");
    }

    const claimHash = await sha256Hex(claimSecret);
    const existing = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (existing) {
      if (existing.id !== channelId) return error(409, "channel identity mismatch");
      if (existing.revoked) return error(410, "channel is permanently revoked");
      if (existing.claimed || existing.claimHash !== claimHash) {
        return error(409, "channel already exists");
      }
      return json(200, { message: "channel already ready" });
    }

    const meta: ChannelMeta = {
      id: channelId,
      claimHash,
      revokePublicKey: "",
      claimed: false,
      revoked: false,
      createdAt: Date.now(),
    };
    await this.ctx.storage.put(META_KEY, meta);
    return json(201, { message: "private channel created" });
  }

  private async claimChannel(channelId: string, request: Request): Promise<Response> {
    const parsed = await readJson<{
      claimSecret?: unknown;
      revokePublicKey?: unknown;
    }>(request);
    if (!parsed.ok) return parsed.response;

    const claimSecret = stringValue(parsed.value.claimSecret);
    const revokePublicKey = stringValue(parsed.value.revokePublicKey);
    if (!validToken(channelId, 12, 128) || !claimSecret || !revokePublicKey) {
      return error(400, "invalid claim request");
    }

    try {
      await importRevokeKey(revokePublicKey);
    } catch {
      return error(400, "invalid revocation key");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId) return error(404, "channel not found");
    if (meta.revoked) return error(410, "channel is revoked");
    if (meta.claimed) {
      if (meta.revokePublicKey === revokePublicKey) {
        return json(200, { message: "Monica already holds the revocation key" });
      }
      return error(409, "channel already claimed");
    }
    if (meta.claimHash !== (await sha256Hex(claimSecret))) {
      return error(403, "claim secret rejected");
    }

    meta.revokePublicKey = revokePublicKey;
    meta.claimHash = "";
    meta.claimed = true;
    meta.claimedAt = Date.now();
    await this.ctx.storage.put(META_KEY, meta);
    this.broadcastControl({ kind: "claimed" });
    return json(200, { message: "Monica now holds the revocation key" });
  }

  private async revokeChannel(channelId: string, request: Request): Promise<Response> {
    const parsed = await readJson<{ timestamp?: unknown; signature?: unknown }>(request);
    if (!parsed.ok) return parsed.response;

    const timestamp = numberValue(parsed.value.timestamp);
    const signature = stringValue(parsed.value.signature);
    if (!timestamp || !signature || Math.abs(Date.now() - timestamp) > 10 * 60 * 1000) {
      return error(400, "revocation timestamp outside accepted window");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId) return error(404, "channel not found");
    if (meta.revoked) return json(200, { message: "channel already revoked" });
    if (!meta.claimed || !meta.revokePublicKey) {
      return error(409, "channel has not been claimed by Monica");
    }

    const signedText = `${channelId}:${timestamp}`;
    const verified = await verifyJavaEcdsa(
      meta.revokePublicKey,
      signedText,
      signature,
    ).catch(() => false);
    if (!verified) return error(403, "revocation signature rejected");

    meta.revoked = true;
    meta.revokedAt = Date.now();
    await this.ctx.storage.put(META_KEY, meta);
    this.broadcastControl({ kind: "revoked" });
    for (const socket of this.ctx.getWebSockets()) {
      try {
        socket.close(4001, "revoked by Monica");
      } catch {
        // Socket may already be closing.
      }
    }
    return json(200, { message: "channel permanently revoked by Monica" });
  }

  private async openSocket(url: URL, request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return error(426, "expected websocket upgrade");
    }

    const channelId = url.searchParams.get("channel") ?? "";
    const peer = url.searchParams.get("peer") as Role | null;
    if (!validToken(channelId, 12, 128) || (peer !== "PIPPI" && peer !== "MONICA")) {
      return error(400, "invalid websocket parameters");
    }

    const meta = await this.ctx.storage.get<ChannelMeta>(META_KEY);
    if (!meta || meta.id !== channelId || meta.revoked) {
      return error(410, "channel is unavailable");
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      peer,
      connectedAt: Date.now(),
    } satisfies ConnectionAttachment);

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  async webSocketMessage(
    sender: WebSocket,
    message: string | ArrayBuffer,
  ): Promise<void> {
    const text =
      typeof message === "string" ? message : new TextDecoder().decode(message);
    if (new TextEncoder().encode(text).byteLength > MAX_PACKET_BYTES) return;
    if (!looksLikeEncryptedPacket(text)) return;

    const senderState = sender.deserializeAttachment() as ConnectionAttachment | null;
    if (!senderState) return;

    for (const target of this.ctx.getWebSockets()) {
      if (target === sender) continue;
      const targetState = target.deserializeAttachment() as ConnectionAttachment | null;
      if (!targetState || targetState.peer === senderState.peer) continue;
      try {
        target.send(text);
      } catch {
        // A closing socket is simply skipped.
      }
    }
  }

  async webSocketClose(
    socket: WebSocket,
    code: number,
    reason: string,
    _wasClean: boolean,
  ): Promise<void> {
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

function numberValue(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function importRevokeKey(encoded: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "spki",
    decodeBase64Url(encoded),
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
  const key = await importRevokeKey(publicKeyEncoded);
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
  if (offset + sequence.length !== signature.length) {
    throw new Error("invalid ECDSA sequence length");
  }

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

function readDerLength(
  bytes: Uint8Array,
  offset: number,
): { length: number; next: number } {
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
  while (value.length - start > 32 && value[start] === 0) start++;
  const trimmed = value.slice(start);
  if (trimmed.length > 32) throw new Error("ECDSA integer too large");
  const normalized = new Uint8Array(32);
  normalized.set(trimmed, 32 - trimmed.length);
  return normalized;
}

function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function looksLikeEncryptedPacket(text: string): boolean {
  try {
    const envelope = JSON.parse(text) as { kind?: unknown; payload?: unknown };
    if (envelope.kind !== "packet" || typeof envelope.payload !== "string") {
      return false;
    }
    if (envelope.payload.length < 40 || envelope.payload.length > 350_000) {
      return false;
    }
    decodeBase64Url(envelope.payload);
    return true;
  } catch {
    return false;
  }
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
    },
  });
}

function error(status: number, message: string): Response {
  return json(status, { message });
}
