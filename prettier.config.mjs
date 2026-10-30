// One format for the web app and the packages (docs/engineering/web.md, "Clean code"). The Tailwind
// plugin sorts utility classes in class names and in cn() and cva() calls, against the app's theme.
const config = {
  printWidth: 120,
  singleQuote: true,
  plugins: ['prettier-plugin-tailwindcss'],
  tailwindStylesheet: './apps/web/src/app/globals.css',
  tailwindFunctions: ['cn', 'cva'],
};

export default config;
