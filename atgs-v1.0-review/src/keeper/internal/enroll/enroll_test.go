package enroll

import "testing"

func TestEnrollmentURLTrimsTrailingSlash(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		centralURL string
		want       string
	}{
		{
			name:       "without trailing slash",
			centralURL: "https://x:8443",
			want:       "https://x:8443/api/v1/enroll",
		},
		{
			name:       "with trailing slash",
			centralURL: "https://x:8443/",
			want:       "https://x:8443/api/v1/enroll",
		},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := enrollmentURL(tc.centralURL); got != tc.want {
				t.Fatalf("enrollmentURL(%q) = %q, want %q", tc.centralURL, got, tc.want)
			}
		})
	}
}
