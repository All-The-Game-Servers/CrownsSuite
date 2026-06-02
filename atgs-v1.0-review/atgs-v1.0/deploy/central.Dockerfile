# ATGS Central — Docker image.
#
# Two-stage build. Final image ~20 MB.
#
# Runtime:
#   docker run -d --name atgs-central \
#       -p 8080:8080 -p 8443:8443 \
#       -v atgs-central-data:/data \
#       -e ATGS_CENTRAL_DATABASE_URL=... \
#       xkstudios/atgs-central:latest

FROM golang:1.25-alpine AS build

RUN apk add --no-cache build-base
WORKDIR /src
COPY . .

RUN cd central/cmd/central && \
    CGO_ENABLED=0 go build \
        -trimpath \
        -ldflags "-s -w" \
        -o /out/central .

# Copy migrations into the build artifact
RUN cp -r /src/migrations /out/migrations

FROM alpine:3.19

RUN apk add --no-cache ca-certificates && \
    adduser -D -u 10000 atgs

WORKDIR /app
COPY --from=build /out/central /app/central
COPY --from=build /out/migrations /app/migrations

# Directory for CA, signing keys, backups.
RUN mkdir -p /data && chown atgs:atgs /data

USER atgs

VOLUME ["/data"]
EXPOSE 8080 8443

ENTRYPOINT ["/app/central"]
CMD ["serve"]
