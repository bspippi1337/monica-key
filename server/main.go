package main

import (
    "crypto/ecdsa"
    "crypto/sha256"
    "crypto/x509"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "errors"
    "fmt"
    "log"
    "net/http"
    "os"
    "path/filepath"
    "strings"
    "sync"
    "time"

    "github.com/gorilla/websocket"
)

type Channel struct {
    ID              string `json:"id"`
    ClaimHash       string `json:"claimHash,omitempty"`
    RevokePublicKey string `json:"revokePublicKey,omitempty"`
    Claimed         bool   `json:"claimed"`
    Revoked         bool   `json:"revoked"`
    CreatedAt       int64  `json:"createdAt"`
    ClaimedAt       int64  `json:"claimedAt,omitempty"`
    RevokedAt       int64  `json:"revokedAt,omitempty"`
}

type client struct {
    channel string
    peer    string
    conn    *websocket.Conn
    send    chan []byte
}

type server struct {
    mu       sync.RWMutex
    channels map[string]*Channel
    clients  map[string]map[*client]struct{}
    dataFile string
    upgrader websocket.Upgrader
}

func main() {
    addr := getenv("MONICA_KEY_ADDR", ":4242")
    dataDir := getenv("MONICA_KEY_DATA", "./data")
    if err := os.MkdirAll(dataDir, 0700); err != nil {
        log.Fatal(err)
    }

    s := &server{
        channels: map[string]*Channel{},
        clients:  map[string]map[*client]struct{}{},
        dataFile: filepath.Join(dataDir, "channels.json"),
        upgrader: websocket.Upgrader{
            ReadBufferSize:  4096,
            WriteBufferSize: 4096,
            CheckOrigin: func(r *http.Request) bool {
                return true
            },
        },
    }
    if err := s.load(); err != nil {
        log.Printf("starting with empty channel store: %v", err)
    }

    mux := http.NewServeMux()
    mux.HandleFunc("GET /healthz", s.health)
    mux.HandleFunc("POST /v1/channels", s.createChannel)
    mux.HandleFunc("POST /v1/channels/{id}/claim", s.claimChannel)
    mux.HandleFunc("POST /v1/channels/{id}/revoke", s.revokeChannel)
    mux.HandleFunc("GET /v1/ws", s.websocket)

    httpServer := &http.Server{
        Addr:              addr,
        Handler:           securityHeaders(mux),
        ReadHeaderTimeout: 10 * time.Second,
        IdleTimeout:       90 * time.Second,
    }
    log.Printf("Monica Key blind relay listening on %s", addr)
    log.Fatal(httpServer.ListenAndServe())
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
    s.mu.RLock()
    channelCount := len(s.channels)
    clientCount := 0
    for _, set := range s.clients {
        clientCount += len(set)
    }
    s.mu.RUnlock()
    writeJSON(w, http.StatusOK, map[string]any{
        "ok":       true,
        "channels": channelCount,
        "clients":  clientCount,
        "time":     time.Now().UnixMilli(),
    })
}

func (s *server) createChannel(w http.ResponseWriter, r *http.Request) {
    var body struct {
        ChannelID   string `json:"channelId"`
        ClaimSecret string `json:"claimSecret"`
    }
    if err := decodeJSON(r, &body); err != nil {
        writeError(w, http.StatusBadRequest, err)
        return
    }
    if !validToken(body.ChannelID, 12, 128) || !validToken(body.ClaimSecret, 16, 256) {
        writeError(w, http.StatusBadRequest, errors.New("invalid channel or claim token"))
        return
    }

    hash := hashString(body.ClaimSecret)
    s.mu.Lock()
    existing := s.channels[body.ChannelID]
    if existing != nil {
        if existing.Revoked {
            s.mu.Unlock()
            writeError(w, http.StatusGone, errors.New("channel is permanently revoked"))
            return
        }
        if existing.Claimed || existing.ClaimHash != hash {
            s.mu.Unlock()
            writeError(w, http.StatusConflict, errors.New("channel already exists"))
            return
        }
        s.mu.Unlock()
        writeJSON(w, http.StatusOK, map[string]any{"message": "channel already ready"})
        return
    }
    s.channels[body.ChannelID] = &Channel{
        ID:        body.ChannelID,
        ClaimHash: hash,
        CreatedAt: time.Now().UnixMilli(),
    }
    err := s.persistLocked()
    s.mu.Unlock()
    if err != nil {
        writeError(w, http.StatusInternalServerError, err)
        return
    }
    writeJSON(w, http.StatusCreated, map[string]any{"message": "private channel created"})
}

