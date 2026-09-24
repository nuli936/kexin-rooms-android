// 科信空教室 · Scriptable 版。仅向河北工程大学教务系统发送登录和空教室查询请求。
// 账号密码由使用者在本机输入，保存在 Scriptable 的 Keychain 中；脚本和 GitHub 发布包不含账号密码。
// 本脚本需要从 App Store 安装免费的 Scriptable。它不是独立 IPA，也没有内嵌网页界面。

const ORIGIN = "https://jwglxxfwpt.hebeu.edu.cn";
const CAMPUS = "106";
const ROOMS_PATH = "/cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155";
const USER_KEY = "kexin.rooms.scriptable.username";
const PASS_KEY = "kexin.rooms.scriptable.password";

function text(value) {
  return value === undefined || value === null ? "" : String(value);
}

function fail(message) {
  throw new Error(message);
}

async function withTimeout(operation, label, milliseconds) {
  let timer;
  try {
    return await Promise.race([
      operation,
      new Promise((_, reject) => {
        timer = Timer.schedule(milliseconds, false, () => reject(new Error(`${label} 超时，请检查学校网络后重试。`)));
      })
    ]);
  } finally {
    if (timer) timer.invalidate();
  }
}

function encode(form) {
  return form.map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(text(value)).replace(/%20/g, "+")}`).join("&");
}

function allowed(url, method) {
  const match = /^https:\/\/jwglxxfwpt\.hebeu\.edu\.cn(?::443)?(\/[^?#]*)(?:\?([^#]*))?$/i.exec(url);
  if (!match) return false;
  const path = match[1], query = match[2] || "";
  if (path === "/xtgl/login_slogin.html") return true;
  if (method === "GET" && ["/xtgl/login_getPublicKey.html", "/xtgl/index_initMenu.html", "/kaptcha", "/cdjy/cdjy_cxXqjc.html"].includes(path)) return true;
  if (path === "/cdjy/cdjy_cxQtlb.html") return true;
  if (path === "/cdjy/cdjy_cxKxcdlb.html") return method === "GET" || /(?:^|&)doType=query(?:&|$)/.test(query);
  return false;
}

function parseJSON(raw) {
  if (raw.trimStart().startsWith("<")) fail("学校返回了页面而非查询数据；本次不显示结果，请重新运行脚本。");
  const value = JSON.parse(raw);
  if (!value || Array.isArray(value) || typeof value !== "object") fail("学校返回的数据格式异常。");
  return value;
}

function dateParts(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) fail("日期格式应为 YYYY-MM-DD。");
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.toISOString().slice(0, 10) !== value) fail("日期无效。");
  return { year, month, day, utc: date.getTime(), weekday: ((date.getUTCDay() + 6) % 7) + 1 };
}

function todayShanghai() {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function configFromSchool(page, metadata, termData) {
  if (!page.campus || !page.campus.includes("科信")) fail("学校页面未确认科信校区，已停止查询。");
  if (!page.year || !page.term || !page.label) fail("学校当前学年、学期信息缺失。");
  const dates = termData.dqzcxq;
  if (!dates || text(dates.ZQST) !== "1") fail("学校校历结构变化，已停止查询。");
  const start = dateParts(text(dates.ZXRQ));
  if (start.weekday !== 1) fail("学校校历起始日异常。");
  const firstWeek = Number(dates.ZXZC);
  const weeks = new Set((termData.nxqzcList || []).filter(item => text(item.zczt) !== "0").map(item => Number(item.dxqzc)));
  if (!Number.isInteger(firstWeek) || firstWeek < 1 || weeks.size === 0 || [...weeks].some(n => !Number.isInteger(n) || n < 1 || n > 52)) fail("学校周次信息异常。");
  if (!Array.isArray(metadata.lhList) || !Array.isArray(metadata.jcList) || !Array.isArray(page.types)) fail("学校查询配置缺失。");
  const buildings = [{ id: "", label: "全部教学楼" }];
  for (const item of metadata.lhList) {
    const area = text(item.XQH_ID);
    if (area && area !== CAMPUS) fail("学校返回的教学楼不属于科信校区。");
    buildings.push({ id: text(item.JXLDM), label: text(item.JXLMC) });
  }
  const periods = metadata.jcList.map(item => ({ number: Number(item.JCMC), time: text(item.SJD) }));
  if (periods.length === 0 || periods.some(p => !Number.isInteger(p.number) || p.number < 1 || p.number > 30) || page.types.length === 0) fail("学校节次或类别配置异常。");
  return { year: page.year, term: page.term, label: page.label, start: text(dates.ZXRQ), firstWeek, weeks, buildings, types: page.types, periods };
}

function queryForm(config, input, page) {
  const selected = dateParts(input.date);
  const start = dateParts(config.start);
  const days = Math.round((selected.utc - start.utc) / 86400000);
  const week = Math.floor(days / 7) + config.firstWeek;
  if (days < 0 || !config.weeks.has(week)) fail("所选日期不在学校当前学期可查询周次内。");
  const valid = new Set(config.periods.map(p => p.number));
  if (!Number.isInteger(input.from) || !Number.isInteger(input.to) || input.from > input.to || input.from < 1 || input.to > 30) fail("节次范围无效。");
  let periodBits = 0n;
  for (let n = input.from; n <= input.to; n++) {
    if (!valid.has(n)) fail("所选节次不在学校配置中。");
    periodBits |= 1n << BigInt(n - 1);
  }
  if (!config.buildings.some(item => item.id === input.building) || !config.types.some(item => item.id === input.type)) fail("查询条件已经失效。");
  return [
    ["xqh_id", CAMPUS], ["xnm", config.year], ["xqm", config.term], ["jyfs", "0"],
    ["zcd", (1n << BigInt(week - 1)).toString()], ["xqj", String(selected.weekday)],
    ["jcd", periodBits.toString()], ["lh", input.building], ["cdlb_id", input.type],
    ...["cdejlb_id", "qszws", "jszws", "cdmc", "cdjylx", "sfbhkc"].map(key => [key, ""]),
    ["queryModel.showCount", "100"], ["queryModel.currentPage", String(page)],
    ["queryModel.sortName", "cdbh"], ["queryModel.sortOrder", "asc"],
    ["_search", "false"], ["nd", String(Date.now())]
  ];
}

function parsePage(response, expectedPage) {
  const total = Number(response.totalCount), pages = Number(response.totalPage), current = Number(response.currentPage);
  if (!Number.isInteger(total) || total < 0 || total > 3000 || !Number.isInteger(pages) || pages < 0 || pages > 30 || current !== expectedPage || !Array.isArray(response.items)) fail("学校返回的分页信息异常。");
  const rooms = response.items.map(item => {
    if (text(item.xqh_id) !== CAMPUS || !text(item.xqmc).includes("科信")) fail("学校返回了非科信校区数据，本次不显示结果。");
    const id = text(item.cd_id), name = text(item.cdmc);
    if (!id || !name) fail("学校返回的教室信息不完整。");
    return { id, name, building: text(item.jxlmc) || "未标注楼号", type: text(item.cdlbmc) || "未标注类型", seats: text(item.zws) || "未提供", bookable: text(item.sfkjy) || "未提供" };
  });
  return { total, pages, current, rooms };
}

class SchoolClient {
  constructor() {
    this.cookies = new Map();
    this.parser = null;
  }

  async request(path, form, binary = false) {
    const url = ORIGIN + path;
    const method = form ? "POST" : "GET";
    if (!allowed(url, method)) fail("脚本只允许访问学校登录和空教室查询接口。");
    const req = new Request(url);
    req.method = method;
    req.timeoutInterval = 20;
    req.onRedirect = next => allowed(next.url, next.method || "GET") ? next : null;
    req.headers = {
      "User-Agent": "KexinRooms/1.0 (iOS; Scriptable)",
      "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
      "Cache-Control": "no-cache, no-store",
      "Referer": ORIGIN + (path.startsWith("/cdjy/") ? ROOMS_PATH : "/xtgl/login_slogin.html")
    };
    if (this.cookies.size) req.headers.Cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join("; ");
    if (form) {
      req.body = encode(form);
      req.headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8";
      req.headers.Origin = ORIGIN;
    }
    let result;
    try {
      result = await withTimeout(binary ? req.load() : req.loadString(), `学校接口 ${path.split("?")[0]}`, 25000);
    } catch (error) {
      throw new Error(`学校接口 ${path.split("?")[0]} 连接失败：${text(error.message || error)}`);
    }
    const response = req.response || {};
    if (!response.url || !allowed(response.url, "GET")) fail("学校请求被重定向到非预期地址。");
    if (response.statusCode === 401) fail("学校登录已过期，请重新运行脚本。");
    if (response.statusCode !== 200) fail(`教务系统暂时无法响应（HTTP ${response.statusCode}）。`);
    for (const cookie of response.cookies || []) {
      if (cookie.domain && !/^(\.|jwglxxfwpt\.)?hebeu\.edu\.cn$/i.test(cookie.domain) && cookie.domain !== "jwglxxfwpt.hebeu.edu.cn") continue;
      if (/^[A-Za-z0-9_\-]+$/.test(cookie.name) && !/[;\r\n]/.test(cookie.value)) this.cookies.set(cookie.name, cookie.value);
    }
    if (!binary && result.length > 8_000_000) fail("学校响应过大，本次不显示结果。");
    return { result, response };
  }

  async html(path, form) { return (await this.request(path, form)).result; }
  async json(path, form) { return parseJSON((await this.request(path, form)).result); }

  async parseHTML(source, mode) {
    if (!this.parser) {
      this.parser = new WebView();
      this.parser.shouldAllowRequest = () => false;
      await withTimeout(this.parser.loadHTML("<!doctype html><html><body></body></html>"), "本机页面解析准备", 10000);
    }
    const code = `(() => { const d = new DOMParser().parseFromString(${JSON.stringify(source)}, 'text/html');
      const value = id => d.getElementById(id)?.value || '';
      if (${JSON.stringify(mode)} === 'login') {
        const c = d.getElementById('yzmDiv');
        return { hasLogin: !!d.querySelector('input[name=yhm]'), token: d.querySelector('input[name=csrftoken]')?.value || '', captcha: !!d.getElementById('yzm') && (!c || !c.getAttribute('style')?.replace(/\\s/g, '').includes('display:none')) };
      }
      const year = value('xnm'), term = value('xqm');
      const campus = [...d.querySelectorAll('#xqh_id option')].find(o => o.value === '106')?.textContent?.trim() || '';
      const label = [...d.querySelectorAll('#dm_cx option')].find(o => o.value === year + '-' + term)?.textContent?.trim() || '';
      const types = [...d.querySelectorAll('#cdlb_id option')].map(o => ({ id: o.value, label: o.value ? o.textContent.trim() : '全部场地类别' }));
      return { hasLogin: !!d.querySelector('input[name=yhm]'), year, term, campus, label, types };
    })()`;
    return await withTimeout(this.parser.evaluateJavaScript(code), "学校页面解析", 10000);
  }

  async encrypt(password, modulus, exponent) {
    if (!/^[A-Za-z0-9+/=]+$/.test(modulus) || !/^[A-Za-z0-9+/=]+$/.test(exponent)) fail("学校加密公钥无效。");
    if (!this.parser) await this.parseHTML("", "login");
    const code = `(() => {
      const decode = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
      const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
      const nBytes = decode(${JSON.stringify(modulus)}), eBytes = decode(${JSON.stringify(exponent)});
      if (!nBytes.length || !eBytes.length || !globalThis.crypto?.getRandomValues) throw Error('无法安全加密密码');
      const k = nBytes.length, message = new TextEncoder().encode(${JSON.stringify(password)});
      if (message.length > k - 11) throw Error('密码超出学校公钥允许长度');
      const em = new Uint8Array(k); em[1] = 2;
      for (let i = 2; i < k - message.length - 1; i++) {
        let b = 0; while (!b) b = crypto.getRandomValues(new Uint8Array(1))[0]; em[i] = b;
      }
      em.set(message, k - message.length);
      const n = BigInt('0x' + hex(nBytes)), e = BigInt('0x' + hex(eBytes));
      let base = BigInt('0x' + hex(em)), power = e, out = 1n;
      while (power > 0n) { if (power & 1n) out = out * base % n; base = base * base % n; power >>= 1n; }
      const result = out.toString(16).padStart(k * 2, '0').match(/../g).map(x => parseInt(x, 16));
      return btoa(String.fromCharCode(...result));
    })()`;
    return await withTimeout(this.parser.evaluateJavaScript(code), "教务密码加密", 10000);
  }

  async captcha() {
    const { result, response } = await this.request(`/kaptcha?time=${Date.now()}`, null, true);
    const type = Object.entries(response.headers || {}).find(([key]) => key.toLowerCase() === "content-type")?.[1] || "";
    if (!String(type).startsWith("image/")) fail("验证码图片暂不可用。");
    await QuickLook.present(Image.fromData(result));
    const prompt = new Alert();
    prompt.title = "输入刚才看到的验证码";
    prompt.addTextField("验证码");
    prompt.addAction("继续登录");
    prompt.addCancelAction("取消");
    if (await prompt.presentAlert() < 0) fail("已取消登录。");
    return prompt.textFieldValue(0).trim();
  }

  async login(username, password) {
    let raw = await this.html("/xtgl/login_slogin.html");
    for (let attempt = 0; attempt < 2; attempt++) {
      const page = await this.parseHTML(raw, "login");
      if (!page.hasLogin || !page.token) fail("学校登录页面已变化。");
      const captcha = page.captcha ? await this.captcha() : "";
      const millis = Date.now();
      const key = await this.json(`/xtgl/login_getPublicKey.html?time=${millis}`);
      const encrypted = await this.encrypt(password, text(key.modulus), text(key.exponent));
      const form = [["csrftoken", page.token], ["yhm", username], ["mm", encrypted], ["language", "zh_CN"], ["ydType", ""]];
      if (captcha) form.push(["yzm", captcha]);
      raw = await this.html(`/xtgl/login_slogin.html?time=${millis}`, form);
      const result = await this.parseHTML(raw, "login");
      if (!result.hasLogin) {
        const rooms = await this.parseHTML(await this.html(ROOMS_PATH), "rooms");
        if (rooms.hasLogin) fail("教务系统未接受登录。");
        return;
      }
      if (!result.captcha) fail("学校未接受此次登录，请核对账号密码；已停止自动重试。");
    }
    fail("学校仍要求验证码，请稍后重新运行脚本。");
  }

  async config() {
    const page = await this.parseHTML(await this.html(ROOMS_PATH), "rooms");
    if (page.hasLogin) fail("学校登录已失效，本次不显示结果。");
    if (!page.year || !page.term) fail("学校当前学年、学期缺失。");
    const metadata = await this.json(`/cdjy/cdjy_cxXqjc.html?gnmkdm=N2155&${encode([["xqh_id", CAMPUS], ["xnm", page.year], ["xqm", page.term]])}`);
    const termData = await this.json("/cdjy/cdjy_cxQtlb.html?gnmkdm=N2155", [["xqh_id", CAMPUS], ["xnm", page.year], ["xqm", page.term], ["flag", "0"]]);
    return configFromSchool(page, metadata, termData);
  }

  async search(config, input) {
    const rooms = [], ids = new Set();
    let expectedTotal = null;
    for (let index = 1; index <= 30; index++) {
      const raw = await this.json(`${ROOMS_PATH}&doType=query`, queryForm(config, input, index));
      const page = parsePage(raw, index);
      if (expectedTotal !== null && page.total !== expectedTotal) fail("查询期间学校数据发生变化，请重新运行脚本。");
      expectedTotal = page.total;
      for (const room of page.rooms) {
        if (ids.has(room.id)) fail("学校分页重复，请重新运行脚本。");
        ids.add(room.id); rooms.push(room);
      }
      if (index >= page.pages) {
        if (rooms.length !== page.total) fail("学校数据没有完整返回，请重新运行脚本。");
        return rooms;
      }
      if (!page.rooms.length) fail("学校分页数据不完整。");
    }
    fail("学校返回页数超过安全上限。");
  }
}

async function credentials() {
  if (Keychain.contains(USER_KEY) && Keychain.contains(PASS_KEY)) return [Keychain.get(USER_KEY), Keychain.get(PASS_KEY), false];
  const prompt = new Alert();
  prompt.title = "科信空教室 · 首次登录";
  prompt.message = "输入你自己的教务账号。成功登录后仅保存在本机 Scriptable 钥匙串。";
  prompt.addTextField("学号 / 教务用户名");
  prompt.addSecureTextField("教务密码");
  prompt.addAction("登录");
  prompt.addCancelAction("取消");
  if (await prompt.presentAlert() < 0) fail("已取消登录。");
  const username = prompt.textFieldValue(0).trim(), password = prompt.textFieldValue(1);
  if (!username || !password) fail("账号或密码为空。");
  return [username, password, true];
}

async function filters(config) {
  const prompt = new Alert();
  prompt.title = `科信校区 · ${config.label}`;
  prompt.message = "每次查询都会重新读取学校当前学期和空教室数据。";
  const first = config.periods[0].number, second = config.periods[1]?.number || first;
  prompt.addTextField("日期 YYYY-MM-DD", todayShanghai());
  prompt.addTextField("开始节次", String(first));
  prompt.addTextField("结束节次", String(second));
  prompt.addAction("查询全部教学楼");
  prompt.addAction("选择教学楼");
  prompt.addCancelAction("取消");
  const selected = await prompt.presentAlert();
  if (selected < 0) fail("已取消查询。");
  const input = { date: prompt.textFieldValue(0).trim(), from: Number(prompt.textFieldValue(1)), to: Number(prompt.textFieldValue(2)), building: "", type: config.types[0].id };
  if (selected === 1) {
    const buildings = new Alert();
    buildings.title = "选择科信校区教学楼";
    for (const item of config.buildings) buildings.addAction(item.label);
    buildings.addCancelAction("取消");
    const index = await buildings.presentSheet();
    if (index < 0) fail("已取消查询。");
    input.building = config.buildings[index].id;
  }
  return input;
}

async function showResults(config, input, rooms) {
  const table = new UITable();
  table.showSeparators = false;
  const heading = new UITableRow();
  heading.height = 48;
  heading.isHeader = true;
  heading.addText(`科信校区 · ${rooms.length} 间空教室`, `${config.label} · ${input.date} 第 ${input.from}–${input.to} 节 · 刚从学校查询`);
  table.addRow(heading);
  if (!rooms.length) {
    const empty = new UITableRow();
    empty.addText("该时段没有符合条件的空教室", "可换日期、节次或教学楼后重新运行脚本");
    table.addRow(empty);
  }
  for (const room of rooms) {
    const row = new UITableRow();
    row.height = 42;
    row.addText(room.name, `${room.building} · ${room.type}`);
    row.addText(`${room.seats} 座`, `可借用 ${room.bookable}`);
    table.addRow(row);
  }
  const note = new UITableRow();
  note.height = 44;
  note.addText("现场开放情况以学校管理为准", "非学校官方应用 · 无结果缓存");
  table.addRow(note);
  const logout = new UITableRow();
  logout.addText("清除本机教务账号", "下次运行脚本时重新输入");
  logout.onSelect = async () => {
    const prompt = new Alert(); prompt.title = "清除本机账号？";
    prompt.addDestructiveAction("清除"); prompt.addCancelAction("取消");
    if (await prompt.presentAlert() === 0) { Keychain.remove(USER_KEY); Keychain.remove(PASS_KEY); }
  };
  table.addRow(logout);
  await table.present();
}

async function main() {
  try {
    const [username, password, fresh] = await credentials();
    const client = new SchoolClient();
    await client.login(username, password);
    if (fresh) { Keychain.set(USER_KEY, username); Keychain.set(PASS_KEY, password); }
    const config = await client.config();
    const input = await filters(config);
    const rooms = await client.search(config, input);
    await showResults(config, input, rooms);
  } catch (error) {
    const alert = new Alert();
    alert.title = "本次未显示空教室";
    alert.message = text(error.message || error);
    alert.addAction("知道了");
    if (Keychain.contains(USER_KEY) || Keychain.contains(PASS_KEY)) alert.addDestructiveAction("清除本机账号");
    if (await alert.presentAlert() === 1) {
      if (Keychain.contains(USER_KEY)) Keychain.remove(USER_KEY);
      if (Keychain.contains(PASS_KEY)) Keychain.remove(PASS_KEY);
    }
  }
}

if (typeof UITable !== "undefined" && typeof Keychain !== "undefined") await main();
if (typeof module !== "undefined") module.exports = { allowed, configFromSchool, queryForm, parsePage, dateParts, encode };
