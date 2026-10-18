/* Real generated SDK request construction, no network or browser globals. */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { File } = require('node:buffer');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { Configuration } = require('../src/api/generated/configuration.ts');
const { AIHYBMediaApi } = require('../src/api/generated/api/aihybmedia-api.ts');
const fixtures = path.resolve(__dirname, '../../../media-fixtures');
(async () => {
  const checks = [];
  for (const [filename, mime] of [['poc-image.png', 'image/png'], ['poc-video.mp4', 'video/mp4'], ['poc-attachment.txt', 'text/plain']]) {
    const bytes = fs.readFileSync(path.join(fixtures, filename));
    const file = new File([bytes], filename, { type: mime });
    let sent;
    const axios = { defaults: { baseURL: '' }, request: async options => { sent = options; return { status: 200, data: {} }; } };
    const api = new AIHYBMediaApi(new Configuration(), '', axios);
    await api.uploadEntryAsset({ id: 'poc-client-test', filename, body: file }, { headers: { 'Content-Type': mime } });
    assert.equal(sent.headers['Content-Type'], mime);
    assert.equal(sent.data, file, 'Generated SDK must preserve the File object, never stringify it');
    const actual = Buffer.from(await sent.data.arrayBuffer());
    assert.deepEqual(actual, bytes);
    checks.push({ filename, bytes: actual.length, sha256: crypto.createHash('sha256').update(actual).digest('hex'), exact_original_bytes: true });
  }
  const evidence = path.resolve(__dirname, '../../../evidence/media-client-verification.json');
  fs.writeFileSync(evidence, JSON.stringify({ timestamp_utc: new Date().toISOString(), checks, complete: true }, null, 2));
  process.stdout.write('Generated SDK preserves original PNG / MP4 / TXT bytes and actual MIME; no network used\n');
})().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