func (s *server) claimChannel(w http.ResponseWriter, r *http.Request) {
    id := r.PathValue("id")
    var body struct {
        ClaimSecret    string `json:"claimSecret"`
        RevokePublicKey string `json:"revokePublicKey"`
    }
    if err := decodeJSON(r, &body); err != nil {
        writeError(w, http.StatusBadRequest, err)
        return
    }
    if !validToken(id, 12, 128) || body.RevokePublicKey == "" {
        writeError(w, http.StatusBadRequest, errors.New("invalid claim request"))
        return
    }
    if _, err := parsePublicKey(body.RevokePublicKey); err != nil {
        writeError(w, http.StatusBadRequest, fmt.Errorf("invalid revocation key: %w", err))
        return
    }

    s.mu.Lock()
    ch := s.channels[id]
    if ch == nil {
        s.mu.Unlock()
        writeError(w, http.StatusNotFound, errors.New("channel not found"))
        return
    }
    if ch.Revoked {
        s.mu.Unlock()
        writeError(w, http.StatusGone, errors.New("channel is revoked"))
        return
    }
    if ch.Claimed {
        s.mu.Unlock()
        writeError(w, http.StatusConflict, errors.New("channel already claimed"))
        return
    }
    if ch.ClaimHash != hashString(body.ClaimSecret) {
        s.mu.Unlock()
        writeError(w, http.StatusForbidden, errors.New("claim secret rejected"))
        return
    }
    ch.RevokePublicKey = body.RevokePublicKey
    ch.ClaimHash = ""
    ch.Claimed = true
    ch.ClaimedAt = time.Now().UnixMilli()
    err := s.persistLocked()
    s.mu.Unlock()
    if err != nil {
        writeError(w, http.StatusInternalServerError, err)
        return
    }

    s.broadcastControl(id, map[string]any{"kind": "claimed"})
    writeJSON(w, http.StatusOK, map[string]any{"message": "Monica now holds the revocation key"})
}

func (s *server) revokeChannel(w http.ResponseWriter, r *http.Request) {
    id := r.PathValue("id")
    var body struct {
        Timestamp int64  `json:"timestamp"`
        Signature string `json:"signature"`
    }
    if err := decodeJSON(r, &body); err != nil {
        writeError(w, http.StatusBadRequest, err)
        return
    }
    if drift := time.Since(time.UnixMilli(body.Timestamp)); drift > 10*time.Minute || drift < -10*time.Minute {
        writeError(w, http.StatusBadRequest, errors.New("revocation timestamp outside accepted window"))
        return
    }

    s.mu.Lock()
    ch := s.channels[id]
    if ch == nil {
        s.mu.Unlock()
        writeError(w, http.StatusNotFound, errors.New("channel not found"))
        return
    }
    if ch.Revoked {
        s.mu.Unlock()
        writeJSON(w, http.StatusOK, map[string]any{"message": "channel already revoked"})
        return
    }
    if !ch.Claimed || ch.RevokePublicKey == "" {
        s.mu.Unlock()
        writeError(w, http.StatusConflict, errors.New("channel has not been claimed by Monica"))
        return
    }
    publicKey := ch.RevokePublicKey
    s.mu.Unlock()

    signedText := fmt.Sprintf("%s:%d", id, body.Timestamp)
    ok, err := verify(publicKey, signedText, body.Signature)
    if err != nil || !ok {
        writeError(w, http.StatusForbidden, errors.New("revocation signature rejected"))
        return
    }

    s.mu.Lock()
    ch = s.channels[id]
    if ch == nil {
        s.mu.Unlock()
        writeError(w, http.StatusNotFound, errors.New("channel disappeared"))
        return
    }
    ch.Revoked = true
    ch.RevokedAt = time.Now().UnixMilli()
    err = s.persistLocked()
    s.mu.Unlock()
    if err != nil {
        writeError(w, http.StatusInternalServerError, err)
        return
    }

    s.broadcastControl(id, map[string]any{"kind": "revoked"})
    s.closeChannel(id)
    writeJSON(w, http.StatusOK, map[string]any{"message": "channel permanently revoked by Monica"})
}

