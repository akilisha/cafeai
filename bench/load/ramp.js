import http from 'k6/http';
import { check } from 'k6';
const target = Number(__ENV.VUS || 100);
export const options = {
  stages: [ { duration: '10s', target }, { duration: __ENV.DUR || '20s', target } ],
  summaryTrendStats: ['avg', 'p(50)', 'p(99)', 'p(99.9)', 'max'],
};
export default function () {
  const r = http.get(`http://127.0.0.1:8080${__ENV.PATH_ || '/json'}`);
  check(r, { ok: (res) => res.status === 200 });
}
