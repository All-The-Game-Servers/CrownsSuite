package main

import "encoding/json"

func jsonMarshal(v any) ([]byte, error) {
	return json.MarshalIndent(v, "", "  ")
}

func jsonUnmarshal(data []byte, v any) error {
	return json.Unmarshal(data, v)
}