func (s *server) websocket(w http.ResponseWriter, r *http.Request) {
    channelID := r.URL.Query().Get("channel")
    peer := r.URL.Query().Get("peer")
    if !validToken(channelID, 12, 128) || (peer != "PIPPI" && peer != "MONICA") {
        writeError(w, http.StatusBadRequest, errors.New("invalid websocket parameters"))
        return
    }

    s.mu.RLock()
    ch := s.channels[channelID]
    allowed := ch != nil && !ch.Revoked
    s.mu.RUnlock()
    if !allowed {
        writeError(w, http.StatusGone, errors.New("channel is unavailable"))
        return
    }

    conn, err := s.upgrader.Upgrade(w, r, nil)
    if err != nil {
        return
    }
    c := &client{channel: channelID, peer: peer, conn: conn, send: make(chan []byte, 128)}
    s.addClient(c)
    go c.writeLoop()
    c.readLoop(s)
}

func (c *client) readLoop(s *server) {
    defer func() {
        s.removeClient(c)
        close(c.send)
        _ = c.conn.Close()
    }()
    c.conn.SetReadLimit(256 * 1024)
    _ = c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
    c.conn.SetPongHandler(func(string) error {
        return c.conn.SetReadDeadline(time.Now().Add(70 * time.Second))
    })
    for {
        messageType, payload, err := c.conn.ReadMessage()
        if err != nil {
            return
        }
        if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
            continue
        }
        if len(payload) > 256*1024 || !looksLikeEncryptedPacket(payload) {
            continue
        }
        s.broadcastPacket(c, payload)
    }
}

func (c *client) writeLoop() {
    ticker := time.NewTicker(25 * time.Second)
    defer ticker.Stop()
    for {
        select {
        case payload, ok := <-c.send:
            if !ok {
                _ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
                return
            }
            if err := c.conn.WriteMessage(websocket.TextMessage, payload); err != nil {
                return
            }
        case <-ticker.C:
            if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
                return
            }
        }
    }
}

func (s *server) addClient(c *client) {
    s.mu.Lock()
    if s.clients[c.channel] == nil {
        s.clients[c.channel] = map[*client]struct{}{}
    }
    s.clients[c.channel][c] = struct{}{}
    s.mu.Unlock()
}

func (s *server) removeClient(c *client) {
    s.mu.Lock()
    if set := s.clients[c.channel]; set != nil {
        delete(set, c)
        if len(set) == 0 {
            delete(s.clients, c.channel)
        }
    }
    s.mu.Unlock()
}

func (s *server) broadcastPacket(sender *client, payload []byte) {
    s.mu.RLock()
    targets := make([]*client, 0, len(s.clients[sender.channel]))
    for c := range s.clients[sender.channel] {
        if c != sender && c.peer != sender.peer {
            targets = append(targets, c)
        }
    }
    s.mu.RUnlock()
    for _, c := range targets {
        select {
        case c.send <- append([]byte(nil), payload...):
        default:
        }
    }
}

func (s *server) broadcastControl(channelID string, payload any) {
    encoded, _ := json.Marshal(payload)
    s.mu.RLock()
    targets := make([]*client, 0, len(s.clients[channelID]))
    for c := range s.clients[channelID] {
        targets = append(targets, c)
    }
    s.mu.RUnlock()
    for _, c := range targets {
        select {
        case c.send <- encoded:
        default:
        }
    }
}

