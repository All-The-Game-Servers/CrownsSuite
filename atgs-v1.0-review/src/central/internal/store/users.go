package store

import (
	"context"
	"errors"
	"net"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// User represents a human-authenticated account.
type User struct {
	ID               uuid.UUID  `json:"id"`
	Email            string     `json:"email"`
	PasswordHash     string     `json:"-"` // never serialize
	Role             string     `json:"role"`
	CreatedAt        time.Time  `json:"created_at"`
	LastLoginAt      *time.Time `json:"last_login_at,omitempty"`
	StripeCustomerID *string    `json:"stripe_customer_id,omitempty"`
	DisabledAt       *time.Time `json:"disabled_at,omitempty"`
}

// Session is a server-side session record for a logged-in user.
type Session struct {
	TokenHash  string
	UserID     uuid.UUID
	CreatedAt  time.Time
	ExpiresAt  time.Time
	LastSeenAt time.Time
	ClientIP   *net.IP
	UserAgent  string
}

// CreateUser inserts a new user and returns its ID. Errors if the email
// is already taken.
func (s *Store) CreateUser(ctx context.Context, email, passwordHash, role string) (uuid.UUID, error) {
	id := uuid.New()
	_, err := s.pool.Exec(ctx, `
		INSERT INTO users (user_id, email, password_hash, role)
		VALUES ($1, $2, $3, $4)
	`, id, email, passwordHash, role)
	return id, err
}

// GetUserByEmail fetches a non-disabled user by email. Returns ErrNotFound
// for missing or disabled users — callers MUST NOT distinguish to avoid
// user enumeration via timing.
func (s *Store) GetUserByEmail(ctx context.Context, email string) (*User, error) {
	var u User
	err := s.pool.QueryRow(ctx, `
		SELECT user_id, email, password_hash, role::text, created_at,
		       last_login_at, stripe_customer_id, disabled_at
		FROM users
		WHERE email = $1 AND disabled_at IS NULL
	`, email).Scan(
		&u.ID, &u.Email, &u.PasswordHash, &u.Role, &u.CreatedAt,
		&u.LastLoginAt, &u.StripeCustomerID, &u.DisabledAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &u, err
}

// GetUserByID fetches a user regardless of disabled status. Used by the
// session middleware after it resolves a session to a user_id.
func (s *Store) GetUserByID(ctx context.Context, id uuid.UUID) (*User, error) {
	var u User
	err := s.pool.QueryRow(ctx, `
		SELECT user_id, email, password_hash, role::text, created_at,
		       last_login_at, stripe_customer_id, disabled_at
		FROM users
		WHERE user_id = $1
	`, id).Scan(
		&u.ID, &u.Email, &u.PasswordHash, &u.Role, &u.CreatedAt,
		&u.LastLoginAt, &u.StripeCustomerID, &u.DisabledAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &u, err
}

// ListUsers returns all users, newest first. Admin-only endpoint consumer.
func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, email, '' AS password_hash, role::text, created_at,
		       last_login_at, stripe_customer_id, disabled_at
		FROM users
		ORDER BY created_at DESC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		if err := rows.Scan(
			&u.ID, &u.Email, &u.PasswordHash, &u.Role, &u.CreatedAt,
			&u.LastLoginAt, &u.StripeCustomerID, &u.DisabledAt,
		); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// TouchUserLogin bumps last_login_at.
func (s *Store) TouchUserLogin(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE users SET last_login_at = NOW() WHERE user_id = $1`, id)
	return err
}

// SetUserStripeCustomerID links a user to a Stripe customer. Called by the
// billing subsystem on first subscription.
func (s *Store) SetUserStripeCustomerID(ctx context.Context, userID uuid.UUID, customerID string) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE users SET stripe_customer_id = $1 WHERE user_id = $2`,
		customerID, userID)
	return err
}

// DisableUser soft-disables a user (disabled_at = now). Sessions for that
// user are revoked as a side effect.
func (s *Store) DisableUser(ctx context.Context, id uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx,
		`UPDATE users SET disabled_at = NOW() WHERE user_id = $1`, id); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`DELETE FROM user_sessions WHERE user_id = $1`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// --- Sessions ---

// CreateSession inserts a new session record.
func (s *Store) CreateUserSession(ctx context.Context, tokenHash string, userID uuid.UUID, ttl time.Duration, clientIP, userAgent string) error {
	var ip any
	if clientIP != "" {
		if parsed := net.ParseIP(clientIP); parsed != nil {
			ip = parsed.String()
		}
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO user_sessions (token_hash, user_id, expires_at, client_ip, user_agent)
		VALUES ($1, $2, $3, $4, $5)
	`, tokenHash, userID, time.Now().Add(ttl), ip, userAgent)
	return err
}

// GetSession looks up a session by token hash. Returns ErrNotFound if missing
// or expired (expired sessions are treated as gone; cleanup sweeps them later).
func (s *Store) GetUserSession(ctx context.Context, tokenHash string) (*Session, error) {
	var sess Session
	var ip *string
	err := s.pool.QueryRow(ctx, `
		SELECT token_hash, user_id, created_at, expires_at, last_seen_at,
		       host(client_ip) AS client_ip, COALESCE(user_agent, '')
		FROM user_sessions
		WHERE token_hash = $1 AND expires_at > NOW()
	`, tokenHash).Scan(
		&sess.TokenHash, &sess.UserID, &sess.CreatedAt, &sess.ExpiresAt,
		&sess.LastSeenAt, &ip, &sess.UserAgent,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if ip != nil {
		parsed := net.ParseIP(*ip)
		if parsed != nil {
			sess.ClientIP = &parsed
		}
	}
	return &sess, nil
}

// TouchSession updates last_seen_at and slides the expiry forward.
func (s *Store) TouchUserSession(ctx context.Context, tokenHash string, ttl time.Duration) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE user_sessions
		SET last_seen_at = NOW(), expires_at = $1
		WHERE token_hash = $2
	`, time.Now().Add(ttl), tokenHash)
	return err
}

// DeleteSession revokes a single session (logout).
func (s *Store) DeleteUserSession(ctx context.Context, tokenHash string) error {
	_, err := s.pool.Exec(ctx,
		`DELETE FROM user_sessions WHERE token_hash = $1`, tokenHash)
	return err
}

// PurgeExpiredSessions drops sessions past their expires_at. Called
// periodically by the background cleanup loop.
func (s *Store) PurgeExpiredSessions(ctx context.Context) (int64, error) {
	res, err := s.pool.Exec(ctx,
		`DELETE FROM user_sessions WHERE expires_at < NOW()`)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected(), nil
}
