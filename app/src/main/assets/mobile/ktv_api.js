/**
 * KTV Song API - JS Hot Update Module
 * ====================================
 * 从 Gitea 热更新, App 无需发版即可修复 API 变更
 *
 * 用法:
 *   const api = new KtvApi({proxy: 'http://127.0.0.1:7897'})
 *   const url = await api.getSongUrl('7678785')
 *   // → http://download.origjoy.com/E/ts/.../7678785.ts?sign=...
 */

(function(global) {
  'use strict';

  // KTV_BRIDGE_API: 9 (Android 缓存校验标记，请勿删除)

  // ─── 配置 ──────────────────────────────────────────
  const CONFIG = {
    APP_ID:  'd4eeacc6cec3434fbc8c41608a3056a0',
    APP_KEY: '024210cba40d4385a93e6c2d3249bfb5',
    SDK_KEY: '19042303a8374f67ae3fe1e25c97936f',
    VN:      '4.100.5.11121504',
    VER:     '4',
    // 歌曲源(唯一; 运行时按可用性自动排序, 解析失败的源沉底)。
    HOSTS:   [
      'http://zk.ktv1.top:6677',
    ],
    MWS:     'https://mws.cherryonline.cn',
    RSA_PUBKEY: 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAucL0oFErd7REM6TSNa3EZdN1YaOs4J1eCybLPyoQ9ru3q1HU67agC9FzhrCG/RvAQUya5iPmQ8Caed05vqcCVcyJChmkSOGQ7DVShe2rGuTMNlpoRV6UzfcraaVS++7m2K/+kSZJ8OAhhhVuqPruMjsFYpdtstAwvyZT28b+eENwzpp9UHqsooZc7FZ0H8kTbs6XMkw4nIWo+4HoPAhNLEY+xdHvwY6drF/3WDTvsaoMrs73TVQCEEHzZNIz2H/is9VLMnIyOfnfcJi9br78Fj2xHzxu3sAySBOTVLmUMxqYh/g1ox5OXGcW93HJkQLkBi42tFAEkWYlYyl93+jbbQIDAQAB'
  };

  // ─── 工具函数 ──────────────────────────────────────
  function randomHex(n) {
    var s = ''; while (n--) s += '0123456789abcdef'[Math.random()*16|0]; return s;
  }

  // ─── MD5 ───────────────────────────────────────────
  function md5(input) {
    function add(a, b) {
      var low = (a & 0xffff) + (b & 0xffff);
      var high = (a >>> 16) + (b >>> 16) + (low >>> 16);
      return (high << 16) | (low & 0xffff);
    }
    function rol(value, shift) { return (value << shift) | (value >>> (32 - shift)); }
    function wordHex(value) {
      var out = '';
      for (var i = 0; i < 4; i++) out += ('0' + ((value >>> (i * 8)) & 255).toString(16)).slice(-2);
      return out;
    }

    // MD5 works on UTF-8 bytes, then appends 0x80 and the original bit length.
    var utf8 = unescape(encodeURIComponent(input));
    var bytes = [];
    for (var i = 0; i < utf8.length; i++) bytes.push(utf8.charCodeAt(i));
    var bitLengthLow = (bytes.length << 3) >>> 0;
    var bitLengthHigh = (bytes.length >>> 29) >>> 0;
    bytes.push(0x80);
    while ((bytes.length % 64) !== 56) bytes.push(0);
    for (i = 0; i < 4; i++) bytes.push((bitLengthLow >>> (i * 8)) & 255);
    for (i = 0; i < 4; i++) bytes.push((bitLengthHigh >>> (i * 8)) & 255);

    var shifts = [
      7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
      5,9,14,20, 5,9,14,20, 5,9,14,20, 5,9,14,20,
      4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
      6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21
    ];
    var constants = [];
    for (i = 0; i < 64; i++) constants[i] = (Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
    var a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;

    for (var offset = 0; offset < bytes.length; offset += 64) {
      var words = [];
      for (i = 0; i < 16; i++) {
        var at = offset + i * 4;
        words[i] = bytes[at] | (bytes[at+1] << 8) | (bytes[at+2] << 16) | (bytes[at+3] << 24);
      }
      var a = a0, b = b0, c = c0, d = d0;
      for (i = 0; i < 64; i++) {
        var f, g;
        if (i < 16) { f = (b & c) | ((~b) & d); g = i; }
        else if (i < 32) { f = (d & b) | ((~d) & c); g = (5 * i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
        else { f = c ^ (b | (~d)); g = (7 * i) % 16; }
        var oldD = d;
        d = c;
        c = b;
        b = add(b, rol(add(add(a, f), add(constants[i], words[g])), shifts[i]));
        a = oldD;
      }
      a0 = add(a0, a); b0 = add(b0, b); c0 = add(c0, c); d0 = add(d0, d);
    }
    return wordHex(a0) + wordHex(b0) + wordHex(c0) + wordHex(d0);
  }

  // ─── RSA Encrypt ───────────────────────────────────
  async function rsaEncrypt(plaintext) {
    if (global.android && typeof global.android.rsaEncryptPkcs1 === 'function') {
      var encrypted = global.android.rsaEncryptPkcs1(CONFIG.RSA_PUBKEY, plaintext);
      if (!encrypted) throw new Error('native RSA encryption failed');
      return encrypted;
    }
    throw new Error('RSA/PKCS1 bridge is unavailable');
  }

  // ─── HTTP ──────────────────────────────────────────
  function sleep(ms) {
    return new Promise(function(resolve) { setTimeout(resolve, ms); });
  }

  function nativeResponse(raw) {
    var value = JSON.parse(raw || '{}');
    if (value.error) throw new Error(value.error);
    return {body: value.body || '', status: Number(value.status || 0)};
  }

  // 部分节点会在 JSON 前输出 PHP Deprecated HTML，从第一个 "{" 开始容错解析。
  function parseJsonBody(body) {
    body = String(body || '');
    var start = body.indexOf('{');
    if (start < 0) throw new Error('JSON object not found');
    return JSON.parse(body.slice(start));
  }

  // 任务3: 点歌结果上报(成功/失败 + 实际命中的源) → Android 侧写入远程日志。
  function reportSongResult(ok, host, musicno, detail) {
    try {
      if (global.android && typeof global.android.reportSongResult === 'function') {
        global.android.reportSongResult(ok ? 'ok' : 'fail', host, String(musicno), String(detail || ''));
      }
    } catch(e) { /* 日志失败不影响点歌 */ }
  }

  async function httpGet(url, timeout) {
    timeout = timeout || 8000;
    // 原 APK 的 native GET 抓包只带 Accept: */*，不伪造来源 IP。
    var headers = {'Accept': '*/*'};
    if (global.android && typeof global.android.httpGet === 'function') {
      return nativeResponse(global.android.httpGet(url, JSON.stringify(headers), timeout));
    }
    return new Promise(function(resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.timeout = timeout;
      xhr.onload = function() { resolve({body:xhr.responseText, status:xhr.status}); };
      xhr.onerror = function() { reject(new Error('network error')); };
      xhr.ontimeout = function() { reject(new Error('timeout')); };
      xhr.send();
    });
  }

  async function httpPost(url, body, timeout) {
    timeout = timeout || 8000;
    var headers = {'Content-Type':'application/json', 'Accept':'*/*', 'User-Agent':'Dalvik/2.1.0'};
    if (global.android && typeof global.android.httpPost === 'function') {
      return nativeResponse(global.android.httpPost(url, body, JSON.stringify(headers), timeout));
    }
    return new Promise(function(resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', url, true);
      xhr.timeout = timeout;
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.onload = function() { resolve({body:xhr.responseText, status:xhr.status}); };
      xhr.onerror = function() { reject(new Error('network error')); };
      xhr.ontimeout = function() { reject(new Error('timeout')); };
      xhr.send(body);
    });
  }

  // ─── KtvApi Class ──────────────────────────────────
  function KtvApi(options) {
    options = options || {};
    this.proxy = options.proxy || '';
    this.mac = options.mac || randomHex(16);
    this.sn = options.sn || randomHex(16);
    this.tokens = {};
    // 歌曲源自动排序: 维护一份"当前优先级"列表(运行时可变)。
    // 某源解析失败时把它挪到队尾, 下次点歌优先尝试更可靠的源。
    this.sourceOrder = (options.hosts && options.hosts.length ? options.hosts : CONFIG.HOSTS).slice();
    // 与 test_ktv_unified.py 一致：首次设备加最多 3 次换设备重试。
    this.maxDeviceRetries = Number.isFinite(Number(options.maxDeviceRetries))
      ? Math.max(0, Number(options.maxDeviceRetries) | 0) : 3;
    // 兼容旧远程脚本/调试代码读取单 token 的行为。
    this.token = '';
    this.debug = options.debug || false;
  }

  KtvApi.prototype = {
    _log: function(msg) { if (this.debug) console.log('[KtvApi]', msg); },

    /** 将失败的源移到优先级队尾(自动排序)。host 不在列表时忽略。 */
    demoteSource: function(host) {
      var idx = this.sourceOrder.indexOf(host);
      if (idx < 0) return;
      this.sourceOrder.splice(idx, 1);
      this.sourceOrder.push(host);
      this._log('Source demoted to end (解析失败): ' + host);
      this._log('Source order now: ' + this.sourceOrder.join(' > '));
    },

    /** 成功解析的源提到队首(最近成功优先; 曾失败的源恢复后自然回到前面)。 */
    promoteSource: function(host) {
      var idx = this.sourceOrder.indexOf(host);
      if (idx <= 0) return;
      this.sourceOrder.splice(idx, 1);
      this.sourceOrder.unshift(host);
      this._log('Source promoted to front (解析成功): ' + host);
      this._log('Source order now: ' + this.sourceOrder.join(' > '));
    },

    regenerateDevice: function() {
      this.mac = randomHex(16);
      this.sn = randomHex(16);
      this.tokens = {};
      this.token = '';
      this._log('Device regenerated: ' + this.mac + '_' + this.sn);
    },

    // 1. MWS Login
    mwsLogin: async function() {
      var self = this;
      var plaintext = JSON.stringify({device_id: self.mac});
      self._log('MWS login...');
      try {
        var encrypted = await rsaEncrypt(plaintext);
        var body = JSON.stringify({
          encrypted_data: encrypted, ip: '172.16.32.15', dns: '', router: '',
          subnet_mask: '255.255.255.0', channel: 'common', mode: 'ott'
        });
        var resp = await httpPost(CONFIG.MWS + '/mls-api/v1/login', body);
        if (resp.status === 200) {
          var data = parseJsonBody(resp.body);
          if (data.authorized) {
            self.mac = data.device_id || self.mac;
            self.sn = self.mac;
            self.tokens = {};
            self.token = '';
            self._log('MWS login OK: ' + self.mac);
            return true;
          }
        }
        self._log('MWS login FAIL: ' + resp.status);
        return false;
      } catch(e) {
        self._log('MWS login error: ' + e.message);
        return false;
      }
    },

    // 2. Get Auth Token
    getToken: async function(host, forceRefresh) {
      var self = this;
      host = host || CONFIG.HOSTS[0];
      if (!forceRefresh && self.tokens[host]) {
        self.token = self.tokens[host];
        return self.token;
      }
      var ts = Math.floor(Date.now() / 1000);
      var params = 'appid=' + CONFIG.APP_ID +
        '&mac=' + self.mac + '_' + self.sn +
        '&sn=' + self.sn +
        '&time=' + ts +
        '&ver=' + CONFIG.VER + '&vn=' + CONFIG.VN;
      var sign = md5(params + CONFIG.APP_KEY);
      var url = host + '/i.php?' + params + '&sign=' + sign;
      self._log('Getting token: ' + host);
      try {
        var resp = await httpGet(url);
        if (resp.status === 200) {
          var data = parseJsonBody(resp.body);
          if (data.code === 200) {
            self.tokens[host] = data.token;
            self.token = data.token;
            self._log('Token OK: ' + host);
            return self.token;
          }
        }
        delete self.tokens[host];
        self._log('Token FAIL: ' + host + ', HTTP ' + resp.status);
        return null;
      } catch(e) {
        delete self.tokens[host];
        self._log('Token error: ' + host + ', ' + e.message);
        return null;
      }
    },

    clearToken: function(host) {
      delete this.tokens[host];
      this.token = '';
    },

    _fetchSongUrl: async function(host, musicno, token, ls, resolution, h265) {
      var ts = Math.floor(Date.now() / 1000);
      // 参数顺序、原始 token 中的 "=" 以及签名拼接必须与原 APK 保持一致。
      var params = 'appid=' + CONFIG.APP_ID +
        '&device=' + this.mac + '_' + this.sn +
        '&ish265=' + (h265 ? '1' : '0') +
        '&ls=' + ls +
        '&musicno=' + musicno +
        '&resolution=' + resolution +
        '&sn=' + this.sn +
        '&time=' + ts +
        '&token=' + token;
      var sign = md5(params + CONFIG.SDK_KEY);
      var resp = await httpGet(host + '/music/do.php?' + params + '&sign=' + sign);
      if (resp.status !== 200) {
        return {code: null, data: '', httpStatus: resp.status};
      }
      return parseJsonBody(resp.body);
    },

    // 3. Get Song Download URL
    // 多源自动排序: 按 this.sourceOrder(当前优先级)依次尝试; 某源解析失败
    // (token 失败 / do.php 非 200 / 无有效 URL)时立即 demote 到队尾,
    // 下次点歌自动优先使用更可靠的源。全部源失败返回 null。
    getSongUrl: async function(musicno, resolution, h265, _preferredLs) {
      resolution = resolution || '720';
      h265 = !!h265;
      var self = this;
      var lastDetail = '';
      var lastAttemptHost = '';
      for (var deviceRound = 0; deviceRound <= self.maxDeviceRetries; deviceRound++) {
        if (deviceRound > 0) self.regenerateDevice();
        // 每一轮使用当时的优先级快照; demote 动态修改 sourceOrder。
        var order = self.sourceOrder.slice();
        for (var hostIndex = 0; hostIndex < order.length; hostIndex++) {
          var host = order[hostIndex];
          lastAttemptHost = host;
          self._log('Try source (' + (hostIndex + 1) + '/' + order.length + '): ' + host);

          var token = await self.getToken(host, false);
          if (!token) {
            lastDetail = 'token-fail';
            self._log('token-fail: ' + host + ' → demote');
            self.demoteSource(host);
            continue;
          }

          // 各源同后台, 只试 ls=1(其余 ls 值会被其风控直接拒绝, 反复试会放大请求量)。
          var lsValues = ['1'];
          var hostOk = false;
          var hostDetail = '';

          for (var lsi = 0; lsi < lsValues.length && !hostOk; lsi++) {
            var ls = lsValues[lsi];
            try {
              var data = await self._fetchSongUrl(host, musicno, token, ls, resolution, h265);
              // token 恒定不变; do.php 403"检测到异常行为"是风控限流, 与 token 过期无关。
              // 失败时同 token 短退避重试一次, 不再清 token 重取 i.php。
              var needRetry = data.code !== 200;
              if (needRetry) {
                hostDetail = 'do.php code=' + data.code + ' http=' + (data.httpStatus || 0);
                self._log(hostDetail + ' for ' + musicno + ' ls=' + ls + '; retry once with same token');
                await sleep(3000);
                data = await self._fetchSongUrl(host, musicno, token, ls, resolution, h265);
              }

              if (data.code === 200) {
                var songUrl = data.data || '';
                if (songUrl && /^https?:\/\//i.test(songUrl)) {
                  self._log('URL OK: ' + musicno + ' ls=' + ls + ' via ' + host);
                  self.promoteSource(host);
                  hostOk = true;
                  // 任务3: 点歌成功上报命中的源(Android 侧写入远程日志)。
                  reportSongResult(true, host, musicno, 'ok');
                  return songUrl;
                }
              }
            } catch(e) {
              hostDetail = e.message;
              self._log('Failed ls=' + ls + ': ' + host + ', ' + e.message);
            }
          }

          // 该源所有 ls 都未取到有效 URL → 判定解析失败, 排到最后。
          if (!hostOk) {
            lastDetail = hostDetail || 'no-url';
            self._log('Source failed (' + (hostDetail || 'no-url') + ') → demote: ' + host);
            self.demoteSource(host);
          }
        }
      }
      self._log('All sources exhausted for ' + musicno + ' (' + lastDetail + ')');
      // 任务3: 全部源失败 → 上报最后尝试的源与原因。
      reportSongResult(false, lastAttemptHost, musicno, lastDetail || 'all-sources-failed');
      return null;
    },

    // 4. Full init sequence
    init: async function() {
      // token 获取属于每个主/备节点请求的一部分；这里不提前绑定单一节点。
      return true;
    }
  };

  // ─── 导出 ──────────────────────────────────────────
  global.KtvApi = KtvApi;
  global.KtvApiConfig = CONFIG;
  global.KtvBridgeApiVersion = 9;

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {KtvApi: KtvApi, CONFIG: CONFIG};
  }

})(typeof window !== 'undefined' ? window : this);