func (s *server) closeChannel(channelID string) {
    s.mu.RLock()
    targets := make([]*client, 0, len(s.clients[channelID]))
    for c := range s.clients[channelID] {
        targets = append(targets, c)
    }
    s.mu.RUnlock()
    for _, c := range targets {
        _ = c.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(4001, "revoked by Monica"), time.Now().Add(time.Second))
        _ = c.conn.Close()
    }
}

func (s *server) load() error {
    raw, err := os.ReadFile(s.dataFile)
    if errors.Is(err, os.ErrNotExist) {
        return nil
    }
    if err != nil {
        return err
    }
    var channels map[string]*Channel
    if err := json.Unmarshal(raw, &channels); err != nil {
        return err
    }
    s.channels = channels
    return nil
}

func (s *server) persistLocked() error {
    raw, err := json.MarshalIndent(s.channels, "", "  ")
    if err != nil {
        return err
    }
    tmp := s.dataFile + ".tmp"
    if err := os.WriteFile(tmp, raw, 0600); err != nil {
        return err
    }
    return os.Rename(tmp, s.dataFile)
}

func parsePublicKey(encoded string) (*ecdsa.PublicKey, error) {
    raw, err := decodeBase64URL(encoded)
    if err != nil {
        return nil, err
    }
    parsed, err := x509.ParsePKIXPublicKey(raw)
    if err != nil {
        return nil, err
    }
    key, ok := parsed.(*ecdsa.PublicKey)
    if !ok {
        return nil, errors.New("key is not ECDSA")
    }
    return key, nil
}

func verify(publicKeyEncoded, text, signatureEncoded string) (bool, error) {
    key, err := parsePublicKey(publicKeyEncoded)
    if err != nil {
        return false, err
    }
    signature, err := decodeBase64URL(signatureEncoded)
    if err != nil {
        return false, err
    }
    sum := sha256.Sum256([]byte(text))
    return ecdsa.VerifyASN1(key, sum[:], signature), nil
}

func looksLikeEncryptedPacket(payload []byte) bool {
    var envelope struct {
        Kind    string `json:"kind"`
        Payload string `json:"payload"`
    }
    if json.Unmarshal(payload, &envelope) != nil {
        return false
    }
    if envelope.Kind != "packet" || len(envelope.Payload) < 40 || len(envelope.Payload) > 350000 {
        return false
    }
    _, err := decodeBase64URL(envelope.Payload)
    return err == nil
}

func validToken(value string, min, max int) bool {
    if len(value) < min || len(value) > max {
        return false
    }
    for _, r := range value {
        if !(r >= 'a' && r <= 'z') && !(r >= 'A' && r <= 'Z') && !(r >= '0' && r <= '9') && r != '-' && r != '_' {
            return false
        }
    }
    return true
}

func hashString(value string) string {
    sum := sha256.Sum256([]byte(value))
    return hex.EncodeToString(sum[:])
}

func decodeBase64URL(value string) ([]byte, error) {
    value = strings.TrimSpace(value)
    if raw, err := base64.RawURLEncoding.DecodeString(value); err == nil {
        return raw, nil
    }
    return base64.URLEncoding.DecodeString(value)
}

func decodeJSON(r *http.Request, target any) error {
    defer r.Body.Close()
    decoder := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20))
    decoder.DisallowUnknownFields()
    return decoder.Decode(target)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
    w.Header().Set("Content-Type", "application/json; charset=utf-8")
    w.WriteHeader(status)
    _ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, err error) {
    writeJSON(w, status, map[string]any{"message": err.Error()})
}

func securityHeaders(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("X-Content-Type-Options", "nosniff")
        w.Header().Set("Referrer-Policy", "no-referrer")
        w.Header().Set("Cache-Control", "no-store")
        next.ServeHTTP(w, r)
    })
}

func getenv(name, fallback string) string {
    if value := os.Getenv(name); value != "" {
        return value
    }
    return fallback
}
