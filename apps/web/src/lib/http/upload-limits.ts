/**
 * The largest upload the workspace BFF passes on: the API's own 10 MB request limit (F04-S02). A larger request
 * is refused 413 `request_rejected` before its form is read.
 */
export const UPLOAD_LIMIT_BYTES = 10 * 1024 * 1024;

/**
 * How much of a request body Next.js buffers when the proxy runs (`experimental.proxyClientMaxBodySize`).
 * Next.js keeps only the first bytes up to this limit and passes the request on silently truncated, so it is
 * set above {@link UPLOAD_LIMIT_BYTES}: a body over the upload limit then still arrives longer than the limit,
 * and the BFF's byte count refuses it instead of reading a cut-off form.
 */
export const PROXY_BODY_LIMIT_BYTES = UPLOAD_LIMIT_BYTES + 64 * 1024;
