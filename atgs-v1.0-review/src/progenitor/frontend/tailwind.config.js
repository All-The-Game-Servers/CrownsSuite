/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        obsidian: '#08090b',
        carbon:   '#101114',
        iron:     '#191a1f',
        graphite: '#2a2c33',
        bone:     '#e8e4d8',
        parchment:'#c9c3b3',
        pewter:   '#7a7668',
        gold:     '#c8a960',
        copper:   '#b87348',
        rust:     '#c03a4b',
        sage:     '#7a9970',
      },
      fontFamily: {
        display: ['"Cormorant Garamond"', 'Garamond', 'Georgia', 'serif'],
        body: ['"Instrument Sans"', '"Helvetica Neue"', 'sans-serif'],
        mono: ['"Space Mono"', '"JetBrains Mono"', 'Consolas', 'monospace'],
      },
      letterSpacing: {
        'caps': '0.15em',
        'number': '0.05em',
      },
    },
  },
  plugins: [],
}
