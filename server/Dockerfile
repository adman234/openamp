FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /openamp-files .

FROM alpine:3.21
RUN apk add --no-cache ffmpeg
COPY --from=build /openamp-files /usr/local/bin/openamp-files
# Unraid's nobody:users, so the cache folder in appdata is writable.
USER 99:100
EXPOSE 8080
VOLUME /cache
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["openamp-files"]
