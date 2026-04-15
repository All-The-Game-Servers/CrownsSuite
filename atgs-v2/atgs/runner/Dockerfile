# ==============================================================
#  ATGS Runner — Base container for all instances
#  A product of XKStudios
#
#  This image is game-agnostic. The panel writes a start.sh
#  into the instance volume. The runner just executes it.
# ==============================================================
FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl jq tar gzip unzip procps gosu && \
    rm -rf /var/lib/apt/lists/*

RUN groupadd -g 1000 runner && \
    useradd -u 1000 -g runner -m -s /bin/bash runner

RUN mkdir -p /instance && chown runner:runner /instance
WORKDIR /instance

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
