// Tailwind CSS 4 runs as a PostCSS plugin; its theme is configured in src/app/globals.css, so
// there is no tailwind.config file (docs/engineering/web.md, "Styling").
const config = {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};

export default config;
