FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/monica-key-relay .

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/monica-key-relay /app/monica-key-relay
VOLUME ["/data"]
ENV MONICA_KEY_ADDR=:4242
ENV MONICA_KEY_DATA=/data
EXPOSE 4242
USER nonroot:nonroot
ENTRYPOINT ["/app/monica-key-relay"]
