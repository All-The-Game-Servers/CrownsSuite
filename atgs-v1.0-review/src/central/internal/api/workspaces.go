package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/store"
)

type workspaceView struct {
	ID                       string  `json:"id"`
	Slug                     string  `json:"slug"`
	DisplayName              string  `json:"display_name"`
	OwnerUserID              *string `json:"owner_user_id,omitempty"`
	MockPlanKey              string  `json:"mock_plan_key"`
	MockSubscriptionStatus   string  `json:"mock_subscription_status"`
	MockSubscriptionSeatLimit int    `json:"mock_subscription_seat_limit"`
}

type workspaceMembershipView struct {
	WorkspaceID  string   `json:"workspace_id"`
	UserID       string   `json:"user_id"`
	UserEmail    string   `json:"user_email"`
	Role         string   `json:"role"`
	Capabilities []string `json:"capabilities"`
}

type workspaceSessionResp struct {
	User       loginResp                  `json:"user"`
	Workspaces []workspaceView            `json:"workspaces"`
	Memberships []workspaceMembershipView `json:"memberships"`
}

type createWorkspaceReq struct {
	Slug                    string `json:"slug"`
	DisplayName             string `json:"display_name"`
	OwnerUserID             string `json:"owner_user_id,omitempty"`
	MockPlanKey             string `json:"mock_plan_key,omitempty"`
	MockSubscriptionStatus  string `json:"mock_subscription_status,omitempty"`
	MockSubscriptionSeatLimit int  `json:"mock_subscription_seat_limit,omitempty"`
}

type assignKeeperWorkspaceReq struct {
	WorkspaceID string `json:"workspace_id"`
}

type upsertWorkspaceMemberReq struct {
	UserID       string   `json:"user_id"`
	Role         string   `json:"role"`
	Capabilities []string `json:"capabilities"`
}

func (s *Server) handleClientSession(w http.ResponseWriter, r *http.Request) {
	user, _ := UserFromContext(r.Context())
	workspaces, memberships, err := s.visibleWorkspacesAndMemberships(r, user)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, workspaceSessionResp{
		User: loginResp{
			UserID: user.ID.String(),
			Email:  user.Email,
			Role:   user.Role,
		},
		Workspaces: workspaces,
		Memberships: memberships,
	})
}

func (s *Server) handleListWorkspaces(w http.ResponseWriter, r *http.Request) {
	workspaces, err := s.Store.ListWorkspaces(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	out := make([]workspaceView, 0, len(workspaces))
	for _, ws := range workspaces {
		out = append(out, toWorkspaceView(ws))
	}
	writeJSON(w, http.StatusOK, map[string]any{"workspaces": out})
}

func (s *Server) handleCreateWorkspace(w http.ResponseWriter, r *http.Request) {
	var req createWorkspaceReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	req.Slug = strings.TrimSpace(strings.ToLower(req.Slug))
	req.DisplayName = strings.TrimSpace(req.DisplayName)
	if req.Slug == "" || req.DisplayName == "" {
		writeError(w, http.StatusBadRequest, "invalid", "slug and display_name are required")
		return
	}
	var ownerUserID *uuid.UUID
	if strings.TrimSpace(req.OwnerUserID) != "" {
		id, err := uuid.Parse(strings.TrimSpace(req.OwnerUserID))
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_owner_user_id", err.Error())
			return
		}
		ownerUserID = &id
	}
	id, err := s.Store.CreateWorkspace(r.Context(), store.CreateWorkspaceParams{
		Slug:                      req.Slug,
		DisplayName:               req.DisplayName,
		OwnerUserID:               ownerUserID,
		MockPlanKey:               req.MockPlanKey,
		MockSubscriptionStatus:    req.MockSubscriptionStatus,
		MockSubscriptionSeatLimit: req.MockSubscriptionSeatLimit,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, "create_workspace", err.Error())
		return
	}
	ws, err := s.Store.GetWorkspace(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, toWorkspaceView(*ws))
}

func (s *Server) handleAssignKeeperWorkspace(w http.ResponseWriter, r *http.Request) {
	keeperID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_keeper_id", err.Error())
		return
	}
	var req assignKeeperWorkspaceReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	workspaceID, err := uuid.Parse(req.WorkspaceID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if err := s.Store.AssignKeeperToWorkspace(r.Context(), keeperID, workspaceID); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleListWorkspaceMembers(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityMembers) {
		return
	}
	members, err := s.Store.ListWorkspaceMemberships(r.Context(), workspaceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	out := make([]workspaceMembershipView, 0, len(members))
	for _, m := range members {
		out = append(out, toWorkspaceMembershipView(m))
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": out})
}

func (s *Server) handleUpsertWorkspaceMember(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityMembers) {
		return
	}
	var req upsertWorkspaceMemberReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	userID, err := uuid.Parse(req.UserID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_user_id", err.Error())
		return
	}
	role := strings.TrimSpace(strings.ToLower(req.Role))
	if role == "" {
		role = store.WorkspaceRoleMember
	}
	switch role {
	case store.WorkspaceRoleOwner:
		req.Capabilities = store.FullWorkspaceCapabilities
	case store.WorkspaceRoleMember:
		req.Capabilities = store.NormalizeCapabilities(req.Capabilities)
	default:
		writeError(w, http.StatusBadRequest, "invalid_role", "role must be owner or member")
		return
	}
	if err := s.Store.UpsertWorkspaceMembership(r.Context(), store.UpsertWorkspaceMembershipParams{
		WorkspaceID:  workspaceID,
		UserID:       userID,
		Role:         role,
		Capabilities: req.Capabilities,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	member, err := s.Store.GetWorkspaceMembership(r.Context(), workspaceID, userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, toWorkspaceMembershipView(*member))
}

func (s *Server) handleClientListWorkspaces(w http.ResponseWriter, r *http.Request) {
	user, _ := UserFromContext(r.Context())
	workspaces, memberships, err := s.visibleWorkspacesAndMemberships(r, user)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"workspaces":  workspaces,
		"memberships": memberships,
	})
}

func (s *Server) handleClientListWorkspaceKeepers(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityKeepers) {
		return
	}
	keepers, err := s.Store.ListKeepersForWorkspace(r.Context(), workspaceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	liveSet := map[uuid.UUID]bool{}
	for _, id := range s.Hub.ConnectedKeepers() {
		liveSet[id] = true
	}
	out := make([]keeperView, 0, len(keepers))
	for _, k := range keepers {
		out = append(out, keeperView{
			ID:                   k.ID.String(),
			WorkspaceID:          k.WorkspaceID.String(),
			DisplayName:          k.DisplayName,
			Platform:             k.Platform,
			Arch:                 k.Arch,
			Hostname:             k.Hostname,
			AgentVersion:         k.AgentVersion,
			CertNotAfter:         k.CertNotAfter,
			EnrolledAt:           k.EnrolledAt,
			LastSeenAt:           k.LastSeenAt,
			RevokedAt:            k.RevokedAt,
			Connected:            liveSet[k.ID],
			PublicKeyFingerprint: k.PublicKeyFingerprint,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"keepers": out})
}

func (s *Server) handleClientListWorkspaceInstances(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityKeepers) {
		return
	}
	insts, err := s.Store.ListInstancesForWorkspace(r.Context(), workspaceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"instances": toInstanceViews(insts)})
}

