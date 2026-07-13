package main

import (
    "crypto/ecdsa"
    "crypto/elliptic"
    "crypto/rand"
    "crypto/sha256"
    "crypto/x509"
    "encoding/base64"
    "testing"
)

func TestRevocationSignature(t *testing.T) {
    privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
    if err != nil {
        t.Fatal(err)
    }
    publicDER, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
    if err != nil {
        t.Fatal(err)
    }
    publicEncoded := base64.RawURLEncoding.EncodeToString(publicDER)
    text := "channel_123456789:1700000000000"
    digest := sha256.Sum256([]byte(text))
    signature, err := ecdsa.SignASN1(rand.Reader, privateKey, digest[:])
    if err != nil {
        t.Fatal(err)
    }
    signatureEncoded := base64.RawURLEncoding.EncodeToString(signature)

    ok, err := verify(publicEncoded, text, signatureEncoded)
    if err != nil {
        t.Fatal(err)
    }
    if !ok {
        t.Fatal("Monica's signature was rejected")
    }

    ok, err = verify(publicEncoded, text+"tampered", signatureEncoded)
    if err != nil {
        t.Fatal(err)
    }
    if ok {
        t.Fatal("tampered revocation was accepted")
    }
}

func TestEncryptedEnvelopeShape(t *testing.T) {
    valid := []byte(`{"kind":"packet","payload":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}`)
    if !looksLikeEncryptedPacket(valid) {
        t.Fatal("valid encrypted envelope was rejected")
    }
    if looksLikeEncryptedPacket([]byte(`{"kind":"chat","payload":"plaintext"}`)) {
        t.Fatal("plaintext-shaped packet was accepted")
    }
}

func TestTokenValidation(t *testing.T) {
    if !validToken("abc_DEF-123456", 12, 128) {
        t.Fatal("valid URL-safe token was rejected")
    }
    if validToken("bad token with spaces", 12, 128) {
        t.Fatal("invalid token was accepted")
    }
}
