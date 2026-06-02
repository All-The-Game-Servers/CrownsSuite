# ATGS Keeper — headless Docker image.
#
# Two-stage build: build in a Go image, ship from Alpine. Final image is
# ~20 MB.
#
# Runtime requirements:
#   - Docker socket mounted at /var/run/docker.sock (the keeper spawns
#     game server containers on its own host)
#   - /state volume for identity + local sqlite
#   - /eggs volume or bind mount for egg manifests
#
# Usage:
#   docker run -d --name atgs-keeper \
#       -v /var/run/docker.sock:/var/run/docker.sock \
#       -v atgs-keeper-state:/state \
#       -v /path/to/eggs:/eggs:ro \
#       -e ATGS_KEEPER_CENTRAL_URL=https://central.example.com:8443 \
#       -e ATGS_ENROLL_TOKEN=... \
#       -e ATGS_KEEPER_STATE_DIR=/state \
#       -e ATGS_KEEPER_EGGS_DIR=/eggs \
#       -e ATGS_KEEPER_DATA_ROOT=/state/instances \
#       xkstudios/atgs-keeper:latest

FROM golang:1.25-alpine AS build

RUN apk add --no-cache build-base
WORKDIR /src
COPY . .

# Build with the keeper_headless tag so no GUI code is compiled in.
# -trimpath + -s -w gives a stripped binary; CGO stays on because
# modernc.org/sqlite is pure-Go but the Docker client has some cgo
# stubs.
RUN cd keeper/cmd/keeper && \
    go build \
        -tags keeper_headless \
        -trimpath \
        -ldflags "-s -w -X main.version=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)" \
        -o /out/keeper .

FROM alpine:3.20

RUN apk add --no-cache ca-certificates tzdata && \
    addgroup -S atgs && adduser -S -G atgs atgs

COPY --from=build /out/keeper /usr/local/bin/keeper

# The keeper needs to talk to the Docker socket. In the host network model
# we expect the operator to mount /var/run/docker.sock and run the keeper
# as root (or with matching docker group id). We DO NOT drop to the atgs
# user by default; that's the operator's call based on their docker group.

ENV ATGS_KEEPER_HEADLESS=true \
    ATGS_KEEPER_STATE_DIR=/state \
    ATGS_KEEPER_EGGS_DIR=/eggs \
    ATGS_KEEPER_DATA_ROOT=/state/instances

VOLUME ["/state", "/eggs"]

ENTRYPOINT ["/usr/local/bin/keeper"]
CMD ["--headless"]
