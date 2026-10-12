// `import source from './x.css?raw'`: a style sheet's source as a string (next.config.ts, turbopack.rules).
declare module '*.css?raw' {
  const source: string;
  export default source;
}
