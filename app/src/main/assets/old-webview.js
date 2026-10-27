// 老 WebView（Chrome 74 及以下出厂内核）兼容垫片 + 白屏诊断。
// 本文件必须以 ES5 语法书写——它要在最老的内核里被解析执行，任何新语法都会
// 让它自己先炸。语法层兼容由构建期 esbuild --target=chrome61 转译 dist 解决
// （见 scripts/build-native.sh），这里只补运行时缺失的 API。
// 注入时机：WebActivity onPageStarted（先于页面自身脚本执行）。
(function () {
  if (window.__piikOldWv) return;
  window.__piikOldWv = true;

  // ---- 白屏诊断：收集错误，页面起不来时给出可读提示（替代静默白屏） ----
  var errs = [];
  function note(msg) {
    try {
      errs.push(String(msg));
      if (window.console && console.error) console.error('[PiikOldWv] ' + msg);
    } catch (e) {}
  }
  window.addEventListener('error', function (ev) {
    note((ev.message || 'resource error') + ' @ ' + (ev.filename || '') + ':' + (ev.lineno || 0));
  }, true);
  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev && ev.reason;
    note('unhandledrejection: ' + ((r && (r.message || r.stack)) || r));
  });

  function showBlankDiag() {
    try {
      if (document.getElementById('piik-oldwv-diag')) return;
      var b = document.body;
      if (!b) return;
      // 页面有实际内容就不弹（video / canvas / 有文字的节点都算活着）。
      var alive = b.querySelector('video,canvas') ||
        (b.innerText && b.innerText.replace(/\s/g, '').length > 20);
      if (alive || errs.length === 0) return;
      var box = document.createElement('div');
      box.id = 'piik-oldwv-diag';
      box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#0b0c10;' +
        'color:#e9ecf5;font:14px/1.7 sans-serif;padding:20px;overflow:auto;white-space:pre-wrap';
      box.textContent = '界面加载失败（页面脚本报错）\n\n' +
        '这通常是因为系统 WebView 内核过旧。请到应用商店把\n' +
        '「Android System WebView」或「Chrome」更新到最新版后重试。\n\n' +
        '错误摘要（供反馈时使用）：\n' + errs.slice(0, 8).join('\n');
      b.appendChild(box);
    } catch (e) {}
  }
  // 页面加载完成后 3 秒仍无内容才判定白屏，避免误报慢加载。
  window.addEventListener('load', function () { setTimeout(showBlankDiag, 3000); });
  setTimeout(showBlankDiag, 8000);

  // ---- 运行时 polyfill（全部带存在性守卫，新内核上是 no-op） ----

  // globalThis（Chrome 71+）
  try {
    if (typeof globalThis === 'undefined') {
      Object.defineProperty(window, 'globalThis', { value: window, configurable: true, writable: true });
    }
  } catch (e) { try { window.globalThis = window; } catch (e2) {} }

  // Promise.allSettled（Chrome 76+）
  if (typeof Promise !== 'undefined' && typeof Promise.allSettled !== 'function') {
    Promise.allSettled = function (list) {
      return Promise.all(Array.prototype.map.call(list || [], function (p) {
        return Promise.resolve(p).then(
          function (value) { return { status: 'fulfilled', value: value }; },
          function (reason) { return { status: 'rejected', reason: reason }; }
        );
      }));
    };
  }

  // String.prototype.replaceAll（Chrome 85+）
  if (typeof String.prototype.replaceAll !== 'function') {
    Object.defineProperty(String.prototype, 'replaceAll', {
      value: function (search, replacement) {
        if (search instanceof RegExp) {
          if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
          return this.replace(search, replacement);
        }
        var s = String(search), out = '', idx = 0, i;
        if (s === '') {
          // 与原生行为一致：空串在每个字符间隙插入 replacement
          out = typeof replacement === 'function' ? '' : String(replacement == null ? '' : replacement);
          return this.split('').join(out) + (typeof replacement === 'function' ? '' : out);
        }
        while ((i = this.indexOf(s, idx)) !== -1) {
          out += this.slice(idx, i);
          out += typeof replacement === 'function' ? replacement(s, i, this) : String(replacement == null ? '' : replacement);
          idx = i + s.length;
        }
        return out + this.slice(idx);
      },
      writable: true, configurable: true
    });
  }

  // Object.hasOwn（Chrome 93+）
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function (obj, prop) {
      if (obj == null) throw new TypeError('Cannot convert undefined or null to object');
      return Object.prototype.hasOwnProperty.call(Object(obj), prop);
    };
  }

  // structuredClone（Chrome 98+）——JSON 兜底，覆盖配置对象类用法；
  // 函数/循环引用等结构化克隆语义无法完整模拟，失败时抛 DataCloneError。
  if (typeof window.structuredClone !== 'function') {
    window.structuredClone = function (value) {
      if (arguments.length === 0) throw new TypeError('structuredClone requires a value');
      try {
        return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
      } catch (e) {
        var err = new Error('value could not be cloned (JSON fallback)');
        err.name = 'DataCloneError';
        throw err;
      }
    };
  }

  // String.prototype.matchAll（Chrome 73+；补守卫以防更老内核）
  if (typeof String.prototype.matchAll !== 'function') {
    Object.defineProperty(String.prototype, 'matchAll', {
      value: function (re) {
        if (!re || !re.global) throw new TypeError('matchAll called with a non-global RegExp argument');
        var str = String(this), out = [], m;
        var copy = new RegExp(re.source, re.flags || (re.global ? 'g' : '') + (re.ignoreCase ? 'i' : '') + (re.multiline ? 'm' : ''));
        copy.lastIndex = re.lastIndex;
        while ((m = copy.exec(str)) !== null) {
          out.push(m);
          if (m[0] === '') copy.lastIndex++;
        }
        return out[Symbol.iterator]();
      },
      writable: true, configurable: true
    });
  }

  // Array/String .at()（Chrome 92+）
  function installAt(proto, isString) {
    if (!proto || typeof proto.at === 'function') return;
    Object.defineProperty(proto, 'at', {
      value: function (n) {
        n = Math.trunc(n) || 0;
        var len = this.length;
        if (n < 0) n += len;
        if (n < 0 || n >= len) return undefined;
        return isString ? this.charAt(n) : this[n];
      },
      writable: true, configurable: true
    });
  }
  try {
    installAt(Array.prototype, false);
    installAt(String.prototype, true);
    if (typeof Int8Array !== 'undefined') installAt(Int8Array.prototype, false);
    if (typeof Uint8Array !== 'undefined') installAt(Uint8Array.prototype, false);
    if (typeof Uint8ClampedArray !== 'undefined') installAt(Uint8ClampedArray.prototype, false);
    if (typeof Int16Array !== 'undefined') installAt(Int16Array.prototype, false);
    if (typeof Uint16Array !== 'undefined') installAt(Uint16Array.prototype, false);
    if (typeof Int32Array !== 'undefined') installAt(Int32Array.prototype, false);
    if (typeof Uint32Array !== 'undefined') installAt(Uint32Array.prototype, false);
    if (typeof Float32Array !== 'undefined') installAt(Float32Array.prototype, false);
    if (typeof Float64Array !== 'undefined') installAt(Float64Array.prototype, false);
  } catch (e) { note('installAt failed: ' + e); }

  // Array.prototype.flat / flatMap（Chrome 69+；补守卫）
  if (typeof Array.prototype.flat !== 'function') {
    Object.defineProperty(Array.prototype, 'flat', {
      value: function (depth) {
        var d = depth === undefined ? 1 : Math.trunc(depth) || 0;
        var out = [];
        (function walk(arr, level) {
          for (var i = 0; i < arr.length; i++) {
            var v = arr[i];
            if (level > 0 && Array.isArray(v)) walk(v, level - 1);
            else out.push(v);
          }
        })(this, d);
        return out;
      },
      writable: true, configurable: true
    });
  }
  if (typeof Array.prototype.flatMap !== 'function') {
    Object.defineProperty(Array.prototype, 'flatMap', {
      value: function (fn, thisArg) {
        var out = [];
        for (var i = 0; i < this.length; i++) {
          var r = fn.call(thisArg, this[i], i, this);
          if (Array.isArray(r)) out = out.concat(r); else out.push(r);
        }
        return out;
      },
      writable: true, configurable: true
    });
  }

  // Object.fromEntries（Chrome 73+；补守卫）
  if (typeof Object.fromEntries !== 'function') {
    Object.fromEntries = function (iterable) {
      var obj = {};
      Array.prototype.forEach.call(iterable, function (pair) { obj[pair[0]] = pair[1]; });
      return obj;
    };
  }

  // queueMicrotask（Chrome 71+）
  if (typeof window.queueMicrotask !== 'function') {
    window.queueMicrotask = function (fn) {
      Promise.resolve().then(fn).catch(function (e) {
        setTimeout(function () { throw e; });
      });
    };
  }
})();
