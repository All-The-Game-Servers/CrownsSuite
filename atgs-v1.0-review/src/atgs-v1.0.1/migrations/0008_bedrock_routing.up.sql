-- Phase 8: protocol-aware routing and Bedrock public-port support.
--
-- Java keeps using hostname routing. Bedrock gets a relay-public UDP port.

ALTER TABLE instances
    ADD COLUMN public_port INT;

CREATE UNIQUE INDEX idx_instances_public_port_unique
    ON instances (public_port)
    WHERE public_port IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE routing_events
    ADD COLUMN route_kind TEXT NOT NULL DEFAULT 'java_hostname',
    ADD COLUMN public_port INT,
    ADD COLUMN protocol TEXT NOT NULL DEFAULT 'tcp';

CREATE INDEX idx_routing_events_route_kind
    ON routing_events (route_kind, version DESC);

CREATE INDEX idx_routing_events_public_port
    ON routing_events (public_port, version DESC)
    WHERE public_port IS NOT NULL;
