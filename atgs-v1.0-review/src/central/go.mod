module github.com/xkstudios/atgs/central

go 1.25.0

require (
	github.com/coder/websocket v1.8.12
	github.com/golang-migrate/migrate/v4 v4.17.1
	github.com/google/uuid v1.6.0
	github.com/jackc/pgx/v5 v5.5.5
	github.com/robfig/cron/v3 v3.0.1
	github.com/xkstudios/atgs/shared v0.0.0
	golang.org/x/crypto v0.20.0
	golang.org/x/time v0.3.0
)

require (
	github.com/hashicorp/errwrap v1.1.0 // indirect
	github.com/hashicorp/go-multierror v1.1.1 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20221227161230-091c0ba34f0a // indirect
	github.com/jackc/puddle/v2 v2.2.1 // indirect
	github.com/lib/pq v1.10.9 // indirect
	go.uber.org/atomic v1.7.0 // indirect
	golang.org/x/sync v0.5.0 // indirect
	golang.org/x/sys v0.17.0 // indirect
	golang.org/x/text v0.14.0 // indirect
)

replace github.com/xkstudios/atgs/shared => ../shared
