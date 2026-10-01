// An Express-shaped req/res, just enough for header-and-decision middleware
// (helmet, cors): it reads the method, url and headers, sets or removes response
// headers, and then either calls next() or ends the response.
//
// Nothing here touches Java while the middleware runs. The middleware works on
// plain JS objects; what it decided comes back as one result, which the Java side
// applies to CafeAI's response:
//
//   { action: 'next' | 'end' | 'error' | 'pending', status, headers: [[name, value]...], body, error }
//
// 'pending' means the middleware returned without calling next() or ending the
// response -- i.e. it wanted to finish later, which this adapter does not support.

function run(middleware, method, url, headerNames, headerValues) {
  const reqHeaders = {};
  for (let i = 0; i < headerNames.length; i++) {
    reqHeaders[headerNames[i].toLowerCase()] = headerValues[i];
  }
  const q = url.indexOf('?');

  const req = {
    method,
    url,
    originalUrl: url,
    path: q < 0 ? url : url.slice(0, q),
    headers: reqHeaders,
    get(name) { return reqHeaders[String(name).toLowerCase()]; },
  };
  req.header = req.get;

  const resHeaders = new Map();   // lower-case name -> [original name, value]
  let outcome = null;
  const res = {
    statusCode: 200,
    headersSent: false,
    locals: {},
    setHeader(name, value) { resHeaders.set(String(name).toLowerCase(), [String(name), value]); return res; },
    getHeader(name) { const h = resHeaders.get(String(name).toLowerCase()); return h ? h[1] : undefined; },
    removeHeader(name) { resHeaders.delete(String(name).toLowerCase()); },
    hasHeader(name) { return resHeaders.has(String(name).toLowerCase()); },
    getHeaderNames() { return [...resHeaders.keys()]; },
    status(code) { res.statusCode = code; return res; },
    end(body) {
      if (!outcome) outcome = { action: 'end', body: body == null ? '' : String(body) };
      res.headersSent = true;
      return res;
    },
  };
  res.set = res.header = function (name, value) { return res.setHeader(name, value); };
  res.get = res.getHeader;

  const next = (err) => {
    if (!outcome) outcome = err ? { action: 'error', error: String(err && err.message || err) } : { action: 'next' };
  };

  try {
    middleware(req, res, next);
  } catch (e) {
    outcome = { action: 'error', error: String(e && e.message || e) };
  }
  if (!outcome) outcome = { action: 'pending' };

  const headers = [];
  for (const [, [name, value]] of resHeaders) {
    if (Array.isArray(value)) for (const v of value) headers.push([name, String(v)]);
    else headers.push([name, String(value)]);
  }
  outcome.status = res.statusCode;
  outcome.headers = headers;
  return outcome;
}

module.exports = { run };
