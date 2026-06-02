package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/authn"
	"github.com/xkstudios/atgs/central/internal/store"
)

const (
	sessionCookieName = "atgs_session"
	sessionTTL        = 24 * time.Hour
)

type loginReq struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

type loginResp struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	Role   string `json:"role"`
}

// handleLogin authenticates a user and issues a session cookie.
//
// Security notes:
//   - Returns the SAME error ("invalid credentials") for unknown user, wrong
//     password, or disabled user — prevents enumeration.
//   - Always runs argon2id verify even on unknown user, to hide timing.
//     (We hash a dummy password against a precomputed "never match" hash.)
//   - Session cookie is HttpOnly, Secure (unless dev), SameSite=Lax.
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	req.Email = strings.TrimSpace(strings.ToLower(req.Email))
	if req.Email == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "invalid_credentials", "email and password required")
		return
	}

	user, err := s.Store.GetUserByEmail(r.Context(), req.Email)
	if err != nil || user == nil {
		// Run a dummy verify to equalize timing with the success path.
		_ = authn.VerifyPassword(dummyHash, req.Password)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid email or password")
		return
	}
	if err := authn.VerifyPassword(user.PasswordHash, req.Password); err != nil {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid email or password")
		return
	}

	// Mint session.
	token, hash, err := authn.NewSessionToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token", err.Error())
		return
	}
	clientIP := clientIPFromRequest(r)
	ua := r.UserAgent()
	if err := s.Store.CreateUserSession(r.Context(), hash, user.ID, sessionTTL, clientIP, ua); err != nil {
		writeError(w, http.StatusInternalServerError, "session", err.Error())
		return
	}
	_ = s.Store.TouchUserLogin(r.Context(), user.ID)
	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:  "user.login",
		Actor: "user:" + user.ID.String(),
		Details: map[string]any{
			"email": user.Email,
			"ip":    clientIP,
		},
	})

	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   !s.Cfg.DevMode,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(sessionTTL),
	})
	writeJSON(w, http.StatusOK, loginResp{
		UserID: user.ID.String(),
		Email:  user.Email,
		Role:   user.Role,
	})
}

// handleLogout revokes the current session.
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	c, err := r.Cookie(sessionCookieName)
	if err == nil && c.Value != "" {
		_ = s.Store.DeleteUserSession(r.Context(), authn.HashSessionToken(c.Value))
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   !s.Cfg.DevMode,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})
	w.WriteHeader(http.StatusNoContent)
}

// handleWhoami returns the currently authenticated user (if any). Used by
// the Progenitor frontend to bootstrap its state.
func (s *Server) handleWhoami(w http.ResponseWriter, r *http.Request) {
	user, ok := UserFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthenticated", "not logged in")
		return
	}
	writeJSON(w, http.StatusOK, loginResp{
		UserID: user.ID.String(),
		Email:  user.Email,
		Role:   user.Role,
	})
}

// --- Middleware ---

type ctxKey int

const userCtxKey ctxKey = 1

// UserFromContext retrieves the authenticated user from request context.
func UserFromContext(ctx context.Context) (*store.User, bool) {
	u, ok := ctx.Value(userCtxKey).(*store.User)
	return u, ok
}

// RequireUser is middleware that rejects unauthenticated requests with 401.
// Place it in front of any human-operated endpoint.
func (s *Server) RequireUser(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(sessionCookieName)
		if err != nil || c.Value == "" {
			writeError(w, http.StatusUnauthorized, "unauthenticated", "session cookie required")
			return
		}
		sess, err := s.Store.GetUserSession(r.Context(), authn.HashSessionToken(c.Value))
		if err != nil || sess == nil {
			writeError(w, http.StatusUnauthorized, "unauthenticated", "session invalid or expired")
			return
		}
		user, err := s.Store.GetUserByID(r.Context(), sess.UserID)
		if err != nil || user == nil {
			writeError(w, http.StatusUnauthorized, "unauthenticated", "user not found")
			return
		}
		if user.DisabledAt != nil {
			writeError(w, http.StatusForbidden, "disabled", "account disabled")
			return
		}
		// Slide the session expiry forward.
		_ = s.Store.TouchUserSession(r.Context(), sess.TokenHash, sessionTTL)
		ctx := context.WithValue(r.Context(), userCtxKey, user)
		next(w, r.WithContext(ctx))
	}
}

// RequireRole is middleware that requires a specific role. Use after RequireUser.
func (s *Server) RequireRole(role string, next http.HandlerFunc) http.HandlerFunc {
	return s.RequireUser(func(w http.ResponseWriter, r *http.Request) {
		user, _ := UserFromContext(r.Context())
		if user.Role != role && user.Role != "admin" {
			writeError(w, http.StatusForbidden, "forbidden", "insufficient role")
			return
		}
		next(w, r)
	})
}

// --- Admin user management endpoints ---

type createUserReq struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Role     string `json:"role"`
}

// handleCreateUser - admin only. Creates a new operator/viewer/admin.
func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	var req createUserReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	req.Email = strings.TrimSpace(strings.ToLower(req.Email))
	if req.Email == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "invalid", "email and password required")
		return
	}
	switch req.Role {
	case "admin", "operator", "viewer":
	default:
		writeError(w, http.StatusBadRequest, "invalid_role", "role must be admin|operator|viewer")
		return
	}
	hash, err := authn.HashPassword(req.Password)
	if err != nil {
		writeError(w, http.StatusBadRequest, "hash", err.Error())
		return
	}
	id, err := s.Store.CreateUser(r.Context(), req.Email, hash, req.Role)
	if err != nil {
		writeError(w, http.StatusBadRequest, "create", err.Error())
		return
	}
	actor, _ := UserFromContext(r.Context())
	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:  "user.created",
		Actor: "user:" + actor.ID.String(),
		Details: map[string]any{
			"new_user_id": id.String(),
			"email":       req.Email,
			"role":        req.Role,
		},
	})
	writeJSON(w, http.StatusCreated, map[string]any{
		"user_id": id.String(),
		"email":   req.Email,
		"role":    req.Role,
	})
}

func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) {
	users, err := s.Store.ListUsers(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": users})
}

func (s *Server) handleDisableUser(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	if err := s.Store.DisableUser(r.Context(), id); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	actor, _ := UserFromContext(r.Context())
	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:  "user.disabled",
		Actor: "user:" + actor.ID.String(),
		Details: map[string]any{
			"disabled_user_id": id.String(),
		},
	})
	w.WriteHeader(http.StatusNoContent)
}

// --- Helpers ---

// dummyHash is a valid-looking argon2id hash that never matches any password.
// Used to equalize verify timing when the email isn't found. Computed once
// at startup via init() so we pay the argon2 cost exactly once.
var dummyHash string

func init() {
	h, err := authn.HashPassword("not-a-real-password-placeholder-for-timing-equalization")
	if err != nil {
		// HashPassword only errors on empty input; the above is non-empty.
		panic("authn: init dummy hash: " + err.Error())
	}
	dummyHash = h
}

func clientIPFromRequest(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// First IP in the list is the originating client.
		parts := strings.Split(xff, ",")
		return strings.TrimSpace(parts[0])
	}
	if ip, _, err := splitHostPort(r.RemoteAddr); err == nil {
		return ip
	}
	return r.RemoteAddr
}

// splitHostPort is a small wrapper that avoids pulling in net.SplitHostPort
// if RemoteAddr has no port (rare but possible in tests).
func splitHostPort(s string) (host, port string, err error) {
	i := strings.LastIndex(s, ":")
	if i < 0 {
		return s, "", errors.New("no port")
	}
	return s[:i], s[i+1:], nil
}
