const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const source = fs.readFileSync(path.join(__dirname, 'Kexin-Rooms.js'), 'utf8')
  .replace(/if \(typeof UITable !== "undefined"[\s\S]*$/, '');
const Timer = { schedule(milliseconds, _repeats, callback) {
  const handle = setTimeout(callback, milliseconds);
  return { invalidate() { clearTimeout(handle); } };
} };
const context = vm.createContext({ TextEncoder, Date, Intl, crypto: crypto.webcrypto, atob, btoa, Timer });
vm.runInContext(source + '\nglobalThis.__test = { allowed, configFromSchool, queryForm, parsePage, dateParts, SchoolClient, withTimeout };', context);
const { allowed, configFromSchool, queryForm, parsePage, dateParts, SchoolClient, withTimeout } = context.__test;
const fixture = name => JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'test', 'resources', name), 'utf8'));

assert.equal(allowed('https://jwglxxfwpt.hebeu.edu.cn/cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155&doType=query', 'POST'), true);
assert.equal(allowed('https://evil.example/cdjy/cdjy_cxKxcdlb.html?doType=query', 'POST'), false);
assert.equal(allowed('http://jwglxxfwpt.hebeu.edu.cn/xtgl/login_slogin.html', 'GET'), false);
assert.throws(() => dateParts('2026-02-30'));

const html = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'config.html'), 'utf8');
const page = {
  year: /id="xnm"[^>]*value="(\d+)"/.exec(html)?.[1] || '2026',
  term: '3', campus: '东校区(科信学院)', label: '2026-2027-1',
  types: [{ id: '', label: '全部场地类别' }]
};
const config = configFromSchool(page, fixture('metadata.json'), fixture('term.json'));
assert.equal(config.year, '2026');
assert.equal(config.buildings[0].id, '');
assert.ok(config.weeks.size > 0);
const input = { date: config.start, from: config.periods[0].number, to: config.periods[0].number, building: '', type: '' };
const form = Object.fromEntries(queryForm(config, input, 1));
assert.equal(form.xqh_id, '106');
assert.equal(form.xnm, '2026');
assert.equal(form.xqm, '3');
assert.equal(form.zcd, (1n << BigInt(config.firstWeek - 1)).toString());
assert.equal(form.jcd, (1n << BigInt(input.from - 1)).toString());
assert.throws(() => queryForm(config, { ...input, date: '2025-01-01' }, 1));
const rawPage = fixture('page.json');
const parsed = parsePage(rawPage, Number(rawPage.currentPage));
assert.equal(parsed.total, 108);
assert.ok(parsed.rooms.length > 0);
assert.ok(parsed.rooms.every(room => room.id && room.name));
assert.throws(() => parsePage({ ...rawPage, items: [{ ...rawPage.items[0], xqh_id: '108' }] }, Number(rawPage.currentPage)));

async function testRSA() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicExponent: 0x10001 });
  const jwk = publicKey.export({ format: 'jwk' });
  const toBase64 = value => Buffer.from(value, 'base64url').toString('base64');
  const client = new SchoolClient();
  client.parser = { evaluateJavaScript: async code => vm.runInContext(code, context) };
  const ciphertext = await client.encrypt('temporary-test-password', toBase64(jwk.n), toBase64(jwk.e));
  const plaintext = crypto.privateDecrypt({ key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING }, Buffer.from(ciphertext, 'base64')).toString();
  assert.equal(plaintext, 'temporary-test-password');
}

async function testTimeout() {
  assert.equal(await withTimeout(Promise.resolve('ok'), '快速请求', 100), 'ok');
  await assert.rejects(withTimeout(new Promise(() => {}), '登录接口', 5), /登录接口 超时/);
}

Promise.all([testRSA(), testTimeout()]).then(() => console.log('Scriptable protocol, RSA, and timeout checks passed')).catch(error => { console.error(error); process.exitCode = 1; });
