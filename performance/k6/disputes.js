// k6 load test (task 2.13): disputes at a constant arrival rate through the shim, which publishes disputes.opened
// and answers once disputes.decided arrives. Each request's e2e_ms is publish to decided event.
//   RATE      disputes per second offered (default 2)
//   DURATION  how long to offer them (default 60s)
//   SHIM_URL  the shim (default http://load-shim:8090, inside the compose network)
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const SHIM = __ENV.SHIM_URL || 'http://load-shim:8090';
const e2e = new Trend('dispute_e2e', true);
const saturated = new Counter('disputes_rejected_all_in_flight');
const timedOut = new Counter('disputes_timed_out');

export const options = {
  scenarios: {
    disputes: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 2),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      // One VU waits for one decision; enough of them that k6 itself never limits the arrival rate.
      preAllocatedVUs: 40,
      maxVUs: 200,
    },
  },
  // A run fails when a decision is lost, not when the system is saturated (saturation is the measurement).
  thresholds: {
    disputes_timed_out: ['count==0'],
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.post(`${SHIM}/disputes`, null, { timeout: '150s', tags: { name: 'POST /disputes' } });
  if (res.status === 503) {
    saturated.add(1);
    return;
  }
  if (res.status === 504) {
    timedOut.add(1);
    return;
  }
  if (check(res, { 'decided': (r) => r.status === 200 })) {
    e2e.add(res.json('e2e_ms'));
  }
}
