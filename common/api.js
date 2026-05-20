(function (global) {
  "use strict";

  function buildQueryString(params) {
    var sp = new URLSearchParams();
    Object.keys(params || {}).forEach(function (key) {
      var val = params[key];
      if (val === undefined || val === null || val === "") {
        return;
      }
      sp.set(key, String(val));
    });
    return sp.toString();
  }

  function buildUIProcessorUrl(params, endpoint) {
    var path = endpoint || "/UIProcessor";
    var qs = buildQueryString(params);
    return qs ? path + "?" + qs : path;
  }

  async function requestText(url, options) {
    var opts = options || {};
    var timeoutMs = typeof opts.timeoutMs === "number" ? opts.timeoutMs : 15000;
    var controller = new AbortController();
    var timer = setTimeout(function () {
      controller.abort();
    }, timeoutMs);

    try {
      var resp = await fetch(url, {
        method: "GET",
        credentials: opts.credentials || "include",
        cache: opts.cache || "no-store",
        signal: controller.signal
      });

      if (!resp.ok) {
        throw new Error("HTTP " + resp.status);
      }

      return {
        text: await resp.text(),
        contentType: resp.headers.get("content-type") || "",
        status: resp.status,
        url: resp.url
      };
    } finally {
      clearTimeout(timer);
    }
  }

  async function requestUIProcessor(params, options) {
    var opts = options || {};
    var endpoint = opts.endpoint || "/UIProcessor";
    var url = buildUIProcessorUrl(params, endpoint);
    return requestText(url, opts);
  }

  global.PageApi = {
    buildQueryString: buildQueryString,
    buildUIProcessorUrl: buildUIProcessorUrl,
    requestText: requestText,
    requestUIProcessor: requestUIProcessor
  };
})(window);