func (s *Server) visibleWorkspacesAndMemberships(r *http.Request, user *store.User) ([]workspaceView, []workspaceMembershipView, error) {
	var (
		workspaces []store.Workspace
		err        error
	)
	if user.Role == "admin" {
		workspaces, err = s.Store.ListWorkspaces(r.Context())
	} else {
		workspaces, err = s.Store.ListWorkspacesForUser(r.Context(), user.ID)
	}
	if err != nil {
		return nil, nil, err
	}
	wsViews := make([]workspaceView, 0, len(workspaces))
	memberViews := make([]workspaceMembershipView, 0, len(workspaces))
	for _, ws := range workspaces {
		wsViews = append(wsViews, toWorkspaceView(ws))
		if user.Role == "admin" {
			memberViews = append(memberViews, workspaceMembershipView{
				WorkspaceID:  ws.ID.String(),
				UserID:       user.ID.String(),
				UserEmail:    user.Email,
				Role:         store.WorkspaceRoleOwner,
				Capabilities: store.FullWorkspaceCapabilities,
			})
			continue
		}
		member, err := s.Store.GetWorkspaceMembership(r.Context(), ws.ID, user.ID)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return nil, nil, err
		}
		if member != nil {
			memberViews = append(memberViews, toWorkspaceMembershipView(*member))
		}
	}
	return wsViews, memberViews, nil
}

func (s *Server) ensureWorkspaceCapability(w http.ResponseWriter, r *http.Request, workspaceID uuid.UUID, capability string) bool {
	user, ok := UserFromContext(r.Context())
	if !ok {
		return true
	}
	if user.Role == "admin" {
		return true
	}
	member, err := s.Store.GetWorkspaceMembership(r.Context(), workspaceID, user.ID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusForbidden, "forbidden", "workspace membership required")
		return false
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return false
	}
	if member.Role == store.WorkspaceRoleOwner {
		return true
	}
	for _, cap := range member.Capabilities {
		if cap == capability {
			return true
		}
	}
	writeError(w, http.StatusForbidden, "forbidden", "missing workspace capability")
	return false
}

func (s *Server) ensureInstanceWorkspaceCapability(w http.ResponseWriter, r *http.Request, workspaceID, instanceID uuid.UUID, capability string) (*store.Instance, bool) {
	inst, err := s.Store.GetInstance(r.Context(), instanceID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return nil, false
	}
	if inst.WorkspaceID != workspaceID {
		writeError(w, http.StatusForbidden, "forbidden", "instance does not belong to this workspace")
		return nil, false
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, capability) {
		return nil, false
	}
	return inst, true
}

func (s *Server) ensureKeeperWorkspaceCapability(w http.ResponseWriter, r *http.Request, workspaceID, keeperID uuid.UUID, capability string) (*store.Keeper, bool) {
	keeper, err := s.Store.GetKeeper(r.Context(), keeperID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return nil, false
	}
	if keeper.WorkspaceID != workspaceID {
		writeError(w, http.StatusForbidden, "forbidden", "keeper does not belong to this workspace")
		return nil, false
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, capability) {
		return nil, false
	}
	return keeper, true
}

func toWorkspaceView(ws store.Workspace) workspaceView {
	var owner *string
	if ws.OwnerUserID != nil {
		id := ws.OwnerUserID.String()
		owner = &id
	}
	return workspaceView{
		ID:                        ws.ID.String(),
		Slug:                      ws.Slug,
		DisplayName:               ws.DisplayName,
		OwnerUserID:               owner,
		MockPlanKey:               ws.MockPlanKey,
		MockSubscriptionStatus:    ws.MockSubscriptionStatus,
		MockSubscriptionSeatLimit: ws.MockSubscriptionSeatLimit,
	}
}

func toWorkspaceMembershipView(m store.WorkspaceMembership) workspaceMembershipView {
	return workspaceMembershipView{
		WorkspaceID:  m.WorkspaceID.String(),
		UserID:       m.UserID.String(),
		UserEmail:    m.UserEmail,
		Role:         m.Role,
		Capabilities: m.Capabilities,
	}
}
