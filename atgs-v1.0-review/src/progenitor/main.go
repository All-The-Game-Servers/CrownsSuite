package main

import (
	"embed"
	"log"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	app := NewApp()

	err := wails.Run(&options.App{
		Title:  "ATGS Progenitor",
		Width:  1280,
		Height: 800,
		MinWidth: 960,
		MinHeight: 640,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 17, G: 17, B: 20, A: 1},
		OnStartup:        app.startup,
		Bind: []any{
			app,
		},
	})
	if err != nil {
		log.Fatal(err)
	}
}
