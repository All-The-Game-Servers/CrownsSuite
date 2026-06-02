// Package routing is Central's side of the relay sync protocol.
//
// The Publisher holds the list of live relay subscribers. When the API layer
// writes a routing event (on instance create/delete/hostname change), it calls
// Publish(event), which fans out to every live relay WS connection via a
// buffered per-subscriber channel. Relays that can't keep up (channel full)
// are disconnected; they'll reconnect and catch up via the snapshot/delta
// replay path.
//
// Design note: this is intentionally simple. No durable message broker. Central
// relies on the routing_events table as the durable record; live fan-out is
// just an optimization so relays don't poll. If Central restarts, all relays
// reconnect and replay from their known_version — correctness is preserved.
package routing

import (
	"log/slog"
	"sync"

	"github.com/xkstudios/atgs/central/internal/store"
)

type Publisher struct {
	log *slog.Logger

	mu   sync.Mutex
	subs map[uint64]*subscriber
	next uint64
}

type subscriber struct {
	id uint64
	ch chan store.RoutingEvent
}

func NewPublisher(log *slog.Logger) *Publisher {
	return &Publisher{
		log:  log,
		subs: make(map[uint64]*subscriber),
	}
}

// Subscribe registers a listener. The returned channel receives events in
// publish order; if the channel buffer fills, the subscriber is dropped
// (Unsubscribe is called automatically).
//
// Buffer size of 256 is generous. A busy Central does maybe 10 routing events
// per minute under normal ops; 256 covers many minutes of lag before a relay
// is considered too slow to keep up.
func (p *Publisher) Subscribe() (id uint64, ch <-chan store.RoutingEvent) {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := &subscriber{
		id: p.next,
		ch: make(chan store.RoutingEvent, 256),
	}
	p.next++
	p.subs[s.id] = s
	return s.id, s.ch
}

// Unsubscribe removes a listener and closes its channel.
func (p *Publisher) Unsubscribe(id uint64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if s, ok := p.subs[id]; ok {
		delete(p.subs, id)
		close(s.ch)
	}
}

// Publish fans an event out to every live subscriber. Non-blocking per
// subscriber; slow subscribers are dropped.
func (p *Publisher) Publish(event store.RoutingEvent) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for id, s := range p.subs {
		select {
		case s.ch <- event:
			// queued
		default:
			// subscriber can't keep up; drop it
			p.log.Warn("routing subscriber too slow, dropping", "sub_id", id)
			delete(p.subs, id)
			close(s.ch)
		}
	}
}

// SubscriberCount reports how many relays are currently connected. Useful for
// liveness dashboards.
func (p *Publisher) SubscriberCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.subs)
}
